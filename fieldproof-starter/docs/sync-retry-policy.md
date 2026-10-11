# Sync retry policy

This policy governs durable evidence-upload operations. It complements the state machine in `sync-state-machine.md`.

## States

| State | Meaning | Allowed next state |
|---|---|---|
| `QUEUED` | Ready for a network attempt | `UPLOADING` |
| `UPLOADING` | A worker owns the request | `SYNCED`, `QUEUED`, `FAILED`, `NEEDS_RESOLUTION` |
| `SYNCED` | Server accepted the idempotency key | terminal |
| `FAILED` | Permanent error or retry budget exhausted | `QUEUED` only after explicit user retry |
| `NEEDS_RESOLUTION` | Server detected a version conflict | terminal until user resolves it |

## Retry classification

Retryable: no network, timeout, connection reset, HTTP 408, 429, and 5xx. These return WorkManager `Result.retry()` and retain the same operation ID and idempotency key.

Permanent: validation failures, missing local evidence, malformed payloads, and unauthorized responses. These move the operation to `FAILED` with an actionable error.

Conflicts: HTTP 409 moves the operation to `NEEDS_RESOLUTION`; the client must not overwrite server data.

## Backoff and idempotency

- Apply a connected-network constraint to upload work.
- Use unique work per operation; do not enqueue duplicate workers.
- Use exponential backoff, beginning at the platform minimum.
- Never generate a new idempotency key on retry.
- Increment attempts only when an upload request begins.
- Do not mark an operation synced until the API confirms success.

## Verification matrix

1. Airplane mode queues evidence without loss.
2. Restoring connectivity performs one upload for a stable idempotency key.
3. A transient server failure reschedules work and preserves evidence.
4. Replaying a successful request creates no duplicate server evidence.
5. A 409 reaches `NEEDS_RESOLUTION` and exposes no destructive automatic action.
6. An explicit retry from `FAILED` creates a new attempt while retaining the original evidence.

Tracked in Linear: SPA-17, SPA-18, SPA-19, SPA-20, SPA-21, SPA-23, and SPA-24.
