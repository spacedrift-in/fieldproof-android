# FieldProof — security & privacy behaviors

This document describes how the Android client and the backend protect captured
inspection evidence, how deletion and retention work, and the explicit key-loss
behavior. It complements `deployment.md` (server hardening) and
`known-limitations.md` (KL-020…KL-023).

## 1. Data at rest (Android)

| Data | Protection |
|---|---|
| Captured evidence photos | Encrypted with **AES-256-GCM** using a **non-exportable Android Keystore** key before they touch disk (`EncryptedEvidenceVault`). Stored under the app-private `filesDir/evidence` as `<uuid>.fpenc`. |
| Room database (`fieldproof.db`) | Relies on Android platform **File-Based Encryption**; the DB itself is not additionally encrypted (KL-020). |
| Cache capture staging | The in-progress capture is written to the app-private cache dir (`cacheDir/fieldproof_capture`), never to shared/external storage. |

The encrypted evidence format is an envelope:
`[magic "FPENC"][version 1][12-byte random IV][AES-GCM ciphertext + 128-bit tag]`.
Keys live only in the Keystore (`fieldproof_evidence_key_v1`, AES-256, GCM,
randomized encryption). The secret bytes are never exportable and are loaded
lazily and cached in-process.

Photo bytes are decrypted **in memory only** for display or upload. Decoded
bitmaps are downsampled (`decodeSampledBitmap`) to bound memory.

Sealing a capture can fail (keystore unavailable, unreadable capture, or the
plaintext file could not be removed). `EvidenceVault.import` returns a typed
`EvidenceImportResult` and a `Failed` result **aborts** the save: no evidence
row, no sync operation, and the temporary plaintext file is deleted. The app
asks the user to retake the photo rather than storing unencrypted bytes.

## 2. Backups and data extraction (Android)

Automatic backup and device-to-device transfer are **disabled** outright:

- `AndroidManifest.xml`: `android:allowBackup="false"`.
- `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`: every
  domain (`root`, `file`, `database`, `sharedpref`, `external`,
  `device_*`) is explicitly excluded from cloud backup and device transfer.

This is deliberate: because the evidence key cannot leave the device, backing up
the encrypted files would be useless at best and a data-exposure risk at worst.
It is pinned by `BackupScopingTest`.

## 3. Key-loss behavior (important)

The evidence key is **device-bound and non-exportable**. There is **no key
escrow, no backup and no recovery path**. Therefore:

- **Clearing app data, uninstalling, factory resetting, or Keystore key
  invalidation permanently destroys all local, not-yet-synced evidence.** It
  cannot be decrypted afterward by anyone, including FieldProof.
- Evidence that has already been uploaded to the server is unaffected (the
  server stores its own copy under its own encryption).
- Operators must treat *sync before wipe* as mandatory. The sync worker sends
  evidence as soon as connectivity permits, and the demo script narrates this.

If server-side recovery of a lost device's work is required, that must come from
the server copy — never from a device backup.

## 4. Screenshots, logs and share

- **Screenshots / recents:** `SecureScreen()` sets `FLAG_SECURE` on the
  inspection detail and camera screens, blocking screenshots/screen recording and
  blanking the recents thumbnail while those screens are foregrounded.
- **Logging:** network logging is compiled to `Level.NONE` in release builds and
  only enabled when `BuildConfig.DEBUG` is set. Evidence content is never logged
  on either side.
- **Sharing:** the only share affordance is the generated **report PDF**
  (via `FileProvider`) — raw evidence bitmaps are not shared. Report generation
  is an explicit, user-initiated action.

## 5. Deletion

**Android (local):**
- Deleting an inspection removes its encrypted evidence files, its queued sync
  operations and its database rows (`InspectionRepository.deleteInspection`).
- "Delete all local data" wipes every inspection, evidence file, sync operation
  and record (`deleteAllLocalData`).

**Backend (server):**
- `DELETE /api/v1/inspections/{id}` deletes the inspection, checklist items,
  evidence rows **and** stored objects; idempotency records are cleared.
- `DELETE /api/v1/tenant` wipes everything for the tenant, gated behind
  `X-Confirm-Delete: <tenant_id>`.
