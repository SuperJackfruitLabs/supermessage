#!/usr/bin/env python3
"""Compare rendered iOS previews against the committed baseline.

## Why this is hand-written

Android gets this from Roborazzi, which records, verifies and writes diff
images for nothing. `SnapshotPreviews` renders and stops — it has no verify
mode — so the comparison is here. It is about sixty lines, which was cheaper
than the alternative: repainting 79 text call sites with nobody checking.

## What it compares

The comparison itself now lives in `scripts/baseline_compare.py`, shared with
the web gate. What stays here is the part that is genuinely about iOS: where
the references live, and which seven frames cannot hold still.

The frames carrying a design-language rule are listed in
`docs/platform-parity.md` §6 and want human eyes on them when they change,
which is what the diff percentages in the failure message are for.
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
from baseline_compare import compare  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
REFERENCES = ROOT / "apple/SupermessagePreviewTests/previews"

# Empty, and it took making it so rather than declaring it.
#
# This list held seven frames, and its comment argued they could not be
# gated: a spinner has no canonical frame, a `.task` resolves while the
# shutter is opening. Both halves were true and neither was a reason to stop.
#
#   `LiveTurnView` ×3   A mini `ProgressView` beside the word "writing…",
#                       disagreeing in a 37×37 box. The label already says
#                       what the spinner says, so `\.rendersStill` drops it —
#                       and `LiveTurnView` now honours `accessibilityReduce\
#                       Motion` for the same redundancy, which is the better
#                       half of that change.
#   `NewRoomPanel` ×2   `.task { await load() }` against a shutter that does
#                       not wait. A `#if DEBUG` initialiser seeds the people
#                       and `loading`, and the task already ran only while
#                       loading — so no second "is this a preview" flag.
#   `RoomInfoPanel`     The same, but it came out a different *size*: a
#                       spinner and a member list are not the same height.
#   `Media without      `hasFailed` flips only once a fetch that can never
#   bytes`              succeed gives up. The fixture starts failed, which is
#                       both the settled state and the one the name promises.
#
# Then the same five-render check found an **eighth** frame — `InvitationView`,
# which had never been on the list and was being gated against a baseline it
# could flip away from at any time. The list was evidence of what had been
# caught, not of what was flaky. That is the argument for emptying it rather
# than curating it: what is left is 45 frames that were each rendered five
# times and agreed five times.
UNSTABLE: set[str] = set()

RECORD = "./scripts/snapshot-previews.sh --record"


def main(rendered_dir: str) -> int:
    return compare(REFERENCES, pathlib.Path(rendered_dir), UNSTABLE, "previews", RECORD)


if __name__ == "__main__":
    # `--unstable` lets the recording script ask which frames are not
    # references, so it does not copy them in and leave the next verify
    # reporting seven ghosts.
    if len(sys.argv) > 1 and sys.argv[1] == "--unstable":
        print("\n".join(sorted(UNSTABLE)))
        sys.exit(0)
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else str(ROOT / ".snapshots")))
