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
| KL-002 | 2026-10-11 | Medium | Mitigated | No server endpoint to set checklist outcomes (`PASS`/`FAIL`/`NOT_APPLICABLE`) or per-item notes. Items are seeded `NOT_STARTED`; the Android client tracks completion locally only. | **Mitigated 2026-10-13:** `PUT /api/v1/inspections/{id}/checklist/{item_id}` (`service.update_checklist_outcome`) sets outcome + note with optimistic concurrency; the Android client queues a durable `CHECKLIST/UPDATE` sync operation. Submit now validates required evidence **and** terminal outcomes (`OUTCOME_REQUIRED`), and report `summary.passed/failed/not_applicable` reflect synced outcomes. Covered by `tests/test_inspections.py` and `SyncRunnerTest`/`ConflictResolverTest`. |
| KL-003 | 2026-10-11 | Medium | Open | Schema is created with SQLAlchemy `create_all`; no migrations (e.g. Alembic). | `backend/app/db.py::init_db`. |
| KL-004 | 2026-10-11 | Medium | Open | Sync is trigger-driven: queuing an inspection or evidence enqueues a unique one-time `SyncWorker` (connected-network constrained), and there is still **no background/periodic worker**. Evidence added while offline waits until connectivity returns or a manual *Retry sync*. | `SyncScheduler` uses `OneTimeWorkRequest` only (no `PeriodicWorkRequest`); `FieldProofApplication.onCreate` also enqueues on launch. Demo script Step 6 narrates this. |
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
| KL-015 | 2026-10-13 | High | Open | Tenancy is derived from an unauthenticated `X-Tenant-Id` header (default `demo`). It namespaces object storage and scopes reads, but is **not** an authorization boundary — a caller can claim any tenant. | `app/tenancy.py`; documented in `api-contract.md` and `deployment.md`. Must be replaced by a verified identity claim when auth (KL-001) lands. |
| KL-016 | 2026-10-13 | Medium | Open | Rate limiting is in-memory and per-process. Under multiple replicas the effective limit is multiplied and resets on restart. | `RateLimitMiddleware` in `app/main.py`; `deployment.md` recommends ingress limiting or a shared store (Redis). |
| KL-017 | 2026-10-13 | Medium | Open | Uploads are validated from bounded in-memory bytes (spooled), not streamed directly into object storage; a full streaming/multipart pass-through is not implemented. The hard `MAX_EVIDENCE_BYTES` cap bounds memory use. | `_read_upload_bounded` in `app/routers/inspections.py`; `media.py`. |
| KL-018 | 2026-10-13 | Medium | Open | Evidence storage now supports S3 with SSE, private-access block and lifecycle rules, but there is no automated **integrity/restore verification job** or content-addressed dedupe. `sha256` is stored per row for spot checks. | `app/storage.py`, `docs/disaster-recovery.md` §4.2. |
| KL-019 | 2026-10-13 | Low | Open | CORS, rate limiting and storage bootstrap are applied at app startup from env; changing them requires a restart. | `create_app` in `app/main.py`. |
| KL-020 | 2026-10-13 | Medium | Open | The Room database on-device is **not** encrypted with SQLCipher/`EncryptedFile`; it relies on Android platform **File-Based Encryption (FBE)** (credentials-encrypted storage once the device is locked). Captured evidence *files* **are** encrypted with a Keystore-backed AES-256-GCM key. | `security/EvidenceVault.kt`; SQLCipher/`androidx.security:crypto` were unavailable offline (see KL-021). Tracked in `linear-backlog.csv`. |
| KL-021 | 2026-10-13 | Medium | Informational | Evidence is encrypted with a **device-bound, non-exportable** Android Keystore key (`fieldproof_evidence_key_v1`). There is no key escrow or backup: clearing app data, factory reset or key invalidation makes the encrypted files permanently unrecoverable. Consequences: the Android backup rules are disabled (KL-020/`security.md`), and evidence must reach the server before the key is lost. | `security/EvidenceKeyProvider.kt`, `security/EvidenceVault.kt`; documented in `docs/security.md`. |
| KL-022 | 2026-10-13 | Low | Open | Retention is **trigger-driven**, not scheduled. The Android client runs a local purge on app launch (`InspectionViewModel` init) and the backend exposes an admin-triggered sweep; neither runs on a timer. | `POST /api/v1/maintenance/retention`; tie to a cron/Job. |
| KL-023 | 2026-10-13 | Low | Open | Screenshot/overlay protection (`FLAG_SECURE`) is applied to the inspection detail and camera screens only. Other screens (lists, dialogs) and OS-level recents-thumbnail capture are not covered, and share is limited to the report PDF. | `ui/components/SecureScreen.kt`. |
| KL-024 | 2026-10-13 | Low | Informational | Build provenance (`actions/attest-build-provenance`) requires a **public** repository or GitHub Enterprise Cloud. On a private repo without GHEC the `release` job's attestation step will fail; drop that step until the repo is public. | `.github/workflows/release.yml`; `docs/release.md` §5. |
| KL-025 | 2026-10-13 | Medium | Open | Play Console publishing and rollback are **manual** — there is no Play Developer API automation or fastlane/GPP integration. The pipeline builds, signs, verifies and archives a signed AAB; a human uploads it and manages staged rollout/halt. | `docs/release.md` §7. |
| KL-026 | 2026-10-13 | Low | Open | `versionCode` is derived as `major*10000 + minor*100 + patch` from the tag. This reserves two digits for `minor`/`patch`; a `minor` or `patch` above 99 would overflow into the next field. Use an explicit `version_code` input for such jumps. | `metadata` job in `.github/workflows/release.yml`. |
| KL-027 | 2026-10-13 | Medium | Open | Signing uses a **single shared upload keystore** fetched from AWS Secrets Manager at build time; there is no per-run ephemeral key and rotation is manual (generate, reset upload key in Play, replace secret). A leaked upload key can be reset by Google, but until then it can sign artifacts. | `scripts/fetch_upload_keystore.sh`; `docs/release.md` §2 (Rotation). |

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