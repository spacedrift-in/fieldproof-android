"""Business logic for inspections, evidence, submission, review and reports."""
from __future__ import annotations

import hashlib
import uuid
from datetime import datetime, timezone

from fastapi import HTTPException
from sqlalchemy.orm import Session

from . import domain
from .demo_data import DEMO_TEMPLATE, DEMO_TEMPLATE_ID
from .models import ChecklistItem, Evidence, Inspection, Review, utcnow
from .schemas import (
    CreateInspectionRequest,
    ReportChecklistItem,
    ReportResponse,
    ReportSummary,
    ReviewOut,
    ReviewRequest,
)
from .storage import EvidenceStorage


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------

def not_found(detail: str = "Inspection not found") -> HTTPException:
    return HTTPException(status_code=404, detail=detail)


def conflict(detail: object) -> HTTPException:
    return HTTPException(status_code=409, detail=detail)


def version_conflict(inspection: Inspection) -> HTTPException:
    return conflict(
        {
            "message": "Version conflict: the inspection changed on the server.",
            "current_version": inspection.version,
            "current_status": inspection.status,
        }
    )


# ---------------------------------------------------------------------------
# Inspections
# ---------------------------------------------------------------------------

def create_inspection(db: Session, payload: CreateInspectionRequest) -> Inspection:
    template_id = payload.template_id or uuid.UUID(DEMO_TEMPLATE_ID)
    inspection = Inspection(
        id=payload.id,
        title=payload.title,
        location=payload.location,
        status=domain.InspectionStatus.DRAFT,
        version=1,
        template_id=template_id,
        client_created_at=payload.createdAt,
    )
    # Seed checklist items from the synthetic demo template. Item ids are derived
    # deterministically from (inspection_id, template_item_id) using the same
    # scheme as the Android client (Java UUID.nameUUIDFromBytes), so offline-created
    # inspections reference the same items the server seeds here.
    for order, item in enumerate(DEMO_TEMPLATE["items"]):
        inspection.checklist_items.append(
            ChecklistItem(
                id=_checklist_item_id(inspection.id, item["id"]),
                inspection_id=inspection.id,
                template_item_id=uuid.UUID(item["id"]),
                label=item["label"],
                required=item["required"],
                evidence_requirement=item["evidence_requirement"],
                status=domain.ChecklistItemStatus.NOT_STARTED,
                sort_order=order,
                version=1,
            )
        )
    db.add(inspection)
    db.commit()
    db.refresh(inspection)
    return inspection


def get_inspection(db: Session, inspection_id: uuid.UUID) -> Inspection:
    inspection = db.get(Inspection, inspection_id)
    if inspection is None:
        raise not_found()
    return inspection


# ---------------------------------------------------------------------------
# Evidence
# ---------------------------------------------------------------------------

