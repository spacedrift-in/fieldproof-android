"""Synthetic demo data.

This project uses synthetic data only. The template mirrors
``tests/fixtures/templates.json`` so the demo has required checklist items that
exercise the evidence/submission rules.
"""
from __future__ import annotations

DEMO_TEMPLATE_ID = "f25dcbf4-46f2-4f84-ae1c-63b5a0f33f01"

DEMO_TEMPLATE = {
    "id": DEMO_TEMPLATE_ID,
    "name": "Synthetic Facility Walkthrough",
    "version": 1,
    "description": "Portfolio demo template with synthetic inspection items.",
    "items": [
        {
            "id": "41adf50d-2c24-4e13-9b7e-2dfae32bfe01",
            "label": "Emergency exit is unobstructed",
            "required": True,
            "evidence_requirement": "photo",
        },
        {
            "id": "41adf50d-2c24-4e13-9b7e-2dfae32bfe02",
            "label": "Fire extinguisher is accessible",
            "required": True,
            "evidence_requirement": "photo",
        },
        {
            "id": "41adf50d-2c24-4e13-9b7e-2dfae32bfe03",
            "label": "General observations",
            "required": False,
            "evidence_requirement": "note",
        },
    ],
}