- Deletes are tenant-scoped and return `404` (never `403`) across tenants so the
  API does not reveal whether another tenant's record exists.

## 6. Retention

- **Android:** on app launch the client purges local copies of evidence that is
  already synced and older than `DEFAULT_RETENTION_DAYS` (30) — queued/unsynced
  evidence is never purged (`purgeExpiredEvidence`).
- **Backend:** `POST /api/v1/maintenance/retention` deletes evidence uploaded
  more than `EVIDENCE_RETENTION_DAYS` ago, but **only for `APPROVED`
  inspections**. Draft/submitted work is never purged, and evidence under a
  legal hold (`fieldproof-legal-hold=true`) is skipped. The endpoint requires
  `X-Admin-Token` and there is no built-in scheduler — trigger it from cron.
- **Object-store lifecycle:** S3 expiration is **tag-scoped** to
  `fieldproof-retention=eligible`. Only the retention sweep applies that tag
  (never for active work or legal holds), so a lifecycle rule can never delete
  evidence the database still references. App-managed lifecycle is opt-in
  (`EVIDENCE_S3_MANAGE_LIFECYCLE`) and merges rules instead of replacing the
  bucket configuration.

## 7. Transport security (Android)

The backend endpoint is injected at build time (`BuildConfig.API_BASE_URL`,
from `FIELD_PROOF_API_BASE_URL` / `fieldproof.apiBaseUrl`) — it is not a
committed constant. The manifest routes all traffic through a network security
config:

- **Release:** `app/src/release/res/xml/network_security_config.xml` sets
  `cleartextTrafficPermitted="false"`, so cleartext HTTP is rejected even if a
  build accidentally points at an `http://` URL. Production builds **must**
  inject an `https://…/` endpoint.
- **Debug:** `app/src/debug/res/xml/network_security_config.xml` permits
  cleartext so `http://127.0.0.1:8000/` works against a local backend.

Network logging is `Level.NONE` in release and `Level.BASIC` (method/URL/status
only, never bodies) in debug. Asserted by `NetworkConfigurationTest`.

## 8. Configuration summary

| Setting | Where | Default |
|---|---|---|
| Evidence encryption | Always on (release DI) | Keystore AES-256-GCM |
| Evidence import failure | `EvidenceVault.import` | Abort save, delete plaintext |
| Backups | `AndroidManifest.xml` | Disabled |
| `FLAG_SECURE` screens | `SecureScreen()` | Detail + camera |
| Debug logging | `BuildConfig.DEBUG` | Off in release |
| API base URL | `BuildConfig.API_BASE_URL` | Injected; loopback default blocked in release |
| Release cleartext | release network security config | Denied |
| Local retention | `DEFAULT_RETENTION_DAYS` | 30 days |
| `EVIDENCE_RETENTION_DAYS` | backend env | `0` (disabled) |
| `RETENTION_ADMIN_TOKEN` | backend env | unset (endpoint `503`) |

## 9. Backend diagnostics & abuse controls

- **Log redaction (KL-034).** Backend logs are single-line JSON. A redaction
  pass masks bearer/`Authorization` values, `token`/`secret`/`password`/`api_key`
  values, and `x-admin-token`/`idempotency-key`/`x-tenant-id` values; entity ids
  are normalized in paths. Photo bytes, evidence content, checklist notes and
  reviewer comments are never placed on a log record. Asserted by
  `tests/test_observability.py`.
- **Metrics.** `GET /metrics` (optional `X-Admin-Token`) exposes request/latency
  and error/conflict/rate-limit/storage/migration counters; it carries no
  request bodies or client content (see `observability.md`).
- **Rate limiting (KL-032).** The limiter keys on the client transport address (or
  a single trusted edge header), evicts expired keys on a TTL and bounds key
  cardinality. It does **not** trust the client-supplied `X-Tenant-Id` unless
  `RATE_LIMIT_TRUST_TENANT_HEADER` is explicitly enabled for a single-tenant edge.
- **No auth yet.** The API still has no authentication/authorization (KL-001);
  these controls limit abuse and leakage, they do not authenticate callers.
