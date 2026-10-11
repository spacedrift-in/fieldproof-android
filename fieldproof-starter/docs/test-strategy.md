# Test strategy

FieldProof is offline-first: the highest-risk path is local capture followed by reliable eventual upload. Tests should prove that path before expanding UI surface area.

## Test layers

| Layer | Scope | Required proof |
|---|---|---|
| Backend unit/API | FastAPI + Postgres | inspection lifecycle, upload idempotency, validation, conflict responses |
| Android JVM | repositories and state mapping | operation transitions, idempotency-key reuse, retry classification |
| Android instrumented | Room + Compose + WorkManager | process-death persistence, permission denial, sync-state visibility |
| Release verification | real build artefacts | debug/release assembly, unit tests, synthetic demo, known limitations |

## Critical scenarios

- Create an inspection and checklist while offline; data survives recreation.
- Capture photo and note evidence; each remains linked to the correct checklist item.
- Queue the same evidence twice; only one unique upload operation is scheduled.
- Retry a timed-out upload; the idempotency key remains unchanged.
- Return HTTP 409 from the API; the Android UI shows a conflict state without overwriting data.
- Finalize with missing required evidence; both client feedback and server enforcement block it.
- Generate a report only from a valid finalized inspection and verify summary counts.

## CI gates

- Run backend tests with Postgres in GitHub Actions.
- Run Android debug assembly, unit tests, and release assembly on every PR.
- Add an emulator lane before release to run critical instrumented tests.
- Upload test reports on failure and preserve the exact command needed to reproduce locally.

## Definition of done

A feature is ready when it has its Linear acceptance criterion, a focused PR, automated coverage at the appropriate layer, and a known-limitations update where behavior is intentionally deferred.

Tracked in Linear: SPA-25, SPA-27, and SPA-28.
