# FieldProof Android (starter)

**Status:** Backend is a working, PostgreSQL-backed demo API. The Android app is an
offline-first vertical slice that still needs to be pointed at the new API contract.

FieldProof is an offline-first field-inspection demo built with synthetic data only.
The API is FastAPI + PostgreSQL; the Android client is Kotlin/Jetpack Compose.

## Repository layout

- `android/` — notes for the Android app.
- `backend/` — FastAPI application (`backend/app`) and tests (`backend/tests`).
- `docs/` — data model, API contract, sync behaviour, acceptance criteria,
  demo script (`demo.md`), screenshot gallery (`screenshots/`) and known-limitations
  log (`known-limitations.md`).
- `scripts/` — demo seeder (`seed_demo.py`) and clean-environment verification
  (`verify_clean_setup.sh`).
- `tests/fixtures/` — synthetic fixtures only (incl. `evidence-sample.png`).

## Backend quick start

Requirements: Python 3.11+ and Docker (for PostgreSQL).

### 1. Start PostgreSQL

```bash
cd fieldproof-starter
docker compose up -d db
```

This starts PostgreSQL 16 on `localhost:5432` with user/password/database
`fieldproof`/`fieldproof`/`fieldproof` (see `docker-compose.yml`).

### 2. Install dependencies

```bash
cd fieldproof-starter
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r backend/requirements.txt
```

### 3. Run the API

```bash
cd fieldproof-starter
export DATABASE_URL="postgresql+psycopg://fieldproof:fieldproof@localhost:5432/fieldproof"
uvicorn app.main:app --app-dir backend --reload
```

- Liveness: <http://127.0.0.1:8000/health>
- Interactive OpenAPI docs: <http://127.0.0.1:8000/docs>
- The schema is created automatically on startup (demo convenience; production
  should use migrations).

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness check (no database dependency) |
| POST | `/api/v1/inspections` | Create/sync an inspection. Requires `Idempotency-Key`. |
| GET | `/api/v1/inspections/{id}` | Fetch inspection, checklist, evidence and reviews |
| POST | `/api/v1/inspections/{id}/evidence` | Upload a photo file + metadata (multipart, idempotent) |
| POST | `/api/v1/inspections/{id}/submit` | Submit; blocked unless required evidence exists |
| POST | `/api/v1/inspections/{id}/review` | Record `approved` / `changes_requested` |
| GET | `/api/v1/inspections/{id}/report` | Report for an approved inspection |

### Behaviour guarantees

- **Idempotency:** repeating a create/evidence request with the same
  `Idempotency-Key` and identical payload returns the original result and creates
  no duplicate. Reusing a key with a different payload returns `409`.
- **Optimistic concurrency:** submit/review accept an optional `expected_version`;
  a mismatch returns `409` with the current server version.
- **No silent overwrites:** conflicting inspection ids or state transitions return
  `409` instead of overwriting.
- **State machine:** `DRAFT → SUBMITTED → APPROVED | CHANGES_REQUESTED`
  (a `CHANGES_REQUESTED` inspection can receive new evidence and be submitted again).
- **Evidence storage:** files are written under `backend/var/evidence/` behind the
  `EvidenceStorage` abstraction (`backend/app/storage.py`), ready to be swapped for
  object storage.

## Tests

Tests exercise a real PostgreSQL database. Point them at a disposable database:

```bash
cd fieldproof-starter
export DATABASE_URL="postgresql+psycopg://fieldproof:fieldproof@localhost:5432/fieldproof"
pytest backend/tests -q
```

If no `DATABASE_URL`/`TEST_DATABASE_URL` is set, the database-backed tests are
**skipped** (rather than silently passing).

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | `postgresql+psycopg://fieldproof:fieldproof@localhost:5432/fieldproof` | SQLAlchemy database URL |
| `EVIDENCE_STORAGE_DIR` | `backend/var/evidence` | Local directory for uploaded evidence |
| `MAX_EVIDENCE_BYTES` | `10485760` | Maximum accepted upload size |

## Known gaps (honest status)

- No authentication or authorization yet (by design for this milestone).
- Checklist item outcomes are seeded as `NOT_STARTED`; there is no endpoint yet to
  set `PASS`/`FAIL`/`NOT_APPLICABLE` or per-item notes on the server. The Android
  client tracks completion locally. Consequence: report `passed/failed` counts are
  `0` until that endpoint exists.
- Schema is created with `create_all`; no migrations (e.g. Alembic) yet.
- The app syncs on launch or manual *Retry sync*; there is no background/periodic
  worker (evidence added after launch waits for the next trigger). See
  `docs/known-limitations.md` `KL-004`.
- PDF reports reference evidence (kind, id, note snippet) and reviewer signoff but
  do not embed raw photo bitmaps (`KL-005`).
- The full, dated list lives in [`docs/known-limitations.md`](docs/known-limitations.md).

## Demo, fixtures & clean-environment verification

- Reproducible walkthrough: [`docs/demo.md`](docs/demo.md) (synthetic data only).
- Press-ready screenshots: [`docs/screenshots/`](docs/screenshots/).
- Seed the approved demo dataset against a running API:
  `python scripts/seed_demo.py` (idempotent replay; writes
  `docs/screenshots/sample-report.pdf`).
- Check a clean machine can reproduce the demo:
  `bash scripts/verify_clean_setup.sh` (add `--full` to also run backend + Android
  unit tests).
- Required versions: Python 3.11+, Docker, JDK 17, Android SDK API 37
  (`build-tools;36.0.0`), Gradle 9.6 (wrapper) — asserted by `verify_clean_setup.sh`.

## Safety and data handling

- Synthetic data only in fixtures, screenshots and demos.
- Do not commit secrets, private client information, real inspection photos, or tokens.
