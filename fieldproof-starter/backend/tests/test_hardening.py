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


class FakeNoLifecycle(Exception):
    def __init__(self):
        self.response = {"Error": {"Code": "NoSuchLifecycleConfiguration"}}


class FakeS3Client:
    def __init__(self, *, lifecycle=None):
        self.calls = []
        self.objects: dict[str, bytes] = {}
        self.tags: dict[str, dict[str, str]] = {}
        self.lifecycle = {"Rules": list(lifecycle)} if lifecycle is not None else None

    def put_object(self, **kwargs):
        self.calls.append(("put_object", kwargs))
        self.objects[kwargs["Key"]] = kwargs.get("Body", b"")

    def get_object(self, **kwargs):
        return {"Body": io.BytesIO(b"payload")}

    def head_object(self, **kwargs):
        if kwargs["Key"].endswith("missing"):
            raise FakeNotFound()
        return {}

    def delete_object(self, **kwargs):
        self.calls.append(("delete_object", kwargs))
        self.objects.pop(kwargs["Key"], None)

    def put_public_access_block(self, **kwargs):
        self.calls.append(("put_public_access_block", kwargs))

    def get_bucket_lifecycle_configuration(self, **kwargs):
        if self.lifecycle is None:
            raise FakeNoLifecycle()
        return self.lifecycle

    def put_bucket_lifecycle_configuration(self, **kwargs):
        self.calls.append(("put_bucket_lifecycle_configuration", kwargs))
        self.lifecycle = kwargs["LifecycleConfiguration"]

    def get_object_tagging(self, **kwargs):
        tagset = [
            {"Key": k, "Value": v} for k, v in self.tags.get(kwargs["Key"], {}).items()
        ]
        return {"TagSet": tagset}

    def put_object_tagging(self, **kwargs):
        self.calls.append(("put_object_tagging", kwargs))
        self.tags[kwargs["Key"]] = {
            t["Key"]: t["Value"] for t in kwargs["Tagging"]["TagSet"]
        }

    def head_bucket(self, **kwargs):
        return {}


def _expiration_rules(client):
    if client.lifecycle is None:
        return []
    return [r for r in client.lifecycle["Rules"] if "Expiration" in r]


def _simulate_lifecycle_expiry(client):
    """Emulate S3 lifecycle: expire objects matching each rule's Filter."""
    for rule in _expiration_rules(client):
        flt = rule["Filter"]
        prefix = flt.get("Prefix", "")
        tag = None
        for clause in flt.get("And", []):
            if "Tag" in clause:
                tag = (clause["Tag"]["Key"], clause["Tag"]["Value"])
        for key in list(client.objects):
            if not key.startswith(prefix):
                continue
            if tag is not None and client.tags.get(key, {}).get(tag[0]) != tag[1]:
                continue
            client.objects.pop(key, None)


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


def test_s3_storage_lifecycle_rules_are_scoped_and_merge():
    existing = [
        {
            "ID": "org-wide-logs-retention",
            "Filter": {"Prefix": "logs/"},
            "Status": "Enabled",
            "Expiration": {"Days": 30},
        }
    ]
    fake = FakeS3Client(lifecycle=existing)
    storage = S3EvidenceStorage(
        "b",
        prefix="evidence",
        lifecycle_days=365,
        lifecycle_transition_days=30,
        lifecycle_glacier_days=90,
        manage_lifecycle=True,
        client=fake,
    )
    storage.apply_lifecycle()
    _, kwargs = fake.calls[-1]
    by_id = {r["ID"]: r for r in kwargs["LifecycleConfiguration"]["Rules"]}

    # Unrelated bucket rules are preserved (no clobbering).
    assert "org-wide-logs-retention" in by_id

    # Transitions are prefix-scoped and non-destructive.
    transition = by_id["fieldproof-evidence-transition"]
    assert transition["Filter"] == {"Prefix": "evidence"}
    assert {"Days": 30, "StorageClass": "STANDARD_IA"} in transition["Transitions"]
    assert {"Days": 90, "StorageClass": "GLACIER"} in transition["Transitions"]

    # Expiration only ever targets objects tagged eligible by the sweep.
    expiration = by_id["fieldproof-evidence-expiration"]
    assert expiration["Expiration"] == {"Days": 365}
    assert expiration["Filter"] == {
        "And": [
            {"Prefix": "evidence"},
            {"Tag": {"Key": "fieldproof-retention", "Value": "eligible"}},
        ]
    }
    assert storage.check() is True


