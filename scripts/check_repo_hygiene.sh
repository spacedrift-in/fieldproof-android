#!/usr/bin/env bash
#
# Repository hygiene gate: fail if signing material or other secrets are
# tracked by git. This is the "no keystore in repository" enforcement.
#
# Usage:
#   scripts/check_repo_hygiene.sh
#
# Exit 0 = clean; exit 1 = a tracked file looks like a key/keystore or secret.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

status=0

echo "== Checking for tracked keystore/key files =="
sensitive_files="$(git ls-files | grep -iE '\.(jks|keystore|p12|pepk|pfx|pem|key)$' || true)"
if [ -n "$sensitive_files" ]; then
  echo "error: the following key/keystore files are tracked by git:" >&2
  printf '  %s\n' $sensitive_files >&2
  status=1
fi

echo "== Checking for tracked secret-bearing properties files =="
secret_props="$(git ls-files | grep -iE '(^|/)(keystore|signing|secrets)\.properties$' || true)"
if [ -n "$secret_props" ]; then
  echo "error: the following secret properties files are tracked by git:" >&2
  printf '  %s\n' $secret_props >&2
  status=1
fi

echo "== Checking for private key material in tracked content =="
# Match classic PEM/PKCS private key headers but ignore public certs.
private_keys="$(git grep -lE 'BEGIN (RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY' -- . || true)"
if [ -n "$private_keys" ]; then
  echo "error: private key material found in tracked files:" >&2
  printf '  %s\n' $private_keys >&2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "Repository hygiene check passed: no keystores or secrets are tracked."
else
  echo "Repository hygiene check FAILED." >&2
fi
exit "$status"
