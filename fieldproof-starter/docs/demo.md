# FieldProof demo script

> **Demo only.** Every inspection, checklist item, photo and note in this script
> is **synthetic**. Nothing used or produced here contains personal or private
> client data, real facility photos, credentials or tokens. See
> [known-limitations.md](known-limitations.md) for the boundaries of what this
> demo proves.

This script is the reproducible walkthrough for the FieldProof offline-first
inspection demo. A reviewer can run it on a clean machine without any private
inputs (see the checklist at the end and `scripts/verify_clean_setup.sh`).

Time: ~15-20 minutes end to end including setup.

---

## 1. What the demo shows

1. Creating an inspection offline, persisted locally (`Room`).
2. Capturing evidence for checklist items: a **photo** (camera) and a **note**.
3. **Permission denial** handling with actionable recovery (no work is lost).
4. Sync state surfaced to the user: queued → syncing → synced (and failure).
5. Server-side evidence rules: missing required evidence **blocks** submission.
6. Optimistic concurrency + idempotent uploads (replay safe, no duplicates).
7. Approval workflow and a **PDF inspection report** (fully synthetic data).

Everything is exercised against the real local PostgreSQL-backed API, then
verified again in the automated test suites (acceptance criteria rely on tests,
not the live demo alone).

## 2. Prerequisites (exact versions)

Verify with `scripts/verify_clean_setup.sh` (or see `README.md`).

| Tool | Version used to author |
|---|---|
| macOS (or any JDK-17 host) | any |
| JDK | 17 (toolchain) |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.6.0 (wrapper) |
| Kotlin | 2.2.10 |
| Android SDK | `platforms;android-37.0`, `build-tools;36.0.0` |
| minSdk / targetSdk / compileSdk | 24 / 37 / 37 |
| Python | 3.11+ (this repo was verified with 3.13) |
| Docker Desktop | any recent (PostgreSQL 16 image) |
| Robolectric / Espresso (tests) | 4.17 / 3.7.0 |
| `adb` | `$ANDROID_HOME/platform-tools/adb` |

## 3. Setup

### 3.1 Backend (PostgreSQL + API)

```bash
cd fieldproof-starter

# 1) PostgreSQL 16 on localhost:5432 (fieldproof/fieldproof/fieldproof)
docker compose up -d db

# 2) Python env (once)
python -m venv .venv
.venv/bin/python -m pip install -r backend/requirements.txt

# 3) Run the API (schema is created on startup)
export DATABASE_URL="postgresql+psycopg://fieldproof:fieldproof@localhost:5432/fieldproof"
.venv/bin/uvicorn app.main:app --app-dir backend --reload
```

- Liveness: <http://127.0.0.1:8000/health> → `{"status":"ok",...}`
- OpenAPI: <http://127.0.0.1:8000/docs>

### 3.2 Seed the synthetic API dataset (optional but recommended)

```bash
cd fieldproof-starter
.venv/bin/python scripts/seed_demo.py
```

Creates the approved synthetic inspection **"Synthetic Facility Walkthrough"**
with two photos + one note, and writes
`docs/screenshots/sample-report.pdf`. Re-running is **idempotent**: the same
`Idempotency-Key`s return the original responses and never duplicate evidence.

### 3.3 Android app on an emulator

```bash
# AVD "Medium_Phone_API_37.0" (API 37, google_apis_playstore, arm64-v8a)
$ANDROID_HOME/emulator/emulator -avd Medium_Phone_API_37.0 &

export ANDROID_SERIAL=emulator-5554
adb reverse tcp:8000 tcp:8000            # app's BASE_URL is http://127.0.0.1:8000/
./gradlew :app:installDebug
adb shell am start -n com.spacedrift.fieldproof/.MainActivity
```

The app points at `http://127.0.0.1:8000/` by default
(`app/src/main/java/com/spacedrift/fieldproof/di/NetworkModule.kt`); `adb
reverse` maps that to the host API.

---

