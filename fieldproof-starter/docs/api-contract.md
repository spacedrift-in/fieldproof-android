# API contract (initial proposal)

Prefix: `/api/v1`. JSON uses snake_case. UUIDs are strings. Timestamps are ISO-8601 UTC. Protected routes will require authentication once the auth approach is selected.

## Initial endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness check; no database guarantee |
| GET | `/readyz` | Readiness check; verifies database and object storage |
| GET | `/api/v1/templates` | List active templates |
| POST | `/api/v1/inspections` | Create/sync an inspection using client-generated ID |
| GET | `/api/v1/inspections/{inspection_id}` | Fetch inspection and server version |
| PUT | `/api/v1/inspections/{inspection_id}` | Update with `expected_version` |
| POST | `/api/v1/inspections/{inspection_id}/evidence` | Upload evidence; requires `Idempotency-Key` header |
| GET | `/api/v1/inspections/{inspection_id}/evidence/{evidence_id}` | Download stored evidence with safe headers |
| PUT | `/api/v1/inspections/{inspection_id}/checklist/{checklist_item_id}` | Set a checklist item outcome (`PASS`/`FAIL`/`NOT_APPLICABLE`) and optional note; uses `expected_version` |
| POST | `/api/v1/inspections/{inspection_id}/submit` | Submit after server validation |
| POST | `/api/v1/inspections/{inspection_id}/review` | Record reviewer decision |
| GET | `/api/v1/inspections/{inspection_id}/report` | Generate/retrieve report for approved version |
| GET | `/api/v1/inspections/{inspection_id}/report.pdf` | Download the report as a PDF |

### Tenancy header

All endpoints resolve the caller's tenant from `X-Tenant-Id` (1–63 chars,
`[A-Za-z0-9_-]`), defaulting to `DEFAULT_TENANT` (`demo`). Records are namespaced
per tenant and cross-tenant access returns `404` (never `403`, so tenant
existence is not leaked). Authentication is not implemented yet (KL-001); when it
lands, this header must be replaced by a verified identity claim.

## Error behavior

- `400`: malformed or invalid business request (also invalid `X-Tenant-Id`).
- `401`: unauthenticated.
- `403`: authenticated but not permitted.
- `404`: record not found or not visible to caller.
- `409`: version conflict or idempotency key reused with a different request body.
- `413`: upload exceeds configured size limit.
- `415`: unsupported media type (not a recognised image, not allowlisted, or declared type contradicts the detected type).
- `422`: schema validation failure (also rejected by the malware scanner).
- `429`: rate limit exceeded (when `RATE_LIMIT_ENABLED=true`); includes `Retry-After`.
- `503`: malware scanning unavailable and `MALWARE_SCAN_FAIL_CLOSED=true`; readiness failure on `/readyz`.
- `5xx`: unexpected server failure; client retains local evidence and retries according to policy.

## Idempotent evidence upload

The client creates a stable operation UUID before attempting an upload and sends it in `Idempotency-Key`. The server records the key and request fingerprint transactionally with the resulting evidence record. A repeat request with the same key and same fingerprint returns the original result. The same key with a different fingerprint returns `409`.

## Optimistic concurrency

An update includes `expected_version`. If it differs from the current server version, return `409` with current server version and enough safe metadata for the client to present a conflict-resolution UI. Do not silently apply last-write-wins.

## Submission validation

`POST /api/v1/inspections/{inspection_id}/submit` runs the server's own
validation before transitioning status. Every **required** checklist item must
have both required evidence **and** a terminal outcome
(`PASS`/`FAIL`/`NOT_APPLICABLE`). On failure it returns `409` with:

```json
{"detail":{"message":"Submission blocked by validation errors.",
 "missing_checklist_item_ids":["<uuid>"],
 "errors":[{"field":"checklist_items.<uuid>.evidence|status",
            "code":"MISSING_REQUIRED_EVIDENCE|OUTCOME_REQUIRED",
            "message":"..."}]}}
```

`missing_checklist_item_ids` lists items missing required evidence (retained for
backward compatibility with the Android field-level highlight); `errors` adds
`field`/`code`/`message` detail so any client can point at the exact failing
control, including the outcome requirement.

## Implementation status

Implemented and covered by tests against PostgreSQL:

- `GET /health` and `GET /readyz`
- `POST /api/v1/inspections` (requires `Idempotency-Key`)
- `GET /api/v1/inspections/{inspection_id}`
- `POST /api/v1/inspections/{inspection_id}/evidence` (multipart file + metadata, requires `Idempotency-Key`)
- `GET /api/v1/inspections/{inspection_id}/evidence/{evidence_id}`
- `PUT /api/v1/inspections/{inspection_id}/checklist/{checklist_item_id}`
- `POST /api/v1/inspections/{inspection_id}/submit`
- `POST /api/v1/inspections/{inspection_id}/review`
- `GET /api/v1/inspections/{inspection_id}/report`
- `GET /api/v1/inspections/{inspection_id}/report.pdf`

