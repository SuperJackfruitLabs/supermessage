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
    """How many times darker `base` becomes once the scrim is over it.

    A proxy for the spec's measurement, which was the mean luminance of a
    whole composited screenshot region. This checks the wash against the
    ground alone; the screenshot check is a separate, manual verification.
    """
    veiled = composite(base_hex, parse_rgba(scrim_css))
    veiled_luminance = relative_luminance(veiled)
    if veiled_luminance == 0:
        return float("inf")
    return relative_luminance(base_hex) / veiled_luminance