## 4. The script

Screenshots referenced below live in `fieldproof-starter/docs/screenshots/` and
were captured on the API-37 emulator using this same script.

### Step 1 — Empty home + sync status
Fresh install shows the empty state: *"No inspections yet"*, *"Create
inspection"*, and a sync status card with **"Sync complete / All inspection data
uploaded"**.
📷 `01-home-empty-synced.png`
**Verify:** the app syncs on launch (`FieldProofApplication` enqueues a sync
worker).

### Step 2 — Create an inspection
Tap **+ (New inspection)**, enter a synthetic title (`Lakeside Pump Station`)
and location (`Demo Site`), tap **Create**.
📷 `02-create-inspection-dialog.png`
**Verify:** the inspection is saved locally as `DRAFT` and a durable sync
operation is queued (see `InspectionRepository.createInspection`).

### Step 3 — Home list reflects the new inspection
Back on home the card shows title, location, `DRAFT`, checklist progress
(`0/3 checklist`) and a **Synced** badge.
📷 `03-home-with-synced-inspection.png`

### Step 4 — Checklist with required-evidence states
Open the inspection. The 3-item synthetic template is seeded (matching
`backend/app/demo_data.py`):
- **Emergency exit is unobstructed** — required, *Evidence missing*, *Add evidence*
- **Fire extinguisher is accessible** — required
- **General observations** — optional

Progress card: **Required evidence 0/2**, **Checklist 0/3**.
📷 `04-detail-checklist.png`
**Verify:** required items surface *"Evidence missing"*; optional items never do
(covered by `ChecklistCompletionDeviceTest`).

### Step 5 — Add a note as evidence
In the evidence composer select **General** → type a note → **Save note**. The
evidence row appears as **Note evidence / Queued** while the upload runs.
📷 `05-detail-note-evidence.png`
**Verify:** the note is linked to the checklist item and survives a restart
(`RoomPersistenceTest`/`OfflineToSyncIntegrationTest` cover the restart case).

### Step 6 — Note syncs
After the sync runs, the row shows **Synced / Satisfies: General** and the
status header reports **Sync complete**. The note exists server-side (see §6).
📷 `06-detail-note-synced.png`

> **Demo quirk to narrate:** sync is trigger-driven. Queuing an inspection or
> evidence enqueues a unique one-time `SyncWorker` (connected-network
> constrained), so it flushes automatically once the API is reachable; there is
> still **no background/periodic worker**, so evidence added while offline waits
> for connectivity or a manual *Retry sync*. Logged in `known-limitations.md` as
> `KL-004`.

### Step 7 — Camera permission request
Open **Add evidence** on the required *Emergency exit* item. The OS shows the
camera permission dialog.
📷 `07-camera-permission-request.png`

### Step 8 — Permission denial is actionable
Choose **Don’t allow**. FieldProof shows its recovery screen: *"Camera
permission required … You can grant it in Settings, or add a written note
instead."*
📷 `08-permission-denied.png`
**Verify:** no local data is lost; the user can add a note instead or re-grant
(`CameraPermissionDeviceTest`).

### Step 9 — Capture a photo
Re-grant camera access. Enter the capture flow, take the photo; the post-capture
review shows **Review photo / Retake / Use photo**.
📷 `09-camera-review-photo.png`
📷 `10-photo-evidence-queued.png` (photo attached, queued for sync)

### Step 10 — Photo syncs and progress updates
After sync, the item shows **Photo attached**, the evidence row shows **Synced**
and Required evidence is **1/2**.
📷 `11-photo-evidence-synced.png`
**Verify server-side:** one `photo` evidence row (`image/jpeg`, `~190 KB`, real
bytes in `backend/var/evidence/`).

### Step 11 — Server rules: missing evidence blocks submit
With only 1 of 2 required photos, `POST /submit` returns:

```json
409 {"detail": {"message": "Missing required evidence for submission.",
     "missing_checklist_item_ids": ["..."]}}
```

