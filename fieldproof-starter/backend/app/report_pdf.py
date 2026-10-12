"""PDF rendering for reviewed inspection reports.

This is a demo inspection report, not a compliance certification. It renders a
point-in-time snapshot of an APPROVED inspection (checklist statuses, evidence
references, reviewer signoff) using the same data as the JSON report endpoint.
"""
from __future__ import annotations

from datetime import datetime, timezone
from io import BytesIO
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from .schemas import ReportResponse


def _utc_label(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def _status_label(status: str) -> str:
    return status.replace("_", " ").title()


def _esc(value: object) -> str:
    """Escape user-authored text so it is safe inside a ReportLab paragraph.

    Values like ``R&D`` or ``<valve>`` are valid inspection content but would
    otherwise be parsed as XML markup and abort PDF generation.
    """
    return escape(str(value))


def render_report_pdf(payload: ReportResponse) -> bytes:
    buffer = BytesIO()

    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        "FieldProofTitle",
        parent=styles["Title"],
        spaceAfter=2 * mm,
    )
    subtitle_style = ParagraphStyle(
        "FieldProofSubtitle",
        parent=styles["Normal"],
        fontSize=10,
        textColor=colors.HexColor("#555555"),
        spaceAfter=6 * mm,
    )
    heading_style = ParagraphStyle(
        "FieldProofHeading",
        parent=styles["Heading2"],
        spaceBefore=6 * mm,
        spaceAfter=3 * mm,
    )
    body_style = styles["BodyText"]
    note_style = ParagraphStyle(
        "FieldProofNote",
        parent=styles["BodyText"],
        fontSize=9,
        textColor=colors.HexColor("#555555"),
        spaceAfter=5 * mm,
    )

    doc = SimpleDocTemplate(
        buffer,
        pagesize=A4,
        leftMargin=18 * mm,
        rightMargin=18 * mm,
        topMargin=16 * mm,
        bottomMargin=16 * mm,
        title=f"FieldProof Inspection Report {payload.inspection_id}",
        author="FieldProof (demo)",
    )

    story: list = []

    story.append(Paragraph("FieldProof Inspection Report", title_style))
    story.append(
        Paragraph(
            "Demo inspection report. This document is not a compliance "
            "certification.",
            subtitle_style,
        )
    )

    story.append(Paragraph(_esc(payload.title), heading_style))
    story.append(Paragraph(f"Location: {_esc(payload.location)}", body_style))
    story.append(
        Paragraph(
            f"Inspection ID: {payload.inspection_id}",
            body_style,
        )
    )
    story.append(
        Paragraph(
            f"Status: {_status_label(payload.status)} · "
            f"Version {payload.version}",
            body_style,
        )
    )
    story.append(
        Paragraph(
            f"Created: {_utc_label(payload.created_at)} · "
            f"Last updated: {_utc_label(payload.updated_at)}",
            body_style,
        )
    )
    if payload.review is not None:
        review = payload.review
        story.append(
            Paragraph(
                f"Reviewer: {_esc(review.reviewer_id)} · "
                f"Decision: {_esc(review.decision.replace('_', ' ').title())} · "
                f"Reviewed: {_utc_label(review.reviewed_at)} · "
                f"Snapshot version {review.inspection_version}",
                body_style,
            )
        )
        if review.comment:
            story.append(
                Paragraph(
                    f"Reviewer comment: {_esc(review.comment)}", body_style
                )
            )

    story.append(Paragraph("Summary", heading_style))
    summary = payload.summary
    story.append(
        Paragraph(
            f"Checklist items: {summary.total_items} "
            f"(passed {summary.passed} · failed {summary.failed} · "
            f"not started {summary.not_started} · "
            f"n/a {summary.not_applicable}) · "
            f"Evidence captured: {summary.evidence_count}",
            body_style,
        )
    )

    story.append(Paragraph("Checklist and evidence", heading_style))

    rows = [
        [
            Paragraph("<b>Item</b>", body_style),
            Paragraph("<b>Status</b>", body_style),
            Paragraph("<b>Evidence</b>", body_style),
            Paragraph("<b>Notes</b>", body_style),
        ]
    ]
    for item in payload.checklist:
        evidence_kinds = [
            _evidence_label(ev) for ev in item.evidence
        ] or ["—"]
        rows.append(
            [
                Paragraph(_esc(item.label), body_style),
                Paragraph(_esc(_status_label(item.status)), body_style),
                Paragraph(", ".join(evidence_kinds), body_style),
                Paragraph(_esc(item.note) if item.note else "—", body_style),
            ]
        )

    table = Table(
        rows,
        colWidths=[72 * mm, 36 * mm, 42 * mm, 40 * mm],
        repeatRows=1,
    )
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#eef2f6")),
                ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#cccccc")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 4),
                ("RIGHTPADDING", (0, 0), (-1, -1), 4),
                ("TOPPADDING", (0, 0), (-1, -1), 4),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
            ]
        )
    )
    story.append(table)

    story.append(Spacer(1, 6 * mm))
    story.append(
        Paragraph(
            f"Report generated: {_utc_label(payload.generated_at)}. "
            "Snapshot reference: inspection version "
            f"{payload.review.inspection_version if payload.review else payload.version}.",
            note_style,
        )
    )

    doc.build(story)
    return buffer.getvalue()


def _evidence_label(evidence) -> str:
    short_id = str(evidence.id)[:8]
    kind = evidence.kind
    if kind == "note" and evidence.note:
        snippet = evidence.note[:60]
        if len(evidence.note) > 60:
            snippet += "…"
        return f"note {short_id} ({_esc(snippet)})"
    return f"{kind} {short_id}"