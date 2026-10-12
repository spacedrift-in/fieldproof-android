# FieldProof — locked scope (v1.0)

- **Status:** LOCKED on 2026-10-12.
- **Owner:** Spacedrift.
- **Nature of this document:** a *consolidation* of already-approved artifacts —
  `README.md`, `fieldproof-starter/README.md`, `docs/decisions/0001-architecture.md`,
  `docs/acceptance-criteria.md`, `docs/demo.md`, `docs/known-limitations.md` and
  `gradle/libs.versions.toml`. **No new scope is introduced here.** Where a source
  is authoritative, it is linked so this page can stay thin.

## Change control

Anything below is frozen for the current milestone. A change requires: (1) a
decision recorded in Linear, (2) a new version + date in this header, and
(3) corresponding updates to the source document it points at. Open decisions and
blockers are tracked in Linear (see §6).

## 1. Approved feature list

Implemented and in scope (source: `README.md` → *Features*; verified by the test
suites / `docs/demo.md`):

| Area | In scope |
| :--- | :--- |
| Inspections | Create offline; list and reopen after process death (Room = source of truth). |
| Checklists | Default items with required/optional flags; per-item completion tracked locally. |
| Evidence | Timestamped photo capture (CameraX) and text notes, stored app-privately, linked to an inspection + checklist item. |
| Sync model | Durable local `SyncOperation` records with a stable per-upload idempotency key; queuing schedules a unique connected-network `SyncWorker` pass. |
| Conflicts | Server conflicts surfaced per item; *Keep local & retry* (fresh key) or *Use server*; no silent overwrite/loss. |
| Permissions | Runtime camera permission with an actionable denied state. |
| UI | Jetpack Compose + Material 3; loading / empty / error-aware states on primary flows. |
| API contract | Retrofit client matching `/api/v1`, incl. `Idempotency-Key`. |

**Explicitly out of scope** (adding any of these requires a decision): background
*periodic* automatic sync; server checklist-outcome upload
(`PASS`/`FAIL`/`NOT_APPLICABLE` + per-item notes); authentication / authorization;
iOS client; background location; raw photo bitmaps embedded in the PDF; DB
migrations (Alembic). Sources: `README.md` → *Not implemented yet*,
`docs/known-limitations.md` (KL-001…KL-005).

## 2. User journeys

Source of truth: `docs/demo.md` → *The script* (Steps 1–12). Frozen journeys:

1. **Launch** → empty home state + sync-status card.
2. **Create inspection offline** → saved locally as `DRAFT` + durable sync op queued.
3. **Home list** reflects the inspection (title, location, status, progress, sync badge).
4. **Open inspection** → checklist showing required-evidence states.
5. **Add a note** as evidence → linked to the checklist item; survives process death.
6. **Note syncs** → row shows Synced.
7. **Camera permission request.**
8. **Permission denial** → actionable recovery copy; no local data lost.
9. **Capture a photo** → review / retake / use.
10. **Photo syncs** → progress updates.
11. **Server rule** → missing required evidence blocks submission (409).
12. **Approve + export PDF** report with checklist outcomes, evidence references, reviewer sign-off.

## 3. Supported devices & platform

Source: `README.md` → *Prerequisites*, `docs/demo.md` → *Prerequisites*,
`.github/workflows/ci.yml`.

| Item | Value |
| :--- | :--- |
| minSdk | 24 (Android 7.0+) |
| compileSdk / targetSdk | 37 |
| Instrumented-test target | emulator API 34 x86_64 (CI) or any device API ≥ 24 |
| Authoring / screenshot emulator | API 37 arm64-v8a (`Medium_Phone_API_37.0`) |
| JDK | 17 |
| AGP / Gradle / Kotlin | 9.4.1 / 9.6.0 (wrapper) / 2.2.10 |
| Backend | Python 3.11+ (verified 3.13), PostgreSQL 16 via Docker |

Known device caveat (KL-009): a Google Play-protected ROM (e.g. MIUI) may require
*Install via USB* and can block instrumented input injection; CI's API-34 emulator
lane is the supported automated path.

## 4. Dependencies (pinned)

Android — source: `gradle/libs.versions.toml`:

| Dependency | Version |
| :--- | :--- |
| Kotlin / Compose BOM | 2.2.10 / 2026.02.01 |
| Hilt (KSP) | 2.60.1 |
| Room (KSP) | 2.8.5 |
| WorkManager | 2.11.1 |
| CameraX | 1.5.0 |
| Retrofit / OkHttp | 3.0.0 / 5.1.0 |
| Navigation Compose | 2.9.5 |
| Lifecycle (runtime ktx/compose) | 2.8.5 (via catalog refs) |
| Test: JUnit / androidx.test.ext / Espresso / Robolectric | 4.13 / 1.6.1 / 3.7.0 / 4.17 |
| KSP plugin / AGP | 2.3.10 / 9.4.1 |

Backend — source: `backend/requirements.txt`: FastAPI, Uvicorn, SQLAlchemy 2.x,
psycopg 3, python-multipart, pytest, httpx, reportlab (PDF), pypdf.

Exact and complete pins live in the source files; this table is a frozen summary.

## 5. Testable acceptance criteria

Source of truth (frozen as of the lock date): `docs/acceptance-criteria.md`. The
criteria themselves are locked; their pass/fail status is a moving snapshot.
Status at lock: **9 of 14 satisfied**, **5 open** (see §6).

## 6. Open decisions & blockers

Recorded in Linear (do **not** invent scope when filing). The working copy that is
imported/kept in sync lives at `docs/linear-backlog.csv`.
