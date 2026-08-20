"""Redraw `assets/logo.svg`.

The mark is *drawn*, not traced. The artwork it came from is a 420px raster
whose bubble edges are soft gradients rather than outlines, so tracing it
reproduced the JPEG's noise at every size — visibly, along the seam where the
two bubbles cross. Both bubbles measure as ellipses to within a pixel or two,
so here they are ellipses, and the mark is clean at any size.

Every number below was measured off that artwork; the colours were sampled
from it. Run this to change the shape, then `assets/build-icon.py` to
regenerate the app icons from the result.
"""

import pathlib

# Measured off the source artwork (see notes in mark.svg). Both bubbles are
# ellipses to within a pixel or two, which is why this is drawn rather than
# traced: a 420px JPEG has no crisp edge to trace, and an ellipse does.
BLUE  = dict(cx=140.0, cy=124.0, rx=131.0, ry=104.0)
CORAL = dict(cx=257.5, cy=196.5, rx=123.5, ry=97.5)

# Tails, as cubic curves that leave and re-enter the ellipse's edge so the two
# read as one shape. Filled with the same user-space gradient as the body, so
# the seam is invisible.
BLUE_TAIL  = "M 78 200 C 66 226 52 236 42 252 C 30 272 56 282 82 270 C 118 254 152 230 176 208 Z"
CORAL_TAIL = "M 316 270 C 330 294 344 304 354 320 C 366 340 340 350 314 338 C 278 322 244 298 220 276 Z"

GRADS = [
    ("smBlue",  (9, 20, 271, 264),    "#CBE1FB", "#9BC0FA"),
    ("smCoral", (134, 99, 381, 329),  "#FFA98A", "#FF8489"),
    ("smOver",  (134, 99, 271, 264),  "#C0BCD2", "#CFACBD"),
]

def defs():
    out = []
    for gid, (x0, y0, x1, y1), c0, c1 in GRADS:
        out.append(
            f'    <linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
            f'x1="{x0}" y1="{y0}" x2="{x1}" y2="{y1}">\n'
            f'      <stop offset="0" stop-color="{c0}"/>\n'
            f'      <stop offset="1" stop-color="{c1}"/>\n'
            f"    </linearGradient>")
    return "\n".join(out)

def bubble(e, tail, fill):
    return (f'    <ellipse cx="{e["cx"]}" cy="{e["cy"]}" rx="{e["rx"]}" ry="{e["ry"]}" fill="{fill}"/>\n'
            f'    <path d="{tail}" fill="{fill}"/>')

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 390 340" width="390" height="340" role="img" aria-label="Supermessage">
  <title>Supermessage</title>
  <desc>Two overlapping speech bubbles.</desc>
  <!--
    Drawn, not traced. The source artwork is a 420px raster whose bubble edges
    are soft gradients rather than outlines, so tracing it reproduced the
    JPEG's noise at every size. Both bubbles measure as ellipses to within a
    pixel or two, so they are ellipses here, and each tail is a cubic that
    leaves and re-enters its ellipse's edge.

    Body and tail are two shapes sharing one `userSpaceOnUse` gradient, which
    is what makes them read as a single object: an `objectBoundingBox`
    gradient would restart at each shape and the seam would show.
  -->
  <defs>
{defs()}
    <clipPath id="smCoralShape">
      <ellipse cx="{CORAL['cx']}" cy="{CORAL['cy']}" rx="{CORAL['rx']}" ry="{CORAL['ry']}"/>
      <path d="{CORAL_TAIL}"/>
    </clipPath>
  </defs>
{bubble(BLUE, BLUE_TAIL, "url(#smBlue)")}
{bubble(CORAL, CORAL_TAIL, "url(#smCoral)")}
  <!--
    Where they cross. Not a third traced shape: the blue bubble clipped to the
    coral one is the same region bounded by the two real curves, so every edge
    in the mark belongs to one of the two bubbles.
  -->
  <g clip-path="url(#smCoralShape)">
{bubble(BLUE, BLUE_TAIL, "url(#smOver)")}
  </g>
</svg>
'''
pathlib.Path("mark.svg").write_text(svg)
print("wrote mark.svg")