def upload_evidence(
    db: Session,
    inspection_id: uuid.UUID,
    *,
    evidence_id: uuid.UUID | None,
    kind: str,
    checklist_item_id: uuid.UUID | None,
    note: str | None,
    captured_at_ms: int | None,
    content: bytes,
    content_type: str | None,
    filename: str | None,
    storage: EvidenceStorage,
) -> Evidence:
    inspection = db.get(Inspection, inspection_id)
    if inspection is None:
        raise not_found()

    if inspection.status not in domain.InspectionStatus.EVIDENCE_EDITABLE:
        raise conflict(
            f"Cannot attach evidence while the inspection is {inspection.status}."
        )

    if kind not in domain.EvidenceKind.ALL:
        raise HTTPException(status_code=422, detail="kind must be 'photo' or 'note'")

    if checklist_item_id is not None:
        item = db.get(ChecklistItem, checklist_item_id)
        if item is None or item.inspection_id != inspection.id:
            raise HTTPException(
                status_code=422,
                detail="checklist_item_id does not belong to this inspection",
            )

    if kind == domain.EvidenceKind.PHOTO:
        if not content:
            raise HTTPException(status_code=422, detail="A photo file is required for kind='photo'")
        if not (content_type or "").startswith("image/"):
            raise HTTPException(
                status_code=415,
                detail="Unsupported media type: kind='photo' requires an image/* upload",
            )
    else:  # note
        if not (note and note.strip()):
            raise HTTPException(status_code=422, detail="A non-empty note is required for kind='note'")

    evidence_uuid = evidence_id or uuid.uuid4()
    sha = hashlib.sha256(content or (note or "").encode()).hexdigest()

    existing = db.get(Evidence, evidence_uuid)
    if existing is not None:
        if existing.sha256 == sha:
            return existing
        raise conflict("Evidence id already exists with different content")

    storage_key = f"evidence/{inspection.id}/{evidence_uuid}{_extension(filename, content_type, kind)}"
    storage.save(storage_key, content or b"")

    evidence = Evidence(
        id=evidence_uuid,
        inspection_id=inspection.id,
        checklist_item_id=checklist_item_id,
        kind=kind,
        note=note,
        storage_key=storage_key,
        sha256=sha,
        content_type=content_type,
        size_bytes=len(content or b""),
        original_filename=filename,
        captured_at=_to_datetime(captured_at_ms),
        uploaded_at=utcnow(),
    )
    db.add(evidence)
    inspection.version += 1
    inspection.updated_at = utcnow()
    db.commit()
    db.refresh(evidence)
    return evidence


# ---------------------------------------------------------------------------
# Submit / review
# ---------------------------------------------------------------------------

def submit_inspection(
    db: Session, inspection_id: uuid.UUID, expected_version: int | None
) -> Inspection:
    inspection = db.get(Inspection, inspection_id)
    if inspection is None:
        raise not_found()

    if expected_version is not None and expected_version != inspection.version:
        raise version_conflict(inspection)

    if inspection.status not in domain.InspectionStatus.SUBMITTABLE:
        raise conflict(
            f"Cannot submit an inspection in status {inspection.status}."
        )

    missing = _missing_required_evidence(inspection)
    if missing:
        raise conflict(
            {
                "message": "Missing required evidence for submission.",
                "missing_checklist_item_ids": [str(item) for item in missing],
            }
        )

    inspection.status = domain.InspectionStatus.SUBMITTED
    inspection.version += 1
    inspection.updated_at = utcnow()
    db.commit()
    db.refresh(inspection)
    return inspection


def review_inspection(
    db: Session, inspection_id: uuid.UUID, payload: ReviewRequest
) -> tuple[Inspection, Review]:
    inspection = db.get(Inspection, inspection_id)
    if inspection is None:
        raise not_found()

    if payload.expected_version is not None and payload.expected_version != inspection.version:
        raise version_conflict(inspection)

    if inspection.status != domain.InspectionStatus.SUBMITTED:
        raise conflict(
            f"Cannot review an inspection in status {inspection.status}; expected SUBMITTED."
        )

    review = Review(
        inspection_id=inspection.id,
        reviewer_id=payload.reviewer_id,
        decision=payload.decision,
        comment=payload.comment,
        inspection_version=inspection.version,
        reviewed_at=utcnow(),
    )
    db.add(review)

    if payload.decision == domain.ReviewDecision.APPROVED:
        inspection.status = domain.InspectionStatus.APPROVED
    else:
        inspection.status = domain.InspectionStatus.CHANGES_REQUESTED

    inspection.version += 1
    inspection.updated_at = utcnow()
    db.commit()
    db.refresh(inspection)
    db.refresh(review)
    return inspection, review


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

