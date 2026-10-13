# Deployment guide — staging & production

> **Scope.** This document describes how to run the FieldProof backend as a
> hardened service: private object storage, upload validation, secrets
> externalization, a non-root container, readiness checks, rate limiting and a
> CORS allowlist. It is a **baseline**, not a compliance certification. See
> `known-limitations.md` for what is intentionally still missing (most notably
> authentication, KL-001, and migrations, KL-003).

## 1. What changed vs. localhost

| Concern | Local demo (`docker-compose.yml`) | Staging / production |
|---|---|---|
| Evidence storage | local filesystem (`backend/var/evidence`) | private S3-compatible bucket, SSE-encrypted, lifecycle policy |
| Secrets | demo creds in compose | injected from a secret manager / env file, never committed |
| Process | `uvicorn --reload` | `gunicorn` + uvicorn workers, non-root, read-only rootfs |
| Schema | `create_all` on startup | `create_all` still (KL-003); run Alembic before GA |
| Edge | open | CORS allowlist, rate limiting, TLS terminated in front |
| Health | `/health` | `/health` (liveness) + `/readyz` (readiness: DB + object storage) |

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
| `DOCS_ENABLED` | Set `false` to hide `/docs`, `/redoc`, `/openapi.json`. |

Secrets — `DATABASE_URL`, AWS credentials, `MINIO_ROOT_PASSWORD` — must come
from a secret manager (AWS Secrets Manager, Vault, Kubernetes secrets, etc.).
Do not bake them into the image or commit them.

## 3. Evidence storage (private, encrypted, lifecycle)

1. Create a **private** bucket (block all public access). The app additionally
   calls `put_public_access_block` at startup.
2. Enable **server-side encryption**. Set `EVIDENCE_S3_SSE=aws:kms` and
   `EVIDENCE_S3_KMS_KEY_ID=<key>` (or `AES256` for SSE-S3).
3. Objects are written per tenant:
   `s3://<bucket>/<prefix>/tenants/<tenant_id>/inspections/<inspection_id>/evidence/<evidence_id><ext>`.
4. **Lifecycle:** at startup the app applies a lifecycle rule under the prefix
   that transitions objects to `STANDARD_IA`/`GLACIER` and expires them
   according to `EVIDENCE_S3_LIFECYCLE_*_DAYS`. Adjust to your retention policy.
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
- [ ] Run the backend test suite against the staging database
      (`TEST_DATABASE_URL=... pytest tests`) before promoting.

## 9. Rolling back

Images are immutable (`fieldproof-api:<git-sha>` recommended). Roll back by
redeploying the previous image tag; the schema is additive and created with
`create_all`, so no destructive migration is applied automatically. If a release
adds columns, deploy it during a maintenance window until real migrations exist
(KL-003).
