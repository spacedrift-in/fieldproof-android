#!/usr/bin/env bash
#
# Verify that a release artifact is signed and print the signer certificate.
#
# Usage:
#   scripts/verify_signature.sh app/build/outputs/bundle/release/app-release.aab
#   scripts/verify_signature.sh app/build/outputs/apk/release/app-release.apk [--expect-sha256 <fingerprint>]
#
# Exit codes:
#   0  artifact is signed by the expected certificate (or any cert if unspecified)
#   1  artifact is missing/unsigned or the certificate does not match
#   2  usage/tooling error
#
# For APKs, `apksigner` (from build-tools) is preferred because it checks the APK
# Signature Scheme (v2/v3); the AAB is a JAR and is checked with `jarsigner`.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <artifact> [--expect-sha256 <fingerprint>]" >&2
  exit 2
fi

artifact="$1"
shift
expected=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --expect-sha256)
      [ "$#" -ge 2 ] || { echo "error: --expect-sha256 needs a value" >&2; exit 2; }
      expected="$2"
      shift 2
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [ ! -f "$artifact" ]; then
  echo "error: artifact not found: $artifact" >&2
  exit 1
fi

certs="$(mktemp)"
cleanup() { rm -f "$certs"; }
trap cleanup EXIT

is_apk=no
case "$artifact" in
  *.apk) is_apk=yes ;;
esac

apksigner="${APKSIGNER:-apksigner}"
if [ "$is_apk" = yes ] && command -v "$apksigner" >/dev/null 2>&1; then
  echo "== Verifying APK signature with apksigner: $artifact =="
  if ! "$apksigner" verify --print-certs "$artifact" >"$certs" 2>&1; then
    echo "error: apksigner verification FAILED for $artifact" >&2
    cat "$certs" >&2
    exit 1
  fi
else
  echo "== Verifying JAR/AAB signature with jarsigner: $artifact =="
  # Do not use -strict: unsigned timestamps are normal for Play uploads and
  # would otherwise be reported as warnings.
  if ! jarsigner -verify -certs "$artifact" >"$certs" 2>&1; then
    echo "error: jarsigner verification FAILED for $artifact" >&2
    cat "$certs" >&2
    exit 1
  fi
  if ! grep -qi "jar verified" "$certs"; then
    echo "error: $artifact is not signed (no 'jar verified' in jarsigner output)" >&2
    cat "$certs" >&2
    exit 1
  fi
fi

# Print a stable SHA-256 fingerprint of the signing certificate for auditing.
fingerprints="$(keytool -printcert -jarfile "$artifact" 2>/dev/null \
  | grep -i 'SHA256:' | sed 's/.*SHA256: //I' | tr -d ':' | tr 'A-F' 'a-f' | sort -u || true)"

grep -iE 'Signer|Verified|CN=' "$certs" || true
if [ -n "$fingerprints" ]; then
  echo "Signing certificate SHA-256 fingerprint(s):"
  echo "$fingerprints" | sed 's/^/  /'
fi

if [ -n "$expected" ]; then
  norm_expected="$(printf '%s' "$expected" | tr -d ': ' | tr 'A-F' 'a-f')"
  if ! printf '%s\n' "$fingerprints" | grep -qx "$norm_expected"; then
    echo "error: signing certificate does not match expected fingerprint $expected" >&2
    exit 1
  fi
  echo "Signing certificate matches the expected fingerprint."
fi

echo "Signature verification passed for $artifact"
