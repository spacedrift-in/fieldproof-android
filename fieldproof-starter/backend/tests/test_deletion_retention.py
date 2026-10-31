"""Tests for user/tenant deletion and the evidence retention policy.

Deletion must remove the stored evidence objects as well as the database rows,
and retention must only touch finalized (APPROVED) evidence past the window.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest

from backend.app import db as db_module
from backend.app import service
from backend.app.models import Evidence, Inspection


class FakeS3Storage:
    """Minimal tag-aware object store used to observe sweep coordination."""

    def __init__(self, *, held_keys=()):
        self.held = set(held_keys)
        self.tagged: list[str] = []
        self.deleted: list[str] = []

    def is_legal_hold(self, key: str) -> bool:
        return key in self.held

    def mark_retention_eligible(self, key: str) -> bool:
        if key in self.held:
            return False
        self.tagged.append(key)
        return True

    def delete(self, key: str) -> None:
        self.deleted.append(key)

PNG_BYTES = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
    b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\nIDATx\x9cc\x00"
    b"\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
)


def _create(client, *, title="Facility A", tenant=None):
    inspection_id = str(uuid.uuid4())
    headers = {"Idempotency-Key": str(uuid.uuid4())}
    if tenant:
        headers["X-Tenant-Id"] = tenant
    response = client.post(
        "/api/v1/inspections",
        json={
            "id": inspection_id,
            "title": title,
            "location": "Building 1",
            "createdAt": 1_700_000_000_000,
        },
        headers=headers,
    )
    assert response.status_code == 201, response.text
    return inspection_id


def _upload(client, inspection_id, *, tenant=None):
    headers = {"Idempotency-Key": str(uuid.uuid4())}
    if tenant:
        headers["X-Tenant-Id"] = tenant
    response = client.post(
        f"/api/v1/inspections/{inspection_id}/evidence",
        data={"id": str(uuid.uuid4()), "kind": "photo"},
        files={"file": ("photo.png", PNG_BYTES, "image/png")},
        headers=headers,
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _stored_files(evidence_dir):
    return {p for p in evidence_dir.rglob("*") if p.is_file()}


# ---------------------------------------------------------------------------
# Inspection deletion
# ---------------------------------------------------------------------------

def test_delete_inspection_removes_rows_and_objects(client, tmp_path):
    inspection_id = _create(client)
    _upload(client, inspection_id)
    evidence_dir = tmp_path / "evidence"
    assert _stored_files(evidence_dir), "expected an uploaded object on disk"

    response = client.delete(f"/api/v1/inspections/{inspection_id}")

    assert response.status_code == 200, response.text
    assert response.json()["objects_deleted"] == 1
    assert _stored_files(evidence_dir) == set(), "stored object must be deleted"
    assert client.get(f"/api/v1/inspections/{inspection_id}").status_code == 404


def test_delete_inspection_is_tenant_scoped(client):
    inspection_id = _create(client, tenant="acme")

    response = client.delete(
        f"/api/v1/inspections/{inspection_id}", headers={"X-Tenant-Id": "beta"}
    )

    assert response.status_code == 404
    # Still present for its real owner.
    assert client.get(
        f"/api/v1/inspections/{inspection_id}", headers={"X-Tenant-Id": "acme"}
    ).status_code == 200


# ---------------------------------------------------------------------------
# Tenant deletion
# ---------------------------------------------------------------------------

def test_delete_tenant_requires_confirmation(client, tmp_path):
    inspection_id = _create(client, tenant="acme")
    _upload(client, inspection_id, tenant="acme")

    missing = client.delete("/api/v1/tenant", headers={"X-Tenant-Id": "acme"})
    assert missing.status_code == 400

    wrong = client.delete(
        "/api/v1/tenant",
        headers={"X-Tenant-Id": "acme", "X-Confirm-Delete": "beta"},
    )
    assert wrong.status_code == 400
    assert (tmp_path / "evidence").exists()


def test_delete_tenant_removes_only_that_tenant(client, tmp_path):
    acme_id = _create(client, tenant="acme")
    _upload(client, acme_id, tenant="acme")
    beta_id = _create(client, tenant="beta")
    _upload(client, beta_id, tenant="beta")

    response = client.delete(
        "/api/v1/tenant",
        headers={"X-Tenant-Id": "acme", "X-Confirm-Delete": "acme"},
    )

    assert response.status_code == 200, response.text
    assert response.json()["inspections_deleted"] == 1
    assert client.get(
        f"/api/v1/inspections/{acme_id}", headers={"X-Tenant-Id": "acme"}
    ).status_code == 404
    assert client.get(
        f"/api/v1/inspections/{beta_id}", headers={"X-Tenant-Id": "beta"}
    ).status_code == 200


# ---------------------------------------------------------------------------
# Retention sweep
# ---------------------------------------------------------------------------

def test_retention_disabled_without_admin_token(make_client):
    client = make_client()  # RETENTION_ADMIN_TOKEN unset
    response = client.post("/api/v1/maintenance/retention")
    assert response.status_code == 503


def test_retention_rejects_bad_admin_token(make_client):
    client = make_client(RETENTION_ADMIN_TOKEN="s3cret")
    response = client.post(
        "/api/v1/maintenance/retention", headers={"X-Admin-Token": "nope"}
    )
    assert response.status_code == 401


def test_retention_purges_only_old_approved_evidence(make_client, tmp_path):
    client = make_client(
        RETENTION_ADMIN_TOKEN="s3cret", EVIDENCE_RETENTION_DAYS="30"
    )

    approved_id = _create(client, title="Old approved")
    _upload(client, approved_id)
    draft_id = _create(client, title="Old draft")
    _upload(client, draft_id)

    _age_and_finalize(approved_id, days_old=40, approve=True)
    _age_and_finalize(draft_id, days_old=40, approve=False)

    before = _stored_files(tmp_path / "evidence")
    assert len(before) == 2

    response = client.post(
        "/api/v1/maintenance/retention",
        headers={"X-Admin-Token": "s3cret"},
    )

    assert response.status_code == 200, response.text
    assert response.json()["purged"] == 1
    # Only the draft's object remains.
    assert len(_stored_files(tmp_path / "evidence")) == 1
    # Approved evidence row is gone; draft evidence row survives.
    with db_module.get_session_factory()() as session:
        approved_rows = _evidence_rows(session, approved_id)
        draft_rows = _evidence_rows(session, draft_id)
    assert approved_rows == 0
    assert draft_rows == 1


def test_retention_tags_only_approved_evidence_for_lifecycle(make_client):
    client = make_client(
        RETENTION_ADMIN_TOKEN="s3cret", EVIDENCE_RETENTION_DAYS="30"
    )

    approved_id = _create(client, title="Old approved")
    _upload(client, approved_id)
    submitted_id = _create(client, title="Old submitted")
    _upload(client, submitted_id)
    draft_id = _create(client, title="Old draft")
    _upload(client, draft_id)

    _age_and_finalize(approved_id, days_old=40, approve=True)
    _age_and_finalize(submitted_id, days_old=40, status="SUBMITTED")
    _age_and_finalize(draft_id, days_old=40, status="DRAFT")

    fake = FakeS3Storage()
    with db_module.get_session_factory()() as session:
        result = service.purge_expired_evidence(
            session, retention_days=30, storage=fake
        )

    # Only the APPROVED object is tagged eligible (and directly deleted).
    assert result["purged"] == 1
    assert result["legal_held"] == 0
    assert len(fake.tagged) == 1
    assert len(fake.deleted) == 1
    assert approved_id in fake.tagged[0]

    # DB metadata stays in lockstep: approved row gone, active work retained.
    with db_module.get_session_factory()() as session:
        assert _evidence_rows(session, approved_id) == 0
        assert _evidence_rows(session, submitted_id) == 1
        assert _evidence_rows(session, draft_id) == 1


def test_retention_skips_legal_hold_evidence(make_client):
    client = make_client(
        RETENTION_ADMIN_TOKEN="s3cret", EVIDENCE_RETENTION_DAYS="30"
    )
    approved_id = _create(client, title="Held approved")
    _upload(client, approved_id)
    _age_and_finalize(approved_id, days_old=40, approve=True)

    with db_module.get_session_factory()() as session:
        keys = {ev.storage_key for ev in session.query(Evidence).all()}

    fake = FakeS3Storage(held_keys=keys)
    with db_module.get_session_factory()() as session:
        result = service.purge_expired_evidence(
            session, retention_days=30, storage=fake
        )

    assert result["purged"] == 0
    assert result["legal_held"] == 1
    assert fake.tagged == []
    assert fake.deleted == []
    with db_module.get_session_factory()() as session:
        assert _evidence_rows(session, approved_id) == 1


def test_retention_days_zero_disables_sweep(make_client):
    client = make_client(RETENTION_ADMIN_TOKEN="s3cret", EVIDENCE_RETENTION_DAYS="0")
    inspection_id = _create(client)
    _upload(client, inspection_id)
    _age_and_finalize(inspection_id, days_old=9999, approve=True)

    response = client.post(
        "/api/v1/maintenance/retention", headers={"X-Admin-Token": "s3cret"}
    )
    assert response.status_code == 200
    assert response.json()["purged"] == 0


def _age_and_finalize(
    inspection_id: str, *, days_old: int, approve: bool = False, status: str | None = None
) -> None:
    old = datetime.now(timezone.utc) - timedelta(days=days_old)
    with db_module.get_session_factory()() as session:
        inspection = session.get(Inspection, uuid.UUID(inspection_id))
        inspection.status = status or ("APPROVED" if approve else "DRAFT")
        for evidence in session.query(Evidence).filter(
            Evidence.inspection_id == uuid.UUID(inspection_id)
        ):
            evidence.uploaded_at = old
        session.commit()


def _evidence_rows(session, inspection_id: str) -> int:
    return (
        session.query(Evidence)
        .filter(Evidence.inspection_id == uuid.UUID(inspection_id))
        .count()
    )
