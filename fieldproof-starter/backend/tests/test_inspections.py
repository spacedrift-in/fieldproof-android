"""End-to-end tests for the Postgres-backed inspection API.

The ``client`` fixture (see ``conftest.py``) points at a disposable PostgreSQL
database and resets the schema between tests.
"""
from __future__ import annotations

import base64
import hashlib
import uuid

# A tiny valid 1x1 PNG; the API only checks that photos are image/* uploads.
PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


def _create(client, *, key=None, inspection_id=None, title="Facility A", location="Building 1"):
    inspection_id = inspection_id or str(uuid.uuid4())
    key = key or str(uuid.uuid4())
    body = {
        "id": inspection_id,
        "title": title,
        "location": location,
        "createdAt": 1_700_000_000_000,
    }
    response = client.post("/api/v1/inspections", json=body, headers={"Idempotency-Key": key})
    return response, inspection_id, key, body


def _get(client, inspection_id):
    return client.get(f"/api/v1/inspections/{inspection_id}")


def _upload(client, inspection_id, item_id, *, key, evidence_id, content=PNG_BYTES, filename="photo.png", content_type="image/png", kind="photo"):
    return client.post(
        f"/api/v1/inspections/{inspection_id}/evidence",
        data={
            "id": str(evidence_id),
            "kind": kind,
            "checklist_item_id": str(item_id) if item_id else "",
        },
        files={"file": (filename, content, content_type)},
        headers={"Idempotency-Key": key},
    )


def _upload_note(client, inspection_id, item_id, *, key, evidence_id, note):
    return client.post(
        f"/api/v1/inspections/{inspection_id}/evidence",
        data={
            "id": str(evidence_id),
            "kind": "note",
            "checklist_item_id": str(item_id) if item_id else "",
            "note": note,
        },
        headers={"Idempotency-Key": key},
    )


def _required_items(client, inspection_id):
    payload = _get(client, inspection_id).json()
    return [item for item in payload["checklist_items"] if item["required"]]


def _checklist_items(client, inspection_id):
    return _get(client, inspection_id).json()["checklist_items"]


def _set_outcome(
    client,
    inspection_id,
    item_id,
    status,
    *,
    note=None,
    expected_version=None,
):
    body = {"status": status}
    if note is not None:
        body["note"] = note
    if expected_version is not None:
        body["expected_version"] = expected_version
    return client.put(
        f"/api/v1/inspections/{inspection_id}/checklist/{item_id}",
        json=body,
    )


def _record_required_outcomes(client, inspection_id):
    for item in _required_items(client, inspection_id):
        response = _set_outcome(client, inspection_id, item["id"], "PASS")
        assert response.status_code == 200
    return inspection_id


# ---------------------------------------------------------------------------
# Creation + idempotency
# ---------------------------------------------------------------------------

def test_create_inspection_seeds_checklist(client):
    response, inspection_id, _, _ = _create(client)
    assert response.status_code == 201
    assert response.json() == {"id": inspection_id, "status": "DRAFT"}

    fetched = _get(client, inspection_id)
    assert fetched.status_code == 200
    payload = fetched.json()
    assert payload["status"] == "DRAFT"
    assert payload["version"] == 1
    assert len(payload["checklist_items"]) == 3
    assert sum(1 for item in payload["checklist_items"] if item["required"]) == 2