Upload the second required photo and submit again → `SUBMITTED`.

### Step 12 — Approve and export the PDF report
`POST /review {decision: approved, reviewer_id: demo-reviewer}` → `APPROVED`.
`GET /report` → JSON summary; `GET /report.pdf` → PDF with checklist outcomes,
evidence references and reviewer signoff.
📷 `12-sample-report-pdf.png` (rendered copy of
`docs/screenshots/sample-report.pdf`, produced by `scripts/seed_demo.py`)

---

## 5. Automated verification (written evidence)

The demo claims are backed by test suites, not only the live walkthrough:

```bash
# Backend (28 tests; real PostgreSQL)
cd fieldproof-starter
export DATABASE_URL="postgresql+psycopg://fieldproof:fieldproof@localhost:5432/fieldproof"
.venv/bin/python -m pytest backend/tests -q

# Android unit tests (93 tests; Robolectric 4.17)
cd ..
./gradlew :app:testDebugUnitTest

# Android instrumented tests (42 tests; API-37 emulator)
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

`93 = Room persistence + offline-to-sync + sync runner + remote repository /
request-factory + UI/state + device tests` (the exact counts are printed by the
suites and are the source of truth). The GitHub Actions workflow
(`.github/workflows/ci.yml`) runs the unit + instrumented lanes on clean
runners on every push.

## 6. Reproducibility guardrails (no private data)

- Every title, location, note, reviewer, item label and template is synthetic
  and version-controlled (`backend/app/demo_data.py`,
  `tests/fixtures/templates.json`, `tests/fixtures/evidence-sample.png`).
- No secrets, credentials or tokens are referenced or committed
  (`.gitignore` covers `.env`, `local.properties`, `backend/var/`).
- The photo is a generated placeholder (see
  `scripts/seed_demo.py :: make_synthetic_photo`).
- Screenshots were captured against a local API-37 emulator with synthetic
  data.
- `scripts/verify_clean_setup.sh` checks tools/versions and runs the repo’s own
  test commands, so a reviewer can prove the same behaviour on a clean machine.
  Note: the backend pytest suite drops and recreates the database tables, so run
  it against a disposable `DATABASE_URL` (see `fieldproof-starter/README.md`) and
  rerun `python scripts/seed_demo.py` afterwards to restore the demo record and
  `sample-report.pdf`.

## 7. Clean-up / reset

```bash
# Backend data (drop the docker Postgres volume to reset everything)
docker compose down -v

# App data on the emulator
adb shell pm clear com.spacedrift.fieldproof
# or uninstall for a truly fresh start
adb uninstall com.spacedrift.fieldproof
```

## 8. Reproducibility checklist (no private data)

A reviewer can tick these on a clean machine; every input is synthetic and
version-controlled.

- [ ] `scripts/verify_clean_setup.sh` exits `0` (add `--full` to also run the
      backend pytest suite and the Android unit tests).
- [ ] `docker compose up -d db` starts PostgreSQL 16 and `/health` returns
      `{"status":"ok",...}`.
- [ ] `scripts/seed_demo.py` runs end to end and re-running it is idempotent
      (still **3** evidence items, no duplicates) — it only uses synthetic ids,
      the deterministic `tests/fixtures/evidence-sample.png` placeholder, and
      fixed `Idempotency-Key`s.
- [ ] `docs/screenshots/` contains `01`–`12` plus `sample-report.pdf`, all
      captured from synthetic data (no real facility photos, names or tokens).
- [ ] The Android app builds (`./gradlew :app:assembleDebug`) and its unit tests
      pass (`./gradlew :app:testDebugUnitTest`).
- [ ] No secrets or private data are required: `.gitignore` excludes `.env`,
      `local.properties`, `.venv/` and `backend/var/`; grep the tree for
      suspicious values if in doubt.
- [ ] Residual state is cleaned with `docker compose down -v` and
      `adb uninstall com.spacedrift.fieldproof`.