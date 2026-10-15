# Android release & delivery guide

This document describes how FieldProof produces a **signed, versioned Android App
Bundle (AAB)**, how signing keys are protected, how the SBOM/license gate works,
and the **Google Play Console release and rollback procedures**.

The pipeline is defined in [`.github/workflows/release.yml`](../../.github/workflows/release.yml).

## 1. Pipeline at a glance

```
tag v1.4.0  ─┐
             ├─► metadata ─► verify ─┐
workflow_dispatch ───────────┘       ├─► release (needs verify + security)
                    security ────────┘
```

| Job | Purpose | Blocks release? |
|---|---|---|
| `metadata` | Derive `versionName`/`versionCode` (tag or input). | yes |
| `verify` | Repo hygiene, unit tests, `lintRelease`. | yes |
| `security` | License policy scan, CycloneDX SBOM, Grype vulnerability scan. | yes |
| `release` | Fetch keystore, build + sign, verify signature, archive, attest, publish. | — |

A signed artifact is **only** produced when `verify` **and** `security` pass and the
signing step succeeds. `release` runs in a protected GitHub **environment** named
`release`, so it can require manual approval and use environment-scoped secrets.

## 2. Signing: protected secrets / KMS, never in the repo

The upload keystore is **never committed**. `scripts/check_repo_hygiene.sh`
fails the build if any `*.jks`/`*.keystore`/private-key material is tracked, and
`.gitignore` excludes the usual filenames.

The release job fetches the keystore at build time:

1. `aws-actions/configure-aws-credentials` assumes an IAM role via **GitHub OIDC**
   (no long-lived AWS keys in GitHub).
2. `scripts/fetch_upload_keystore.sh` reads the base64 keystore from **AWS Secrets
   Manager** (optionally a KMS-encrypted secret) and writes it to a `0600` temp
   file, which is deleted when the runner is torn down.
3. Gradle signs using environment variables (see `app/build.gradle.kts`).

### Provisioning (one-time)

**a. Create the upload keystore** (offline, on a trusted machine):

```bash
keytool -genkeypair -v \
  -keystore fieldproof-upload.jks \
  -alias fieldproof-upload \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 fieldproof-upload.jks > fieldproof-upload.jks.b64   # -w0 avoids wrapping
```

> Keep `fieldproof-upload.jks` and its passwords in your password manager /
> hardware token. If you use **Play App Signing** (recommended), this is only the
> *upload* key; Google holds the *app signing* key and can reset a lost upload key.

**b. Store the secret in AWS Secrets Manager** (base64 string form):

```bash
aws secretsmanager create-secret \
  --name fieldproof/upload-keystore \
  --secret-string file://fieldproof-upload.jks.b64
```

A JSON form is also supported if you prefer one secret for everything:

```json
{ "keystoreBase64": "<base64>", "storePassword": "...", "keyAlias": "fieldproof-upload", "keyPassword": "..." }
```

**c. Create an IAM role trusted by GitHub OIDC** with permission only for that
secret (`secretsmanager:GetSecretValue`) and, if KMS-encrypted,
`kms:Decrypt` on the key. Set its ARN as the `AWS_RELEASE_ROLE_ARN` secret.

**d. Configure the GitHub `release` environment** (Settings → Environments → New
environment → `release`):

| Type | Name | Value |
|---|---|---|
| Secret | `AWS_RELEASE_ROLE_ARN` | IAM role ARN from (c) |
| Secret | `UPLOAD_KEYSTORE_SECRET_ID` | Secrets Manager name/ARN from (b) |
| Secret | `UPLOAD_KEYSTORE_PASSWORD` | keystore password |
| Secret | `UPLOAD_KEY_ALIAS` | e.g. `fieldproof-upload` |
| Secret | `UPLOAD_KEY_PASSWORD` | key password |
| Secret | `UPLOAD_CERT_SHA256` | *(optional)* expected signing-cert SHA-256 for verification |
| Variable | `AWS_REGION` | secret region (defaults to `us-east-1`) |

Enable **Required reviewers** on the environment so a human approves every release.
Add yourself as a reviewer and restrict who may deploy to the environment.

### Rotation

