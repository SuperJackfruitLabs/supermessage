#!/usr/bin/env bash
#
# Run CI's checks on this machine before pushing, so a push is not how a
# failure is found.
#
# Mirrors .github/workflows/ci.yml job by job. Build products go to
# $SM_BUILD_ROOT (default: /Volumes/SMBuild, an APFS sparse bundle on an
# external SSD) rather than the internal disk: DerivedData, TMPDIR and the
# preview/story renders. The Rust `target/` is expected to be a symlink into
# the same volume.
#
# The story snapshots are the one check CI judges on Linux pixels, and
# `snapshot-stories.sh` refuses to judge on a Mac. So they run in a Linux
# container with CI's Playwright and Node. The container is arm64 while CI's
# runner is x86-64, and that alone moves pixels: measured 2026-09-23, 21 of
# 91 stories differed from CI's renders of the same commit by 0.01-1.8%
# (glyph rasterisation). amd64 emulation (`--platform linux/amd64`) crashes
# Node under QEMU. So this check can only say "matches the baseline" or
# "look at these"; a failure limited to sub-2% text diffs is expected here.
# New story baselines are always CI's own renders (its `story-snapshots`
# artifact), never this container's.
#
# Not mirrored: the XCFramework and Android ABI builds (15 minutes each; run
# ./scripts/build-xcframework.sh or ./scripts/build-android-libs.sh when the
# FFI surface changes) and Android instrumented tests (need an emulator; pass
# --instrumented with one running).
#
# Usage:  ./scripts/ci-local.sh [--only tokens,rust,frontend,stories,ios,android]
#                               [--instrumented]
set -uo pipefail
cd "$(dirname "$0")/.."

ROOT_DIR="$(pwd)"
BUILD_ROOT="${SM_BUILD_ROOT:-/Volumes/SMBuild}"
ONLY="tokens,rust,frontend,stories,ios,android"
INSTRUMENTED=no
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift 2 ;;
    --instrumented) INSTRUMENTED=yes; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
want() { [[ ",$ONLY," == *",$1,"* ]]; }

if [ ! -d "$BUILD_ROOT" ]; then
  echo "error: $BUILD_ROOT is not mounted. Attach the sparse bundle first:" >&2
  echo "  hdiutil attach '/Volumes/Extreme SSD/SMBuild.sparsebundle' -nobrowse" >&2
  exit 1
fi
LOGS="$BUILD_ROOT/logs/ci-local"
mkdir -p "$LOGS" "$BUILD_ROOT/tmp" "$BUILD_ROOT/DerivedData"
export TMPDIR="$BUILD_ROOT/tmp/"
DERIVED="$BUILD_ROOT/DerivedData"

# CI diffs a clean checkout; here the tree may hold work in progress, so the
# equivalent question is "does regenerating change anything?".
unchanged_by() {
  local before after
  before="$(git diff | shasum)"
  "$@" >/dev/null || return 1
  after="$(git diff | shasum)"
  [ "$before" = "$after" ] || { git diff --stat; return 1; }
}

results=()
run() {
  local name="$1"; shift
  local log="$LOGS/$(echo "$name" | tr ' /' '__').log"
  printf '%-52s' "$name"
  if "$@" > "$log" 2>&1; then
    echo "pass"; results+=("pass  $name")
  else
    echo "FAIL  ($log)"; results+=("FAIL  $name")
  fi
}

# ---- Design tokens -------------------------------------------------------
if want tokens; then
  run "tokens: contracts and emitters" python3 -m unittest discover -s scripts/tests -t .
  run "tokens: generated files match the source" unchanged_by python3 scripts/generate-tokens.py
fi

# ---- Rust ----------------------------------------------------------------
if want rust; then
  run "rust: fmt" cargo fmt --all --check
  run "rust: clippy" cargo clippy --workspace --all-targets --all-features -- -D warnings
  run "rust: tests" cargo test --workspace --all-features
fi

# ---- Frontend ------------------------------------------------------------
if want frontend; then
  run "frontend: unit tests" pnpm test
  run "frontend: type and template check" pnpm check
  run "frontend: build" pnpm build
  run "frontend: fixtures are not in the bundle" node scripts/tests/test_fixture_leak.mjs
fi

# ---- Stories, on Linux pixels ------------------------------------------
if want stories; then
  run "stories: look like the baseline (Linux container)" \
    docker run --rm -v "$ROOT_DIR":/repo -v sm-linux-node-modules:/repo/node_modules \
      -v sm-pnpm-store:/pnpm-store \
      -w /repo mcr.microsoft.com/playwright:v1.63.0-noble bash -lc \
      'corepack enable >/dev/null 2>&1 && pnpm install --frozen-lockfile --store-dir /pnpm-store >/dev/null && ./scripts/snapshot-stories.sh'
fi

# ---- iOS -----------------------------------------------------------------
if want ios; then
  # Overridable: with a newer simulator runtime installed than this Xcode
  # can run, "iPhone 16 Pro" alone resolves to that runtime and finds
  # nothing. SIM_DESTINATION="platform=iOS Simulator,name=iPhone 16 Pro,OS=18.6"
  SIM="${SIM_DESTINATION:-platform=iOS Simulator,name=iPhone 16 Pro}"
  run "ios: generate the project" bash -c 'cd apple && xcodegen generate -q'
  run "ios: kit tests" xcodebuild test -project apple/Supermessage.xcodeproj \
    -scheme SupermessageKit -destination "$SIM" -derivedDataPath "$DERIVED"
  run "ios: app and UI tests compile" xcodebuild build-for-testing \
    -project apple/Supermessage.xcodeproj -scheme Supermessage -destination "$SIM" \
    -derivedDataPath "$DERIVED"
  run "ios: previews look like the baseline" ./scripts/snapshot-previews.sh
  run "ios: previews stay out of Release" ./scripts/tests/test_ios_preview_leak.sh
fi

# ---- Android -------------------------------------------------------------
if want android; then
  unset ANDROID_SDK_ROOT
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
  run "android: host core" cargo build -p supermessage-ffi
  run "android: bindings match the Rust" unchanged_by ./scripts/generate-kotlin-bindings.sh
  run "android: unit and preview tests" bash -c \
    'cd android && ./gradlew -q :kit:testDebugUnitTest :app:testDebugUnitTest :core:testDebugUnitTest'
  run "android: instrumented tests compile" bash -c 'cd android && ./gradlew -q :app:compileDebugAndroidTestKotlin'
  run "android: previews stay out of release" ./scripts/tests/test_android_preview_leak.sh
  if [ "$INSTRUMENTED" = yes ]; then
    run "android: instrumented tests" bash -c 'cd android && ./gradlew -q :app:connectedDebugAndroidTest'
  fi
fi

echo
printf '%s\n' "${results[@]}"
for r in "${results[@]}"; do [[ "$r" == FAIL* ]] && exit 1; done
exit 0
