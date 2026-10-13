"""Inspection API routes (``/api/v1``).

Evidence upload uses ``multipart/form-data`` so a real photo file can be sent
alongside its metadata. All write endpoints require an ``Idempotency-Key`` header.

Uploads are hardened:

* reads are bounded (the whole body is never buffered before the size check),
* the image type is derived from the file's magic bytes and checked against an
  allowlist (the client-declared content type cannot be trusted),
* content passes a malware-scanning integration point before persistence,
* downloads are served with safe headers (``attachment`` + ``nosniff``).

Tenancy is request-scoped via ``X-Tenant-Id`` (see :mod:`app.tenancy`).
"""
from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse, Response
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import idempotency, media, scanning, service, tenancy
from ..config import get_settings
from ..db import get_db
from ..report_pdf import render_report_pdf
from ..schemas import (
    ChecklistItemOut,
    ChecklistOutcomeResponse,
    ChecklistOutcomeUpdate,
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

_UPLOAD_CHUNK_BYTES = 1024 * 1024

_CONFLICT_RESPONSES = {
    409: {"description": "Version or state conflict, or conflicting idempotency key reuse."}
}


def _require_idempotency_key(value: str | None) -> str:
    if not value:
        raise HTTPException(status_code=400, detail="Idempotency-Key header is required")
    return value


def _read_upload_bounded(file: UploadFile, max_bytes: int) -> bytes:
    """Read an upload in chunks, aborting as soon as the limit is exceeded.

    The ``Content-Length`` hint (when present) allows an early rejection, but it
    is never trusted: the running total is enforced while streaming.
    """
    if file.size is not None and file.size > max_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"Uploaded file exceeds the {max_bytes} byte limit.",
        )
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = file.file.read(_UPLOAD_CHUNK_BYTES)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise HTTPException(
                status_code=413,
                detail=f"Uploaded file exceeds the {max_bytes} byte limit.",
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _scan_upload(content: bytes, *, filename: str | None, content_type: str | None) -> None:
    settings = get_settings()
    verdict = scanning.get_scanner(settings).scan(
        content, filename=filename, content_type=content_type
    )
    if verdict.status == "infected":
        raise HTTPException(
            status_code=422,
            detail="Uploaded file failed malware scanning and was rejected.",
        )
    if verdict.status == "error" and settings.malware_scan_fail_closed:
        raise HTTPException(
            status_code=503,
            detail="Malware scanning is temporarily unavailable; please retry.",
        )


def _safe_download_filename(evidence) -> str:
    from pathlib import PurePosixPath

    if evidence.original_filename:
        base = PurePosixPath(evidence.original_filename).name
        cleaned = "".join(c for c in base if c.isprintable() and c not in {'"', "\\", "\r", "\n"})
        if cleaned:
            return cleaned
    suffix = (evidence.storage_key or "").rsplit(".", 1)
    ext = f".{suffix[1]}" if len(suffix) == 2 and len(suffix[1]) <= 10 else ""
    return f"evidence-{evidence.id}{ext}"


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
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> JSONResponse:
    key = _require_idempotency_key(idempotency_key)
    scope = f"create_inspection:{tenant}"
    request_fingerprint = idempotency.fingerprint(payload.model_dump(mode="json"))

    record = idempotency.get_record(db, key, scope)
    if record is not None:
        if record.fingerprint != request_fingerprint:
            raise service.conflict(
                "Idempotency-Key was already used with a different request body."
            )
        return JSONResponse(status_code=record.response_status, content=record.response_body)

    existing = db.get(service.Inspection, payload.id)
    if existing is not None and existing.tenant_id == tenant:
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

    inspection = service.create_inspection(db, payload, tenant_id=tenant)
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
def get_inspection(
    inspection_id: uuid.UUID,
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> InspectionOut:
    inspection = service.get_inspection(db, inspection_id, tenant)
    return InspectionOut.model_validate(inspection)


# ---------------------------------------------------------------------------
# Checklist outcomes
# ---------------------------------------------------------------------------

@router.put(
    "/inspections/{inspection_id}/checklist/{checklist_item_id}",
    response_model=ChecklistOutcomeResponse,
    summary="Set a checklist item outcome and note (versioned, idempotent)",
    responses=_CONFLICT_RESPONSES,
)
def update_checklist_outcome(
    inspection_id: uuid.UUID,
    checklist_item_id: uuid.UUID,
    payload: ChecklistOutcomeUpdate,
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> ChecklistOutcomeResponse:
    inspection, item = service.update_checklist_outcome(
        db,
        inspection_id,
        checklist_item_id,
        payload,
        tenant_id=tenant,
    )
    return ChecklistOutcomeResponse(
        inspection_id=inspection.id,
        inspection_version=inspection.version,
        item=ChecklistItemOut.model_validate(item),
    )


# ---------------------------------------------------------------------------
# Evidence upload / download (multipart)
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
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> JSONResponse:
    key = _require_idempotency_key(idempotency_key)
    scope = f"evidence:{tenant}:{inspection_id}"

    settings = get_settings()
    content = _read_upload_bounded(file, settings.max_evidence_bytes) if file is not None else b""

    content_type = file.content_type if file is not None else None
    if kind == service.domain.EvidenceKind.PHOTO or (content and file is not None):
        # Derive the trusted type from the bytes; reject spoofed/unknown images
        # and anything not on the allowlist.
        content_type = media.validate_image_upload(
            content,
            declared_content_type=file.content_type if file is not None else None,
            allowed=settings.allowed_image_types,
        )
        _scan_upload(content, filename=file.filename if file is not None else None, content_type=content_type)

    metadata = {
        "id": str(evidence_id) if evidence_id else None,
        "kind": kind,
        "checklist_item_id": str(checklist_item_id) if checklist_item_id else None,
        "note": note,
        "captured_at": captured_at,
        "filename": file.filename if file is not None else None,
        "content_type": content_type,
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
        content_type=content_type,
        filename=file.filename if file is not None else None,
        storage=get_storage(),
        tenant_id=tenant,
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


@router.get(
    "/inspections/{inspection_id}/evidence/{evidence_id}",
    summary="Download stored evidence with safe headers",
)
def download_evidence(
    inspection_id: uuid.UUID,
    evidence_id: uuid.UUID,
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> Response:
    evidence = service.get_evidence(db, inspection_id, evidence_id, tenant)
    if not evidence.storage_key:
        raise HTTPException(status_code=404, detail="Evidence has no stored file.")
    storage = get_storage()
    if not storage.exists(evidence.storage_key):
        raise service.not_found("Evidence file not found.")
    content = storage.read(evidence.storage_key)
    return Response(
        content=content,
        media_type=evidence.content_type or "application/octet-stream",
        headers={
            "Content-Disposition": f'attachment; filename="{_safe_download_filename(evidence)}"',
            "X-Content-Type-Options": "nosniff",
            "Cache-Control": "private, no-store",
        },
    )


# ---------------------------------------------------------------------------
# Deletion / retention
# ---------------------------------------------------------------------------

@router.delete(
    "/inspections/{inspection_id}",
    summary="Delete an inspection, its evidence objects and related records",
)
def delete_inspection(
    inspection_id: uuid.UUID,
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> dict:
    return service.delete_inspection(
        db,
        inspection_id,
        tenant_id=tenant,
        storage=get_storage(),
    )


@router.delete(
    "/tenant",
    summary="Delete all data for the caller's tenant (requires confirmation header)",
)
def delete_tenant(
    x_confirm_delete: str | None = Header(default=None, alias="X-Confirm-Delete"),
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> dict:
    # A tenant wipe is irreversible; require the caller to echo the tenant id in
    # a confirmation header so a stray DELETE cannot destroy data.
    if x_confirm_delete != tenant:
        raise HTTPException(
            status_code=400,
            detail="Set X-Confirm-Delete to the tenant id to confirm deletion.",
        )
    return service.delete_tenant_data(db, tenant, storage=get_storage())


@router.post(
    "/maintenance/retention",
    summary="Run the evidence retention sweep (admin token required)",
)
def run_retention(
    days: int | None = None,
    x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
    db: Session = Depends(get_db),
) -> dict:
    settings = get_settings()
    if not settings.retention_admin_token:
        raise HTTPException(
            status_code=503,
            detail="Retention sweep is disabled; set RETENTION_ADMIN_TOKEN to enable it.",
        )
    if x_admin_token != settings.retention_admin_token:
        raise HTTPException(status_code=401, detail="Invalid admin token.")

    retention_days = days if days is not None else settings.evidence_retention_days
    return service.purge_expired_evidence(
        db,
        retention_days=retention_days,
        storage=get_storage(),
    )


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
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> SubmitResponse:
    expected = payload.expected_version if payload else None
    inspection = service.submit_inspection(db, inspection_id, expected, tenant_id=tenant)
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
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> ReviewResponse:
    inspection, review = service.review_inspection(db, inspection_id, payload, tenant_id=tenant)
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
def get_report(
    inspection_id: uuid.UUID,
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> ReportResponse:
    return service.build_report(db, inspection_id, tenant)


@router.get(
    "/inspections/{inspection_id}/report.pdf",
    summary="Download the reviewed inspection report as a PDF",
    responses=_CONFLICT_RESPONSES,
)
def get_report_pdf(
    inspection_id: uuid.UUID,
    tenant: str = Depends(tenancy.tenant_id),
    db: Session = Depends(get_db),
) -> Response:
    report = service.build_report(db, inspection_id, tenant)
    pdf = render_report_pdf(report)
    return Response(
        content=pdf,
        media_type="application/pdf",
        headers={
            "Content-Disposition": f'attachment; filename="inspection-{inspection_id}.pdf"',
            "X-Content-Type-Options": "nosniff",
            "Cache-Control": "private, no-store",
        },
    )