1. Generate a new upload keystore (step a).
2. Upload the new certificate to Play (**Release → Setup → App integrity → Upload
   key certificate** → *Request upload key reset*) — the app signing key is
   unchanged, so users are unaffected.
3. Replace the Secrets Manager secret value.
4. Update `UPLOAD_CERT_SHA256` if you pin the fingerprint.

## 3. Versioning

- **Tag pushes** (`v1.4.0`): `versionName=1.4.0`; `versionCode = major*10000 +
  minor*100 + patch` (e.g. `10400`). See the `metadata` job.
- **Manual dispatch**: pass `version_name` and an optional explicit
  `version_code`. If omitted, the same derivation applies.

The Gradle build reads `FIELD_PROOF_VERSION_NAME` / `FIELD_PROOF_VERSION_CODE`
(or the `fieldproof.versionName` / `fieldproof.versionCode` properties), so a
local build can override them without editing files.

## 4. Running a release

**Option A — tag (preferred):**

```bash
git tag v1.4.0
git push origin v1.4.0
```

**Option B — manual dispatch:**

Actions → **Release** → *Run workflow* → enter `version_name` (and optional
`version_code`), choose whether to publish a GitHub Release.

### Local dry-run (no cloud, no real key)

```bash
# 1. Unsigned build (safe smoke test; produces app-release-unsigned.apk)
./gradlew :app:assembleRelease

# 2. Full signed build against a throwaway keystore
keytool -genkeypair -v -keystore /tmp/dryrun.jks -storepass dryrun123 \
  -keypass dryrun123 -alias dryrun -keyalg RSA -keysize 2048 -validity 30 \
  -dname "CN=Dry Run"
FIELD_PROOF_UPLOAD_KEYSTORE_PATH=/tmp/dryrun.jks \
FIELD_PROOF_UPLOAD_KEYSTORE_PASSWORD=dryrun123 \
FIELD_PROOF_UPLOAD_KEY_ALIAS=dryrun \
FIELD_PROOF_UPLOAD_KEY_PASSWORD=dryrun123 \
  ./gradlew :app:bundleRelease :app:assembleRelease -Pfieldproof.requireSigning=true

scripts/verify_signature.sh app/build/outputs/bundle/release/app-release.aab
```

`-Pfieldproof.requireSigning=true` makes the build **fail fast** if no keystore is
configured — this is what the pipeline uses so an unsigned artifact can never be
shipped by accident.

## 5. Artifacts & provenance

Each release archives (as a GitHub Actions artifact, retained 90 days, and as
GitHub Release assets):

| Artifact | Notes |
|---|---|
| `fieldproof-<ver>-<code>.aab` | Signed App Bundle — the Play upload artifact. |
| `fieldproof-<ver>-<code>.apk` | Signed APK (internal/device testing). |
| `mapping/mapping.txt` + `configuration.txt`, `seeds.txt`, `usage.txt`, `resources.txt` | R8 deobfuscation files — upload `mapping.txt` to Play to read crashes. |
| `sbom.json` | CycloneDX 1.5 SBOM of the release runtime classpath. |
| `license-report.json` | Per-dependency license decisions against the policy. |
| `checksums.txt` | SHA-256 of every archived file. |

**Signature verification** runs before archiving: `scripts/verify_signature.sh`
uses `apksigner` for APKs and `jarsigner` for the AAB, prints the signing
certificate, and can pin the expected SHA-256 fingerprint.

**Provenance**: `actions/attest-build-provenance` creates a signed SLSA build
provenance attestation for the AAB and APK. Verify with the GitHub CLI:

```bash
gh attestation verify fieldproof-1.4.0-10400.aab --repo <owner>/FieldProof
```

> Build attestations require a public repository (or GitHub Enterprise Cloud for
> private repos). See `known-limitations.md` KL-024.

## 6. SBOM & license gate

`scripts/generate_sbom.py` and `scripts/license_scan.py` are dependency-free
(standard library only). They parse the resolved `releaseRuntimeClasspath`,
inspect each module's POM in the Gradle cache, and emit a CycloneDX SBOM plus a
license report.

The policy lives in [`scripts/license-policy.json`](../../scripts/license-policy.json):

