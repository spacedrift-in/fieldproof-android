# FieldProof — known limitations log

> Honest, versioned list of known limitations and the verified status of each.
> Entries are appended over time and point at the evidence that confirms or
> mitigates them. **None of these entries weaken the acceptance criteria that
> the demo is synthetic-only and private-data-free** (see
> `acceptance-criteria.md`).

Status legend: `Open` · `Mitigated` · `Informational`.

| ID | Date | Severity | Status | Limitation | Evidence / workaround |
|---|---|---|---|---|---|
| KL-001 | 2026-10-11 | High | Open | No authentication or authorization on the API. | By design for this milestone; `main.py` docstring states it. `create_all` bootstrap runs on startup. Demo is localhost-only. |
| KL-002 | 2026-10-11 | Medium | Open | No server endpoint to set checklist outcomes (`PASS`/`FAIL`/`NOT_APPLICABLE`) or per-item notes. Items are seeded `NOT_STARTED`; the Android client tracks completion locally only. | `backend/app/domain.py`, `service.create_inspection`. Consequence: report `summary.passed/failed` is `0` until the endpoint exists. |
| KL-003 | 2026-10-11 | Medium | Open | Schema is created with SQLAlchemy `create_all`; no migrations (e.g. Alembic). | `backend/app/db.py::init_db`. |
| KL-004 | 2026-10-11 | Medium | Open | Sync triggers are app launch and manual *Retry sync* only — there is no background/periodic worker. Evidence added after launch stays `QUEUED` until a relaunch or manual retry. | `SyncScheduler` uses `OneTimeWorkRequest` only; `FieldProofApplication.onCreate` enqueues. Demo script Step 6 narrates this. |
| KL-005 | 2026-10-11 | Medium | Open | PDF report references evidence (kind, id, note snippet) and the reviewer signoff but does **not** embed the raw photo bitmaps. | `backend/app/report_pdf.py::_evidence_label`. Photos are stored under `backend/var/evidence/`. |
| KL-006 | 2026-10-11 | Low | Open | No iOS client. | Android-only milestone. |
| KL-007 | 2026-10-11 | Low | Open | No background location tracking. | Not requested for the demo. |
| KL-008 | 2026-10-11 | High | Informational | No autonomous safety or compliance certification. The demo is not evidence of production readiness. | README + `demo.md` disclaimers; acceptance criteria require claims be backed by test evidence. |
| KL-009 | 2026-10-11 | Medium | Mitigated | Device-test QA on physical hardware is limited: a MIUI device rejected instrumented-test installs/input injection. | Tests run on the API-37 emulator lane in CI instead (`.github/workflows/ci.yml`). |
| KL-010 | 2026-10-11 | Low | Mitigated | Robolectric 4.14.1 crashed with `Unsupported class file major version 69` (one dependency is compiled for Java 25). | Pinned Robolectric **4.17** (`gradle/libs.versions.toml`). |
| KL-011 | 2026-10-11 | Low | Mitigated | Espresso 3.5.1 failed input injection on API 37 (`NoSuchMethodException: android.hardware.input.InputManager.getInstance`). | Pinned Espresso **3.7.0**. |
| KL-012 | 2026-10-11 | Low | Mitigated | Camera permission dialog stop-printing after repeated denies on the emulator; `adb pm revoke` + grant needed to re-test the capture flow. | Captured both the request dialog (`07-…`) and the denied state (`08-…`); `CameraPermissionDeviceTest` covers recovery UI. |
| KL-013 | 2026-10-11 | Low | Open | Backend dev server started before the empty dev database was initialised, so writes 500'd until `init_db()` ran. | Documented in `demo.md` §3.1 (schema is created on startup for a fresh DB). |
| KL-014 | 2026-10-11 | Info | Informational | `.venv/bin/pip` had a broken shebang on this machine. | Use `.venv/bin/python -m pip` (used throughout `demo.md`). |

## Synthetic-data guarantee

Every fixture, screenshot, log and PDF in this repository is synthetic and
contains no secrets, credentials, or private client data. This is enforced by:

- fixtures living only under `tests/fixtures/` and `backend/app/demo_data.py`;
- the evidence photo being a generated placeholder
  (`scripts/seed_demo.py :: make_synthetic_photo`);
- `.gitignore` excluding `.env`, `local.properties`, `.venv/`,
  `backend/var/`, `__pycache__`.

If a reviewer finds anything real, it is a bug — please report it and the
offending file will be scrubbed.