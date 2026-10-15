"""Acceptance tests for the generated inspection report PDF.

These build a :class:`ReportResponse` directly (no database required) and render
it through the same code path the ``/report.pdf`` endpoint uses, then assert the
document matches the documented criteria in ``docs/acceptance-criteria.md``:

    "PDF includes inspection summary, checklist outcomes, evidence
    references/images, timestamps, and reviewer decision."
"""
from __future__ import annotations

import io
import re
import uuid
from datetime import datetime, timezone

import pytest
from pypdf import PdfReader

from backend.app.report_pdf import render_report_pdf
from backend.app.schemas import (
    EvidenceOut,
    ReportChecklistItem,
    ReportResponse,
    ReportSummary,
    ReviewOut,
)

# Stable ids so the test can assert the evidence reference is traceable.
PHOTO_ID = uuid.UUID("8b9a7f6e-5d4c-4b1a-b907-2e3f4a5b6c7d")
NOTE_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
INSPECTION_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")

REVIEWED_AT = datetime(2026, 10, 11, 15, 10, tzinfo=timezone.utc)
GENERATED_AT = datetime(2026, 10, 11, 16, 25, tzinfo=timezone.utc)

NOTE_TEXT = "Handrail loose on stair B; retest <after> fix & re-torque."


def _report(*, title="Synthetic Facility Walkthrough", location="Unit A") -> ReportResponse:
    photo = EvidenceOut(
        id=PHOTO_ID,
        checklist_item_id=None,
        kind="photo",
        note=None,
        sha256="a" * 64,
        content_type="image/jpeg",
        size_bytes=190_000,
        captured_at=REVIEWED_AT,
        uploaded_at=REVIEWED_AT,
    )
    note = EvidenceOut(
        id=NOTE_ID,
        checklist_item_id=None,
        kind="note",
        note=NOTE_TEXT,
        sha256="b" * 64,
        content_type=None,
        size_bytes=0,
        captured_at=REVIEWED_AT,
        uploaded_at=REVIEWED_AT,
    )
    checklist = [
        ReportChecklistItem(
            id=uuid.uuid4(),
            label="Emergency exit is unobstructed",
            required=True,
            evidence_requirement="photo_or_note",
            status="PASS",
            note="Clear after clearing pallets",
            evidence=[photo, note],
        ),
        ReportChecklistItem(
            id=uuid.uuid4(),
            label="Fire extinguisher is accessible",
            required=True,
            evidence_requirement="photo",
            status="FAIL",
            note=None,
            evidence=[],
        ),
        ReportChecklistItem(
            id=uuid.uuid4(),
            label="General observations",
            required=False,
            evidence_requirement="none",
            status="NOT_APPLICABLE",
            note=None,
            evidence=[],
        ),
    ]
    return ReportResponse(
        inspection_id=INSPECTION_ID,
        title=title,
        location=location,
        status="APPROVED",
        version=6,
        created_at=datetime(2026, 10, 11, 15, 0, tzinfo=timezone.utc),
        updated_at=datetime(2026, 10, 11, 15, 10, tzinfo=timezone.utc),
        generated_at=GENERATED_AT,
        summary=ReportSummary(
            total_items=3,
            passed=1,
            failed=1,
            not_applicable=1,
            not_started=0,
            evidence_count=2,
        ),
        checklist=checklist,
        review=ReviewOut(
            id=uuid.uuid4(),
            reviewer_id="demo-reviewer",
            decision="approved",
            comment="Synthetic approval: all required evidence present.",
            inspection_version=5,
            reviewed_at=REVIEWED_AT,
        ),
    )


def _pdf_text(payload: ReportResponse) -> str:
    reader = PdfReader(io.BytesIO(render_report_pdf(payload)))
    text = "\n".join(page.extract_text() or "" for page in reader.pages)
    # ReportLab wraps table cells across lines; normalise whitespace so assertions
    # are about content, not layout.
    return re.sub(r"\s+", " ", text)


def test_report_pdf_matches_documented_criteria():
    text = _pdf_text(_report())

    # 1. Inspection summary.
    assert "FieldProof Inspection Report" in text
    assert "Synthetic Facility Walkthrough" in text
    assert f"Inspection ID: {INSPECTION_ID}" in text
    assert "Status: Approved · Version 6" in text
    assert "Summary" in text
    assert "Checklist items: 3 (passed 1 · failed 1 · not started 0 · n/a 1)" in text
    assert "Evidence captured: 2" in text

    # 2. Checklist outcomes.
    assert "Checklist and evidence" in text
    assert "Emergency exit is unobstructed" in text
    assert "Pass" in text
    assert "Fail" in text
    assert "Not Applicable" in text

    # 3. Evidence references (traceable id + kind + note snippet).
    assert f"photo {str(PHOTO_ID)[:8]}" in text
    assert f"note {str(NOTE_ID)[:8]}" in text
    assert "Handrail loose on stair B; retest <after> fix & re-torque." in text

    # 4. Timestamps.
    assert "Created: 2026-10-11 15:00 UTC" in text
    assert "Last updated: 2026-10-11 15:10 UTC" in text
    assert "Reviewed: 2026-10-11 15:10 UTC" in text
    assert "Report generated: 2026-10-11 16:25 UTC" in text

    # 5. Reviewer decision.
    assert "Reviewer: demo-reviewer" in text
    assert "Decision: Approved" in text
    assert "Synthetic approval: all required evidence present." in text


def test_report_pdf_escapes_markup_in_user_text():
    """``&``/``<``/``>`` in titles, labels, notes and comments must not break or
    alter the document."""
    payload = _report(title="Facilities <North> & <South>", location="Bay 2 & 3")
    payload.checklist[0].label = "Pipe & fitting <inspection>"
    payload.checklist[0].note = "A & B < C"
    payload.review.comment = "Approved <ok> & filed"

    text = _pdf_text(payload)

    assert "Facilities <North> & <South>" in text
    assert "Bay 2 & 3" in text
    assert "Pipe & fitting <inspection>" in text
    assert "A & B < C" in text
    assert "Approved <ok> & filed" in text
    # Escaping must not leak XML entities into the rendered text.
    assert "&amp;" not in text
    assert "&lt;" not in text


def test_report_pdf_renders_without_a_review():
    payload = _report()
    payload.review = None
    payload.status = "SUBMITTED"
    text = _pdf_text(payload)
    assert "Reviewer:" not in text
    assert "Report generated:" in text


@pytest.mark.parametrize("bad", ["<not-a-tag>", "R&D", "100% & rising"])
def test_evidence_note_never_aborts_rendering(bad):
    payload = _report()
    payload.checklist[0].evidence[1].note = bad
    assert render_report_pdf(payload).startswith(b"%PDF")
