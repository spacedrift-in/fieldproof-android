# FieldProof backend — observability runbook

> Structured logging, metrics and the alerting signals for the FastAPI backend.
> Covers what is emitted, what must **never** be emitted, and suggested alert
> thresholds. See `known-limitations.md` KL-032/KL-034 and `deployment.md` §6.

## 1. Structured logs

Every request emits exactly one `http_request` record on the
`fieldproof.request` logger, rendered as single-line JSON by
`backend/app/observability.py`:

```json
{"ts": "...", "level": "INFO", "logger": "fieldproof.request",
 "message": "http_request", "request_id": "…", "method": "PUT",
 "path": "/api/v1/inspections/{id}/checklist/{id}", "status": 200,
 "duration_ms": 12.3, "client": "10.0.0.7"}
```

- **`request_id`** is echoed as the `X-Request-Id` response header so a client
  error can be matched to a log line.
- **`path`** is normalized (`normalize_path`): UUID segments collapse to `{id}`,
  so log/metric cardinality stays bounded and raw entity ids do not land in the
  log stream.
- The same redaction pass runs over every log record (message and any
  structured fields) before it is written.

### What is never logged (privacy)

The redaction filter (`RedactionFilter`) masks, with `[redacted]`:

- bearer tokens and `Authorization` values;
- `token` / `secret` / `password` / `api_key` values;
- `x-admin-token` / `idempotency-key` / `x-tenant-id` values;
- long opaque base64/hex runs (>= 40 chars).

Photo bytes, evidence content, checklist notes and reviewer comments are **never**
placed on a log record by design — the request log carries only
`request_id`/`method`/`path`/`status`/`duration_ms`/`client`. `test_observability.py`
asserts a note body and raw UUIDs do not appear in the captured log output.
A signed privacy review of the shipped pipeline is still pending (deferred).

## 2. Metrics (`GET /metrics`)

Prometheus text exposition, enabled with `METRICS_ENABLED=true` (default on).
Set `METRICS_ADMIN_TOKEN` to require `X-Admin-Token` on every scrape. The
endpoint is exempt from rate limiting. Counters reset on process restart.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `http_requests_total` | counter | `method`, `path`, `status` | Requests served (all responses). |
| `http_request_duration_seconds` | summary | `method`, `path` | Latency; exposed as `_count` / `_sum`. |
| `api_errors_total` | counter | `path`, `status` | 5xx responses. |
| `api_conflicts_total` | counter | `path` | 409 responses (optimistic-concurrency / sync conflicts). |
| `rate_limit_rejections_total` | counter | `path` | 429 responses (KL-032). |
| `evidence_uploads_total` | counter | `kind` | Successful evidence uploads (`photo` / `note`). |
| `storage_failures_total` | counter | `operation` | Object-store operation failures (e.g. `delete`). |
| `migrations_applied_total` | counter | — | Successful `alembic upgrade` runs (in-process path). |
| `migration_failures_total` | counter | — | Failed migration runs — alert immediately. |

## 3. Suggested alert thresholds

Tune to your traffic; these are safe starting points for a single-replica
service. Path labels are normalized (`{id}`), so they are low-cardinality.

| Alert | Condition | Why |
|---|---|---|
| API error rate | `rate(api_errors_total[5m]) / rate(http_requests_total[5m]) > 0.02` | Sustained 5xx. |
| Latency | `histogram`-style: `sum(rate(http_request_duration_seconds_sum[5m])) / sum(rate(http_request_duration_seconds_count[5m])) > 1.0` | p95 proxy above ~1s. |
| Sync conflict spike | `increase(api_conflicts_total[10m]) > 20` | Many clients fighting over the same items (KL-029/KL-033). |
| Rate-limit abuse | `increase(rate_limit_rejections_total[5m]) > 500` | Credential stuffing / runaway client / missing edge limit. |
| Storage failures | `increase(storage_failures_total[5m]) > 0` | Object store unreachable or misconfigured; retention/deletion degraded. |
| Migration failure | `increase(migration_failures_total[10m]) > 0` | Deploy is broken — pages immediately. |
| Upload collapse | `sum(rate(evidence_uploads_total[15m])) == 0` over a working window | No photos/notes landing (client or backend outage). |

Scrape `/metrics` with a Prometheus/Grafana agent and alert from those rules.
Wiring a live stack and routing alerts is deferred (no monitoring provider is
provisioned in this repo).

## 4. Runbooks

### `migration_failures_total` is firing (deploy failed)

1. Check the `migrate` service logs: `docker compose -f docker-compose.prod.yml logs migrate`.
2. Reproduce locally against a scratch DB: `python -m alembic -c backend/alembic.ini upgrade head`.
3. If the schema is half-applied, restore from PITR/backup (see
   `disaster-recovery.md`) and re-run the migration from a clean revision. Do not
   start `api` against a partially migrated schema (`/readyz` will still pass on
   connectivity, so rely on the migration gate, not readiness).

### `storage_failures_total` is firing

1. Confirm bucket reachability and credentials (`/readyz` reports the object-store
   component).
2. Check KMS/SSE permissions and that the bucket policy still allows the app's
   principal to `DeleteObject`/`GetObject`.
3. Deletion failures leave objects that the retention **tag** backstop still
   catches on the next sweep; verify the `fieldproof-retention=eligible` tag
   lifecycle does not expire anything still referenced (KL-028).

### `api_conflicts_total` is spiking

1. Normal after a long-offline client reconnects; the conflict UI resolves it.
2. A sustained spike usually means two devices editing the same inspection —
   confirm the client surfaces the per-item conflict resolver rather than
   silently retrying (KL-029/KL-033).

### `rate_limit_rejections_total` is spiking

1. Identify the client (`client` field on request logs; limiter keys on the
   transport IP or a trusted header).
2. If legitimate traffic is being limited, raise `RATE_LIMIT_REQUESTS` or
   enforce a higher limit at the edge instead.
3. In multi-replica deployments the in-process limiter is per-replica (KL-032);
   move enforcement to the gateway or a shared store.

## 5. Health vs. metrics

`/health` (liveness) and `/readyz` (readiness: DB + object store) are separate
from `/metrics`. Probe with `/health`+`/readyz`, scrape with `/metrics`; do not
use `/metrics` as a health signal (it is exempt from rate limiting on purpose).
