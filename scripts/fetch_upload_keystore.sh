#!/usr/bin/env bash
#
# Fetch the Android upload keystore from AWS Secrets Manager into a temp file.
#
# The keystore is NEVER committed. In CI the workflow assumes a role via OIDC
# (aws-actions/configure-aws-credentials) and the secret is protected by the
# GitHub `release` environment + AWS IAM. See fieldproof-starter/docs/release.md.
#
# The secret may be stored as one of:
#   * raw base64 of the .jks bytes, or
#   * a JSON object: {"keystoreBase64": "<base64>", "storePassword": "...",
#                     "keyAlias": "...", "keyPassword": "..."}
#   * SecretBinary (AWS base64-encodes it in the API response).
#
# Output: prints the absolute path of the decoded keystore on stdout and exports
# nothing. The file is created with 0600 permissions.
#
# Environment:
#   UPLOAD_KEYSTORE_SECRET_ID   (required) Secrets Manager secret id/ARN.
#   AWS_REGION                  (required by the AWS CLI) region of the secret.
#   UPLOAD_KEYSTORE_OUT         (optional) output path; defaults to a mktemp file.
#
# Idempotent: if FIELD_PROOF_UPLOAD_KEYSTORE_PATH already points at an existing
# file, that path is printed and nothing is fetched (useful for local dry-runs).
set -euo pipefail

if [ -n "${FIELD_PROOF_UPLOAD_KEYSTORE_PATH:-}" ] && [ -f "$FIELD_PROOF_UPLOAD_KEYSTORE_PATH" ]; then
  echo "Using existing keystore at $FIELD_PROOF_UPLOAD_KEYSTORE_PATH" >&2
  printf '%s\n' "$FIELD_PROOF_UPLOAD_KEYSTORE_PATH"
  exit 0
fi

if [ -z "${UPLOAD_KEYSTORE_SECRET_ID:-}" ]; then
  echo "error: UPLOAD_KEYSTORE_SECRET_ID is required" >&2
  exit 2
fi

if ! command -v aws >/dev/null 2>&1; then
  echo "error: the AWS CLI is required to fetch the upload keystore" >&2
  exit 2
fi

python_bin="${PYTHON_BIN:-python3}"
if ! command -v "$python_bin" >/dev/null 2>&1; then
  echo "error: $python_bin is required to parse the secret" >&2
  exit 2
fi

out="${UPLOAD_KEYSTORE_OUT:-$(mktemp -t fieldproof-upload-keystore.XXXXXX.jks)}"

secret_json="$(aws secretsmanager get-secret-value \
  --secret-id "$UPLOAD_KEYSTORE_SECRET_ID" \
  --query '{SecretString: SecretString, SecretBinary: SecretBinary}' \
  --output json)"

# Files created below inherit the restrictive umask.
umask 077

program_file="$(mktemp)"
cleanup() { rm -f "$program_file"; }
trap cleanup EXIT

cat >"$program_file" <<'PY'
import base64
import json
import sys

out_path = sys.argv[1]
try:
    payload = json.load(sys.stdin)
except json.JSONDecodeError as exc:  # pragma: no cover - defensive
    raise SystemExit(f"error: could not parse Secrets Manager response: {exc}")

raw = payload.get("SecretString")
if not raw:
    raw = payload.get("SecretBinary")
if not raw:
    raise SystemExit("error: secret has neither SecretString nor SecretBinary")

# A JSON-wrapped secret carries the base64 under a known key.
wrapped = None
try:
    parsed = json.loads(raw)
    if isinstance(parsed, dict):
        wrapped = parsed
except (ValueError, TypeError):
    wrapped = None

if wrapped is not None:
    b64 = (
        wrapped.get("keystoreBase64")
        or wrapped.get("keystore_base64")
        or wrapped.get("keystore")
    )
    if not b64:
        raise SystemExit("error: JSON secret is missing a keystore base64 field")
    data = base64.b64decode(b64)
else:
    cleaned = raw.strip()
    if cleaned.startswith("data:"):
        cleaned = cleaned.split(",", 1)[-1]
    try:
        data = base64.b64decode(cleaned, validate=True)
    except Exception as exc:  # noqa: BLE001
        raise SystemExit(f"error: secret is not valid base64: {exc}")

if not data:
    raise SystemExit("error: decoded keystore is empty")

with open(out_path, "wb") as handle:
    handle.write(data)
PY

printf '%s' "$secret_json" | "$python_bin" "$program_file" "$out"

chmod 600 "$out"
echo "Wrote upload keystore to $out" >&2
printf '%s\n' "$out"