def test_s3_lifecycle_is_opt_in_and_never_clobbers_bucket():
    existing = [{"ID": "org-wide", "Filter": {"Prefix": "x/"}, "Status": "Enabled"}]
    fake = FakeS3Client(lifecycle=existing)
    storage = S3EvidenceStorage(
        "b", prefix="evidence", lifecycle_days=365, client=fake
    )
    storage.apply_lifecycle()
    # No write by default; the operator's bucket config is untouched.
    assert not any(name == "put_bucket_lifecycle_configuration" for name, _ in fake.calls)
    assert fake.lifecycle == {"Rules": existing}


def test_s3_lifecycle_removes_stale_managed_rule_without_touching_others():
    existing = [
        {"ID": "org-wide", "Filter": {"Prefix": "x/"}, "Status": "Enabled"},
        {
            "ID": "fieldproof-evidence-expiration",
            "Filter": {"Prefix": "old"},
            "Status": "Enabled",
            "Expiration": {"Days": 1},
        },
    ]
    fake = FakeS3Client(lifecycle=existing)
    storage = S3EvidenceStorage("b", prefix="evidence", manage_lifecycle=True, client=fake)
    storage.apply_lifecycle()
    rules = fake.lifecycle["Rules"]
    ids = {r["ID"] for r in rules}
    assert ids == {"org-wide"}
    assert rules[0] == existing[0]


def test_s3_lifecycle_expires_only_tagged_eligible_objects():
    fake = FakeS3Client()
    storage = S3EvidenceStorage(
        "b", prefix="evidence", manage_lifecycle=True, lifecycle_days=30, client=fake
    )
    draft = "evidence/tenants/t/inspections/d1/evidence/a.png"
    submitted = "evidence/tenants/t/inspections/s1/evidence/b.png"
    approved = "evidence/tenants/t/inspections/ap1/evidence/c.png"
    fake.objects = {draft: b"draft", submitted: b"submitted", approved: b"approved"}

    # Only the sweep-confirmed APPROVED object is tagged eligible.
    assert storage.mark_retention_eligible("tenants/t/inspections/ap1/evidence/c.png")
    storage.apply_lifecycle()
    _simulate_lifecycle_expiry(fake)

    assert set(fake.objects) == {draft, submitted}
    assert approved not in fake.objects


def test_s3_lifecycle_never_expires_a_legal_hold():
    fake = FakeS3Client()
    storage = S3EvidenceStorage(
        "b", prefix="evidence", manage_lifecycle=True, lifecycle_days=30, client=fake
    )
    held = "evidence/tenants/t/inspections/ap1/evidence/held.png"
    fake.objects = {held: b"held"}

    storage.mark_legal_hold("tenants/t/inspections/ap1/evidence/held.png")
    # Eligibility tagging must refuse under hold, so the object stays untagged.
    assert storage.mark_retention_eligible("tenants/t/inspections/ap1/evidence/held.png") is False
    assert storage.is_legal_hold("tenants/t/inspections/ap1/evidence/held.png") is True

    storage.apply_lifecycle()
    _simulate_lifecycle_expiry(fake)
    assert held in fake.objects


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


