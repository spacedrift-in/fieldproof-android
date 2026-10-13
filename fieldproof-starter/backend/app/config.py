"""Runtime configuration for the FieldProof API.

Every value is read from the environment on each :func:`get_settings` call
(cheap and easy to test). Production deployments are expected to supply these
via a secret manager / environment file rather than baking them into the image.

Secrets (``DATABASE_URL``, S3 credentials, etc.) must never be checked into the
repository; see ``docs/deployment.md``.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent

# The demo default matches ``docker-compose.yml`` in this folder. Production
# must override this with a managed database URL.
DEFAULT_DATABASE_URL = "postgresql+psycopg://fieldproof:fieldproof@localhost:5432/fieldproof"

DEFAULT_MAX_EVIDENCE_BYTES = 10 * 1024 * 1024  # 10 MiB

DEFAULT_ALLOWED_IMAGE_TYPES = ("image/jpeg", "image/png", "image/webp", "image/heic", "image/gif")


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return int(raw)


def _env_list(name: str, default: tuple[str, ...] = ()) -> tuple[str, ...]:
    raw = os.getenv(name)
    if raw is None:
        return default
    return tuple(part.strip() for part in raw.split(",") if part.strip())


@dataclass(frozen=True)
class Settings:
    # --- Core -------------------------------------------------------------
    environment: str
    database_url: str
    app_version: str
    docs_enabled: bool

    # --- Evidence storage -------------------------------------------------
    storage_backend: str  # "local" (dev) or "s3" (production)
    evidence_storage_dir: Path
    max_evidence_bytes: int
    allowed_image_types: tuple[str, ...]
    s3_bucket: str
    s3_region: str | None
    s3_endpoint_url: str | None
    s3_use_ssl: bool
    s3_prefix: str
    s3_sse: str  # "AES256" or "aws:kms"
    s3_kms_key_id: str | None
    s3_lifecycle_days: int
    s3_lifecycle_transition_days: int
    s3_lifecycle_glacier_days: int

    # --- Upload hardening -------------------------------------------------
    malware_scanner: str  # "noop" or "clamav"
    clamav_host: str
    clamav_port: int
    malware_scan_fail_closed: bool

    # --- Tenancy ----------------------------------------------------------
    default_tenant: str

    # --- Retention / deletion ---------------------------------------------
    # Number of days evidence is retained after upload. 0 disables automatic
    # purging (the default; operators opt in). See docs/deployment.md.
    evidence_retention_days: int
    # Shared secret required to trigger the maintenance retention sweep. When
    # unset the sweep endpoint is disabled (503) rather than open.
    retention_admin_token: str | None

    # --- Edge/security ----------------------------------------------------
    allowed_origins: tuple[str, ...]
    cors_allow_credentials: bool
    rate_limit_enabled: bool
    rate_limit_requests: int
    rate_limit_window_seconds: int


def get_settings() -> Settings:
    storage_override = os.getenv("EVIDENCE_STORAGE_DIR")
    return Settings(
        environment=os.getenv("APP_ENV", os.getenv("ENVIRONMENT", "development")).lower(),
        database_url=os.getenv("DATABASE_URL", DEFAULT_DATABASE_URL),
        app_version=os.getenv("APP_VERSION", "0.3.0"),
        docs_enabled=_env_bool("DOCS_ENABLED", True),
        storage_backend=os.getenv("EVIDENCE_STORAGE_BACKEND", "local").strip().lower(),
        evidence_storage_dir=(
            Path(storage_override).expanduser()
            if storage_override
            else BACKEND_DIR / "var" / "evidence"
        ),
        max_evidence_bytes=_env_int("MAX_EVIDENCE_BYTES", DEFAULT_MAX_EVIDENCE_BYTES),
        allowed_image_types=_env_list("ALLOWED_IMAGE_TYPES", DEFAULT_ALLOWED_IMAGE_TYPES),
        s3_bucket=os.getenv("EVIDENCE_S3_BUCKET", "").strip(),
        s3_region=os.getenv("EVIDENCE_S3_REGION") or None,
        s3_endpoint_url=os.getenv("EVIDENCE_S3_ENDPOINT_URL") or None,
        s3_use_ssl=_env_bool("EVIDENCE_S3_USE_SSL", True),
        s3_prefix=os.getenv("EVIDENCE_S3_PREFIX", "").strip().strip("/"),
        s3_sse=os.getenv("EVIDENCE_S3_SSE", "AES256").strip() or "AES256",
        s3_kms_key_id=os.getenv("EVIDENCE_S3_KMS_KEY_ID") or None,
        s3_lifecycle_days=_env_int("EVIDENCE_S3_LIFECYCLE_DAYS", 0),
        s3_lifecycle_transition_days=_env_int("EVIDENCE_S3_LIFECYCLE_TRANSITION_DAYS", 0),
        s3_lifecycle_glacier_days=_env_int("EVIDENCE_S3_LIFECYCLE_GLACIER_DAYS", 0),
        malware_scanner=os.getenv("MALWARE_SCANNER", "noop").strip().lower(),
        clamav_host=os.getenv("CLAMAV_HOST", "127.0.0.1"),
        clamav_port=_env_int("CLAMAV_PORT", 3310),
        malware_scan_fail_closed=_env_bool("MALWARE_SCAN_FAIL_CLOSED", True),
        default_tenant=os.getenv("DEFAULT_TENANT", "demo").strip() or "demo",
        evidence_retention_days=_env_int("EVIDENCE_RETENTION_DAYS", 0),
        retention_admin_token=os.getenv("RETENTION_ADMIN_TOKEN") or None,
        allowed_origins=_env_list("CORS_ALLOWED_ORIGINS", ()),
        cors_allow_credentials=_env_bool("CORS_ALLOW_CREDENTIALS", False),
        rate_limit_enabled=_env_bool("RATE_LIMIT_ENABLED", False),
        rate_limit_requests=_env_int("RATE_LIMIT_REQUESTS", 120),
        rate_limit_window_seconds=_env_int("RATE_LIMIT_WINDOW_SECONDS", 60),
    )
