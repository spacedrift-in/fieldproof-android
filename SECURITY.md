# Security Policy

## Supported versions

FieldProof is under active early development. Security fixes are applied to the
`main` branch; there are no maintained release branches yet.

| Version | Supported |
| :--- | :--- |
| `main` | ✅ |

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, email **spacedrift.contact@gmail.com** with:

- a description of the issue and its impact,
- steps to reproduce (a proof of concept if possible),
- affected component(s) and version/commit,
- any suggested remediation.

We aim to acknowledge reports within a few business days and will keep you
informed as we work on a fix. Please give us reasonable time to address the
issue before any public disclosure. We are happy to credit reporters who wish
to be acknowledged.

## Scope and expectations

- This repository uses **synthetic data only**. Do not submit real client data,
  evidence, credentials or secrets in issues, pull requests, fixtures, logs or
  screenshots.
- The client-side checks in this project (for example, "required evidence")
  are for usability. **Authorization and validation must be enforced
  server-side**; the API skeleton in `fieldproof-starter/` does not implement
  authentication yet.
- Local evidence file paths are private device metadata and must never be
  treated as server storage paths.

## Handling secrets

- Never commit secrets, API keys or tokens. `local.properties`, `.env` files and
  the Android upload keystore (`*.jks`/`*.keystore`) are git-ignored, and CI runs
  `scripts/check_repo_hygiene.sh` to fail the build if any signing material or
  private key is ever tracked.
- Release signing keys live in **AWS Secrets Manager** (optionally KMS-encrypted)
  and are fetched at build time via GitHub OIDC — they are never stored in the
  repository or as long-lived CI secrets. See
  [the release guide](fieldproof-starter/docs/release.md).
- If you believe a secret or the upload key has been committed or leaked, report
  it via the email above so it can be rotated and purged from history. A leaked
  upload key can be reset in the Play Console without changing the app signing
  key.
