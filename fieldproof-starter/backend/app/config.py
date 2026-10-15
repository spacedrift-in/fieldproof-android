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
    # Whether the app may write the bucket lifecycle configuration at startup.
    # Default off: provision lifecycle via infrastructure management (Terraform/
    # CloudFormation) so app startup never clobbers unrelated bucket rules. When
    # enabled the app merges only its own rules and only expires objects tagged
    # eligible by the retention sweep (active work and legal holds are excluded).
    s3_manage_lifecycle: bool

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
    # Maximum number of distinct client keys tracked in the in-process limiter
    # before the oldest window is evicted. Bounds memory so a client rotating
    # identifiers cannot grow the table without limit (see KL-032).
    rate_limit_max_keys: int
    # Name of a header (e.g. ``x-forwarded-for``) that a *trusted* ingress sets
    # to the real client address. Only enable this when a trusted proxy
    # overwrites the header; otherwise the limiter keys on the transport peer.
    rate_limit_client_ip_header: str | None
    # Opt-in per-tenant keying. Disabled by default because the tenant header is
    # unauthenticated until SPA-51; enabling it re-opens the header-rotation
    # bypass and is only safe once a verified principal exists.
    rate_limit_trust_tenant_header: bool

    # --- Observability ----------------------------------------------------
    # Expose the Prometheus text endpoint. When a token is set it is required in
    # ``X-Admin-Token``; otherwise the endpoint is intended to be reachable only
    # from the cluster/ingress (documented in docs/observability.md).
    metrics_enabled: bool
    metrics_admin_token: str | None

    # --- Schema management ------------------------------------------------
    # Whether startup may run ``create_all`` to bootstrap a demo/development
    # schema. This is a demo convenience only: in production it is off so
    # startup never performs implicit structural changes (KL-003); the schema is
    # managed by migrations instead (see alembic.ini / backend/migrations).
    auto_create_schema: bool
    # Opt-in to run ``alembic upgrade head`` during app startup. Off by default:
    # migrations are run explicitly by the deploy/CI step, never implicitly.
    run_migrations_on_start: bool


def get_settings() -> Settings:
    storage_override = os.getenv("EVIDENCE_STORAGE_DIR")
    environment = os.getenv("APP_ENV", os.getenv("ENVIRONMENT", "development")).lower()
    production = environment in ("production", "prod", "staging")
    return Settings(
        environment=environment,
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
        s3_manage_lifecycle=_env_bool("EVIDENCE_S3_MANAGE_LIFECYCLE", False),
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
        rate_limit_max_keys=_env_int("RATE_LIMIT_MAX_KEYS", 10000),
        rate_limit_client_ip_header=(
            os.getenv("RATE_LIMIT_CLIENT_IP_HEADER", "").strip().lower() or None
        ),
        rate_limit_trust_tenant_header=_env_bool("RATE_LIMIT_TRUST_TENANT_HEADER", False),
        metrics_enabled=_env_bool("METRICS_ENABLED", True),
        metrics_admin_token=os.getenv("METRICS_ADMIN_TOKEN") or None,
        # Demo/development bootstraps its own schema; production never does.
        auto_create_schema=_env_bool("AUTO_CREATE_SCHEMA", not production),
        run_migrations_on_start=_env_bool("RUN_MIGRATIONS_ON_START", False),
    )
