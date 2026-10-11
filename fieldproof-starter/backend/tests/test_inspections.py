"""End-to-end tests for the Postgres-backed inspection API.

The ``client`` fixture (see ``conftest.py``) points at a disposable PostgreSQL
database and resets the schema between tests.
"""
from __future__ import annotations

import base64
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


def _required_items(client, inspection_id):
    payload = _get(client, inspection_id).json()
    return [item for item in payload["checklist_items"] if item["required"]]


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


def test_submit_then_double_submit_conflicts(client):
    _, inspection_id, _, _ = _create(client)
    for item in _required_items(client, inspection_id):
        assert _upload(
            client, inspection_id, item["id"], key=str(uuid.uuid4()), evidence_id=uuid.uuid4()
        ).status_code == 201

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
# Review
# ---------------------------------------------------------------------------

def _submit_ready_inspection(client):
    _, inspection_id, _, _ = _create(client)
    for item in _required_items(client, inspection_id):
        _upload(client, inspection_id, item["id"], key=str(uuid.uuid4()), evidence_id=uuid.uuid4())
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
