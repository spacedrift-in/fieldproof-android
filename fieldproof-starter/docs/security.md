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
  inspections**. Draft/submitted work is never purged. The endpoint requires
  `X-Admin-Token` and there is no built-in scheduler — trigger it from cron.

## 7. Configuration summary

| Setting | Where | Default |
|---|---|---|
| Evidence encryption | Always on (release DI) | Keystore AES-256-GCM |
| Backups | `AndroidManifest.xml` | Disabled |
| `FLAG_SECURE` screens | `SecureScreen()` | Detail + camera |
| Debug logging | `BuildConfig.DEBUG` | Off in release |
| Local retention | `DEFAULT_RETENTION_DAYS` | 30 days |
| `EVIDENCE_RETENTION_DAYS` | backend env | `0` (disabled) |
| `RETENTION_ADMIN_TOKEN` | backend env | unset (endpoint `503`) |
