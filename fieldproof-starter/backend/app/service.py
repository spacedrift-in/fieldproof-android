"""Business logic for inspections, evidence, submission, review and reports."""
from __future__ import annotations

import hashlib
import logging
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import domain, idempotency
from .demo_data import DEMO_TEMPLATE, DEMO_TEMPLATE_ID
from .models import ChecklistItem, Evidence, Inspection, Review, utcnow
from .observability import metrics
from .schemas import (
    ChecklistItemOut,
    ChecklistOutcomeUpdate,
    CreateInspectionRequest,
    ReportChecklistItem,
    ReportResponse,
    ReportSummary,
    ReviewOut,
    ReviewRequest,
)
from .storage import EvidenceStorage

logger = logging.getLogger(__name__)


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

def create_inspection(
    db: Session,
    payload: CreateInspectionRequest,
    tenant_id: str = "demo",
) -> Inspection:
    template_id = payload.template_id or uuid.UUID(DEMO_TEMPLATE_ID)
    inspection = Inspection(
        id=payload.id,
        tenant_id=tenant_id,
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


def _load_inspection(db: Session, inspection_id: uuid.UUID, tenant_id: str | None) -> Inspection:
    inspection = db.get(Inspection, inspection_id)
    if inspection is None or (tenant_id is not None and inspection.tenant_id != tenant_id):
        # A cross-tenant id is surfaced as 404 so tenant existence is not leaked.
        raise not_found()
    return inspection


def get_inspection(
    db: Session,
    inspection_id: uuid.UUID,
    tenant_id: str | None = None,
) -> Inspection:
    return _load_inspection(db, inspection_id, tenant_id)


# ---------------------------------------------------------------------------
# Checklist outcomes
# ---------------------------------------------------------------------------

def update_checklist_outcome(
    db: Session,
    inspection_id: uuid.UUID,
    checklist_item_id: uuid.UUID,
    payload: ChecklistOutcomeUpdate,
    tenant_id: str | None = None,
) -> tuple[Inspection, ChecklistItem]:
    """Set a checklist item's outcome/note with optimistic concurrency.

    Replay semantics (lost-response safe): if the item already holds exactly the
    requested status/note, the request is treated as a replay of an already
    committed mutation, regardless of ``expected_version``, and returns the
    current item **without** bumping any version. This distinguishes the same
    committed operation from a conflicting later edit: a stale
    ``expected_version`` only raises 409 when the server state no longer matches
    the request (i.e. someone else changed it to something different).
    """
    inspection = _load_inspection(db, inspection_id, tenant_id)

    item = db.get(ChecklistItem, checklist_item_id)
    if item is None or item.inspection_id != inspection.id:
        raise HTTPException(
            status_code=404,
            detail="Checklist item not found for this inspection.",
        )

    if inspection.status not in domain.InspectionStatus.CHECKLIST_EDITABLE:
        raise conflict(
            f"Cannot change checklist outcomes while the inspection is {inspection.status}."
        )

    if payload.status not in domain.ChecklistItemStatus.ALL:
        raise HTTPException(
            status_code=422,
            detail=f"status must be one of {sorted(domain.ChecklistItemStatus.ALL)}",
        )

    normalized_note = payload.note.strip() if (payload.note and payload.note.strip()) else None

    # Replay check must come *before* the version check. A retry of an update
    # whose response was lost carries the pre-mutation ``expected_version``; the
    # server already applied it, so the item matches the request exactly and we
    # confirm the replay without bumping any version. Checking the version first
    # turned that legitimate retry into a false 409 (KL-033). A genuine conflict
    # (the item was since changed to something else) still fails the version
    # check below.
    if item.status == payload.status and (item.note or None) == normalized_note:
        return inspection, item

    if (
        payload.expected_version is not None
        and payload.expected_version != item.version
    ):
        raise conflict(
            {
                "message": "Version conflict: the checklist item changed on the server.",
                "current_version": item.version,
                "current_status": item.status,
                "checklist_item_id": str(item.id),
            }
        )

    item.status = payload.status
    item.note = normalized_note
    item.version += 1
    inspection.version += 1
    inspection.updated_at = utcnow()
    db.commit()
    db.refresh(item)
    db.refresh(inspection)
    return inspection, item


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
    tenant_id: str = "demo",
) -> Evidence:
    inspection = _load_inspection(db, inspection_id, tenant_id)

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

    storage_key = (
        f"tenants/{tenant_id}/inspections/{inspection.id}/evidence/"
        f"{evidence_uuid}{_extension(filename, content_type, kind)}"
    )
    storage.save(storage_key, content or b"", content_type=content_type if kind == domain.EvidenceKind.PHOTO else None)

    evidence = Evidence(
        id=evidence_uuid,
        tenant_id=tenant_id,
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
    metrics.inc("evidence_uploads_total", {"kind": kind})
    return evidence


def get_evidence(
    db: Session,
    inspection_id: uuid.UUID,
    evidence_id: uuid.UUID,
    tenant_id: str | None = None,
) -> Evidence:
    inspection = _load_inspection(db, inspection_id, tenant_id)
    evidence = db.get(Evidence, evidence_id)
    if evidence is None or evidence.inspection_id != inspection.id:
        raise not_found("Evidence not found")
    if tenant_id is not None and evidence.tenant_id != tenant_id:
        raise not_found("Evidence not found")
    return evidence


# ---------------------------------------------------------------------------
# Deletion / retention
# ---------------------------------------------------------------------------

def _delete_objects(storage: EvidenceStorage, keys: list[str]) -> int:
    """Best-effort delete of stored objects; failures are logged, not fatal."""
    deleted = 0
    for key in keys:
        try:
            storage.delete(key)
            deleted += 1
        except Exception:  # noqa: BLE001
            metrics.inc("storage_failures_total", {"operation": "delete"})
            logger.warning("Failed to delete evidence object %s", key, exc_info=True)
    return deleted


def _is_legal_hold(storage: EvidenceStorage, key: str) -> bool:
    """Return True when an object is under legal hold.

    Object stores that do not support tags (the local dev backend) are never
    held. A failure to *check* is treated as held (fail safe) so a transient
    object-store error cannot cause an object under litigation hold to be
    purged.
    """
    checker = getattr(storage, "is_legal_hold", None)
    if checker is None:
        return False
    try:
        return bool(checker(key))
    except Exception:  # noqa: BLE001
        logger.warning("Legal-hold check failed for %s; treating as held", key, exc_info=True)
        return True


def _mark_retention_eligible(storage: EvidenceStorage, key: str) -> None:
    """Tag an object so the S3 lifecycle rule may expire it later.

    This is the coordination point between the database and object storage: the
    sweep decides eligibility (APPROVED + past retention) and only then tags the
    object. Stores without tag support ignore this.
    """
    marker = getattr(storage, "mark_retention_eligible", None)
    if marker is None:
        return
    try:
        marker(key)
    except Exception:  # noqa: BLE001
        logger.warning("Failed to tag %s eligible for lifecycle expiry", key, exc_info=True)


def delete_inspection(
    db: Session,
    inspection_id: uuid.UUID,
    *,
    tenant_id: str | None = None,
    storage: EvidenceStorage,
) -> dict:
    """Delete an inspection and everything under it.

    Stored evidence objects are removed before the database rows so an
    interrupted delete cannot leave orphaned, undeletable objects behind. The
    tenant check uses the same 404-not-403 rule as reads so tenant existence is
    never leaked.
    """
    inspection = _load_inspection(db, inspection_id, tenant_id)

    evidence = list(inspection.evidence)
    checklist = list(inspection.checklist_items)
    keys = [ev.storage_key for ev in evidence if ev.storage_key]
    objects_deleted = _delete_objects(storage, keys)

    entity_ids = [str(inspection.id), *[str(ev.id) for ev in evidence], *[str(c.id) for c in checklist]]
    idempotency.delete_for_entities(db, entity_ids)

    db.delete(inspection)
    db.commit()
    return {
        "inspection_id": str(inspection_id),
        "evidence_deleted": len(evidence),
        "objects_deleted": objects_deleted,
    }


def delete_tenant_data(
    db: Session,
    tenant_id: str,
    *,
    storage: EvidenceStorage,
) -> dict:
    """Delete every inspection and evidence object for a tenant."""
    evidence = db.execute(
        select(Evidence).where(Evidence.tenant_id == tenant_id)
    ).scalars().all()
    inspections = db.execute(
        select(Inspection).where(Inspection.tenant_id == tenant_id)
    ).scalars().all()

    keys = [ev.storage_key for ev in evidence if ev.storage_key]
    objects_deleted = _delete_objects(storage, keys)

    entity_ids = [str(i.id) for i in inspections] + [str(ev.id) for ev in evidence]
    idempotency.delete_for_entities(db, entity_ids)

    for inspection in inspections:
        db.delete(inspection)
    db.commit()
    return {
        "tenant_id": tenant_id,
        "inspections_deleted": len(inspections),
        "evidence_deleted": len(evidence),
        "objects_deleted": objects_deleted,
    }


def purge_expired_evidence(
    db: Session,
    *,
    retention_days: int,
    storage: EvidenceStorage,
    now: datetime | None = None,
) -> dict:
    """Retention sweep: delete evidence uploaded more than ``retention_days`` ago.

    Only evidence attached to a *finalized* (APPROVED) inspection is eligible:
    active drafts and submitted work are never purged. Objects under legal hold
    are skipped entirely. Eligible objects are also tagged so the object-store
    lifecycle rule (which only targets tagged objects) can expire them as a
    backstop if the direct delete fails. A non-positive ``retention_days``
    disables the sweep.
    """
    if retention_days <= 0:
        return {"purged": 0, "objects_deleted": 0, "legal_held": 0}

    cutoff = (now or utcnow()) - timedelta(days=retention_days)
    evidence = db.execute(
        select(Evidence)
        .join(Inspection, Evidence.inspection_id == Inspection.id)
        .where(
            Evidence.uploaded_at < cutoff,
            Inspection.status == domain.InspectionStatus.APPROVED,
        )
    ).scalars().all()

    eligible: list[Evidence] = []
    legal_held = 0
    for ev in evidence:
        if not ev.storage_key:
            continue
        if _is_legal_hold(storage, ev.storage_key):
            legal_held += 1
            continue
        eligible.append(ev)

    keys = [ev.storage_key for ev in eligible]
    for key in keys:
        _mark_retention_eligible(storage, key)
    objects_deleted = _delete_objects(storage, keys)
    idempotency.delete_for_entities(db, [str(ev.id) for ev in eligible])

    for ev in eligible:
        db.delete(ev)
    db.commit()
    return {
        "purged": len(eligible),
        "objects_deleted": objects_deleted,
        "legal_held": legal_held,
    }


# ---------------------------------------------------------------------------
# Submit / review
# ---------------------------------------------------------------------------

def submit_inspection(
    db: Session,
    inspection_id: uuid.UUID,
    expected_version: int | None,
    tenant_id: str | None = None,
) -> Inspection:
    inspection = _load_inspection(db, inspection_id, tenant_id)

    if expected_version is not None and expected_version != inspection.version:
        raise version_conflict(inspection)

    if inspection.status not in domain.InspectionStatus.SUBMITTABLE:
        raise conflict(
            f"Cannot submit an inspection in status {inspection.status}."
        )

    missing, errors = _submit_validation(inspection)
    if errors:
        raise conflict(
            {
                "message": "Submission blocked by validation errors.",
                "missing_checklist_item_ids": [str(item) for item in missing],
                "errors": errors,
            }
        )

    inspection.status = domain.InspectionStatus.SUBMITTED
    inspection.version += 1
    inspection.updated_at = utcnow()
    db.commit()
    db.refresh(inspection)
    return inspection


def review_inspection(
    db: Session,
    inspection_id: uuid.UUID,
    payload: ReviewRequest,
    tenant_id: str | None = None,
) -> tuple[Inspection, Review]:
    inspection = _load_inspection(db, inspection_id, tenant_id)

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

def build_report(
    db: Session,
    inspection_id: uuid.UUID,
    tenant_id: str | None = None,
) -> ReportResponse:
    inspection = _load_inspection(db, inspection_id, tenant_id)

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

def _evidence_satisfied(requirement: str, kinds: set[str]) -> bool:
    if requirement == domain.EvidenceRequirement.NONE:
        return True
    if requirement == domain.EvidenceRequirement.PHOTO:
        return domain.EvidenceKind.PHOTO in kinds
    if requirement == domain.EvidenceRequirement.NOTE:
        return domain.EvidenceKind.NOTE in kinds
    if requirement == domain.EvidenceRequirement.PHOTO_OR_NOTE:
        return bool(kinds)
    return True


def _submit_validation(
    inspection: Inspection,
) -> tuple[list[uuid.UUID], list[dict]]:
    """Validate required items before submission.

    Returns the ids of required items missing their evidence plus a list of
    actionable field errors so the client can point at the exact row/field.
    Required items must have their required evidence *and* a recorded outcome
    (``PASS``/``FAIL``/``NOT_APPLICABLE``) before the inspection can be finalized.
    """
    by_item: dict[uuid.UUID, set[str]] = {}
    for ev in inspection.evidence:
        if ev.checklist_item_id is not None:
            by_item.setdefault(ev.checklist_item_id, set()).add(ev.kind)

    missing: list[uuid.UUID] = []
    errors: list[dict] = []
    for item in inspection.checklist_items:
        if not item.required:
            continue

        kinds = by_item.get(item.id, set())
        if not _evidence_satisfied(item.evidence_requirement, kinds):
            missing.append(item.id)
            requirement = item.evidence_requirement.replace("_or_", " or ")
            errors.append(
                {
                    "field": f"checklist_items.{item.id}.evidence",
                    "code": "MISSING_REQUIRED_EVIDENCE",
                    "message": (
                        f"'{item.label}' requires {requirement} evidence before submission."
                    ),
                }
            )

        if item.status not in domain.ChecklistItemStatus.RESOLVED:
            errors.append(
                {
                    "field": f"checklist_items.{item.id}.status",
                    "code": "OUTCOME_REQUIRED",
                    "message": (
                        f"Set PASS, FAIL or NOT_APPLICABLE for '{item.label}' before submission."
                    ),
                }
            )
    return missing, errors


def _missing_required_evidence(inspection: Inspection) -> list[uuid.UUID]:
    missing, _ = _submit_validation(inspection)
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
