"""Tests for the production-hardening baseline.

Covers content-based MIME validation, streaming upload limits, the malware-scan
integration point, safe download headers, tenant scoping, S3 storage wiring,
readiness, CORS allowlisting and rate limiting.
"""
from __future__ import annotations

import base64
import io
import uuid

import pytest
from fastapi import HTTPException

from backend.app import media, tenancy
from backend.app.config import get_settings
from backend.app.scanning import ClamAVScanner, NoOpScanner, ScanVerdict
from backend.app.storage import S3EvidenceStorage

PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)
JPEG_BYTES = b"\xff\xd8\xff\xe0" + b"\x00" * 16


def _create(client, *, tenant=None, key=None):
    inspection_id = str(uuid.uuid4())
    headers = {"Idempotency-Key": key or str(uuid.uuid4())}
    if tenant:
        headers["X-Tenant-Id"] = tenant
    response = client.post(
        "/api/v1/inspections",
        json={
            "id": inspection_id,
            "title": "Facility A",
            "location": "Building 1",
            "createdAt": 1_700_000_000_000,
        },
        headers=headers,
    )
    return response, inspection_id


def _upload(client, inspection_id, *, filename="photo.png", content=PNG_BYTES,
            content_type="image/png", tenant=None, key=None, evidence_id=None):
    headers = {"Idempotency-Key": key or str(uuid.uuid4())}
    if tenant:
        headers["X-Tenant-Id"] = tenant
    return client.post(
        f"/api/v1/inspections/{inspection_id}/evidence",
        data={"id": str(evidence_id or uuid.uuid4()), "kind": "photo"},
        files={"file": (filename, content, content_type)},
        headers=headers,
    )


# ---------------------------------------------------------------------------
# Media validation (unit)
# ---------------------------------------------------------------------------

def test_sniff_recognises_common_image_types():
    assert media.sniff_image_type(PNG_BYTES) == "image/png"
    assert media.sniff_image_type(JPEG_BYTES) == "image/jpeg"
    assert media.sniff_image_type(b"GIF89a" + b"\x00" * 8) == "image/gif"
    assert media.sniff_image_type(b"RIFF\x00\x00\x00\x00WEBP" + b"\x00") == "image/webp"
    assert media.sniff_image_type(b"not an image") is None


def test_validate_rejects_unrecognised_content():
    with pytest.raises(HTTPException) as exc:
        media.validate_image_upload(
            b"<html>", declared_content_type="image/png", allowed=("image/png",)
        )
    assert exc.value.status_code == 415


def test_validate_rejects_declared_type_mismatch():
    with pytest.raises(HTTPException) as exc:
        media.validate_image_upload(
            PNG_BYTES, declared_content_type="image/jpeg", allowed=("image/png", "image/jpeg")
        )
    assert exc.value.status_code == 415


def test_validate_ignores_octet_stream_and_returns_canonical_type():
    assert media.validate_image_upload(
        PNG_BYTES,
        declared_content_type="application/octet-stream",
        allowed=("image/png",),
    ) == "image/png"


def test_validate_enforces_allowlist():
    with pytest.raises(HTTPException) as exc:
        media.validate_image_upload(
            JPEG_BYTES, declared_content_type=None, allowed=("image/png",)
        )
    assert exc.value.status_code == 415


# ---------------------------------------------------------------------------
# Malware scanning (unit)
# ---------------------------------------------------------------------------

def test_noop_scanner_is_clean():
    assert NoOpScanner().scan(b"x", filename="a", content_type="image/png").clean


def test_clamav_scanner_verdicts():
    scanner = ClamAVScanner("localhost", 3310)

    class FakeClamd:
        def __init__(self, result=None, error=None):
            self._result = result
            self._error = error

        def instream(self, _stream):
            if self._error:
                raise self._error
            return self._result

    scanner._client = FakeClamd(result={"stream": ("OK", None)})
    assert scanner.scan(b"x", filename=None, content_type=None).status == "clean"

    scanner._client = FakeClamd(result={"stream": ("FOUND", "Eicar-Test-Signature")})
    verdict = scanner.scan(b"x", filename=None, content_type=None)
    assert verdict.status == "infected"
    assert verdict.signature == "Eicar-Test-Signature"

    scanner._client = FakeClamd(error=RuntimeError("daemon down"))
    assert scanner.scan(b"x", filename=None, content_type=None).status == "error"


# ---------------------------------------------------------------------------
# S3 storage (unit, injected fake client)
# ---------------------------------------------------------------------------

class FakeNotFound(Exception):
    def __init__(self):
        self.response = {"Error": {"Code": "404"}}


