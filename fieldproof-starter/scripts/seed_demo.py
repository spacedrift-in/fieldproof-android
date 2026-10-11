#!/usr/bin/env python3
"""Seed the FieldProof API with a fully synthetic demo dataset.

Everything created here is fabricated: the facility, the checklist outcomes, the
photo and the notes contain no personal or private client data. The seeded data
exists so a reviewer can reproduce the demo (submit -> approve -> PDF report)
without any private data.

How to run (from the repo root, with the API already running):

    ../.venv/bin/python scripts/seed_demo.py

or:

    FIELDPROOF_BASE_URL=http://127.0.0.1:8000 python scripts/seed_demo.py

Idempotency: writes use stable synthetic UUIDs and fixed ``Idempotency-Key``
values, so re-running the script returns the original responses and does not
create duplicates. Submit/review intentionally tolerate the "already in a later
state" 409 so the script can be replayed after an inspection was approved.
"""
from __future__ import annotations

import hashlib
import os
import struct
import sys
import uuid
import zlib
from pathlib import Path

import httpx

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURES_DIR = REPO_ROOT / "tests" / "fixtures"
SCREENSHOTS_DIR = REPO_ROOT / "docs" / "screenshots"
PHOTO_FIXTURE = FIXTURES_DIR / "evidence-sample.png"
SAMPLE_REPORT_PDF = SCREENSHOTS_DIR / "sample-report.pdf"

DEMO_TEMPLATE_ID = "f25dcbf4-46f2-4f84-ae1c-63b5a0f33f01"
TEMPLATE_ITEMS = [
    {
        "id": "41adf50d-2c24-4e13-9b7e-2dfae32bfe01",
        "label": "Emergency exit is unobstructed",
        "evidence_requirement": "photo",
    },
    {
        "id": "41adf50d-2c24-4e13-9b7e-2dfae32bfe02",
        "label": "Fire extinguisher is accessible",
        "evidence_requirement": "photo",
    },
    {
        "id": "41adf50d-2c24-4e13-9b7e-2dfae32bfe03",
        "label": "General observations",
        "evidence_requirement": "note",
    },
]

# Stable synthetic inspection id so names, evidence ids and idempotency keys
# are the same on every seed run.
INSPECTION_ID = uuid.UUID("7f6d5c4b-3a29-4817-9e05-0a1b2c3d4e5f")
INSPECTION_TITLE = "Synthetic Facility Walkthrough"
INSPECTION_LOCATION = "Unit A, Synthetic Facility Plot 7"
PHOTO_EVIDENCE_ID = uuid.UUID("8a7f6e5d-4c3b-4a19-b806-1d2e3f4a5b6c")
PHOTO_EVIDENCE_ID_2 = uuid.UUID("8b9a7f6e-5d4c-4b1a-b907-2e3f4a5b6c7d")
NOTE_EVIDENCE_ID = uuid.UUID("9a8b7c6d-5e4f-4a28-c917-2e3f4a5b6c7d")
REVIEWER_ID = "demo-reviewer"

BASE_URL = os.environ.get("FIELDPROOF_BASE_URL", "http://127.0.0.1:8000")
API = f"{BASE_URL}/api/v1"


