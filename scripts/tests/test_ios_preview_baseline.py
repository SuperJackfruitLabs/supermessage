#!/usr/bin/env python3
"""Compare rendered iOS previews against the committed baseline.

## Why this is hand-written

Android gets this from Roborazzi, which records, verifies and writes diff
images for nothing. `SnapshotPreviews` renders and stops — it has no verify
mode — so the comparison is here. It is about sixty lines, which was cheaper
than the alternative: repainting 79 text call sites with nobody checking.

## What it compares

Pixels, not bytes. Two renders on this machine are byte-identical, but a PNG
encoder is free to produce different bytes for the same image and a baseline
that fails on a libpng version is a baseline nobody will keep. So a frame
fails when its *pixels* differ, and the report says how many.

## What it cannot tell you

That the baseline is right — only that it has not moved. A wrong colour
committed as a reference is a wrong colour the gate will defend. The frames
carrying a design-language rule are listed in `docs/platform-parity.md` §6
and want human eyes on them when they change, which is what the diff count
in the failure message is for.
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
from png_decode import decode  # noqa: E402

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


def pixels_differ(a: pathlib.Path, b: pathlib.Path) -> tuple[int, int]:
    """Differing pixels and total, or (-1, -1) when the sizes disagree."""
    wa, ha, cha, rows_a = decode(str(a))
    wb, hb, chb, rows_b = decode(str(b))
    if (wa, ha) != (wb, hb):
        return -1, -1
    differing = 0
    for ra, rb in zip(rows_a, rows_b):
        if ra == rb:
            continue
        for x in range(wa):
            if ra[x * cha:x * cha + 3] != rb[x * chb:x * chb + 3]:
                differing += 1
    return differing, wa * ha


def main(rendered_dir: str) -> int:
    rendered = pathlib.Path(rendered_dir)
    actual = {p.name: p for p in rendered.glob("*.png") if p.name not in UNSTABLE}
    expected = {p.name: p for p in REFERENCES.glob("*.png")}

    if not expected:
        print(f"FAIL: no baseline in {REFERENCES}.")
        print("Record one with: ./scripts/snapshot-previews.sh --record")
        return 1
    if not actual:
        print(f"FAIL: nothing rendered into {rendered}, so nothing was compared.")
        return 1

    missing = sorted(set(expected) - set(actual))
    added = sorted(set(actual) - set(expected))
    moved = []
    for name in sorted(set(expected) & set(actual)):
        n, total = pixels_differ(expected[name], actual[name])
        if n != 0:
            moved.append((name, n, total))

    if not (missing or added or moved):
        print(f"PASS: {len(actual)} previews match the baseline "
              f"({len(UNSTABLE)} excluded as unstable).")
        return 0

    print("FAIL: the rendered previews do not match the baseline.\n")
    for name in missing:
        print(f"  gone     {name}  — a preview was removed or renamed")
    for name in added:
        print(f"  new      {name}  — a preview was added")
    for name, n, total in moved:
        if n < 0:
            print(f"  resized  {name}")
        else:
            print(f"  changed  {name}  {n} of {total} pixels ({100 * n / total:.2f}%)")
    print(f"\nRendered frames are in {rendered} — look at them.")
    print("If the change is intended: ./scripts/snapshot-previews.sh --record")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else str(ROOT / ".snapshots")))
