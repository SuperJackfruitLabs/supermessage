#!/usr/bin/env python3
"""Compare a directory of rendered frames against a committed baseline.

Extracted from the iOS gate when the web gate needed the same thing. Not
copied — shared, because the two most useful behaviours here were each
learned from a bug, and a second copy would have been written without them:

  - Pixels are compared, never bytes. Two renders on one machine are
    byte-identical, but a PNG encoder may emit different bytes for the same
    image, and a baseline that fails on a libpng upgrade is one nobody keeps.
  - `unstable` is filtered from **both** sides. Filtering only the rendered
    side made every excluded frame read as "gone", because the baseline still
    held a reference the comparison would never look at.

What it cannot tell you is that the baseline is *right* — only that it has
not moved. A wrong colour committed as a reference is a wrong colour this
will defend, which is what the percentages in the failure report are for.
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from png_decode import decode  # noqa: E402


def pixels_differ(a: pathlib.Path, b: pathlib.Path, tolerance: int = 0) -> tuple[int, int]:
    """Differing pixels and total, or (-1, -1) when the sizes disagree.

    `tolerance` is the largest per-channel difference still counted as equal,
    and it is **not** a fudge factor to be turned up until the gate goes
    quiet. It admits exactly one thing: Chromium does not rasterize a rounded
    corner bit-reproducibly, and four runs of the same 83 stories produced
    three frames disagreeing by 7 to 25 pixels, every one of them on a curve,
    none by more than 2 of 255 on any channel. Excluding those three frames
    would have been the smaller change and the wrong one — the next run
    curdles a different corner.

    What it cannot hide is any regression a screenshot gate is for. Text
    moving one pixel swaps content against ground, roughly 0x22 against
    0xf2. A changed token moves a channel by tens. A layout shift resizes
    the frame. All of those are orders of magnitude above ±2, and the
    padding change used to prove it moved 9,000 pixels.

    What it does hide, stated plainly: a colour nudged by one or two parts
    in 255. The design tokens are asserted against contrast contracts in
    their own test, which is the better place to catch that anyway.

    iOS passes 0 and keeps an exact comparison, because it has never needed
    this and a gate should not be loosened on speculation.
    """
    wa, ha, cha, rows_a = decode(str(a))
    wb, hb, chb, rows_b = decode(str(b))
    if (wa, ha) != (wb, hb):
        return -1, -1
    differing = 0
    for ra, rb in zip(rows_a, rows_b):
        if ra == rb:
            continue
        for x in range(wa):
            pa = ra[x * cha:x * cha + 3]
            pb = rb[x * chb:x * chb + 3]
            if pa == pb:
                continue
            if tolerance and all(abs(u - v) <= tolerance for u, v in zip(pa, pb)):
                continue
            differing += 1
    return differing, wa * ha


def compare(references: pathlib.Path, rendered: pathlib.Path, unstable: set,
            noun: str, record_hint: str, tolerance: int = 0) -> int:
    actual = {p.name: p for p in rendered.glob("*.png") if p.name not in unstable}
    expected = {p.name: p for p in references.glob("*.png") if p.name not in unstable}

    if not expected:
        print(f"FAIL: no baseline in {references}.")
        print(f"Record one with: {record_hint}")
        return 1
    if not actual:
        print(f"FAIL: nothing rendered into {rendered}, so nothing was compared.")
        return 1

    missing = sorted(set(expected) - set(actual))
    added = sorted(set(actual) - set(expected))
    moved = []
    for name in sorted(set(expected) & set(actual)):
        n, total = pixels_differ(expected[name], actual[name], tolerance)
        if n != 0:
            moved.append((name, n, total))

    if not (missing or added or moved):
        excluded = f" ({len(unstable)} excluded as unstable)" if unstable else ""
        print(f"PASS: {len(actual)} {noun} match the baseline{excluded}.")
        return 0

    print(f"FAIL: the rendered {noun} do not match the baseline.\n")
    for name in missing:
        print(f"  gone     {name}  — one was removed or renamed")
    for name in added:
        print(f"  new      {name}  — one was added")
    for name, n, total in moved:
        if n < 0:
            print(f"  resized  {name}")
        else:
            print(f"  changed  {name}  {n} of {total} pixels ({100 * n / total:.2f}%)")
    print(f"\nRendered frames are in {rendered} — look at them.")
    print(f"If the change is intended: {record_hint}")
    return 1
