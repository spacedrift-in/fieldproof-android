# Deployment guide — staging & production

> **Scope.** This document describes how to run the FieldProof backend as a
> hardened service: private object storage, upload validation, secrets
> externalization, a non-root container, readiness checks, rate limiting, CORS
> allowlisting, Prometheus metrics and Alembic-managed schema. It is a
> **baseline**, not a compliance certification. See `known-limitations.md` for
> what is intentionally still missing (most notably authentication, KL-001).

## 1. What changed vs. localhost

| Concern | Local demo (`docker-compose.yml`) | Staging / production |
|---|---|---|
| Evidence storage | local filesystem (`backend/var/evidence`) | private S3-compatible bucket, SSE-encrypted, lifecycle policy |
| Secrets | demo creds in compose | injected from a secret manager / env file, never committed |
| Process | `uvicorn --reload` | `gunicorn` + uvicorn workers, non-root, read-only rootfs |
| Schema | `create_all` on startup (dev) | Alembic migrations applied by a dedicated `migrate` service before `api` starts; startup never mutates the production schema |
| Edge | open | CORS allowlist, rate limiting, TLS terminated in front |
| Health | `/health` | `/health` (liveness) + `/readyz` (readiness: DB + object storage) |
| Observability | stdout | structured JSON logs + `GET /metrics` (Prometheus) |

## 2. Configuration

All configuration is environment-driven (`backend/app/config.py`). Copy
`.env.example` to `.env` and fill it in; **never commit `.env`**.

Key variables:

| Variable | Purpose |
|---|---|
| `APP_ENV` | `development` / `staging` / `production` (reported by `/health`). |
| `DATABASE_URL` | Managed Postgres URL; use `sslmode=require` in production. |
| `EVIDENCE_STORAGE_BACKEND` | `s3` in production (`local` is dev-only). |
| `EVIDENCE_S3_BUCKET` / `_REGION` / `_ENDPOINT_URL` / `_PREFIX` | Object storage target. |
| `EVIDENCE_S3_SSE` / `_KMS_KEY_ID` | `AES256` (SSE-S3) or `aws:kms` + key id. |
| `EVIDENCE_S3_LIFECYCLE_*_DAYS` | Auto-archive/expire rules applied at startup. |
| `MAX_EVIDENCE_BYTES` | Hard upload limit enforced while streaming (default 10 MiB). |
| `ALLOWED_IMAGE_TYPES` | MIME allowlist checked against magic bytes. |
| `MALWARE_SCANNER` / `CLAMAV_HOST` / `CLAMAV_PORT` | `clamav` in production. |
| `MALWARE_SCAN_FAIL_CLOSED` | `true` rejects uploads when the scanner is down. |
| `DEFAULT_TENANT` | Tenant used when `X-Tenant-Id` is absent (still `demo`). |
| `EVIDENCE_RETENTION_DAYS` | Age (days) after which **approved** evidence is purged by the retention sweep. `0` (default) disables it. |
| `RETENTION_ADMIN_TOKEN` | Shared secret required by `POST /api/v1/maintenance/retention`. When unset the endpoint returns `503`. |
| `CORS_ALLOWED_ORIGINS` / `CORS_ALLOW_CREDENTIALS` | Exact-origin allowlist. |
| `RATE_LIMIT_ENABLED` / `_REQUESTS` / `_WINDOW_SECONDS` | Per-process limiter. |
| `RATE_LIMIT_MAX_KEYS` | Upper bound on distinct limiter keys held in memory (KL-032). |
| `RATE_LIMIT_CLIENT_IP_HEADER` | Trusted client-IP header (e.g. `X-Real-IP`) set by your edge; leave blank to use the socket peer address. |
| `RATE_LIMIT_TRUST_TENANT_HEADER` | Keep `false` unless a trusted edge forces a single tenant; otherwise a caller could rotate `X-Tenant-Id` to evade the limit. |
| `METRICS_ENABLED` / `METRICS_ADMIN_TOKEN` | `GET /metrics` Prometheus endpoint; set the token to require `X-Admin-Token` on every scrape. |
| `AUTO_CREATE_SCHEMA` | Dev-only `create_all`; keep `false` in production (schema is owned by migrations). |
| `RUN_MIGRATIONS_ON_START` | Opt-in in-process `alembic upgrade head` at startup; production uses the `migrate` service instead. |
| `DOCS_ENABLED` | Set `false` to hide `/docs`, `/redoc`, `/openapi.json`. |