def _java_name_uuid(data: bytes) -> uuid.UUID:
    """Replica of Java/Kotlin UUID.nameUUIDFromBytes (MD5, version 3, RFC variant)."""
    digest = bytearray(hashlib.md5(data).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


def test_checklist_item_ids_match_client_derivation(client):
    response, inspection_id, _, _ = _create(client)
    assert response.status_code == 201

    DEMO_TEMPLATE_ITEM_IDS = [
        "41adf50d-2c24-4e13-9b7e-2dfae32bfe01",
        "41adf50d-2c24-4e13-9b7e-2dfae32bfe02",
        "41adf50d-2c24-4e13-9b7e-2dfae32bfe03",
    ]
    expected = {
        str(_java_name_uuid(f"{inspection_id}:{template_id}".encode("utf-8")))
        for template_id in DEMO_TEMPLATE_ITEM_IDS
    }

    payload = _get(client, inspection_id).json()
    assert {item["id"] for item in payload["checklist_items"]} == expected


def test_create_inspection_is_idempotent(client):
    response, inspection_id, key, body = _create(client)
    assert response.status_code == 201

    replay = client.post("/api/v1/inspections", json=body, headers={"Idempotency-Key": key})
    assert replay.status_code == 201
    assert replay.json()["id"] == inspection_id

    # A different key but the same inspection id returns the existing record (200).
    same_id = client.post(
        "/api/v1/inspections", json=body, headers={"Idempotency-Key": str(uuid.uuid4())}
    )
    assert same_id.status_code == 200
    assert same_id.json()["id"] == inspection_id


def test_idempotency_key_reuse_with_different_body_conflicts(client):
    _, _, key, _ = _create(client)
    response = client.post(
        "/api/v1/inspections",
        json={
            "id": str(uuid.uuid4()),
            "title": "Different",
            "location": "Elsewhere",
            "createdAt": 1_700_000_000_001,
        },
        headers={"Idempotency-Key": key},
    )
    assert response.status_code == 409


def test_same_inspection_id_with_different_attributes_conflicts(client):
    _, inspection_id, _, _ = _create(client)
    response = client.post(
        "/api/v1/inspections",
        json={
            "id": inspection_id,
            "title": "Changed title",
            "location": "Building 1",
            "createdAt": 1_700_000_000_000,
        },
        headers={"Idempotency-Key": str(uuid.uuid4())},
    )
    assert response.status_code == 409


def test_missing_idempotency_key_is_rejected(client):
    response = client.post(
        "/api/v1/inspections",
        json={
            "id": str(uuid.uuid4()),
            "title": "Facility A",
            "location": "Building 1",
            "createdAt": 1_700_000_000_000,
        },
    )
    assert response.status_code == 400


def test_get_unknown_inspection_returns_404(client):
    assert _get(client, str(uuid.uuid4())).status_code == 404


# ---------------------------------------------------------------------------
# Evidence upload
# ---------------------------------------------------------------------------

def test_evidence_upload_is_idempotent(client):
    _, inspection_id, _, _ = _create(client)
    item = _required_items(client, inspection_id)[0]
    evidence_id = uuid.uuid4()
    key = str(uuid.uuid4())

    first = _upload(client, inspection_id, item["id"], key=key, evidence_id=evidence_id)
    assert first.status_code == 201
    assert first.json() == {"id": str(evidence_id), "status": "SYNCED"}

    replay = _upload(client, inspection_id, item["id"], key=key, evidence_id=evidence_id)
    assert replay.status_code == 201
    assert replay.json() == {"id": str(evidence_id), "status": "SYNCED"}

    payload = _get(client, inspection_id).json()
    assert len(payload["evidence"]) == 1


def test_evidence_key_reuse_with_different_content_conflicts(client):
    _, inspection_id, _, _ = _create(client)
    item = _required_items(client, inspection_id)[0]
    evidence_id = uuid.uuid4()
    key = str(uuid.uuid4())

    assert _upload(client, inspection_id, item["id"], key=key, evidence_id=evidence_id).status_code == 201
    conflict = _upload(
        client, inspection_id, item["id"], key=key, evidence_id=evidence_id, content=PNG_BYTES + b"x"
    )
    assert conflict.status_code == 409


def test_note_evidence_persists_content(client):
    _, inspection_id, _, _ = _create(client)
    item = _required_items(client, inspection_id)[0]
    evidence_id = uuid.uuid4()

    response = _upload_note(
        client,
        inspection_id,
        item["id"],
        key=str(uuid.uuid4()),
        evidence_id=evidence_id,
        note="Handrail loose",
    )
    assert response.status_code == 201
    assert response.json() == {"id": str(evidence_id), "status": "SYNCED"}

    payload = _get(client, inspection_id).json()
    saved = [e for e in payload["evidence"] if e["id"] == str(evidence_id)]
    assert len(saved) == 1
    assert saved[0]["kind"] == "note"
    assert saved[0]["note"] == "Handrail loose"


def test_note_requires_non_empty_text(client):
    _, inspection_id, _, _ = _create(client)
    item = _required_items(client, inspection_id)[0]
    response = _upload_note(
        client,
        inspection_id,
        item["id"],
        key=str(uuid.uuid4()),
        evidence_id=uuid.uuid4(),
        note="   ",
    )
    assert response.status_code == 422


def test_photo_requires_a_file(client):
    _, inspection_id, _, _ = _create(client)
    item = _required_items(client, inspection_id)[0]
    response = client.post(
        f"/api/v1/inspections/{inspection_id}/evidence",
        data={"id": str(uuid.uuid4()), "kind": "photo", "checklist_item_id": item["id"]},
        headers={"Idempotency-Key": str(uuid.uuid4())},
    )
    assert response.status_code == 422


def test_photo_rejects_non_image(client):
    _, inspection_id, _, _ = _create(client)
    item = _required_items(client, inspection_id)[0]
    response = _upload(
        client,
        inspection_id,
        item["id"],
        key=str(uuid.uuid4()),
        evidence_id=uuid.uuid4(),
        filename="notes.txt",
        content=b"not an image",
        content_type="text/plain",
    )
    assert response.status_code == 415


# ---------------------------------------------------------------------------
# Submit
# ---------------------------------------------------------------------------

def test_submit_blocked_without_required_evidence(client):
    _, inspection_id, _, _ = _create(client)
    response = client.post(f"/api/v1/inspections/{inspection_id}/submit", json={})
    assert response.status_code == 409
    detail = response.json()["detail"]
    assert len(detail["missing_checklist_item_ids"]) == 2

    # Field errors are actionable: each names the offending field and a code.
    errors = detail["errors"]
    codes = {error["code"] for error in errors}
    assert "MISSING_REQUIRED_EVIDENCE" in codes
    assert "OUTCOME_REQUIRED" in codes
    for error in errors:
        assert error["field"].startswith("checklist_items.")
        assert error["message"]


def test_submit_blocked_when_required_item_has_no_outcome(client):
    _, inspection_id, _, _ = _create(client)
    for item in _required_items(client, inspection_id):
        _upload(client, inspection_id, item["id"], key=str(uuid.uuid4()), evidence_id=uuid.uuid4())

    response = client.post(f"/api/v1/inspections/{inspection_id}/submit", json={})
    assert response.status_code == 409
    detail = response.json()["detail"]
    # Evidence is present, so the only remaining field errors are missing outcomes.
    assert detail["missing_checklist_item_ids"] == []
    assert {error["code"] for error in detail["errors"]} == {"OUTCOME_REQUIRED"}
    assert len(detail["errors"]) == 2


def test_submit_then_double_submit_conflicts(client):
    _, inspection_id, _, _ = _create(client)
    for item in _required_items(client, inspection_id):
        assert _upload(
            client, inspection_id, item["id"], key=str(uuid.uuid4()), evidence_id=uuid.uuid4()
        ).status_code == 201
    _record_required_outcomes(client, inspection_id)

    first = client.post(f"/api/v1/inspections/{inspection_id}/submit", json={})
    assert first.status_code == 200
    assert first.json()["status"] == "SUBMITTED"

    second = client.post(f"/api/v1/inspections/{inspection_id}/submit", json={})
    assert second.status_code == 409


def test_submit_version_conflict(client):
    _, inspection_id, _, _ = _create(client)
    response = client.post(
        f"/api/v1/inspections/{inspection_id}/submit", json={"expected_version": 999}
    )
    assert response.status_code == 409
    assert response.json()["detail"]["current_version"] == 1


# ---------------------------------------------------------------------------
# Checklist outcomes
# ---------------------------------------------------------------------------

def test_checklist_outcome_persists_status_and_note(client):
    _, inspection_id, _, _ = _create(client)
    item = _checklist_items(client, inspection_id)[0]

    response = _set_outcome(
        client,
        inspection_id,
        item["id"],
        "FAIL",
        note="  Loose handrail  ",
    )

    assert response.status_code == 200
    body = response.json()
    assert body["item"]["status"] == "FAIL"
    assert body["item"]["note"] == "Loose handrail"
    assert body["item"]["version"] == item["version"] + 1

    fetched = {i["id"]: i for i in _checklist_items(client, inspection_id)}
    assert fetched[item["id"]]["status"] == "FAIL"
    assert fetched[item["id"]]["note"] == "Loose handrail"


def test_checklist_outcome_replay_is_idempotent(client):
    _, inspection_id, _, _ = _create(client)
    item = _checklist_items(client, inspection_id)[0]

    first = _set_outcome(client, inspection_id, item["id"], "PASS", note="Clear")
    assert first.status_code == 200
    version = first.json()["item"]["version"]

    replay = _set_outcome(client, inspection_id, item["id"], "PASS", note="Clear")
    assert replay.status_code == 200
    # No version bump and the inspection version is unchanged on a replay.
    assert replay.json()["item"]["version"] == version
    assert replay.json()["inspection_version"] == first.json()["inspection_version"]


def test_checklist_outcome_version_conflict(client):
    _, inspection_id, _, _ = _create(client)
    item = _checklist_items(client, inspection_id)[0]

    # Optimistic concurrency: a stale expected_version is rejected with the
    # server's current item version so the client can resolve the conflict.
    conflict = _set_outcome(
        client,
        inspection_id,
        item["id"],
        "PASS",
        expected_version=999,
    )
    assert conflict.status_code == 409
    detail = conflict.json()["detail"]
    assert detail["current_version"] == item["version"]
    assert detail["checklist_item_id"] == item["id"]

    # A correct expected_version succeeds and is reusable for a retry.
    ok = _set_outcome(
        client,
        inspection_id,
        item["id"],
        "PASS",
        expected_version=item["version"],
    )
    assert ok.status_code == 200


def test_checklist_outcome_rejects_unknown_item(client):
    _, inspection_id, _, _ = _create(client)
    response = _set_outcome(client, inspection_id, str(uuid.uuid4()), "PASS")
    assert response.status_code == 404


def test_checklist_outcome_lost_response_retry_succeeds(client):
    """A retry whose response was lost must confirm the committed replay.

    The client sends v1→PASS, the server commits v2, but the 200 is lost. The
    retry still carries ``expected_version=v1``. It must return success with the
    already-committed state (no false 409, no extra version bump) so the client
    can mark the item synced.
    """
    _, inspection_id, _, _ = _create(client)
    item = _checklist_items(client, inspection_id)[0]
    original_version = item["version"]

    first = _set_outcome(
        client,
        inspection_id,
        item["id"],
        "PASS",
        note="Clear",
        expected_version=original_version,
    )
    assert first.status_code == 200
    committed = first.json()

    # Replay the exact same mutation with the now-stale expected_version.
    replay = _set_outcome(
        client,
        inspection_id,
        item["id"],
        "PASS",
        note="Clear",
        expected_version=original_version,
    )
    assert replay.status_code == 200
    assert replay.json()["item"]["version"] == committed["item"]["version"]
    assert replay.json()["inspection_version"] == committed["inspection_version"]


def test_checklist_outcome_stale_version_with_different_value_conflicts(client):
    """A stale expected_version with a *different* value is a real conflict."""
    _, inspection_id, _, _ = _create(client)
    item = _checklist_items(client, inspection_id)[0]
    original_version = item["version"]

    assert _set_outcome(
        client, inspection_id, item["id"], "PASS", expected_version=original_version
    ).status_code == 200

    # The item is now PASS; a retry asking for FAIL with the stale version must
    # not silently overwrite — it is a conflicting later edit.
    conflict = _set_outcome(
        client,
        inspection_id,
        item["id"],
        "FAIL",
        expected_version=original_version,
    )
    assert conflict.status_code == 409
    assert conflict.json()["detail"]["current_status"] == "PASS"


def test_checklist_outcome_rejected_after_submission(client):
    inspection_id = _submit_ready_inspection(client)
    item = _required_items(client, inspection_id)[0]

    response = _set_outcome(client, inspection_id, item["id"], "FAIL")
    assert response.status_code == 409


def test_report_totals_reflect_synced_outcomes(client):
    _, inspection_id, _, _ = _create(client)
    required = _required_items(client, inspection_id)
    optional = [
        item for item in _checklist_items(client, inspection_id) if not item["required"]
    ]

    for item in required:
        _upload(client, inspection_id, item["id"], key=str(uuid.uuid4()), evidence_id=uuid.uuid4())

    _set_outcome(client, inspection_id, required[0]["id"], "PASS")
    _set_outcome(client, inspection_id, required[1]["id"], "FAIL", note="Blocked by stock")
    assert _set_outcome(client, inspection_id, optional[0]["id"], "NOT_APPLICABLE")

    submit = client.post(f"/api/v1/inspections/{inspection_id}/submit", json={})
    assert submit.status_code == 200
    review = client.post(
        f"/api/v1/inspections/{inspection_id}/review",
        json={"decision": "approved", "comment": "Reviewed"},
    )
    assert review.status_code == 200

    report = client.get(f"/api/v1/inspections/{inspection_id}/report")
    assert report.status_code == 200
    summary = report.json()["summary"]
    assert summary["passed"] == 1
    assert summary["failed"] == 1
    assert summary["not_applicable"] == 1
    assert summary["not_started"] == 0

    # PDF generation reflects the same synced outcomes.
    pdf = client.get(f"/api/v1/inspections/{inspection_id}/report.pdf")
    assert pdf.status_code == 200
    assert pdf.content.startswith(b"%PDF")


# ---------------------------------------------------------------------------
# Review
# ---------------------------------------------------------------------------

def _submit_ready_inspection(client):
    _, inspection_id, _, _ = _create(client)
    for item in _required_items(client, inspection_id):
        _upload(client, inspection_id, item["id"], key=str(uuid.uuid4()), evidence_id=uuid.uuid4())
    _record_required_outcomes(client, inspection_id)
    client.post(f"/api/v1/inspections/{inspection_id}/submit", json={})
    return inspection_id


def test_review_requires_submitted_status(client):
    _, inspection_id, _, _ = _create(client)
    response = client.post(
        f"/api/v1/inspections/{inspection_id}/review", json={"decision": "approved"}
    )
    assert response.status_code == 409


def test_review_approve_and_report(client):
    inspection_id = _submit_ready_inspection(client)

    before = client.get(f"/api/v1/inspections/{inspection_id}/report")
    assert before.status_code == 409

    review = client.post(
        f"/api/v1/inspections/{inspection_id}/review",
        json={"decision": "approved", "comment": "Looks good", "reviewer_id": "demo-reviewer"},
    )
    assert review.status_code == 200
    assert review.json()["status"] == "APPROVED"

    report = client.get(f"/api/v1/inspections/{inspection_id}/report")
    assert report.status_code == 200
    payload = report.json()
    assert payload["status"] == "APPROVED"
    assert payload["summary"]["evidence_count"] == 2
    assert payload["summary"]["passed"] == 2
    assert payload["review"]["decision"] == "approved"

    # Evidence can no longer be attached once approved.
    blocked = _upload(
        client,
        inspection_id,
        _required_items(client, inspection_id)[0]["id"],
        key=str(uuid.uuid4()),
        evidence_id=uuid.uuid4(),
    )
    assert blocked.status_code == 409


def test_review_changes_requested_allows_more_evidence(client):
    inspection_id = _submit_ready_inspection(client)
    review = client.post(
        f"/api/v1/inspections/{inspection_id}/review",
        json={"decision": "changes_requested", "comment": "Retake photo"},
    )
    assert review.status_code == 200
    assert review.json()["status"] == "CHANGES_REQUESTED"

    item = _required_items(client, inspection_id)[0]
    assert _upload(
        client, inspection_id, item["id"], key=str(uuid.uuid4()), evidence_id=uuid.uuid4()
    ).status_code == 201


def test_report_pdf_export_after_approval(client):
    inspection_id = _submit_ready_inspection(client)
    client.post(
        f"/api/v1/inspections/{inspection_id}/review",
        json={"decision": "approved", "comment": "Looks good"},
    )

    response = client.get(f"/api/v1/inspections/{inspection_id}/report.pdf")

    assert response.status_code == 200
    assert response.headers["content-type"] == "application/pdf"
    assert response.content.startswith(b"%PDF")
    assert len(response.content) > 1000


def test_report_pdf_unavailable_before_approval(client):
    inspection_id = _submit_ready_inspection(client)
    response = client.get(f"/api/v1/inspections/{inspection_id}/report.pdf")
    assert response.status_code == 409
