"""Render every raster icon from the mark.

`scripts/generate-tokens.py` writes the mark itself (`assets/logo.svg`) and
everything that can stay vector. This renders the rest, which needs
`librsvg` and ImageMagick (`brew install librsvg imagemagick`) and so cannot
run in CI's token job — rerun it by hand after the mark or a ground changes:

    python3 assets/build-icon.py

- iOS: the three AppIcon variants iOS 18 asks for. Paper for light, ink for
  dark, and a transparent one for the tinted variant, which the system
  recolours itself and therefore must carry no ground of its own.
- Desktop: a 1024px source handed to `tauri icon`, which writes every size
  and container (`.icns`, `.ico`, the Windows Store squares) Tauri bundles.
- Web: the 180px `apple-touch-icon.png` Safari and iOS home screens ask for,
  since neither accepts an SVG there.

Grounds are read from design/tokens.toml's [brand.ground], which names a
role rather than holding a value — the previous version of this script
carried #F6F4EF and #171B22 as literals, and they outlived the palette they
came from.
"""
import pathlib
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from scripts.tokens.emit_mark import VIEW_H as VB_H  # noqa: E402
from scripts.tokens.emit_mark import VIEW_W as VB_W  # noqa: E402
from scripts.tokens.emit_mark import VIEW_Y as VB_Y  # noqa: E402
from scripts.tokens.model import load  # noqa: E402

GROUND = load(REPO / "design" / "tokens.toml").brand.ground
MARK = (REPO / "assets" / "logo.svg").read_text()
INNER = MARK.split(">", 1)[1].rsplit("</svg>", 1)[0]


def svg(canvas: int, mark_width: float, ground: str | None, plate: float = 1.0,
        radius: float = 0.0) -> str:
    """The mark centred on a canvas.

    `plate` is the fraction of the canvas the ground covers. iOS and a home
    screen mask the corners themselves and want a full-bleed square; macOS
    draws nothing, so its icon carries its own rounded plate with the
    platform's standard margin around it.
    """
    scale = canvas * mark_width / VB_W
    x, y = (canvas - VB_W * scale) / 2, (canvas - VB_H * scale) / 2 - VB_Y * scale
    bg = ""
    if ground:
        size = canvas * plate
        inset = (canvas - size) / 2
        bg = (f'<rect x="{inset:.2f}" y="{inset:.2f}" width="{size:.2f}" height="{size:.2f}" '
              f'rx="{radius:.2f}" fill="{ground}"/>')
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{canvas}" height="{canvas}" '
        f'viewBox="0 0 {canvas} {canvas}">{bg}'
        f'<g transform="translate({x:.2f},{y:.2f}) scale({scale:.5f})">{INNER}</g></svg>')


def render(source: str, out: pathlib.Path, canvas: int, flatten: str | None = None) -> None:
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as fh:
        fh.write(source)
    subprocess.run(["rsvg-convert", "-w", str(canvas), "-h", str(canvas), fh.name,
                    "-o", str(out)], check=True)
    pathlib.Path(fh.name).unlink()
    # An App Store icon must have no alpha channel.
    if flatten:
        subprocess.run(["magick", str(out), "-background", flatten, "-alpha", "remove",
                        "-alpha", "off", str(out)], check=True)
    print("wrote", out)


# ── iOS ──────────────────────────────────────────────────────────────────────
# Apple's guidance is to keep artwork clear of the squircle's corners. 76% of
# the canvas leaves a comfortable margin and matches how the system's own
# icons sit.
APPICON = REPO / "apple/Supermessage/Assets.xcassets/AppIcon.appiconset"
for name, ground in (("light", GROUND["paper"]), ("dark", GROUND["ink"]), ("tinted", None)):
    render(svg(1024, 0.76, ground), APPICON / f"icon-{name}.png", 1024, flatten=ground)

# ── Web ──────────────────────────────────────────────────────────────────────
render(svg(180, 0.76, GROUND["ink"]), REPO / "landing/public/apple-touch-icon.png", 180,
       flatten=GROUND["ink"])

# ── Desktop ──────────────────────────────────────────────────────────────────
# macOS's icon grid: an 824px plate on a 1024px canvas with a ~185px corner
# radius. Windows and Linux show the same file, and a rounded plate reads
# correctly on both.
#
# Into a scratch directory, then copied over only the files src-tauri/icons
# already has. Left to its defaults `tauri icon` also writes an iOS set and
# rewrites the mipmaps under src-tauri/gen/android — the Tauri mobile
# scaffold, which ships nothing: the phones run the native apps.
ICONS = REPO / "src-tauri/icons"
with tempfile.TemporaryDirectory() as tmp:
    source = pathlib.Path(tmp) / "desktop.png"
    out = pathlib.Path(tmp) / "icons"
    render(svg(1024, 0.62, GROUND["ink"], plate=824 / 1024, radius=185), source, 1024)
    subprocess.run(["pnpm", "tauri", "icon", str(source), "--output", str(out)], cwd=REPO,
                   check=True)
    for existing in sorted(ICONS.iterdir()):
        existing.write_bytes((out / existing.name).read_bytes())
        print("wrote", existing)