Secrets — `DATABASE_URL`, AWS credentials, `MINIO_ROOT_PASSWORD` — must come
from a secret manager (AWS Secrets Manager, Vault, Kubernetes secrets, etc.).
Do not bake them into the image or commit them.

### Android client configuration

The Android client resolves its backend endpoint at **build time**, never from
a committed constant:

| Property | Equivalent env var | Purpose |
|---|---|---|
| `fieldproof.apiBaseUrl` (Gradle) | `FIELD_PROOF_API_BASE_URL` | Backend base URL compiled into `BuildConfig.API_BASE_URL` and used by Retrofit. Must end with `/`. |

- **Release / production:** build with the HTTPS endpoint, e.g.
  `FIELD_PROOF_API_BASE_URL=https://api.example.com/ ./gradlew :app:bundleRelease`.
  The release network security config (`app/src/release/res/xml/network_security_config.xml`)
  sets `cleartextTrafficPermitted="false"`, so a cleartext URL fails closed —
  if the property is unset the default loopback URL is blocked on purpose.
- **Debug / local development:** no override is needed. The debug network
  security config allows cleartext so `http://127.0.0.1:8000/` keeps working
  against a locally running backend.

## 3. Evidence storage (private, encrypted, lifecycle)

1. Create a **private** bucket (block all public access). The app additionally
   calls `put_public_access_block` at startup.
2. Enable **server-side encryption**. Set `EVIDENCE_S3_SSE=aws:kms` and
   `EVIDENCE_S3_KMS_KEY_ID=<key>` (or `AES256` for SSE-S3).
3. Objects are written per tenant:
   `s3://<bucket>/<prefix>/tenants/<tenant_id>/inspections/<inspection_id>/evidence/<evidence_id><ext>`.
4. **Lifecycle.** Provision lifecycle through **infrastructure management**
   (Terraform/CloudFormation) wherever possible. The app only writes the bucket
   lifecycle configuration when `EVIDENCE_S3_MANAGE_LIFECYCLE=true`, and even
   then it **merges** two rules it owns (by ID) and leaves every unrelated rule
   on the bucket untouched — it never full-replaces the configuration. Leaving
   it `false` (the default) guarantees app startup cannot clobber rules owned by
   a platform team.
   - `EVIDENCE_S3_LIFECYCLE_TRANSITION_DAYS` / `..._GLACIER_DAYS` transitions are
     **prefix-scoped** (archiving is non-destructive).
   - `EVIDENCE_S3_LIFECYCLE_DAYS` expiration is **tag-scoped**: the rule only
     matches objects tagged `fieldproof-retention=eligible`, and the only code
     that applies that tag is the retention sweep in `service.py`, which runs
     solely for evidence on **APPROVED** inspections past `EVIDENCE_RETENTION_DAYS`.
     Draft/submitted work is never tagged, so it never expires; objects under a
     legal hold (`fieldproof-legal-hold=true`) are refused the tag and skipped.
   - This keeps object storage and the database in lockstep: objects the API
     still references can never be removed by a lifecycle rule. Direct deletion
     (with the matching `Evidence` row) remains the sweep's primary path; the
     tag is a backstop if that delete fails.
5. Downloads are app-mediated (never public URLs) for the calling tenant with
   `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`.
   If you prefer presigned URLs, generate short-lived ones server-side and keep
   the tenant check.

## 4. Upload validation & scanning

- **Size:** the body is read in chunks and rejected with `413` once
  `MAX_EVIDENCE_BYTES` is exceeded; a `Content-Length` hint is used only for an
  early rejection.
- **Type:** the canonical MIME type is derived from the file's magic bytes and
  must be in `ALLOWED_IMAGE_TYPES`; a contradicting client `Content-Type`
  returns `415`.
- **Malware:** content is sent to the configured scanner before persistence.
  - `MALWARE_SCANNER=clamav` runs ClamAV `INSTREAM` against `CLAMAV_HOST:PORT`.
  - `MALWARE_SCAN_FAIL_CLOSED=true` returns `503` if the scanner is unavailable.
  - Keep ClamAV's signature database updated (the `clamav/clamav` image does
    this automatically on start; in managed setups use a sidecar/scanner service).

## 5. Container & runtime

`backend/Dockerfile` builds a multi-stage image that runs as the non-root
`app` user (uid 1001) and starts `gunicorn` with uvicorn workers. Build and run
the reference stack:

```bash
cd fieldproof-starter
cp .env.example .env      # edit values
docker compose -f docker-compose.prod.yml up -d --build
curl -fsS http://localhost:8000/readyz
```