def checklist_item_id(inspection_id: uuid.UUID, template_item_id: str) -> uuid.UUID:
    """Mirror the Android/backend derivation (Java UUID.nameUUIDFromBytes)."""
    seed = f"{inspection_id}:{template_item_id}".encode("utf-8")
    digest = bytearray(hashlib.md5(seed).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


def make_synthetic_photo() -> bytes:
    """Build a small, deterministic PNG placeholder with zero private data."""
    width, height = 640, 480
    rows = bytearray()
    for y in range(height):
        rows.append(0)  # PNG filter type 0 (None) per scanline
        for x in range(width):
            r = (x * 255) // width
            g = (y * 255) // height
            b = 0 if (x // 40 + y // 40) % 2 else 200  # checker tint
            rows += bytes((r & 0xFF, g & 0xFF, b & 0xFF, 255))
    compressed = zlib.compress(bytes(rows), 9)

    def chunk(tag: bytes, payload: bytes) -> bytes:
        length = struct.pack(">I", len(payload))
        crc = struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        return length + tag + payload + crc

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", compressed)
        + chunk(b"IEND", b"")
    )
    return png


def ensure_photo_fixture() -> bytes:
    if PHOTO_FIXTURE.exists() and PHOTO_FIXTURE.stat().st_size > 0:
        return PHOTO_FIXTURE.read_bytes()
    PHOTO_FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    data = make_synthetic_photo()
    PHOTO_FIXTURE.write_bytes(data)
    print(f"[fixture] wrote {PHOTO_FIXTURE.relative_to(REPO_ROOT)} ({len(data)} bytes)")
    return data


def checksum(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:16]


def create_inspection(client: httpx.Client) -> None:
    key = f"seed:inspection:{INSPECTION_ID}"
    payload = {
        "id": str(INSPECTION_ID),
        "title": INSPECTION_TITLE,
        "location": INSPECTION_LOCATION,
        "createdAt": 1_700_000_000_000,
    }
    response = client.post(
        f"{API}/inspections",
        json=payload,
        headers={"Idempotency-Key": key},
    )
    if response.status_code not in (200, 201):
        response.raise_for_status()
    print(f"[inspection] {INSPECTION_TITLE!r} at {INSPECTION_LOCATION!r} -> {response.json()}")


def upload_photo(client: httpx.Client, photo: bytes, *, item_index: int, evidence_id: uuid.UUID) -> None:
    item = TEMPLATE_ITEMS[item_index]
    item_id = checklist_item_id(INSPECTION_ID, item["id"])
    key = f"seed:evidence:{evidence_id}"
    response = client.post(
        f"{API}/inspections/{INSPECTION_ID}/evidence",
        headers={"Idempotency-Key": key},
        data={
            "id": str(evidence_id),
            "kind": "photo",
            "checklist_item_id": str(item_id),
            "captured_at": "1700000100000",
        },
        files={"file": ("evidence-sample.png", photo, "image/png")},
    )
    if response.status_code not in (200, 201):
        response.raise_for_status()
    print(
        f"[photo] synthetic photo evidence for {item['label']!r} "
        f"(sha256:{checksum(photo)}) -> {response.json()}"
    )


def upload_note(client: httpx.Client) -> None:
    item_id = checklist_item_id(INSPECTION_ID, TEMPLATE_ITEMS[2]["id"])
    key = f"seed:evidence:{NOTE_EVIDENCE_ID}"
    response = client.post(
        f"{API}/inspections/{INSPECTION_ID}/evidence",
        headers={"Idempotency-Key": key},
        data={
            "id": str(NOTE_EVIDENCE_ID),
            "kind": "note",
            "checklist_item_id": str(item_id),
            "note": "Synthetic note: dead-leg valve flow-tested, tag FST-0042.",
            "captured_at": "1700000200000",
        },
    )
    if response.status_code not in (200, 201):
        response.raise_for_status()
    print(f"[note] synthetic note evidence -> {response.json()}")


def submit(client: httpx.Client, tolerated: set[int]) -> None:
    response = client.post(f"{API}/inspections/{INSPECTION_ID}/submit", json={})
    if response.status_code in tolerated:
        print(f"[submit] already in a later state ({response.status_code}): {response.text}")
        return
    response.raise_for_status()
    print(f"[submit] -> {response.json()}")


def review_approve(client: httpx.Client, tolerated: set[int]) -> None:
    response = client.post(
        f"{API}/inspections/{INSPECTION_ID}/review",
        json={
            "decision": "approved",
            "comment": "Synthetic approval: all required evidence present.",
            "reviewer_id": REVIEWER_ID,
        },
    )
    if response.status_code in tolerated:
        print(f"[review] already approved ({response.status_code}): {response.text}")
        return
    response.raise_for_status()
    body = response.json()
    print(f"[review] approved by {REVIEWER_ID} (version {body['version']})")


def export_report(client: httpx.Client) -> None:
    report = client.get(f"{API}/inspections/{INSPECTION_ID}/report")
    report.raise_for_status()
    summary = report.json()["summary"]
    print(
        "[report] JSON summary: "
        f"{summary['passed']} passed / {summary['failed']} failed / "
        f"{summary['evidence_count']} evidence items"
    )

    SCREENSHOTS_DIR.mkdir(parents=True, exist_ok=True)
    pdf = client.get(f"{API}/inspections/{INSPECTION_ID}/report.pdf")
    pdf.raise_for_status()
    SAMPLE_REPORT_PDF.write_bytes(pdf.content)
    print(
        f"[report] PDF saved to {SAMPLE_REPORT_PDF.relative_to(REPO_ROOT)} "
        f"({len(pdf.content)} bytes)"
    )


def main() -> int:
    photo = ensure_photo_fixture()
    print(f"[base] {BASE_URL} (template {DEMO_TEMPLATE_ID})")

    with httpx.Client(timeout=30) as client:
        health = client.get(f"{BASE_URL}/health")
        health.raise_for_status()
        print(f"[health] {health.json()['status']}")

        create_inspection(client)
        upload_photo(client, photo, item_index=0, evidence_id=PHOTO_EVIDENCE_ID)
        upload_photo(client, photo, item_index=1, evidence_id=PHOTO_EVIDENCE_ID_2)
        upload_note(client)
        submit(client, tolerated={409})
        review_approve(client, tolerated={409})
        export_report(client)

    print("\nDone. Synthetic demo data only; no private data was used or stored.")
    return 0


if __name__ == "__main__":
    sys.exit(main())