def test_rate_limit_ignores_rotated_tenant_header(make_client):
    """Rotating X-Tenant-Id must not dodge the per-client quota (KL-032)."""
    client = make_client(
        RATE_LIMIT_ENABLED="true",
        RATE_LIMIT_REQUESTS="2",
        RATE_LIMIT_WINDOW_SECONDS="60",
    )
    missing = str(uuid.uuid4())
    statuses = [
        client.get(
            f"/api/v1/inspections/{missing}", headers={"X-Tenant-Id": f"tenant{i}"}
        ).status_code
        for i in range(4)
    ]
    # The unauthenticated tenant header is not the limiter key, so the third
    # request from the same client is rejected regardless of the header value.
    assert statuses == [404, 404, 429, 429]


def test_rate_limit_retry_after_stays_within_window(make_client):
    client = make_client(
        RATE_LIMIT_ENABLED="true",
        RATE_LIMIT_REQUESTS="1",
        RATE_LIMIT_WINDOW_SECONDS="60",
    )
    missing = str(uuid.uuid4())
    client.get(f"/api/v1/inspections/{missing}")
    rejected = client.get(f"/api/v1/inspections/{missing}")
    assert rejected.status_code == 429
    retry_after = int(rejected.headers["retry-after"])
    # Stable and bounded by the window, never zero/negative.
    assert 1 <= retry_after <= 60


# ---------------------------------------------------------------------------
# Rate limiter internals (no database required)
# ---------------------------------------------------------------------------

def _limiter(**kwargs):
    from backend.app.main import RateLimitMiddleware

    async def _app(scope, receive, send):
        return None

    return RateLimitMiddleware(_app, **kwargs)


def test_rate_limit_key_is_transport_ip_not_header():
    limiter = _limiter(requests=2, window_seconds=60)
    scope = {"client": ("203.0.113.7", 1234)}
    assert limiter._client_key(scope, {}) == "203.0.113.7"
    # A rotated tenant header does not change the key when tenant keying is off.
    assert limiter._client_key(scope, {"x-tenant-id": "acme"}) == "203.0.113.7"

    trusted = _limiter(requests=2, window_seconds=60, trust_tenant_header=True)
    assert trusted._client_key(scope, {"x-tenant-id": "acme"}) == "203.0.113.7|acme"


def test_rate_limit_uses_configured_client_ip_header():
    limiter = _limiter(requests=2, window_seconds=60, client_ip_header="x-forwarded-for")
    scope = {"client": ("10.0.0.1", 1234)}
    key = limiter._client_key(scope, {"x-forwarded-for": "198.51.100.4, 10.0.0.1"})
    assert key == "198.51.100.4"


def test_rate_limit_purges_expired_windows():
    limiter = _limiter(requests=2, window_seconds=60)
    limiter._hits["stale"] = [100.0, 1]
    limiter._hits["fresh"] = [180.0, 1]
    limiter._purge_expired(now=200.0)
    # 200-100 >= 60 -> stale evicted; 200-180 < 60 -> fresh retained.
    assert list(limiter._hits) == ["fresh"]


def test_rate_limit_evicts_oldest_at_capacity():
    limiter = _limiter(requests=2, window_seconds=60, max_keys=2)
    limiter._hits["a"] = [0.0, 1]
    limiter._hits["b"] = [0.0, 1]
    limiter._evict_oldest()
    assert list(limiter._hits) == ["b"]


def test_rate_limit_new_key_evicts_stale_at_capacity(monkeypatch):
    """Cardinality stays bounded even when a client rotates its key."""
    import asyncio

    import backend.app.main as main_module

    clock = {"now": 1000.0}
    monkeypatch.setattr(main_module.time, "monotonic", lambda: clock["now"])
    limiter = _limiter(requests=2, window_seconds=30, max_keys=3)

    async def _receive():
        return {"type": "http.request"}

    async def _send(message):
        return None

    async def _drive_all():
        for ip in ("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5"):
            scope = {
                "type": "http",
                "path": "/api/v1/x",
                "client": (ip, 1),
                "headers": [],
            }
            await limiter(scope, _receive, _send)

    asyncio.run(_drive_all())
    # max_keys bounds the table regardless of how many distinct keys appear.
    assert len(limiter._hits) <= 3


