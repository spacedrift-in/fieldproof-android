# API contract (initial proposal)

Prefix: `/api/v1`. JSON uses snake_case. UUIDs are strings. Timestamps are ISO-8601 UTC. Protected routes will require authentication once the auth approach is selected.

## Initial endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness check; no database guarantee |
| GET | `/api/v1/templates` | List active templates |
| POST | `/api/v1/inspections` | Create/sync an inspection using client-generated ID |
| GET | `/api/v1/inspections/{inspection_id}` | Fetch inspection and server version |
| PUT | `/api/v1/inspections/{inspection_id}` | Update with `expected_version` |
| POST | `/api/v1/inspections/{inspection_id}/evidence` | Upload evidence; requires `Idempotency-Key` header |
| POST | `/api/v1/inspections/{inspection_id}/submit` | Submit after server validation |
| POST | `/api/v1/inspections/{inspection_id}/review` | Record reviewer decision |
| GET | `/api/v1/inspections/{inspection_id}/report` | Generate/retrieve report for approved version |

## Error behavior

- `400`: malformed or invalid business request.
- `401`: unauthenticated.
- `403`: authenticated but not permitted.
- `404`: record not found or not visible to caller.
- `409`: version conflict or idempotency key reused with a different request body.
- `413`: upload exceeds configured size limit.
- `415`: unsupported media type.
- `422`: schema validation failure.
- `5xx`: unexpected server failure; client retains local evidence and retries according to policy.

## Idempotent evidence upload

The client creates a stable operation UUID before attempting an upload and sends it in `Idempotency-Key`. The server records the key and request fingerprint transactionally with the resulting evidence record. A repeat request with the same key and same fingerprint returns the original result. The same key with a different fingerprint returns `409`.

## Optimistic concurrency

An update includes `expected_version`. If it differs from the current server version, return `409` with current server version and enough safe metadata for the client to present a conflict-resolution UI. Do not silently apply last-write-wins.

## Implementation status

Implemented and covered by tests against PostgreSQL:

- `GET /health`
- `POST /api/v1/inspections` (requires `Idempotency-Key`)
- `GET /api/v1/inspections/{inspection_id}`
- `POST /api/v1/inspections/{inspection_id}/evidence` (multipart file + metadata, requires `Idempotency-Key`)
- `POST /api/v1/inspections/{inspection_id}/submit`
- `POST /api/v1/inspections/{inspection_id}/review`
- `GET /api/v1/inspections/{inspection_id}/report`

Not implemented yet: `GET /api/v1/templates`, `PUT /api/v1/inspections/{inspection_id}`,
authentication/authorization, PDF report rendering, and checklist item outcome updates.

### Evidence upload format

`POST /api/v1/inspections/{inspection_id}/evidence` uses `multipart/form-data`:

- `file` — the photo (required when `kind=photo`; must be `image/*`, max `MAX_EVIDENCE_BYTES`).
- `id` — optional client-generated evidence UUID (server generates one if omitted).
- `kind` — `photo` or `note`.
- `checklist_item_id` — optional checklist item the evidence belongs to.
- `note` — text for `kind=note`.
- `captured_at` — optional capture time, epoch milliseconds.
- header `Idempotency-Key` — required.