def build_report(db: Session, inspection_id: uuid.UUID) -> ReportResponse:
    inspection = db.get(Inspection, inspection_id)
    if inspection is None:
        raise not_found()

    if inspection.status != domain.InspectionStatus.APPROVED:
        raise conflict(
            f"Report is only available after approval; current status is {inspection.status}."
        )

    evidence_by_item: dict[uuid.UUID | None, list[Evidence]] = {}
    for ev in inspection.evidence:
        evidence_by_item.setdefault(ev.checklist_item_id, []).append(ev)

    checklist: list[ReportChecklistItem] = []
    counts = {s: 0 for s in (
        domain.ChecklistItemStatus.PASS,
        domain.ChecklistItemStatus.FAIL,
        domain.ChecklistItemStatus.NOT_APPLICABLE,
        domain.ChecklistItemStatus.NOT_STARTED,
    )}
    for item in inspection.checklist_items:
        counts[item.status] = counts.get(item.status, 0) + 1
        checklist.append(
            ReportChecklistItem(
                id=item.id,
                label=item.label,
                required=item.required,
                evidence_requirement=item.evidence_requirement,
                status=item.status,
                note=item.note,
                evidence=[_evidence_out(ev) for ev in evidence_by_item.get(item.id, [])],
            )
        )

    latest_review = inspection.reviews[-1] if inspection.reviews else None

    return ReportResponse(
        inspection_id=inspection.id,
        title=inspection.title,
        location=inspection.location,
        status=inspection.status,
        version=inspection.version,
        created_at=inspection.created_at,
        updated_at=inspection.updated_at,
        generated_at=utcnow(),
        summary=ReportSummary(
            total_items=len(inspection.checklist_items),
            passed=counts[domain.ChecklistItemStatus.PASS],
            failed=counts[domain.ChecklistItemStatus.FAIL],
            not_applicable=counts[domain.ChecklistItemStatus.NOT_APPLICABLE],
            not_started=counts[domain.ChecklistItemStatus.NOT_STARTED],
            evidence_count=len(inspection.evidence),
        ),
        checklist=checklist,
        review=ReviewOut.model_validate(latest_review) if latest_review else None,
    )


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _missing_required_evidence(inspection: Inspection) -> list[uuid.UUID]:
    by_item: dict[uuid.UUID, set[str]] = {}
    for ev in inspection.evidence:
        if ev.checklist_item_id is not None:
            by_item.setdefault(ev.checklist_item_id, set()).add(ev.kind)

    missing: list[uuid.UUID] = []
    for item in inspection.checklist_items:
        if not item.required:
            continue
        kinds = by_item.get(item.id, set())
        req = item.evidence_requirement
        if req == domain.EvidenceRequirement.PHOTO and domain.EvidenceKind.PHOTO not in kinds:
            missing.append(item.id)
        elif req == domain.EvidenceRequirement.NOTE and domain.EvidenceKind.NOTE not in kinds:
            missing.append(item.id)
        elif req == domain.EvidenceRequirement.PHOTO_OR_NOTE and not kinds:
            missing.append(item.id)
    return missing


def _checklist_item_id(inspection_id: uuid.UUID, template_item_id: str) -> uuid.UUID:
    seed = f"{inspection_id}:{template_item_id}".encode("utf-8")
    digest = bytearray(hashlib.md5(seed).digest())
    # Java/Kotlin UUID.nameUUIDFromBytes sets version 3 and the RFC 4122 variant bits.
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


def _evidence_out(evidence: Evidence):
    from .schemas import EvidenceOut

    return EvidenceOut.model_validate(evidence)


def _to_datetime(epoch_ms: int | None) -> datetime:
    if epoch_ms is None:
        return utcnow()
    return datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc)


def _extension(filename: str | None, content_type: str | None, kind: str) -> str:
    if filename:
        from pathlib import PurePosixPath

        suffix = PurePosixPath(filename).suffix
        if suffix and len(suffix) <= 10 and suffix.isprintable():
            return suffix
    mapping = {
        "image/png": ".png",
        "image/jpeg": ".jpg",
        "image/jpg": ".jpg",
        "image/webp": ".webp",
        "image/heic": ".heic",
    }
    if content_type in mapping:
        return mapping[content_type]
    return ".txt" if kind == domain.EvidenceKind.NOTE else ".bin"
