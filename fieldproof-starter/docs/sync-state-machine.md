# Sync state machine (initial proposal)

## Evidence sync states

- `queued`: durable local operation exists; not currently uploading.
- `uploading`: a worker has claimed the operation.
- `synced`: server acknowledged the idempotent operation and remote reference is stored locally.
- `failed`: last attempt failed; local evidence remains available and retry is offered.
- `needs_resolution`: server returned a conflict that cannot be safely retried without user input.

Transitions:

```text
queued -> uploading
uploading -> synced
uploading -> queued        (transient failure / retry scheduled)
uploading -> failed        (retry budget exhausted or actionable permanent error)
uploading -> needs_resolution (version conflict)
failed -> queued           (user retry or retry policy)
needs_resolution -> queued (after explicit resolution creates a new operation)
```

## Retry policy

Use WorkManager network constraints and exponential backoff for transient errors. Keep retry counts and last error codes in the local database. Do not delete local files merely because an upload was attempted. Avoid endless retries for permanent errors such as unsupported media type or authorization denial; show a useful action instead.

## Upload uncertainty

If the server stores an upload but the response is lost, the client retries with the same idempotency key. The server returns the prior result instead of creating a duplicate. WorkManager alone does not provide exactly-once delivery.

## Conflict policy

Never silently overwrite. Return `409`, show the user's local version and server version, and require a deliberate resolution. Record the selected resolution as a new versioned operation for auditability.

## Implementation notes (Android client)

The client persists the proposed `needs_resolution` state as the `SyncOperation.status` string `CONFLICT`. A conflict is resolved deliberately through `ConflictResolver`; the retired operation is retained as `RESOLVED` (never deleted) for auditability, and superseded local evidence is kept and marked `SUPERSEDED`:

- **Keep local & retry** — for evidence, the local row is re-keyed (new id) and a fresh `QUEUED` operation is created so the identical content uploads under a new idempotency key; for an inspection, the existing operation is requeued. This is the `needs_resolution -> queued (after explicit resolution creates a new operation)` transition.
- **Use server version** — the conflicting local record is retained but marked `SUPERSEDED` and the operation is marked `RESOLVED`. No local data is deleted.

A `RESOLVED` inspection operation unblocks its dependent evidence, which treats it like `SYNCED` in the inspection gate.

The server's `409` body is mapped to a stable `ServerConflict` domain model (`app/.../domain/conflict/`) at the network boundary. Structured version conflicts (`current_version`/`current_status`) and missing-required-evidence conflicts (`missing_checklist_item_ids`) get typed cases; plain-string idempotency/state rejections keep the server message verbatim; anything unrecognized is retained as `Unrecognized` with the raw body. The raw payload is stored on the operation's `conflictDetail` column so the conflict can be re-presented after process death. Mapping a conflict only updates sync metadata — local inspection/evidence content is never overwritten.
