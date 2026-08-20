"""Render the App Icon variants from the mark.

Three grounds, because iOS 18 asks for three: Paper for light, Slate for dark,
and a transparent one for the tinted variant, which the system recolours
itself and therefore must carry no ground of its own.
"""
import pathlib, subprocess

OUT = pathlib.Path(__file__).resolve().parents[1] / "apple/Supermessage/Assets.xcassets/AppIcon.appiconset"

MARK = pathlib.Path(__file__).with_name("logo.svg").read_text()
# The mark's own box, from `build.py`.
VB_W, VB_H = 390.0, 340.0
CANVAS = 1024
# Apple's guidance is to keep artwork clear of the squircle's corners. 76% of
# the canvas leaves a comfortable margin and matches how the system's own
# icons sit.
SCALE = CANVAS * 0.76 / VB_W
W, H = VB_W * SCALE, VB_H * SCALE
X, Y = (CANVAS - W) / 2, (CANVAS - H) / 2

inner = MARK.split(">", 1)[1].rsplit("</svg>", 1)[0]

def wrap(ground: str | None) -> str:
    bg = f'<rect width="{CANVAS}" height="{CANVAS}" fill="{ground}"/>' if ground else ""
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS}" height="{CANVAS}" '
        f'viewBox="0 0 {CANVAS} {CANVAS}">{bg}'
        f'<g transform="translate({X:.2f},{Y:.2f}) scale({SCALE:.5f})">{inner}</g></svg>')

for name, ground in (("light", "#F6F4EF"), ("dark", "#171B22"), ("tinted", None)):
    src = pathlib.Path(__file__).with_name(f".icon-{name}.svg")
    src.write_text(wrap(ground))
    out = str(OUT / f"icon-{name}.png")
    subprocess.run(["rsvg-convert", "-w", str(CANVAS), "-h", str(CANVAS), str(src), "-o", out],
                   check=True)
    # An App Store icon must have no alpha channel; the tinted one keeps its
    # transparency, which is the whole point of that variant.
    if ground:
        subprocess.run(["magick", out, "-background", ground, "-alpha", "remove",
                        "-alpha", "off", out], check=True)
    src.unlink()
    print("wrote", out)
