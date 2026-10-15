# MVP acceptance criteria

> **Status: LOCKED 2026-10-12** (see `scope-lock.md`). The criteria below are
> frozen for the current milestone. Status at lock: 9 of 14 satisfied, 5 open
> (tracked in Linear via `linear-backlog.csv`); updated 2026-10-12 after the
> primary-journey UX and persistence pass: **14 of 14 satisfied, 0 open**.

- [x] Inspection and checklist changes persist after process termination while offline.
      (Room is the source of truth; `RoomPersistenceTest` inserts, closes and reopens the
      database file, then reads the row back — `inspection survives closing and reopening
      the database file` — and the offline-to-sync path is covered by
      `OfflineToSyncIntegrationTest`.)
- [x] Photos and notes persist locally and are linked to the correct checklist item.
      (`EvidenceEntity` carries a nullable `checklistItemId` + `note`; `RoomPersistenceTest`
      asserts evidence is read back linked to its checklist item with the note intact —
      `evidence is linked to its checklist item and keeps its note`.)
- [x] Missing required evidence blocks submission and report finalization on the server.
      (server `_submit_validation` → 409 with `missing_checklist_item_ids` + field-level
      `errors` codes `MISSING_REQUIRED_EVIDENCE`/`OUTCOME_REQUIRED`, asserted by
      `test_submit_blocked_without_required_evidence` and the required-outcome tests;
      the Android submit flow surfaces those ids per checklist row and blocks re-submit
      until each required item has evidence **and** a terminal outcome — `InspectionViewModel`
      + `ChecklistItemRow`, asserted by `InspectionViewModelSubmitValidationTest` and
      `ChecklistCompletionDeviceTest`.)
- [x] Sync UI distinguishes queued, uploading, synced, failed, and conflict states.
      (`syncStatusLabel`/`SyncBadge` render each state distinctly — inspection
      operation badge, per-evidence badges and the aggregate `SyncStatusCard`; the
      failed state offers an actionable retry, per-evidence and aggregate. Asserted
      by `SyncStatusDisplayTest` (JVM) and `SyncStateDisplayDeviceTest` (device).)
- [x] Replaying the same upload operation does not create duplicate evidence.
      (Android sends the multipart evidence contract — `file` + `id`/`kind`/
      `captured_at`/`checklist_item_id`/`note` + the `Idempotency-Key` header —
      and replays with the same key, which the server dedupes. Asserted end-to-end
      over the real Retrofit/OkHttp stack by `EvidenceUploadIntegrationTest` and
      server-side by `test_evidence_upload_is_idempotent`.)
- [x] Interrupted upload can be retried without losing local evidence.
      (Queuing an inspection/evidence schedules a *unique* `SyncWorker` pass keyed
      by `WorkManagerSyncScheduler.WORK_NAME`, constrained to a connected network
      with exponential backoff; the local row and its durable entity-id
      idempotency key survive, so retrying never duplicates or drops evidence.
      Asserted by `SyncSchedulerWorkManagerTest` — the worker stays ENQUEUED while
      offline and executes once the network constraint is met.)
- [x] Permission denial provides actionable feedback and does not corrupt local work.
      (A denied request explains why and offers a recovery path; once the system reports
      permanent denial the screen switches to an *Open settings* action rather than a dead
      end, permission is re-checked on `ON_RESUME` so granting it in Settings recovers the
      flow, and *Not now* falls back to a written note — no local data is lost. Asserted by
      `CameraPermissionDeviceTest`.)
- [x] Conflicting updates return a visible conflict instead of silently overwriting.
      (`ConflictResolver` + `ConflictResolutionCard`; the user chooses *Keep local
      & retry* or *Use server version*, local work is never discarded — asserted by
      `ConflictResolverTest` and `ConflictDescriptionTest`.)
- [x] Reviewer sign-off references a specific inspection version.
      (The server stamps the review with `inspection_version = inspection.version` and a
      `reviewed_at` timestamp (`service.py` review flow), stores it on `Review.inspection_version`
      (`models.py`) and renders it in the report (`report_pdf.py` → "Snapshot version N").
      Covered by `test_report_pdf.py`, whose review fixture pins `inspection_version=5`.)
- [x] PDF includes inspection summary, checklist outcomes, evidence references/images, timestamps, and reviewer decision.
      (`backend/app/report_pdf.py`; asserted by `backend/tests/test_report_pdf.py`
      and `test_report_pdf_export_after_approval`.)
- [x] Loading, empty, error, and success states are present on primary flows.
      (Home distinguishes first-frame loading `LoadingState` from `EmptyState` and
      the populated list; the detail screen observes its inspection by id and shows a
      loading state instead of flashing *not found* before the first emission — only a
      genuinely null row shows the not-found state; the camera models
      `Starting`/`Ready`/`Failed` with a recoverable error screen and a photo-review state;
      the permission-denied state offers recovery. Asserted by `HomeLoadingStateDeviceTest`,
      `CameraStateDeviceTest`, `CameraPermissionDeviceTest`, `InspectionDetailStateTest`
      and the existing evidence/checklist device tests.)
- [x] Fixtures, logs, and screenshots contain no secrets or private client data.
      (fixtures under `tests/fixtures/` and `backend/app/demo_data.py`; screenshots
      in `docs/screenshots/`; `.gitignore` excludes `.env`, `local.properties`,
      `.venv/`, `backend/var/`)
- [x] Setup instructions work on a clean environment and list required versions.
      (`scripts/verify_clean_setup.sh`; versions in `README.md` and `docs/demo.md`)
- [x] Demo explicitly identifies synthetic data and documents limitations.
      (`docs/demo.md` and `docs/known-limitations.md`)

## Release verification gate (not executed here)

The criteria above are satisfied by code + automated tests. Shipping a build
additionally requires a **human** verification pass on real hardware — device
matrix, offline/poor-network/camera/storage/reboot scenarios, accessibility
(TalkBack/font scale/contrast), performance & battery thresholds, and a signed
go/no-go report. That plan and the report template live in
`docs/release-verification.md`; it is **pending execution** (no physical devices
in this environment). Do not promote a build without the signed report.