The reference stack runs migrations as a **separate one-shot `migrate` service**
(`python -m alembic -c alembic.ini upgrade head`) that `api` depends on via
`service_completed_successfully`, so the schema is always migrated before the
first worker accepts traffic and the API container itself never mutates the
schema. To apply migrations outside compose (or for a manual deploy), run the
same command against the target `DATABASE_URL`.

Hardening already applied by the image/compose:

- non-root user, `read_only: true` root filesystem, `tmpfs /tmp`,
  `no-new-privileges`;
- no secrets in the image (runtime env only);
- `HEALTHCHECK` against `/health`, compose healthcheck against `/readyz`;
- log rotation via the `json-file` driver.

Scale horizontally with `gunicorn -w <workers>` or by running more replicas
behind a load balancer. **Rate limiting is per-process**: for multi-replica
deployments enforce limits at the ingress/API gateway or back the limiter with a
shared store (Redis).

## 6. Health, readiness & observability

- `GET /health` — liveness; always `200` if the process is up.
- `GET /readyz` — readiness; returns `200` only when the database answers
  `SELECT 1` **and** the object-store bucket is reachable; otherwise `503` with
  a component breakdown. Point load-balancer readiness probes at `/readyz` and
  liveness/restart probes at `/health`.
- Rate-limited responses return `429` with `Retry-After`.
- `GET /metrics` — Prometheus text exposition (when `METRICS_ENABLED=true`),
  covering request counts/latency, API errors, sync conflicts, rate-limit
  rejections, storage failures and migration counters. Require
  `METRICS_ADMIN_TOKEN` (sent as `X-Admin-Token`) when the endpoint is reachable
  beyond a private network. See `observability.md` for the metric catalogue and
  suggested alert thresholds.
- Logs are single-line JSON with credentials/notes redacted and entity ids
  normalized, so diagnostics can ship to a log aggregator without leaking
  secrets or client content (KL-034).

## 7. Edge security

- Terminate TLS in front of the app (ingress/ALB/reverse proxy); never expose
  plain HTTP in production.
- Set `CORS_ALLOWED_ORIGINS` to exact origins. Wildcard `*` with credentials is
  refused by the app; keep `CORS_ALLOW_CREDENTIALS=false` unless you truly need
  cookies.
- Enable `RATE_LIMIT_ENABLED=true`.
- Keep `DOCS_ENABLED=false` in production.

## 8. Staging → production checklist

- [ ] Managed Postgres with backups/PITR (see `disaster-recovery.md`).
- [ ] Private, encrypted object-storage bucket + lifecycle + versioning.
- [ ] Secrets loaded from a secret manager; `.env` not committed.
- [ ] `APP_ENV=production`, `DOCS_ENABLED=false`.
- [ ] `EVIDENCE_STORAGE_BACKEND=s3`, KMS key set.
- [ ] `MALWARE_SCANNER=clamav`, `MALWARE_SCAN_FAIL_CLOSED=true`.
- [ ] `CORS_ALLOWED_ORIGINS` set to real origins; rate limiting enabled.
- [ ] `/readyz` wired to readiness probes; `/health` to liveness probes.
- [ ] TLS terminated; container runs non-root.
- [ ] Migrations applied (`migrate` service / `alembic upgrade head`) and
      `alembic check` reports no model drift.
- [ ] `GET /metrics` scraped by monitoring; alert rules wired (see `observability.md`).
- [ ] Run the backend test suite against the staging database
      (`TEST_DATABASE_URL=... pytest tests`) before promoting.

## 9. Rolling back

Images are immutable (`fieldproof-api:<git-sha>` recommended). Roll back by
redeploying the previous image tag.

**Schema.** Migrations are Alembic-managed, so schema rollback is explicit and
never applied implicitly at startup:

- **Additive releases** (new tables/columns, no destructive change): rolling the
  image back is safe — the old code ignores the extra columns. Keep migrations
  additive within a release window.
- **Releases with a destructive/downgrade step**: the initial migration ships a
  `downgrade` path. To roll the schema back, run
  `python -m alembic -c alembic.ini downgrade <target_revision>` against the
  target `DATABASE_URL`, then redeploy the previous image. Take a database
  backup/PITR snapshot first (see `disaster-recovery.md`) — a downgrade that
  drops columns/tables is irreversible without it.
- Do **not** set `RUN_MIGRATIONS_ON_START` during a rollback; let the
  `migrate` service (pinned to the rolled-back image's migrations) apply the
  correct revision.
