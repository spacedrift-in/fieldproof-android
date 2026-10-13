# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Offline-first inspection vertical slice:
  - Room data layer for inspections, checklist items, evidence and sync operations.
  - Repositories (`InspectionRepository`, `SyncRepository`, `RemoteRepository`)
    and `InspectionViewModel`.
  - Jetpack Compose UI for the inspection list/detail, checklist and evidence
    capture, plus a CameraX capture screen with runtime permission handling.
  - Retrofit/OkHttp client matching the documented `/api/v1` contract,
    including the `Idempotency-Key` header on evidence uploads.
- `fieldproof-starter/`: project brief, ADRs, API contract, data model, sync
  state machine, FastAPI health skeleton and synthetic fixtures.
- Project documentation: README, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY and
  this changelog; Apache-2.0 license and NOTICE.
- GitHub Actions CI for the Android module and the backend skeleton.
- Acceptance test asserting the generated PDF report matches the documented
  criteria (`backend/tests/test_report_pdf.py`).
- Conflict resolution for sync: conflicted operations are surfaced per item with
  both the local and server versions, and the user deliberately chooses *Keep
  local & retry* or *Use server version* (`ConflictResolver`,
  `ConflictResolutionCard`). Keeping local re-keys and re-queues the evidence
  under a fresh idempotency key; accepting the server archives the local copy as
  `SUPERSEDED` — local work is never silently discarded.
- Stable `ServerConflict` domain model (`domain/conflict/`) that maps the
  backend's HTTP 409 payloads — structured version conflicts, missing-required-
  evidence conflicts, and plain state/idempotency rejections — while preserving
  the raw server payload. The 409 body is now parsed at the network boundary
  (`RemoteRepository`) and persisted on the sync operation (`conflictDetail`,
  Room migration 1→2), so the UI can show the server's version/status and
  message. Contract tests lock the wire format: `ServerConflictParserTest`,
  `RemoteRepositoryConflictContractTest`, and `SyncConflictPreservationTest`
  (which asserts local content is never overwritten by a conflict).
- Unique, connectivity-gated sync scheduling: queuing an inspection or evidence
  now schedules the unique `SyncWorker` pass through `SyncScheduler`
  (`WorkManagerSyncScheduler`), so work captured offline is dispatched as soon as
  the device reconnects instead of waiting for the next app launch. The request
  carries a `NetworkType.CONNECTED` constraint and exponential backoff, runs under
  the Hilt `HiltWorkerFactory`, and reuses each row's durable entity id as its
  idempotency key. Asserted by `SyncSchedulerWorkManagerTest`.
- Wire-level integration coverage for the multipart evidence contract:
  `EvidenceUploadIntegrationTest` drives the real Retrofit/OkHttp stack against a
  loopback server that replays the backend's idempotency rule. It asserts the
  `file` + metadata parts and the `Idempotency-Key` header, and that replaying the
  same upload returns the stored evidence without creating a duplicate (a reused
  key with changed content is a 409 conflict).
- Finalization against the server submit endpoint: submitting an inspection now
  surfaces the server's required-evidence 409 at field level. The missing
  checklist item ids (`missing_checklist_item_ids`) are held in
  `WorkflowUiState.missingEvidenceItemIds`, used to flag the exact
  `ChecklistItemRow`s, and to keep the submit button disabled until the user
  addresses them (`InspectionViewModelSubmitValidationTest`,
  `ChecklistCompletionDeviceTest`).
- Polished primary-journey states so no screen is ever ambiguous:
  - The inspection list shows a centered `LoadingState` while the first frame of
    data loads, instead of briefly claiming *No inspections yet*
    (`HomeLoadingStateDeviceTest`).
  - The CameraX screen models its own lifecycle explicitly
    (`Starting`/`Ready`/`Failed`), overlays a progress spinner until the preview
    is bound, and replaces a blank frame with a recoverable error state
    (`CameraErrorScreen`) that offers *Try again* or close. The capture header now
    carries a real close button, capture is guarded against double-taps, and
    decode/upload failures surface inline instead of failing silently
    (`CameraStateDeviceTest`).
  - The inspection detail screen observes its inspection by id and shows a
    loading state until the first database emission, so opening or deep-linking
    never flashes *Inspection not found* (`InspectionDetailStateTest`).
  - The camera permission screen re-checks the grant on `ON_RESUME` (granting it
    in Settings recovers the flow without a relaunch) and, once the system
    reports permanent denial, swaps *Allow camera* for an *Open settings* action
    instead of a dead end; *Not now* still falls back to a written note
    (`CameraPermissionDeviceTest`).

