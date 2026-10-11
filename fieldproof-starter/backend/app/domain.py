"""Domain vocabulary shared by the API layer.

Status strings are stored uppercase to stay compatible with the Android client,
which seeds local inspections with status ``DRAFT``.
"""
from __future__ import annotations


class InspectionStatus:
    DRAFT = "DRAFT"
    IN_PROGRESS = "IN_PROGRESS"
    SUBMITTED = "SUBMITTED"
    CHANGES_REQUESTED = "CHANGES_REQUESTED"
    APPROVED = "APPROVED"

    ALL = {DRAFT, IN_PROGRESS, SUBMITTED, CHANGES_REQUESTED, APPROVED}
    # Statuses where new evidence may still be attached.
    EVIDENCE_EDITABLE = {DRAFT, IN_PROGRESS, CHANGES_REQUESTED}
    # Statuses from which a submission is allowed.
    SUBMITTABLE = {DRAFT, IN_PROGRESS, CHANGES_REQUESTED}


class EvidenceKind:
    PHOTO = "photo"
    NOTE = "note"

    ALL = {PHOTO, NOTE}


class EvidenceRequirement:
    NONE = "none"
    PHOTO = "photo"
    NOTE = "note"
    PHOTO_OR_NOTE = "photo_or_note"

    ALL = {NONE, PHOTO, NOTE, PHOTO_OR_NOTE}


class ChecklistItemStatus:
    NOT_STARTED = "NOT_STARTED"
    PASS = "PASS"
    FAIL = "FAIL"
    NOT_APPLICABLE = "NOT_APPLICABLE"


class ReviewDecision:
    APPROVED = "approved"
    CHANGES_REQUESTED = "changes_requested"

    ALL = {APPROVED, CHANGES_REQUESTED}