class FakeS3Client:
    def __init__(self):
        self.calls = []

    def put_object(self, **kwargs):
        self.calls.append(("put_object", kwargs))

    def get_object(self, **kwargs):
        return {"Body": io.BytesIO(b"payload")}

    def head_object(self, **kwargs):
        if kwargs["Key"].endswith("missing"):
            raise FakeNotFound()
        return {}

    def delete_object(self, **kwargs):
        self.calls.append(("delete_object", kwargs))

    def put_public_access_block(self, **kwargs):
        self.calls.append(("put_public_access_block", kwargs))

    def put_bucket_lifecycle_configuration(self, **kwargs):
        self.calls.append(("put_bucket_lifecycle_configuration", kwargs))

    def head_bucket(self, **kwargs):
        return {}


def test_s3_storage_prefixes_keys_and_encrypts():
    fake = FakeS3Client()
    storage = S3EvidenceStorage(
        "evidence-bucket",
        prefix="prod",
        sse="aws:kms",
        kms_key_id="kms-key",
        client=fake,
    )
    storage.save("tenants/acme/evidence/x.jpg", b"data", content_type="image/jpeg")
    _, kwargs = fake.calls[-1]
    assert kwargs["Bucket"] == "evidence-bucket"
    assert kwargs["Key"] == "prod/tenants/acme/evidence/x.jpg"
    assert kwargs["ContentType"] == "image/jpeg"
    assert kwargs["ServerSideEncryption"] == "aws:kms"
    assert kwargs["SSEKMSKeyId"] == "kms-key"


def test_s3_storage_read_exists_delete_and_private():
    fake = FakeS3Client()
    storage = S3EvidenceStorage("b", client=fake)
    assert storage.read("k") == b"payload"
    assert storage.exists("present") is True
    assert storage.exists("missing") is False
    storage.delete("k")
    assert any(name == "delete_object" for name, _ in fake.calls)

    storage.ensure_private()
    _, kwargs = fake.calls[-1]
    assert kwargs["PublicAccessBlockConfiguration"]["BlockPublicAcls"] is True


def test_s3_storage_lifecycle_rule():
    fake = FakeS3Client()
    storage = S3EvidenceStorage(
        "b",
        prefix="evidence",
        lifecycle_days=365,
        lifecycle_transition_days=30,
        lifecycle_glacier_days=90,
        client=fake,
    )
    storage.apply_lifecycle()
    _, kwargs = fake.calls[-1]
    rule = kwargs["LifecycleConfiguration"]["Rules"][0]
    assert rule["Filter"] == {"Prefix": "evidence"}
    assert rule["Expiration"] == {"Days": 365}
    assert {"Days": 30, "StorageClass": "STANDARD_IA"} in rule["Transitions"]
    assert {"Days": 90, "StorageClass": "GLACIER"} in rule["Transitions"]
    assert storage.check() is True


# ---------------------------------------------------------------------------
# Tenancy + config (unit)
# ---------------------------------------------------------------------------

def test_tenant_resolution_and_validation(monkeypatch):
    monkeypatch.delenv("DEFAULT_TENANT", raising=False)
    assert tenancy.resolve_tenant_id(None) == "demo"
    assert tenancy.resolve_tenant_id("acme-1") == "acme-1"
    with pytest.raises(HTTPException) as exc:
        tenancy.resolve_tenant_id("bad tenant!")
    assert exc.value.status_code == 400


def test_settings_are_env_driven(monkeypatch):
    monkeypatch.setenv("EVIDENCE_STORAGE_BACKEND", "s3")
    monkeypatch.setenv("EVIDENCE_S3_BUCKET", "my-bucket")
    monkeypatch.setenv("EVIDENCE_S3_SSE", "aws:kms")
    monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://a.example, https://b.example")
    monkeypatch.setenv("RATE_LIMIT_ENABLED", "true")
    monkeypatch.setenv("RATE_LIMIT_REQUESTS", "5")
    settings = get_settings()
    assert settings.storage_backend == "s3"
    assert settings.s3_bucket == "my-bucket"
    assert settings.s3_sse == "aws:kms"
    assert settings.allowed_origins == ("https://a.example", "https://b.example")
    assert settings.rate_limit_enabled is True
    assert settings.rate_limit_requests == 5


# ---------------------------------------------------------------------------
# API behaviour (database required)
# ---------------------------------------------------------------------------

def test_upload_rejects_spoofed_content_type(client):
    _, inspection_id = _create(client)
    response = _upload(
        client,
        inspection_id,
        filename="photo.png",
        content=b"<script>alert(1)</script>",
        content_type="image/png",
    )
    assert response.status_code == 415


