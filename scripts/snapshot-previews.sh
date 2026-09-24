#!/usr/bin/env bash
#
# Renders every iOS `#Preview` to a PNG, and writes an index to look at them.
#
# P2b wrote 52 previews on this platform and rendered none of them: Xcode
# 16.4 here ships the iOS 18.5 SDK against a device on 26.6.1, so the canvas
# was never available. This is what closes that, and the first run of it found
# two things nothing else could have — see `docs/preview-snapshots.md`.
#
# Verify by default; `--record` replaces the baseline.
#
# This header used to say the images were not committed and this was a viewer
# rather than a gate, because several previews depended on the wall clock and
# others raced a `.task`. All 45 are compared now — see
# docs/preview-snapshots.md, "What it took to make them hold still". The
# rendered output stays gitignored; the baseline under
# apple/SupermessagePreviewTests/previews does not.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RECORD=no
if [ "${1:-}" = "--record" ]; then RECORD=yes; shift; fi
OUT="${1:-$ROOT/.snapshots}"
REFERENCES="$ROOT/apple/SupermessagePreviewTests/previews"
DERIVED="${TMPDIR:-/tmp}/supermessage-snapshots"

cd "$ROOT/apple"
xcodegen generate > /dev/null

# The newest booted-or-available iPhone. Resolved rather than hard-coded, for
# the reason CI's own simulator step gives: the runtime set is tied to the
# Xcode that ships it and neither is ours to pin.
# Overridable, for a machine with a newer simulator runtime than its Xcode
# renders the baseline with (the newest phone would then be on that runtime).
UDID="${PREVIEW_UDID:-}"
[ -n "$UDID" ] || UDID=$(xcrun simctl list devices available --json | python3 -c '
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

if [ "$RECORD" = yes ]; then
  mkdir -p "$REFERENCES"
  rm -f "$REFERENCES"/*.png
  cp "$OUT"/*.png "$REFERENCES"/
  # Unstable frames are rendered but are not references — copying them in
  # leaves the next verify reporting them as "gone", because the comparison
  # skips them on the rendered side.
  #
  # The list is empty now, and `[ -n "$name" ]` is why that is quiet rather
  # than destructive: an empty list still yields one blank line, and
  # `rm -f "$REFERENCES/"` then argues with the directory itself.
  python3 "$ROOT/scripts/tests/test_ios_preview_baseline.py" --unstable \
    | while read -r name; do [ -n "$name" ] && rm -f "$REFERENCES/$name"; done
  echo "RECORDED: $count previews are the new baseline."
  echo "Look at them before committing: open $OUT/index.html"
  exit 0
fi

# Verify by default, for the reason Android's gradle.properties gives: a
# capture-by-default gate rewrites its own baseline to match whatever the
# code now does, and agrees with every regression.
python3 "$ROOT/scripts/tests/test_ios_preview_baseline.py" "$OUT"
