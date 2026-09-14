#!/usr/bin/env bash
#
# Renders all 83 Storybook stories and compares them to the committed
# baseline. Same shape as `snapshot-previews.sh`: verify by default, record
# only when asked.
#
# Verify-by-default for the reason Android's gradle.properties gives — a
# capture-by-default gate rewrites its own baseline to match whatever the
# code now does, and so agrees with every regression it was built to catch.
#
# **On a Mac this renders but does not judge.** The baseline is Linux pixels;
# see the docstring in scripts/tests/test_story_baseline.py for why one
# platform has to own it. The contact sheet is the point of running it here.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RECORD=no
if [ "${1:-}" = "--record" ]; then RECORD=yes; shift; fi

OUT="$ROOT/.snapshots-web"
REFERENCES="$ROOT/src/lib/stories/baseline"

# Rebuilt every time rather than reused. A stale `storybook-static` is a
# gate that passes because it is looking at last week's components, which is
# worse than no gate — it reports green about code it never loaded.
echo "Building the story catalogue…"
pnpm storybook:build >/dev/null

node "$ROOT/scripts/snapshot-stories.mjs" "$OUT"
python3 "$ROOT/scripts/snapshot-index.py" "$OUT"

if [ "$RECORD" = yes ]; then
  PLATFORM="$(cat "$OUT/PLATFORM")"
  if [ "$PLATFORM" != linux ]; then
    echo
    echo "REFUSED: these are $PLATFORM pixels and the baseline is linux."
    echo "Recording here would replace a baseline CI can never match, and the"
    echo "damage shows up as 83 unexplained diffs in somebody else's PR."
    echo "Re-record through CI: the 'Record the story baseline' workflow."
    exit 1
  fi
  mkdir -p "$REFERENCES"
  rm -f "$REFERENCES"/*.png
  cp "$OUT"/*.png "$REFERENCES"/
  echo "RECORDED: $(ls "$REFERENCES"/*.png | wc -l | tr -d ' ') stories are the new baseline."
  exit 0
fi

python3 "$ROOT/scripts/tests/test_story_baseline.py" "$OUT"