Not implemented yet: `GET /api/v1/templates`, `PUT /api/v1/inspections/{inspection_id}`,
and authentication/authorization (KL-001). Schema migrations are still
`create_all` based (KL-003).

### Checklist outcome format

`PUT /api/v1/inspections/{inspection_id}/checklist/{checklist_item_id}` sets one
item's outcome so report totals reflect real results. Body:

- `status` — one of `NOT_STARTED`, `PASS`, `FAIL`, `NOT_APPLICABLE`.
- `note` — optional free text; blank/whitespace is stored as `null`.
- `expected_version` — optional checklist **item** version for optimistic concurrency.

No `Idempotency-Key` is required: repeating the same `status`+`note` is an
idempotent no-op that does not bump any version, so client retries are safe.
A stale `expected_version` returns `409` with
`{"detail":{"message","current_version","current_status","checklist_item_id"}}`.
Editing is only allowed while the inspection is in an editable state
(`DRAFT`, `IN_PROGRESS`, `CHECKLIST_EDITABLE`); otherwise `409`.

Response: `{"inspection_id","inspection_version","item":{...}}` where `item`
includes the updated `status`, `note` and `version`.

### Evidence upload format

`POST /api/v1/inspections/{inspection_id}/evidence` uses `multipart/form-data`:

- `file` — the photo (required when `kind=photo`; max `MAX_EVIDENCE_BYTES`).
- `id` — optional client-generated evidence UUID (server generates one if omitted).
- `kind` — `photo` or `note`.
- `checklist_item_id` — optional checklist item the evidence belongs to.
- `note` — text for `kind=note`.
- `captured_at` — optional capture time, epoch milliseconds.
- header `Idempotency-Key` — required.
- header `X-Tenant-Id` — optional; defaults to `DEFAULT_TENANT`.

Uploads are hardened server-side:

- **Bounded streaming.** The body is read in chunks and rejected with `413` as
  soon as `MAX_EVIDENCE_BYTES` is exceeded (a `Content-Length` hint allows an
  early reject but is never trusted).
- **Content-based MIME.** The image type is derived from the file's magic bytes
  and must be in `ALLOWED_IMAGE_TYPES`; the client `Content-Type` is ignored
  unless it contradicts the detected type (then `415`).
- **Malware scanning.** Content passes the configured `MALWARE_SCANNER` before
  persistence. An infected file returns `422`; an unavailable scanner returns
  `503` when `MALWARE_SCAN_FAIL_CLOSED=true`.
- **Storage keys.** Objects are written under
  `tenants/{tenant_id}/inspections/{inspection_id}/evidence/{evidence_id}{ext}`
  in private, encrypted object storage.

### Evidence download format

`GET /api/v1/inspections/{inspection_id}/evidence/{evidence_id}` streams the
stored object for the calling tenant with safe headers:
`Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`, and
`Cache-Control: private, no-store`. Missing or cross-tenant evidence returns
`404`.

## Deletion & retention

Deletion removes **both** the database rows and the stored evidence objects, and
clears any idempotency records for the deleted entities so a replayed request
cannot return a stale response. Deletes are tenant-scoped with the same
`404`-not-`403` rule as reads, so tenant existence is never leaked.

| Endpoint | Behavior |
|---|---|
| `DELETE /api/v1/inspections/{inspection_id}` | Deletes the inspection, its checklist items, evidence rows and evidence objects. Returns `{inspection_id, evidence_deleted, objects_deleted}`. Cross-tenant → `404`. |
| `DELETE /api/v1/tenant` | Deletes **all** data for the caller's tenant. Requires `X-Confirm-Delete: <tenant_id>` matching the resolved tenant, otherwise `400`. Returns `{tenant_id, inspections_deleted, evidence_deleted, objects_deleted}`. |
| `POST /api/v1/maintenance/retention` | Runs the retention sweep (see below). Requires `X-Admin-Token` matching `RETENTION_ADMIN_TOKEN`; `503` if the token is unset, `401` on mismatch. Optional `?days=` overrides `EVIDENCE_RETENTION_DAYS`. Returns `{purged, objects_deleted}`. |

**Retention policy.** `POST /api/v1/maintenance/retention` deletes evidence
uploaded more than the configured number of days ago **only when it belongs to
an `APPROVED` (finalized) inspection**. Draft and submitted work is never
purged. A `days` value `<= 0` disables the sweep. There is no built-in
scheduler — trigger it from a cron/Job/EventBridge rule.
