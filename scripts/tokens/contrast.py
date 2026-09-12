"""Contrast maths for the token generator.

Two instruments, deliberately. `contrast_ratio` is WCAG's, and it is right
for every text pair in the palette. `luminance_drop` is for the scrim, where
WCAG is the wrong instrument: its +0.05 flare term swamps luminances that
small and reports 1.10:1 for a region that has visibly lost more than half
its light.
"""

import re

_RGBA = re.compile(r"rgba?\(\s*(\d+)\s+(\d+)\s+(\d+)\s*/\s*([0-9.]+)\s*\)")


def _channel(value: int) -> float:
    c = value / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _rgb(hex_color: str) -> tuple[int, int, int]:
    h = hex_color.lstrip("#")
    if len(h) != 6:
        raise ValueError(f"expected #rrggbb, got {hex_color!r}")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def relative_luminance(hex_color: str) -> float:
    r, g, b = _rgb(hex_color)
    return 0.2126 * _channel(r) + 0.7152 * _channel(g) + 0.0722 * _channel(b)


def contrast_ratio(a: str, b: str) -> float:
    la, lb = relative_luminance(a), relative_luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def parse_rgba(css: str) -> tuple[int, int, int, float]:
    m = _RGBA.fullmatch(css.strip())
    if not m:
        raise ValueError(f"expected 'rgb(r g b / a)', got {css!r}")
    return int(m[1]), int(m[2]), int(m[3]), float(m[4])


def composite(base_hex: str, over_rgba: tuple[int, int, int, float]) -> str:
    """Alpha-composite `over` onto `base` in sRGB, the way a browser does."""
    br, bg, bb = _rgb(base_hex)
    orr, og, ob, alpha = over_rgba
    out = (
        round(orr * alpha + br * (1 - alpha)),
        round(og * alpha + bg * (1 - alpha)),
        round(ob * alpha + bb * (1 - alpha)),
    )
    return "#%02x%02x%02x" % out


def luminance_drop(base_hex: str, scrim_css: str) -> float:
    """How many times darker one flat colour becomes under the scrim.

    A building block for `region_luminance_drop`, and not the right check
    for a scrim on its own — see that function for why.
    """
    veiled = composite(base_hex, parse_rgba(scrim_css))
    veiled_luminance = relative_luminance(veiled)
    if veiled_luminance == 0:
        return float("inf")
    return relative_luminance(base_hex) / veiled_luminance


#: What fraction of a veiled region is text rather than ground. A roster is
#: mostly ground. The exact figure barely matters — see the test that pins
#: how little the verdict moves across 5%–40%.
TEXT_FRACTION = 0.15


def region_luminance_drop(
    ground_hex: str,
    content_hex: str,
    scrim_css: str,
    text_fraction: float = TEXT_FRACTION,
) -> float:
    """How much light a veiled *region* loses — the spec's own instrument.

    Measuring one flat colour is not enough, and each theme fails that
    check in the opposite direction:

    - The light scrim IS `content` (the ramp's dark end used as a wash), so
      it drops the ground 3.02x and the text 1.00x.
    - The dark scrim IS `surface-sunken` (the ramp's floor), so it drops
      the text 9.53x and the ground 1.00x.

    Either single-surface measurement therefore reports "paints nothing"
    for a scrim that works fine. A region is ground plus the text on it,
    which is what the spec measured: "the mean luminance of the whole
    roster region, screenshot composited to canvas, panel shut vs
    scrimmed."
    """
    scrim = parse_rgba(scrim_css)
    ground, text = 1 - text_fraction, text_fraction

    before = ground * relative_luminance(ground_hex) + text * relative_luminance(
        content_hex
    )
    after = ground * relative_luminance(
        composite(ground_hex, scrim)
    ) + text * relative_luminance(composite(content_hex, scrim))

    return float("inf") if after == 0 else before / after