def test_upload_rejects_oversized_stream(make_client):
    client = make_client(MAX_EVIDENCE_BYTES=32)
    _, inspection_id = _create(client)
    response = _upload(client, inspection_id)
    assert response.status_code == 413


def test_upload_and_safe_download_headers(client):
    _, inspection_id = _create(client)
    response = _upload(client, inspection_id)
    assert response.status_code == 201
    evidence_id = response.json()["id"]

    download = client.get(f"/api/v1/inspections/{inspection_id}/evidence/{evidence_id}")
    assert download.status_code == 200
    assert download.content == PNG_BYTES
    assert download.headers["content-type"] == "image/png"
    assert download.headers["content-disposition"].startswith("attachment;")
    assert download.headers["x-content-type-options"] == "nosniff"
    assert download.headers["cache-control"] == "private, no-store"


def test_upload_rejected_when_scanner_finds_malware(client, monkeypatch):
    from backend.app.routers import inspections as router_module

    class Infected:
        def scan(self, data, *, filename, content_type):
            return ScanVerdict(status="infected", signature="Eicar-Test-Signature")

    monkeypatch.setattr(router_module.scanning, "get_scanner", lambda settings: Infected())
    _, inspection_id = _create(client)
    response = _upload(client, inspection_id)
    assert response.status_code == 422


def test_upload_blocked_when_scanner_unavailable_and_fail_closed(client, monkeypatch):
    from backend.app.routers import inspections as router_module

    class Broken:
        def scan(self, data, *, filename, content_type):
            return ScanVerdict(status="error")

    monkeypatch.setattr(router_module.scanning, "get_scanner", lambda settings: Broken())
    monkeypatch.setenv("MALWARE_SCAN_FAIL_CLOSED", "true")
    _, inspection_id = _create(client)
    response = _upload(client, inspection_id)
    assert response.status_code == 503


def test_upload_allowed_when_scanner_unavailable_and_fail_open(client, monkeypatch):
    from backend.app.routers import inspections as router_module

    class Broken:
        def scan(self, data, *, filename, content_type):
            return ScanVerdict(status="error")

    monkeypatch.setattr(router_module.scanning, "get_scanner", lambda settings: Broken())
    monkeypatch.setenv("MALWARE_SCAN_FAIL_CLOSED", "false")
    _, inspection_id = _create(client)
    assert _upload(client, inspection_id).status_code == 201


def test_cross_tenant_access_is_hidden(client):
    _, inspection_id = _create(client, tenant="acme")
    assert client.get(
        f"/api/v1/inspections/{inspection_id}", headers={"X-Tenant-Id": "acme"}
    ).status_code == 200
    assert client.get(
        f"/api/v1/inspections/{inspection_id}", headers={"X-Tenant-Id": "other"}
    ).status_code == 404
    # Default tenant must not see another tenant's inspection either.
    assert client.get(f"/api/v1/inspections/{inspection_id}").status_code == 404


def test_evidence_download_is_tenant_scoped(client):
    _, inspection_id = _create(client, tenant="acme")
    evidence_id = _upload(client, inspection_id, tenant="acme").json()["id"]
    ok = client.get(
        f"/api/v1/inspections/{inspection_id}/evidence/{evidence_id}",
        headers={"X-Tenant-Id": "acme"},
    )
    assert ok.status_code == 200
    leaked = client.get(
        f"/api/v1/inspections/{inspection_id}/evidence/{evidence_id}",
        headers={"X-Tenant-Id": "other"},
    )
    assert leaked.status_code == 404


def test_readiness_reports_database_and_storage(client):
    response = client.get("/readyz")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["database"] == "ok"
    assert body["storage"] == "ok"
    assert body["storage_backend"] == "local"


def test_cors_allowlist(make_client):
    client = make_client(CORS_ALLOWED_ORIGINS="https://app.example.com")
    allowed = client.get("/health", headers={"Origin": "https://app.example.com"})
    assert allowed.headers.get("access-control-allow-origin") == "https://app.example.com"
    denied = client.get("/health", headers={"Origin": "https://evil.example.com"})
    assert "access-control-allow-origin" not in denied.headers


def test_rate_limit_returns_429(make_client):
    client = make_client(
        RATE_LIMIT_ENABLED="true",
        RATE_LIMIT_REQUESTS="2",
        RATE_LIMIT_WINDOW_SECONDS="60",
    )
    missing = str(uuid.uuid4())
    first = client.get(f"/api/v1/inspections/{missing}")
    second = client.get(f"/api/v1/inspections/{missing}")
    third = client.get(f"/api/v1/inspections/{missing}")
    assert first.status_code == 404
    assert second.status_code == 404
    assert third.status_code == 429
    assert third.headers.get("retry-after") == "60"
