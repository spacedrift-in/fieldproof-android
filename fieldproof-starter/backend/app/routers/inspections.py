"""Inspection API routes (``/api/v1``).

Evidence upload uses ``multipart/form-data`` so a real photo file can be sent
alongside its metadata. All write endpoints require an ``Idempotency-Key`` header.
"""
from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse, Response
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import idempotency, service
from ..config import get_settings
from ..db import get_db
from ..report_pdf import render_report_pdf
from ..schemas import (
    CreateInspectionRequest,
    CreateInspectionResponse,
    InspectionOut,
    ReportResponse,
    ReviewRequest,
    ReviewResponse,
    SubmitRequest,
    SubmitResponse,
    UploadEvidenceResponse,
)
from ..storage import get_storage

router = APIRouter(tags=["inspections"])

_CONFLICT_RESPONSES = {
    409: {"description": "Version or state conflict, or conflicting idempotency key reuse."}
}


def _require_idempotency_key(value: str | None) -> str:
    if not value:
        raise HTTPException(status_code=400, detail="Idempotency-Key header is required")
    return value


# ---------------------------------------------------------------------------
# Create / read
# ---------------------------------------------------------------------------

@router.post(
    "/inspections",
    response_model=CreateInspectionResponse,
    status_code=201,
    summary="Create or sync an inspection",
    responses=_CONFLICT_RESPONSES,
)
def create_inspection(
    payload: CreateInspectionRequest,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    db: Session = Depends(get_db),
) -> JSONResponse:
    key = _require_idempotency_key(idempotency_key)
    scope = "create_inspection"
    request_fingerprint = idempotency.fingerprint(payload.model_dump(mode="json"))

    record = idempotency.get_record(db, key, scope)
    if record is not None:
        if record.fingerprint != request_fingerprint:
            raise service.conflict(
                "Idempotency-Key was already used with a different request body."
            )
        return JSONResponse(status_code=record.response_status, content=record.response_body)

    existing = db.get(service.Inspection, payload.id)
    if existing is not None:
        if (
            existing.title != payload.title
            or existing.location != payload.location
            or existing.client_created_at != payload.createdAt
        ):
            raise service.conflict(
                "An inspection with this id already exists with different attributes."
            )
        body = {"id": str(existing.id), "status": existing.status}
        idempotency.store(
            db,
            key=key,
            scope=scope,
            request_fingerprint=request_fingerprint,
            entity_type="inspection",
            entity_id=existing.id,
            status_code=200,
            body=body,
        )
        db.commit()
        return JSONResponse(status_code=200, content=body)

    inspection = service.create_inspection(db, payload)
    body = {"id": str(inspection.id), "status": inspection.status}
    idempotency.store(
        db,
        key=key,
        scope=scope,
        request_fingerprint=request_fingerprint,
        entity_type="inspection",
        entity_id=inspection.id,
        status_code=201,
        body=body,
    )
    db.commit()
    return JSONResponse(status_code=201, content=body)


@router.get(
    "/inspections/{inspection_id}",
    response_model=InspectionOut,
    summary="Fetch an inspection with checklist, evidence and reviews",
)
def get_inspection(inspection_id: uuid.UUID, db: Session = Depends(get_db)) -> InspectionOut:
    inspection = service.get_inspection(db, inspection_id)
    return InspectionOut.model_validate(inspection)


# ---------------------------------------------------------------------------
# Evidence upload (multipart)
# ---------------------------------------------------------------------------

