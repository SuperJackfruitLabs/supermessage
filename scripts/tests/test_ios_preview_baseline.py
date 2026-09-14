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

# Frames whose content arrives after the shutter, so no two renders agree.
#
# **Found by rendering five times and comparing, not by reasoning.** Two runs
# agreed on all forty; the third disagreed on one. So "I rendered it twice and
# it matched" is not evidence of determinism, and this list is the reason the
# gate is trustworthy rather than merely green.
#
# Every one of them is either an animation or a `.task` that resolves while
# the shutter is opening:
#
#   LiveTurnView    streaming text, which is an animation
#   NewRoomPanel    loads its people
#   RoomInfoPanel   loads the room
#   Media without bytes   asks MediaCache for an image it will not get
#
# They are still **rendered**, and still on the contact sheet, because they
# are worth looking at. They are only excluded from the comparison, which is
# the one thing they cannot support. Android does the cruder thing and skips
# rendering its seven entirely; it has to, because there they hang rather
# than race.
UNSTABLE = {
    "Supermessage_LiveTurnView.swift_Answering_only.png",
    "Supermessage_LiveTurnView.swift_Mid-turn.png",
    "Supermessage_LiveTurnView.swift_Thinking_only.png",
    "Supermessage_NewRoomPanel.swift_Known_people.png",
    "Supermessage_NewRoomPanel.swift_Nobody_yet.png",
    "Supermessage_RoomInfoPanel.swift_Furnished.png",
    "Supermessage_TimelineRowView.swift_Media_without_bytes.png",
}


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
