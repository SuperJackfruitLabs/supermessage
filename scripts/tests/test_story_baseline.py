#!/usr/bin/env python3
"""Compare rendered Storybook stories against the committed baseline.

## Why the baseline is Linux and this machine may well not be

The other two gates are lucky: Xcode previews are recorded on macOS and
verified on macOS runners, Robolectric renders on the JVM, so both sides of
each comparison agree by construction. The web does not get that. CI is
ubuntu-24.04 and most of this repository's development happens on a Mac, and
Chromium's text rasterization is not the same on the two — same Skia, but
CoreText and FreeType disagree about glyph positions below the pixel.

The typeface itself is not the problem: IBM Plex and Source Serif ship in
the bundle via `@fontsource`, so both platforms draw from the same outlines.
It is the rasterizer, and no font bundling fixes that.

Three ways out, and the reason for the one chosen:

  - **A tolerance** — allow N% of pixels to differ. Rejected: the number that
    absorbs a rasterizer also absorbs a one-pixel shift, which is exactly the
    class of regression a screenshot gate exists to catch. A gate tuned until
    it stops complaining has been tuned until it stops working.
  - **Docker**, so both sides render on the same Linux. Correct, and the
    usual answer. Rejected here for a dull reason: the Playwright image is
    about 2GB and this machine had 3.4GB free.
  - **One platform owns the baseline.** Chosen. Linux records it, because
    that is where the gate runs unattended.

So the baseline carries a `PLATFORM` marker and this refuses to compare
across a mismatch. That refusal is the point of the file. Without it the
first Mac to run `--record` silently replaces the Linux baseline with one CI
can never match, and the failure surfaces as 83 unexplained diffs in
somebody else's pull request.

On a Mac the useful half still works: render, then open the contact sheet
and look. Comparing is what needs the matching platform, not rendering.
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
from baseline_compare import compare  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
REFERENCES = ROOT / "src/lib/stories/baseline"
BASELINE_PLATFORM = "linux"
RECORD = "./scripts/snapshot-stories.sh --record"

# Empty, and it stays empty on purpose.
#
# iOS excludes seven frames and Android eight, because there a racing `.task`
# or a running animation produces a genuinely different picture each time.
# The web has no frame like that: Playwright can be told to hold still, with
# `reducedMotion`, `document.fonts.ready`, a blanket `animation: none` and a
# wait on `storyFinished`.
#
# It does have a smaller problem. Two runs of all 83 were byte-identical;
# four runs were not — three frames disagreed, by 7 to 25 pixels, every one
# of them on a rounded corner. **Two runs agreeing is not evidence of
# determinism**, which is the same thing the iOS list learned by rendering
# five times.
#
# The tolerance is 2 because 2 is what was measured. It was set to 1 first,
# on the strength of the handful of deltas that had been printed, and changed
# nothing at all — the three frames still disagreed. The largest channel
# delta across every differing pixel in all six pairings is exactly 2.
#
# That is answered with `TOLERANCE` rather than three exclusions, because the
# cause is Chromium's antialiasing of curves and not those three stories: the
# next run would spoil a different corner, and the list would grow until the
# gate covered nothing. See `pixels_differ` for what ±1 can and cannot hide.
UNSTABLE = set()
TOLERANCE = 2


def main(rendered_dir: str) -> int:
    rendered = pathlib.Path(rendered_dir)
    marker = rendered / "PLATFORM"
    where = marker.read_text().strip() if marker.exists() else "unknown"
    if where != BASELINE_PLATFORM:
        print(f"SKIP: the baseline is {BASELINE_PLATFORM} pixels and these "
              f"were rendered on {where}.")
        print("Chromium does not rasterize text identically across platforms,")
        print("so comparing them reports a difference in FreeType as a change")
        print("in the design. Look at the frames instead:")
        print(f"  open {rendered / 'index.html'}")
        return 0
    return compare(REFERENCES, rendered, UNSTABLE, "stories", RECORD, TOLERANCE)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else str(ROOT / ".snapshots-web")))
