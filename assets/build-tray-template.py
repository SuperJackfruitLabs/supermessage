"""Render the macOS menu-bar (template) glyph from the mark.

The menu bar recolours a template image by its alpha alone, so this is the mark as black on
transparent: the blue bubble solid, the coral bubble solid with a clear gap cut around the
blue one (without the gap the two merge into one blob). The glyph sits bottom-left and leaves
the top-right corner free for the Needs you dot (`src-tauri/src/tray.rs`, `dot_icon`).

Needs `rsvg-convert` (librsvg); not run in CI. Rerun by hand after the mark changes:

    python3 assets/build-tray-template.py

Writes src-tauri/tray/template.png (32x32) and template@2x.png (64x64).
"""
import pathlib
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from scripts.tokens.emit_mark import (  # noqa: E402
    BLUE,
    BLUE_TAIL,
    CORAL,
    CORAL_TAIL,
    VIEW_H,
    VIEW_W,
    VIEW_Y,
)

#: The glyph's width as a fraction of the canvas, and the gap around the blue bubble in the
#: mark's own units.
WIDTH = 27 / 32
GAP = 36


def ellipse(shape: dict, extra: str) -> str:
    return (f'<ellipse cx="{shape["cx"]}" cy="{shape["cy"]}" rx="{shape["rx"]}" '
            f'ry="{shape["ry"]}" {extra}/>')


def svg(canvas: int) -> str:
    scale = canvas * WIDTH / VIEW_W
    # Bottom-left, with one canvas-32nd of margin.
    x = canvas / 32
    y = canvas - canvas / 32 - VIEW_H * scale - VIEW_Y * scale
    cut = f'fill="black" stroke="black" stroke-width="{GAP}" stroke-linejoin="round"'
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{canvas}" height="{canvas}" '
        f'viewBox="0 0 {canvas} {canvas}"><defs>'
        f'<mask id="gap" maskUnits="userSpaceOnUse" x="-100" y="-100" width="700" height="700">'
        f'<rect x="-100" y="-100" width="700" height="700" fill="white"/>'
        f'{ellipse(BLUE, cut)}<path d="{BLUE_TAIL}" {cut}/></mask></defs>'
        f'<g transform="translate({x:.3f},{y:.3f}) scale({scale:.5f})" fill="black">'
        f'{ellipse(BLUE, "")}<path d="{BLUE_TAIL}"/>'
        f'<g mask="url(#gap)">{ellipse(CORAL, "")}<path d="{CORAL_TAIL}"/></g>'
        f'</g></svg>')


OUT = REPO / "src-tauri" / "tray"
OUT.mkdir(exist_ok=True)
for canvas, name in ((32, "template.png"), (64, "template@2x.png")):
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as fh:
        fh.write(svg(canvas))
    subprocess.run(["rsvg-convert", "-w", str(canvas), "-h", str(canvas), fh.name,
                    "-o", str(OUT / name)], check=True)
    pathlib.Path(fh.name).unlink()
    print("wrote", OUT / name)
