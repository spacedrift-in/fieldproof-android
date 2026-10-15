"""Pydantic request/response models.

These double as the OpenAPI documentation schema, available at ``/docs``.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


# ---------------------------------------------------------------------------
# System
# ---------------------------------------------------------------------------

class HealthResponse(BaseModel):
    status: str
    service: str
    stage: str


class ReadyResponse(BaseModel):
    status: str
    database: str
    storage: str
    storage_backend: str


# ---------------------------------------------------------------------------
# Inspections
# ---------------------------------------------------------------------------

class CreateInspectionRequest(BaseModel):
    """Client-authored inspection. ``id`` is generated on the device while offline.

    Field naming mirrors the existing Android client contract.
    """

    id: uuid.UUID
    title: str = Field(min_length=1, max_length=200)
    location: str = Field(min_length=1, max_length=200)
    createdAt: int = Field(ge=0, description="Client creation time, epoch milliseconds")
    template_id: uuid.UUID | None = Field(
        default=None,
        description="Optional template id; the synthetic demo template is used when omitted.",
    )


class CreateInspectionResponse(BaseModel):
    id: uuid.UUID
    status: str


class ChecklistItemOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    label: str
    required: bool
    evidence_requirement: str
    status: str
    note: str | None = None
    sort_order: int
    version: int


class EvidenceOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    checklist_item_id: uuid.UUID | None = None
    kind: str
    note: str | None = None
    sha256: str
    content_type: str | None = None
    size_bytes: int
    captured_at: datetime
    uploaded_at: datetime


class ReviewOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    reviewer_id: str
    decision: str
    comment: str | None = None
    inspection_version: int
    reviewed_at: datetime


class InspectionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    title: str
    location: str
    status: str
    version: int
    template_id: uuid.UUID | None = None
    created_at: datetime
    updated_at: datetime
    checklist_items: list[ChecklistItemOut] = Field(default_factory=list)
    evidence: list[EvidenceOut] = Field(default_factory=list)
    reviews: list[ReviewOut] = Field(default_factory=list)


class UploadEvidenceResponse(BaseModel):
    """Response mirrors the Android ``UploadEvidenceResponse`` contract."""

    id: uuid.UUID
    status: str


# ---------------------------------------------------------------------------
# Checklist outcomes
# ---------------------------------------------------------------------------

class ChecklistOutcomeUpdate(BaseModel):
    """Set a checklist item's outcome (and optional per-item note).

    ``expected_version`` is the checklist item's server version used for
    optimistic concurrency. Omitting it performs an unconditional update; if the
    item already matches the requested outcome/note the call is an idempotent
    no-op so client retries are safe.
    """

    status: Literal["NOT_STARTED", "PASS", "FAIL", "NOT_APPLICABLE"]
    note: str | None = Field(default=None, max_length=2000)
    expected_version: int | None = Field(
        default=None,
        description="Checklist item version the client last saw; mismatch returns 409.",
    )


class ChecklistOutcomeResponse(BaseModel):
    """Result of a checklist outcome update, carrying the new inspection version."""

    inspection_id: uuid.UUID
    inspection_version: int
    item: ChecklistItemOut


class FieldError(BaseModel):
    """Actionable, field-addressable validation error for a failed submission."""

    field: str
    code: str
    message: str


class SubmitRequest(BaseModel):
    expected_version: int | None = Field(
        default=None,
        description="If provided and it differs from the server version, the submit is rejected with 409.",
    )


class SubmitResponse(BaseModel):
    id: uuid.UUID
    status: str
    version: int


class ReviewRequest(BaseModel):
    decision: Literal["approved", "changes_requested"]
    comment: str | None = Field(default=None, max_length=2000)
    reviewer_id: str = Field(default="demo-reviewer", max_length=200)
    expected_version: int | None = None


class ReviewResponse(BaseModel):
    id: uuid.UUID
    status: str
    version: int
    review: ReviewOut


class ReportChecklistItem(BaseModel):
    id: uuid.UUID
    label: str
    required: bool
    evidence_requirement: str
    status: str
    note: str | None = None
    evidence: list[EvidenceOut] = Field(default_factory=list)


class ReportSummary(BaseModel):
    total_items: int
    passed: int
    failed: int
    not_applicable: int
    not_started: int
    evidence_count: int


class ReportResponse(BaseModel):
    inspection_id: uuid.UUID
    title: str
    location: str
    status: str
    version: int
    created_at: datetime
    updated_at: datetime
    generated_at: datetime
    summary: ReportSummary
    checklist: list[ReportChecklistItem]
    review: ReviewOut | None = None
