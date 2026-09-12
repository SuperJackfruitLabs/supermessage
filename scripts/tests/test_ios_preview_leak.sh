#!/usr/bin/env bash
#
# Previews and their fixtures must never reach a release build.
#
# `#if DEBUG` is what excludes them, and "so it should" is a belief until
# something checks. This builds the app for Release and looks for two
# literals in everything the .app contains — the app binary and the embedded
# SupermessageKit and SupermessageFFI frameworks, since the DEBUG-only
# `Session(previewClient:phase:)` lives in the Kit rather than the app.
#
# **Two markers, not one, and the second is the interesting one.**
# `PREVIEW_FIXTURE_MARKER` is anchored by a `precondition` in
# `PreviewClient.init`, so it travels with the stub — which is the realistic
# leak, because the stub is what a preview moved outside `#if DEBUG` would
# still reference. But a leak of a *fixture value alone* would carry neither
# the stub nor the marker, so the second grep is for a fixture string that
# nothing in the product would ever contain. Neither grep catches everything
# and this comment is the honest bound on what the gate proves.
#
# **What this gate cannot catch, established by mutation rather than
# reasoning.** Three deliberately leaked builds were run against it:
#
#   A. a fixture moved outside `#if DEBUG`, referenced by nothing — PASSES.
#      The optimiser strips it and no string reaches the binary. Arguably
#      not a leak at all, but do not mistake this gate's silence for proof
#      that an ungated fixture is gated.
#   B. the same fixture read into an unused stored property on a shipped
#      view — PASSES, for the same reason. This is precisely the shape that
#      made `test_fixture_leak.mjs` pass meaninglessly on the web side.
#   C. the same fixture *rendered* by `LoginView` — FAILS, and names the
#      binary. That is the leak worth having a gate for.
#
# Mutation C also found a bug in this script that mattered more than the
# mutation did: see the `grep -c` comment below. The whole gate was reporting
# PASS on a build whose binary provably contained the string.
#
# The `scanned` check is from the same lesson in a different direction: a
# gate that scanned nothing must fail rather than pass.
set -euo pipefail

MARKER="__supermessage_ios_preview_fixture_do_not_ship__"
# A fixture string, verbatim from PreviewFixtures.roomInfo.
CANARY="Platform work: the core, the seams, and whatever is on fire."

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DERIVED="${TMPDIR:-/tmp}/supermessage-release-gate"

cd "$ROOT/apple"
xcodegen generate > /dev/null

echo "Building for Release…"
xcodebuild build \
  -project Supermessage.xcodeproj \
  -scheme Supermessage \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$DERIVED" \
  CODE_SIGNING_ALLOWED=NO \
  -quiet

APP="$DERIVED/Build/Products/Release-iphoneos/Supermessage.app"
[ -d "$APP" ] || { echo "FAIL: no Release .app at $APP"; exit 1; }

scanned=0
hits=()
while IFS= read -r -d '' binary; do
  # Mach-O only: the asset catalog and Info.plist are not where a Swift
  # string literal ends up, and scanning them would only add noise.
  file "$binary" | grep -q "Mach-O" || continue
  scanned=$((scanned + 1))
  # `grep -c`, not `grep -q`, and that is not a style choice. Under
  # `set -o pipefail` a `grep -q` exits on its first match, `strings` takes
  # SIGPIPE, and the pipeline's status becomes 141 — so a match reported as a
  # *failure to match*. This gate passed three deliberately leaked builds in a
  # row before that was found, including one where the leaked string was
  # rendered on the login screen and provably present in the binary.
  found=$(strings -a "$binary" | grep -cF -e "$MARKER" -e "$CANARY" || true)
  if [ "$found" -gt 0 ]; then
    hits+=("$binary")
  fi
done < <(find "$APP" -type f -print0)

if [ "$scanned" -eq 0 ]; then
  echo "FAIL: scanned no Mach-O binaries in $APP — the gate proved nothing."
  exit 1
fi

if [ "${#hits[@]}" -gt 0 ]; then
  echo "FAIL: preview fixtures reached the Release build:"
  printf '  %s\n' "${hits[@]}"
  echo
  echo "Everything in Supermessage/Previews/ and the Session(previewClient:)"
  echo "initialiser must stay inside #if DEBUG."
  exit 1
fi

echo "PASS: $scanned Mach-O binaries scanned, no preview fixtures in Release."