- `allowed` / `denied` / `review` — SPDX ids.
- `allowUnknown` — if `false` (default) a dependency whose POM has no license
  metadata **fails** the gate.
- `overrides` — documented, human-verified SPDX ids for modules that omit
  license metadata (e.g. `com.google.guava:listenablefuture`).

To add a license exception, update the allow/deny lists **and** explain it in the
PR. To review the current result locally:

```bash
mkdir -p app/build/reports/dependencies
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain \
  > app/build/reports/dependencies/release-runtime.txt
python3 scripts/license_scan.py \
  --gradle-report app/build/reports/dependencies/release-runtime.txt \
  --policy scripts/license-policy.json \
  --output app/build/reports/dependencies/license-report.json
```

A **vulnerability scan** (Grype) runs over the SBOM and fails on `high`/`critical`
findings. To suppress a reviewed, unfixable finding, add a `.grype.yaml` ignore
with a comment and expiry, e.g.:

```yaml
ignore:
  - vulnerability: CVE-2024-0000
    reason: "transitive, no fix; not reachable — review by 2026-12-31"
```

## 7. Google Play Console release procedure

The pipeline produces the artifact; publishing is a deliberate manual step.

1. **Download** the signed `.aab` from the GitHub Release (or the workflow
   artifact). Verify its checksum against `checksums.txt`.
2. Play Console → your app → **Test and release** → choose a track
   (**Internal testing** first, then **Closed**/**Open**, then **Production**).
3. **Create new release** → upload the `.aab`.
4. Confirm the **App signing** tab shows the expected signing certificate
   (`UPLOAD_CERT_SHA256`). If it does not, **stop** and investigate.
5. **Upload `mapping.txt`** (Play: *App bundle explorer* / deobfuscation files) so
   crash reports are readable.
6. Fill in **release notes** and review the **pre-launch report** (internal track).
7. Roll out: staged rollout percentage for production (e.g. 10% → 50% → 100%),
   monitor **Android vitals** and crash-free rate between stages.
8. Keep the GitHub Release SBOM, checksums and provenance with the release record.

### Rollback / halt

Play has no "downgrade"; rollback means stopping the bad version and shipping a
fixed, higher-`versionCode` build.

1. **Halt the rollout** immediately: Play Console → release → **Halt rollout**
   (staged rollouts can be halted; full rollouts can be paused).
2. If the release is fully live and severe, **disable the app version** or set the
   track back to the previous release in **Release → Production → Releases**.
3. **Re-issue a fix**: branch from the last good tag, bump the version
   (`v1.4.1` → `versionCode` 10401), and run the Release workflow.
4. If the **upload key** is suspected compromised, perform the upload-key reset in
   §2 (Rotation) before rolling forward.
5. Record the incident and the rollback in the release notes and CHANGELOG.

### Never

- Never upload the APK produced by a plain `assembleRelease` (it is unsigned).
- Never commit the keystore or its passwords.
- Never reuse a `versionCode`; Play rejects it.

## 8. Failure modes

| Symptom | Meaning / fix |
|---|---|
| `Release signing was requested ... no upload keystore` | The keystore wasn't fetched. Check `UPLOAD_KEYSTORE_SECRET_ID`, AWS OIDC role trust, and IAM `secretsmanager:GetSecretValue`. |
| `signature does not match expected fingerprint` | Wrong key in Secrets Manager, or `UPLOAD_CERT_SHA256` is stale. |
| License scan `FAILED` | A new dependency is denied/unknown; update the dependency or the policy with justification. |
| Grype step fails | A high/critical CVE; upgrade or add a reviewed `.grype.yaml` ignore. |
| Provenance step fails | Repo/plan does not support attestations (KL-024). |
| `versionCode` rejected by Play | It is not greater than the live version — bump the version/tag. |

## 9. Security checklist (per release)

- [ ] `verify` and `security` jobs are green.
- [ ] Signed AAB signature verified with the expected certificate.
- [ ] `checksums.txt`, `sbom.json`, `license-report.json` archived with the release.
- [ ] Provenance attestation created; `gh attestation verify` succeeds.
- [ ] `mapping.txt` uploaded to Play.
- [ ] No keystores or secrets in the repository (`check_repo_hygiene.sh`).