@router.post(
    "/inspections/{inspection_id}/evidence",
    response_model=UploadEvidenceResponse,
    status_code=201,
    summary="Upload evidence (photo file and/or note) idempotently",
    responses=_CONFLICT_RESPONSES,
)
def upload_evidence(
    inspection_id: uuid.UUID,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    file: UploadFile | None = File(default=None),
    evidence_id: uuid.UUID | None = Form(default=None, alias="id"),
    kind: str = Form(default="photo"),
    checklist_item_id: uuid.UUID | None = Form(default=None),
    note: str | None = Form(default=None),
    captured_at: int | None = Form(default=None),
    db: Session = Depends(get_db),
) -> JSONResponse:
    key = _require_idempotency_key(idempotency_key)
    scope = f"evidence:{inspection_id}"

    content = file.file.read() if file is not None else b""
    max_bytes = get_settings().max_evidence_bytes
    if len(content) > max_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"Uploaded file exceeds the {max_bytes} byte limit.",
        )

    metadata = {
        "id": str(evidence_id) if evidence_id else None,
        "kind": kind,
        "checklist_item_id": str(checklist_item_id) if checklist_item_id else None,
        "note": note,
        "captured_at": captured_at,
        "filename": file.filename if file is not None else None,
        "content_type": file.content_type if file is not None else None,
    }
    request_fingerprint = idempotency.fingerprint(content, metadata)

    record = idempotency.get_record(db, key, scope)
    if record is not None:
        if record.fingerprint != request_fingerprint:
            raise service.conflict(
                "Idempotency-Key was already used with different evidence content."
            )
        return JSONResponse(status_code=record.response_status, content=record.response_body)

    evidence = service.upload_evidence(
        db,
        inspection_id,
        evidence_id=evidence_id,
        kind=kind,
        checklist_item_id=checklist_item_id,
        note=note,
        captured_at_ms=captured_at,
        content=content,
        content_type=file.content_type if file is not None else None,
        filename=file.filename if file is not None else None,
        storage=get_storage(),
    )

    body = {"id": str(evidence.id), "status": "SYNCED"}
    idempotency.store(
        db,
        key=key,
        scope=scope,
        request_fingerprint=request_fingerprint,
        entity_type="evidence",
        entity_id=evidence.id,
        status_code=201,
        body=body,
    )
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        record = idempotency.get_record(db, key, scope)
        if record is not None and record.fingerprint == request_fingerprint:
            return JSONResponse(status_code=record.response_status, content=record.response_body)
        raise
    return JSONResponse(status_code=201, content=body)


# ---------------------------------------------------------------------------
# Submit / review / report
# ---------------------------------------------------------------------------

@router.post(
    "/inspections/{inspection_id}/submit",
    response_model=SubmitResponse,
    summary="Submit an inspection for review",
    responses=_CONFLICT_RESPONSES,
)
def submit_inspection(
    inspection_id: uuid.UUID,
    payload: SubmitRequest | None = None,
    db: Session = Depends(get_db),
) -> SubmitResponse:
    expected = payload.expected_version if payload else None
    inspection = service.submit_inspection(db, inspection_id, expected)
    return SubmitResponse(id=inspection.id, status=inspection.status, version=inspection.version)


@router.post(
    "/inspections/{inspection_id}/review",
    response_model=ReviewResponse,
    summary="Record a reviewer decision on a submitted inspection",
    responses=_CONFLICT_RESPONSES,
)
def review_inspection(
    inspection_id: uuid.UUID,
    payload: ReviewRequest,
    db: Session = Depends(get_db),
) -> ReviewResponse:
    inspection, review = service.review_inspection(db, inspection_id, payload)
    return ReviewResponse(
        id=inspection.id,
        status=inspection.status,
        version=inspection.version,
        review=review,
    )


@router.get(
    "/inspections/{inspection_id}/report",
    response_model=ReportResponse,
    summary="Generate the report for an approved inspection",
    responses=_CONFLICT_RESPONSES,
)
def get_report(inspection_id: uuid.UUID, db: Session = Depends(get_db)) -> ReportResponse:
    return service.build_report(db, inspection_id)


@router.get(
    "/inspections/{inspection_id}/report.pdf",
    summary="Download the reviewed inspection report as a PDF",
    responses=_CONFLICT_RESPONSES,
)
def get_report_pdf(inspection_id: uuid.UUID, db: Session = Depends(get_db)) -> Response:
    report = service.build_report(db, inspection_id)
    pdf = render_report_pdf(report)
    return Response(
        content=pdf,
        media_type="application/pdf",
        headers={
            "Content-Disposition": (
                f'inline; filename="inspection-{inspection_id}.pdf"'
            )
        },
    )
