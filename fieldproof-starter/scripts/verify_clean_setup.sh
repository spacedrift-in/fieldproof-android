#!/usr/bin/env bash
#
# Verify a clean environment can build, test and demo FieldProof.
#
# Usage:
#   scripts/verify_clean_setup.sh            # prerequisite checks only
#   scripts/verify_clean_setup.sh --full     # prerequisites + backend pytest + Android unit tests
#
# Exit code 0 = ready to reproduce the demo (see docs/demo.md).
# Exit code 1 = a required tool/version is missing.
#
# Requires network-free tool/version checks only; nothing is installed for you.
set -u

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER="$(cd "$SCRIPTS_DIR/.." && pwd)"          # fieldproof-starter/
REPO_ROOT="$(cd "$STARTER/.." && pwd)"             # Android project root (gradlew lives here)
MODE="${1:-prereq}"

failures=0

check() {
  local name="$1" present="$2" version="$3" ok="no"
  if [ "$present" = "yes" ]; then ok="yes"; else ok="no"; failures=$((failures+1)); fi
  printf '  %-40s %-4s %s\n' "$name" "$ok" "$version"
}

echo "== FieldProof clean-environment verification =="
echo "(app root: $REPO_ROOT | starter: $STARTER)"
echo

echo "-- required toolchains --"
if command -v java >/dev/null 2>&1; then
  ver="$(java -version 2>&1 | head -1)"
  check "JDK" yes "$ver"
else
  check "JDK" no "missing"
fi

if command -v python3 >/dev/null 2>&1; then
  ver="$(python3 --version 2>&1)"
  check "Python" yes "$ver"
else
  check "Python" no "missing"
fi

if command -v docker >/dev/null 2>&1; then
  ver="$(docker --version 2>&1)"
  check "Docker" yes "$ver"
else
  check "Docker" no "missing"
fi

# Locate the Android SDK: env var > local.properties > common macOS path.
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ] && [ -f "$REPO_ROOT/local.properties" ]; then
  ANDROID_HOME="$(sed -n 's/^sdk.dir\s*=\s*//p' "$REPO_ROOT/local.properties" 2>/dev/null)"
fi
if [ -z "$ANDROID_HOME" ] && [ -d "$HOME/Library/Android/sdk" ]; then
  ANDROID_HOME="$HOME/Library/Android/sdk"
fi

if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]; then
  check "Android SDK" yes "$ANDROID_HOME"
  for p in "platforms/android-37.0" "build-tools/36.0.0" "platform-tools"; do
    if [ -d "$ANDROID_HOME/$p" ]; then
      check "SDK $p" yes "present"
    else
      check "SDK $p" no "missing (install via sdkmanager)"
    fi
  done
else
  check "Android SDK" no "missing (set ANDROID_HOME or ANDROID_SDK_ROOT)"
fi

echo

echo "-- optional demo tools --"
FIELDPROOF_BASE_URL="${FIELDPROOF_BASE_URL:-http://127.0.0.1:8000}"
if curl -s --max-time 5 "${FIELDPROOF_BASE_URL}/health" >/dev/null 2>&1; then
  check "API at ${FIELDPROOF_BASE_URL}" yes "reachable"
else
  check "API at ${FIELDPROOF_BASE_URL}" no "not running (see docs/demo.md §3.1)"
fi

echo

echo "-- repository sanity --"
if [ -x "$REPO_ROOT/gradlew" ] && [ -f "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties" ]; then
  check "Gradle wrapper" yes "$(grep distributionUrl "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties" | cut -d= -f2)"
else
  check "Gradle wrapper" no "missing $REPO_ROOT/gradlew"
fi
if [ -d "$STARTER/backend/app" ]; then
  check "Backend source" yes "$STARTER/backend/app"
else
  check "Backend source" no "missing"
fi
if [ -f "$STARTER/tests/fixtures/evidence-sample.png" ]; then
  check "Synthetic photo fixture" yes "$STARTER/tests/fixtures/evidence-sample.png"
else
  check "Synthetic photo fixture" no "missing (regenerate: python3 scripts/seed_demo.py)"
fi
if [ -f "$STARTER/docs/demo.md" ]; then
  check "Demo script" yes "$STARTER/docs/demo.md"
else
  check "Demo script" no "missing"
fi
if [ -f "$STARTER/docs/known-limitations.md" ]; then
  check "Known-limitations log" yes "$STARTER/docs/known-limitations.md"
else
  check "Known-limitations log" no "missing"
fi

echo

if [ "$MODE" = "--full" ]; then
  echo "-- running backend tests (requires DATABASE_URL) --"
  if [ -n "${DATABASE_URL:-}" ]; then
    ( cd "$STARTER" && .venv/bin/python -m pytest backend/tests -q ) \
      || failures=$((failures+1))
  else
    echo "  skipped: DATABASE_URL not set (db-backed tests are skipped by design)"
  fi

  echo "-- running Android unit tests --"
  ( cd "$REPO_ROOT" && ./gradlew :app:testDebugUnitTest --console=plain -q ) \
    || failures=$((failures+1))
else
  echo "note: run with '--full' to also execute backend pytest + Android unit tests."
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "RESULT: ready to reproduce the demo (see docs/demo.md)."
  exit 0
else
  echo "RESULT: $failures check(s) failed. See docs/demo.md for required versions/setup."
  exit 1
fi