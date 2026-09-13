#!/usr/bin/env bash
#
# Renders every iOS `#Preview` to a PNG, and writes an index to look at them.
#
# P2b wrote 52 previews on this platform and rendered none of them: Xcode
# 16.4 here ships the iOS 18.5 SDK against a device on 26.6.1, so the canvas
# was never available. This is what closes that, and the first run of it found
# two things nothing else could have — see `docs/preview-snapshots.md`.
#
# The images are **not committed**. This is a viewer, not a regression gate:
# several previews still depend on the wall clock through relative-time
# formatting, so a committed baseline would diff against itself. Turning this
# into a gate is a separate decision and needs those fixed first.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/.snapshots}"
DERIVED="${TMPDIR:-/tmp}/supermessage-snapshots"

cd "$ROOT/apple"
xcodegen generate > /dev/null

# The newest booted-or-available iPhone. Resolved rather than hard-coded, for
# the reason CI's own simulator step gives: the runtime set is tied to the
# Xcode that ships it and neither is ours to pin.
UDID=$(xcrun simctl list devices available --json | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
phones = [d for runtime, ds in devices.items() if "iOS" in runtime
          for d in ds if d["name"].startswith("iPhone")]
print(phones[0]["udid"] if phones else "")
')
[ -n "$UDID" ] || { echo "FAIL: no iPhone simulator available."; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT"
# `${UDID}`, braced: an unbraced `$UDID…` makes bash read the ellipsis as
# part of the name, and under `set -u` that is an unbound-variable abort
# naming a variable nobody wrote.
echo "Rendering previews on ${UDID}…"
TEST_RUNNER_SNAPSHOTS_EXPORT_DIR="$OUT" xcodebuild test \
  -project Supermessage.xcodeproj \
  -scheme PreviewSnapshots \
  -destination "id=$UDID" \
  -derivedDataPath "$DERIVED" \
  -quiet

count=$(find "$OUT" -name '*.png' | wc -l | tr -d ' ')
if [ "$count" -eq 0 ]; then
  echo "FAIL: the run produced no images, so it proved nothing."
  echo "Most likely the previews were compiled out — they are #if DEBUG, and"
  echo "this scheme's target sets SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG"
  echo "for exactly that reason."
  exit 1
fi

python3 "$ROOT/scripts/snapshot-index.py" "$OUT"
echo "PASS: $count previews rendered."
echo "open $OUT/index.html"