- Privacy & security hardening on Android: `allowBackup=false` with explicit
  backup/data-extraction excludes, Keystore-backed AES-256-GCM encryption of
  captured evidence files (`security/EvidenceVault.kt`), `FLAG_SECURE` on the
  detail and camera screens (`ui/components/SecureScreen.kt`), and
  release-gated logging. See `fieldproof-starter/docs/security.md`.
- Deletion and retention across the stack: `DELETE /api/v1/inspections/{id}`,
  confirmed tenant wipe (`DELETE /api/v1/tenant`) and an admin-triggered
  retention sweep (`POST /api/v1/maintenance/retention`, `EVIDENCE_RETENTION_DAYS`);
  the Android client also purges expired evidence on launch and supports
  deleting all local data (`InspectionRepository`, `SyncRepository`).
- Production deployment/security baseline for the backend: private S3-compatible
  object storage with SSE, lifecycle and public-access block, size-bounded
  streaming uploads, magic-byte MIME validation and a malware-scan integration
  point, `/readyz`, CORS allowlist, per-process rate limiting, a non-root
  production image, and `docs/deployment.md` / `docs/disaster-recovery.md`.
- Signed Android release pipeline (`.github/workflows/release.yml`): a gated
  build produces a versioned, signed AAB **and** APK, verifies the signature,
  and archives R8 mapping files, a CycloneDX SBOM, a license report and
  checksums, with SLSA build provenance and a GitHub Release. Signing keys are
  never committed — the upload keystore is fetched from AWS Secrets Manager over
  GitHub OIDC at build time (`scripts/fetch_upload_keystore.sh`,
  `scripts/check_repo_hygiene.sh`). Dependency/license tooling
  (`scripts/generate_sbom.py`, `scripts/license_scan.py`,
  `scripts/license-policy.json`) enforces an allow/deny policy over the release
  runtime classpath. See `fieldproof-starter/docs/release.md`.

### Documentation & reproducibility

- `fieldproof-starter/docs/demo.md` gains a reproducibility checklist and its
  test counts are refreshed; the sync caveat now reflects the on-queue
  `SyncWorker` (trigger-driven, no periodic worker).
- `scripts/verify_clean_setup.sh` no longer fails when the optional demo API is
  not yet running, asserts the demo artifacts exist (synthetic template,
  seed script, 12 screenshots, sample report PDF), and `--full` runs the backend
  pytest suite plus the Android unit tests.
- `known-limitations.md` `KL-004` and `fieldproof-starter/README.md` updated to
  describe on-queue sync scheduling accurately.
- Verified end to end against a throwaway PostgreSQL 16 instance:
  `scripts/seed_demo.py` is idempotent (3 evidence items, no duplicates after a
  re-run), regenerates `docs/screenshots/sample-report.pdf`, and the synthetic
  photo fixture is byte-for-byte deterministic.

### Changed

- Sync-state presentation now distinguishes every state independently instead of
  collapsing `QUEUED` and `UPLOADING`: `syncStatusLabel` labels them *Queued* and
  *Uploading* and gives each its own icon. The inspection detail shows the
  inspection operation's own status badge, and a failed evidence upload offers a
  field-level *Retry* action (`retryOperation`) alongside the existing aggregate
  retry. Covered by `SyncStatusDisplayTest` and `SyncStateDisplayDeviceTest`.
- The app now draws edge-to-edge via `enableEdgeToEdge()`, applies system-bar
  insets to the camera surfaces, and opts into predictive back
  (`android:enableOnBackInvokedCallback="true"`).

### Fixed

- Apply the Hilt Gradle plugin and add the Hilt dependencies so dependency
  injection is actually wired at runtime.
- Bump Hilt to 2.60.1 for AGP 9 compatibility.
- Annotate `MainActivity` with `@AndroidEntryPoint` so `hiltViewModel()` resolves.
- Add `INTERNET` and `ACCESS_NETWORK_STATE` permissions.
- Disable the default WorkManager initializer for on-demand configuration.
- Replace the deprecated `LocalLifecycleOwner` import with the
  `lifecycle-runtime-compose` equivalent.
- Escape user-authored text (titles, locations, labels, notes, reviewer
  comments) before rendering PDF paragraphs, so characters like `&` or `<` no
  longer abort report export.
- Include a traceable evidence id in the PDF report's evidence references.

## [0.0.1] - 2026-10-10

### Added

- Initial project scaffold and product brief.
