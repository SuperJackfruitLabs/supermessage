# Design Language Tokens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace three hand-maintained, mutually inconsistent themes with one TOML source that generates all five surfaces and refuses to emit a palette that fails its own contrast contracts.

**Architecture:** `design/tokens.toml` holds sixteen colour roles × three appearances, plus type, radius, elevation, motion and layout scales. `scripts/tokens/` is an importable Python package — a contrast module, a load-and-validate model, and one emitter per target. `scripts/generate-tokens.py` is the CLI. Generated files are committed; CI regenerates and fails on `git diff --quiet`, matching the two UniFFI binding jobs already in `ci.yml`.

**Tech Stack:** Python 3.14 stdlib only (`tomllib`, `unittest`) — no new dependency on any platform. Targets are Tailwind v4 CSS, SwiftUI, Jetpack Compose, and two Astro sites.

**Spec:** `docs/superpowers/specs/2026-09-12-design-language-tokens-design.md`

## Global Constraints

- **Python 3.14, stdlib only.** No pip dependency may be added. `tomllib` and `unittest` are the tools.
- **Sixteen colour roles, in every appearance:** `surface`, `surface-sunken`, `surface-raised`, `border`, `border-strong`, `content`, `content-muted`, `content-faint`, `accent`, `accent-content`, `accent-soft`, `signal`, `signal-soft`, `danger`, `ok`, `scrim`. A role missing from any appearance is a generator failure, never a fallback.
- **Three appearances:** `light`, `dark`, `paper`. Desktop and web bind `light` ↔ `dark`; iOS and Android bind `paper` ↔ `dark`. No appearance picker.
- **Contrast floors, asserted before any file is written:** `content-faint` ≥ 4.5 against `surface`, `surface-sunken` *and* `surface-raised`; `accent-content` ≥ 4.5 on `accent`; `signal` ≥ 6.0 on `signal-soft`; `danger` and `ok` ≥ 4.5 on `surface`.
- **Depth band:** `surface : surface-sunken` contrast must fall within 1.05–1.13.
- **Scrim is checked as a luminance drop, never a WCAG ratio.** A ratio's `+0.05` flare term reports 1.10:1 for a region that visibly lost half its light.
- **Dynamic Type must not be flattened.** Type roles emit a rem size for web and a platform *text style* for iOS and Android. Emitting a fixed point size on native is a defect.
- **Generated files carry their rationale.** TOML comments are emitted as comments in each target.
- **Amber (`signal`) means a pending decision and nothing else.** Any other use is a review defect.
- **A test that has never failed is not a regression test.** Every assertion added by this plan must be mutation-proven: break the input, watch the test fail, restore, record what was seen.

### Palette, verbatim — the single source of truth for Task 2

| Role | light | dark | paper |
|---|---|---|---|
| `surface` | `#fdfcff` | `#1f1838` | `#faf8f3` |
| `surface-sunken` | `#f3f2f9` | `#151129` | `#f2eee6` |
| `surface-raised` | `#fafafd` | `#251f41` | `#f7f6f1` |
| `border` | `#e0deea` | `#383254` | `#e1dbcd` |
| `border-strong` | `#c3bfd4` | `#534a75` | `#c5bea8` |
| `content` | `#221c38` | `#f4f2fb` | `#22192e` |
| `content-muted` | `#50496d` | `#c6c2d8` | `#51475e` |
| `content-faint` | `#70688f` | `#8e86ab` | `#726681` |
| `accent` | `#5b43d4` | `#9d8ff0` | `#5b43d4` |
| `accent-content` | `#ffffff` | `#1a1433` | `#ffffff` |
| `accent-soft` | `#ebe7fb` | `#2b224f` | `#ebe7fb` |
| `signal` | `#814904` | `#e8a33d` | `#814904` |
| `signal-soft` | `#fcf3e4` | `#332919` | `#f7edda` |
| `danger` | `#b4222a` | `#ed6e74` | `#b4222a` |
| `ok` | `#1d7c59` | `#70cdab` | `#1d7c59` |
| `scrim` | `rgb(34 28 56 / 0.45)` | `rgb(21 17 41 / 0.70)` | `rgb(34 25 46 / 0.45)` |

### What P1 does NOT do

Named here because a reader will otherwise assume it does.

- **Native surface adoption is out of scope.** iOS defines `ground`/`sunken`/`hairline` and has **zero** call sites for them — it paints with SwiftUI defaults and `Color.secondary`. Android routes **90** colour reads through `MaterialTheme.colorScheme` and only 5 through its own roles. P1 generates and rewires the native theme *files*, and updates the roles that are genuinely used (`accent`, `signal`, `danger`, `ok` — 36 sites on iOS, 5 on Android). Making the native apps actually paint their grounds with the ramp is **P6**, specced separately.
- Component extraction, Storybook, native previews — P2.
- Icons — P3. Native accessibility — P4. i18n — P5.
- No screen is redesigned. P1 changes which colours and measurements existing screens use.

---

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `design/tokens.toml` | The source. Values, rationale comments, contrast contracts. |
| `scripts/tokens/__init__.py` | Package marker. Empty. |
| `scripts/tokens/contrast.py` | Luminance, WCAG ratio, alpha compositing, luminance drop. Pure maths, no I/O. |
| `scripts/tokens/model.py` | Load TOML, validate role completeness and every contract, expose a typed `Tokens` object. Raises; never warns. |
| `scripts/tokens/emit_css.py` | The three CSS targets (app, landing, docs). |
| `scripts/tokens/emit_swift.py` | `ThemeTokens.swift`. |
| `scripts/tokens/emit_kotlin.py` | `GeneratedThemeTokens.kt`. |
| `scripts/generate-tokens.py` | CLI entry. Hyphenated to match `build-icon.py`; the package is underscored so it can be imported by tests. |
| `scripts/tests/test_contrast.py` | Contrast maths against known values. |
| `scripts/tests/test_model.py` | Validation, including every mutation. |
| `scripts/tests/test_emit.py` | Golden-file comparison per target. |
| `scripts/tests/golden/` | Five expected outputs. |
| `src/lib/tokens.css` | **Generated.** Tailwind v4 `@theme` + appearance blocks. |
| `apple/Supermessage/Generated/ThemeTokens.swift` | **Generated.** |
| `android/app/src/main/kotlin/dev/supermessage/GeneratedThemeTokens.kt` | **Generated.** |
| `landing/src/styles/tokens.css` | **Generated.** |
| `docs-site/src/styles/tokens.css` | **Generated.** |
| `docs/design-language.md` | The rules. Supersedes the console-design spec as authority. |

**Modified:**

| Path | Change |
|---|---|
| `src/app.css` | Import `tokens.css`; drop the `@theme` colour block; keep base layer, safe areas, `user-select`, the iOS 16px rule. |
| `apple/Supermessage/Theme.swift` | Consume `ThemeTokens`; keep `ThemedFace`, `metaFace()`, `nameFace()`; rename roles at 36 call sites. |
| `android/.../Theme.kt` | Consume `GeneratedThemeTokens`; keep the composable, CompositionLocals, Material bridge. |
| `landing/src/pages/index.astro` | Import the generated tokens; delete the inline `:root`; rename `--ink`/`--violet`/`--amber` etc. to role names. |
| `docs-site/src/styles/theme.css` | Import the generated tokens; keep the `html:root` specificity trick and the Starlight mapping. |
| `src/routes/+page.svelte` | Replace ten `1238` literals and `1293` with generated tokens. |
| `.github/workflows/ci.yml` | New `tokens` job + paths filter. |
| `apple/project.yml` | Register `Supermessage/Generated`. |

---

## Task 1: The contrast module

**Files:**
- Create: `scripts/tokens/__init__.py`, `scripts/tokens/contrast.py`
- Test: `scripts/tests/test_contrast.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `relative_luminance(hex: str) -> float`, `contrast_ratio(a: str, b: str) -> float`, `parse_rgba(css: str) -> tuple[int, int, int, float]`, `composite(base_hex: str, over_rgba: tuple[int,int,int,float]) -> str`, `luminance_drop(base_hex: str, scrim_css: str) -> float`. All hex inputs are `#rrggbb`, lowercase.

- [ ] **Step 1: Write the failing test**

`scripts/tests/test_contrast.py`:

```python
import unittest
from scripts.tokens.contrast import (
    relative_luminance, contrast_ratio, parse_rgba, composite, luminance_drop,
)


class ContrastTests(unittest.TestCase):
    def test_luminance_of_the_endpoints(self):
        self.assertAlmostEqual(relative_luminance("#ffffff"), 1.0, places=6)
        self.assertAlmostEqual(relative_luminance("#000000"), 0.0, places=6)

    def test_black_on_white_is_the_maximum_ratio(self):
        self.assertAlmostEqual(contrast_ratio("#000000", "#ffffff"), 21.0, places=2)

    def test_ratio_is_symmetric(self):
        self.assertAlmostEqual(
            contrast_ratio("#221c38", "#fdfcff"),
            contrast_ratio("#fdfcff", "#221c38"),
            places=9,
        )

    def test_a_colour_against_itself_is_one(self):
        self.assertAlmostEqual(contrast_ratio("#5b43d4", "#5b43d4"), 1.0, places=9)

    def test_the_palettes_own_light_ramp(self):
        # From the spec's §4.1 table. If these drift the spec is wrong,
        # not the maths.
        self.assertAlmostEqual(contrast_ratio("#221c38", "#fdfcff"), 15.91, places=1)
        self.assertAlmostEqual(contrast_ratio("#50496d", "#fdfcff"), 8.17, places=1)
        self.assertAlmostEqual(contrast_ratio("#70688f", "#fdfcff"), 5.05, places=1)

    def test_parse_rgba(self):
        self.assertEqual(parse_rgba("rgb(34 28 56 / 0.45)"), (34, 28, 56, 0.45))

    def test_composite_at_full_alpha_is_the_overlay(self):
        self.assertEqual(composite("#ffffff", (34, 28, 56, 1.0)), "#221c38")

    def test_composite_at_zero_alpha_is_the_base(self):
        self.assertEqual(composite("#f3f2f9", (34, 28, 56, 0.0)), "#f3f2f9")

    def test_luminance_drop_is_not_a_wcag_ratio(self):
        # The whole reason this function exists. The light scrim over the
        # roster drops its light by ~3x; a WCAG ratio reports ~1.5 for the
        # same pair and would pass a scrim that paints almost nothing.
        drop = luminance_drop("#f3f2f9", "rgb(34 28 56 / 0.45)")
        self.assertGreater(drop, 3.0)
        veiled = composite("#f3f2f9", parse_rgba("rgb(34 28 56 / 0.45)"))
        self.assertLess(contrast_ratio("#f3f2f9", veiled), 3.0)

    def test_a_scrim_that_paints_nothing_shows_a_drop_of_one(self):
        self.assertAlmostEqual(
            luminance_drop("#f3f2f9", "rgb(243 242 249 / 0.45)"), 1.0, places=6
        )


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd /Users/rakeshgangwar/Projects/supermessage && python3 -m unittest scripts.tests.test_contrast -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'scripts.tokens'`

- [ ] **Step 3: Write the minimal implementation**

Create empty `scripts/__init__.py`, `scripts/tokens/__init__.py`, `scripts/tests/__init__.py`.

`scripts/tokens/contrast.py`:

```python
"""Contrast maths for the token generator.

Two instruments, deliberately. `contrast_ratio` is WCAG's, and it is right
for every text pair in the palette. `luminance_drop` is for the scrim, where
WCAG is the wrong instrument: its +0.05 flare term swamps luminances that
small and reports 1.10:1 for a region that has visibly lost more than half
its light.
"""

import re

_RGBA = re.compile(
    r"rgba?\(\s*(\d+)\s+(\d+)\s+(\d+)\s*/\s*([0-9.]+)\s*\)"
)


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
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `python3 -m unittest scripts.tests.test_contrast -v`
Expected: PASS, 10 tests.

- [ ] **Step 5: Mutation-prove the two that matter**

This repo has shipped four tests that passed against broken implementations. Prove these two do not.

1. In `relative_luminance`, change `0.7152` to `0.2126`. Run the tests.
   Expected: `test_the_palettes_own_light_ramp` and `test_black_on_white_is_the_maximum_ratio` FAIL. Restore.
2. In `luminance_drop`, return `contrast_ratio(base_hex, veiled)` instead.
   Run the tests. Expected: `test_luminance_drop_is_not_a_wcag_ratio` FAILS —
   the WCAG ratio for that pair is under 3.0. Restore.

Record both observations in the commit message. If either mutation does **not** fail, the test is worthless — fix the test before continuing.

- [ ] **Step 6: Commit**

```bash
git add scripts/__init__.py scripts/tokens/ scripts/tests/
git commit -m "tokens: contrast maths, with the scrim's own instrument

WCAG's ratio is right for every text pair and wrong for the scrim — its
+0.05 flare term reports 1.10:1 for a region that visibly lost half its
light. So there are two functions, and the test asserts they disagree.

Mutation-proven: swapping the green coefficient in relative_luminance
fails the ramp test; returning a WCAG ratio from luminance_drop fails
the scrim test."
```

---

## Task 2: The token source and its validator

**Files:**
- Create: `design/tokens.toml`, `scripts/tokens/model.py`
- Test: `scripts/tests/test_model.py`

**Interfaces:**
- Consumes: `scripts.tokens.contrast` from Task 1.
- Produces: `load(path: Path) -> Tokens`, raising `TokenError` on any violation. `Tokens` exposes `.appearances: dict[str, Appearance]`, and each `Appearance` exposes `.colors: dict[str, str]` (role → value) and `.comments: dict[str, str]` (role → rationale, `""` when absent). `ROLES: tuple[str, ...]` is the canonical sixteen, in emission order.

- [ ] **Step 1: Write `design/tokens.toml`**

Colour only in this task; type and the other scales arrive in Tasks 7 and 8. Values are copied from the Global Constraints table above — do not re-derive them.

```toml
# The design language, as one source.
#
# Generated into five targets by scripts/generate-tokens.py. Do not edit any
# generated file; edit this one and regenerate. CI fails on drift.
#
# Every colour carries the contrast contract it must satisfy, and the
# generator asserts all of them before writing a byte. That is what makes the
# accessibility floor a build failure rather than a comment — which is what
# it was, in three separate themes, until 2026-09-12.
#
# Three literals are inherited from the marketing site so the product and the
# site are recognisably one thing: #151129 (ink), #9d8ff0 (violet), #e8a33d
# (amber). Every other value is solved to a contrast target.

[meta]
roles = [
  "surface", "surface-sunken", "surface-raised",
  "border", "border-strong",
  "content", "content-muted", "content-faint",
  "accent", "accent-content", "accent-soft",
  "signal", "signal-soft",
  "danger", "ok", "scrim",
]
depth_band = { min = 1.05, max = 1.13 }

# ---------------------------------------------------------------- light ----
# Desktop and web, when the OS asks for light.

[appearance.light.color]
surface = { value = "#fdfcff" }
surface-sunken = { value = "#f3f2f9", contrast = [
  { against = "surface", min = 1.05, max = 1.13 },
] }
surface-raised = { value = "#fafafd" }
border = { value = "#e0deea" }
border-strong = { value = "#c3bfd4" }
content = { value = "#221c38", contrast = [{ against = "surface", min = 12.0 }] }
content-muted = { value = "#50496d", contrast = [{ against = "surface", min = 7.0 }] }

# The bottom rung is the whole ramp's constraint. It must clear the floor on
# all three grounds, not just the reading surface — the earlier cool-slate
# attempt passed on `surface` and measured 2.92:1 on `surface-sunken`.
content-faint = { value = "#70688f", contrast = [
  { against = "surface", min = 4.5 },
  { against = "surface-sunken", min = 4.5 },
  { against = "surface-raised", min = 4.5 },
] }

accent = { value = "#5b43d4" }
accent-content = { value = "#ffffff", contrast = [{ against = "accent", min = 4.5 }] }
accent-soft = { value = "#ebe7fb", contrast = [{ against = "content", min = 4.5 }] }

# Amber, and it means exactly one thing: the reader owes someone an answer.
# 6.0 rather than 4.5 because it carries the 10px AWAITING YOUR DECISION
# label — the smallest text in the app, on the one element that must not be
# missed.
signal = { value = "#814904", contrast = [{ against = "signal-soft", min = 6.0 }] }
signal-soft = { value = "#fcf3e4" }

danger = { value = "#b4222a", contrast = [{ against = "surface", min = 4.5 }] }
ok = { value = "#1d7c59", contrast = [{ against = "surface", min = 4.5 }] }

# Not a fifth hue and not a new rank — the ramp's dark end used as a wash.
# Checked as a luminance drop, never a ratio.
scrim = { value = "rgb(34 28 56 / 0.45)", drop = { over = "surface-sunken", min = 3.0 } }

# ----------------------------------------------------------------- dark ----
# Every platform, when the OS asks for dark. Anchored on the landing page's
# own ink so arriving from the site does not feel like leaving it.

[appearance.dark.color]
surface = { value = "#1f1838" }
surface-sunken = { value = "#151129", contrast = [
  { against = "surface", min = 1.05, max = 1.13 },
] }
surface-raised = { value = "#251f41" }
border = { value = "#383254" }
border-strong = { value = "#534a75" }
content = { value = "#f4f2fb", contrast = [{ against = "surface", min = 12.0 }] }
content-muted = { value = "#c6c2d8", contrast = [{ against = "surface", min = 7.0 }] }

# In dark the worst ground is `surface-raised`, not `surface-sunken` — the
# ramp runs the other way. Listing all three is what makes that automatic
# rather than something a reader has to notice.
content-faint = { value = "#8e86ab", contrast = [
  { against = "surface", min = 4.5 },
  { against = "surface-sunken", min = 4.5 },
  { against = "surface-raised", min = 4.5 },
] }

accent = { value = "#9d8ff0" }
accent-content = { value = "#1a1433", contrast = [{ against = "accent", min = 4.5 }] }
accent-soft = { value = "#2b224f", contrast = [{ against = "content", min = 4.5 }] }
signal = { value = "#e8a33d", contrast = [{ against = "signal-soft", min = 6.0 }] }
signal-soft = { value = "#332919" }
danger = { value = "#ed6e74", contrast = [{ against = "surface", min = 4.5 }] }
ok = { value = "#70cdab", contrast = [{ against = "surface", min = 4.5 }] }

# A dark theme scrims by going darker still, at a higher alpha, because the
# ground is already near the ramp's floor. It cannot reach light's 3.2x and
# should not try: matching it exactly would take an alpha near 0.95, which
# erases the region rather than veiling it.
scrim = { value = "rgb(21 17 41 / 0.70)", drop = { over = "surface-sunken", min = 2.0 } }

# ---------------------------------------------------------------- paper ----
# What "light" means on a phone. Same roles, same accent, warm ground.

[appearance.paper.color]
surface = { value = "#faf8f3" }
surface-sunken = { value = "#f2eee6", contrast = [
  { against = "surface", min = 1.05, max = 1.13 },
] }
surface-raised = { value = "#f7f6f1" }
border = { value = "#e1dbcd" }
border-strong = { value = "#c5bea8" }
content = { value = "#22192e", contrast = [{ against = "surface", min = 12.0 }] }
content-muted = { value = "#51475e", contrast = [{ against = "surface", min = 7.0 }] }
content-faint = { value = "#726681", contrast = [
  { against = "surface", min = 4.5 },
  { against = "surface-sunken", min = 4.5 },
  { against = "surface-raised", min = 4.5 },
] }
accent = { value = "#5b43d4" }
accent-content = { value = "#ffffff", contrast = [{ against = "accent", min = 4.5 }] }
accent-soft = { value = "#ebe7fb", contrast = [{ against = "content", min = 4.5 }] }
signal = { value = "#814904", contrast = [{ against = "signal-soft", min = 6.0 }] }
signal-soft = { value = "#f7edda" }
danger = { value = "#b4222a", contrast = [{ against = "surface", min = 4.5 }] }
ok = { value = "#1d7c59", contrast = [{ against = "surface", min = 4.5 }] }
scrim = { value = "rgb(34 25 46 / 0.45)", drop = { over = "surface-sunken", min = 3.0 } }
```

- [ ] **Step 2: Write the failing test**

`scripts/tests/test_model.py`:

```python
import copy
import tomllib
import unittest
from pathlib import Path

from scripts.tokens.model import ROLES, TokenError, Tokens, load, validate

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "design" / "tokens.toml"


def raw() -> dict:
    with SOURCE.open("rb") as fh:
        return tomllib.load(fh)


class RealSourceTests(unittest.TestCase):
    def test_the_committed_source_validates(self):
        tokens = load(SOURCE)
        self.assertIsInstance(tokens, Tokens)

    def test_all_three_appearances_are_present(self):
        self.assertEqual(
            sorted(load(SOURCE).appearances), ["dark", "light", "paper"]
        )

    def test_every_appearance_defines_every_role(self):
        for name, appearance in load(SOURCE).appearances.items():
            with self.subTest(appearance=name):
                self.assertEqual(sorted(appearance.colors), sorted(ROLES))

    def test_rationale_comments_are_captured(self):
        # The comment above `content-faint` in light is the reason the value
        # is what it is. If it does not survive the load it cannot survive
        # into the generated files, which is half the point of TOML.
        light = load(SOURCE).appearances["light"]
        self.assertIn("2.92", light.comments["content-faint"])


class ValidationTests(unittest.TestCase):
    """Each of these breaks the source on purpose and demands a failure."""

    def test_a_missing_role_is_an_error(self):
        broken = copy.deepcopy(raw())
        del broken["appearance"]["paper"]["color"]["ok"]
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("paper", str(caught.exception))
        self.assertIn("ok", str(caught.exception))

    def test_a_faint_that_fails_on_one_ground_is_an_error(self):
        # Lightened until it still clears `surface` but not `surface-sunken`
        # — precisely the failure the cool-slate ramp shipped with.
        broken = copy.deepcopy(raw())
        broken["appearance"]["light"]["color"]["content-faint"]["value"] = "#7d76a0"
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("content-faint", str(caught.exception))
        self.assertIn("surface-sunken", str(caught.exception))

    def test_a_signal_at_only_the_text_floor_is_an_error(self):
        # 4.8:1 on its soft ground — legal for body text, not enough for a
        # 10px label on the one element that must not be missed.
        broken = copy.deepcopy(raw())
        broken["appearance"]["light"]["color"]["signal"]["value"] = "#9c5c0a"
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("signal", str(caught.exception))

    def test_a_collapsed_depth_step_is_an_error(self):
        broken = copy.deepcopy(raw())
        broken["appearance"]["dark"]["color"]["surface-sunken"]["value"] = "#1e1736"
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("surface-sunken", str(caught.exception))

    def test_a_scrim_that_paints_nothing_is_an_error(self):
        # Sunken over sunken: 1.0 by construction, and a WCAG check would
        # not catch it either.
        broken = copy.deepcopy(raw())
        broken["appearance"]["light"]["color"]["scrim"]["value"] = (
            "rgb(243 242 249 / 0.45)"
        )
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("scrim", str(caught.exception))

    def test_an_unknown_role_is_an_error(self):
        broken = copy.deepcopy(raw())
        broken["appearance"]["light"]["color"]["brand"] = {"value": "#ff0000"}
        with self.assertRaises(TokenError):
            validate(broken)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `python3 -m unittest scripts.tests.test_model -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'scripts.tokens.model'`

- [ ] **Step 4: Write the implementation**

`scripts/tokens/model.py`:

```python
"""Load design/tokens.toml, and refuse to return anything invalid.

`validate` raises on the first violation with a message naming the
appearance, the role and the contract it broke. There is no warning level
and no fallback: a role missing from one appearance is exactly how three
themes drifted into disagreement, and a fallback would have hidden it.
"""

import re
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

from .contrast import contrast_ratio, luminance_drop

ROLES: tuple[str, ...] = (
    "surface", "surface-sunken", "surface-raised",
    "border", "border-strong",
    "content", "content-muted", "content-faint",
    "accent", "accent-content", "accent-soft",
    "signal", "signal-soft",
    "danger", "ok", "scrim",
)


class TokenError(Exception):
    """A contract in design/tokens.toml is not satisfied."""


@dataclass(frozen=True)
class Appearance:
    name: str
    colors: dict[str, str]
    comments: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class Tokens:
    appearances: dict[str, Appearance]


def _is_scrim(value: str) -> bool:
    return value.startswith("rgb")


def validate(data: dict) -> None:
    appearances = data.get("appearance", {})
    if not appearances:
        raise TokenError("no [appearance.*] tables found")

    for name, table in appearances.items():
        colors = {role: spec["value"] for role, spec in table["color"].items()}

        missing = set(ROLES) - set(colors)
        if missing:
            raise TokenError(
                f"appearance '{name}' is missing role(s): "
                f"{', '.join(sorted(missing))}. A role absent from any "
                f"appearance is an error, never a fallback."
            )
        unknown = set(colors) - set(ROLES)
        if unknown:
            raise TokenError(
                f"appearance '{name}' declares unknown role(s): "
                f"{', '.join(sorted(unknown))}"
            )

        for role, spec in table["color"].items():
            for rule in spec.get("contrast", []):
                against = rule["against"]
                actual = contrast_ratio(colors[role], colors[against])
                low, high = rule.get("min"), rule.get("max")
                if low is not None and actual < low:
                    raise TokenError(
                        f"{name}.{role} ({colors[role]}) is {actual:.2f}:1 "
                        f"against {against} ({colors[against]}); "
                        f"the contract requires at least {low}"
                    )
                if high is not None and actual > high:
                    raise TokenError(
                        f"{name}.{role} ({colors[role]}) is {actual:.3f}:1 "
                        f"against {against}; the contract requires at most "
                        f"{high}"
                    )

            drop_rule = spec.get("drop")
            if drop_rule:
                ground = colors[drop_rule["over"]]
                actual = luminance_drop(ground, colors[role])
                if actual < drop_rule["min"]:
                    raise TokenError(
                        f"{name}.{role} veils {drop_rule['over']} by only "
                        f"{actual:.2f}x; the contract requires "
                        f"{drop_rule['min']}x. (Checked as a luminance drop, "
                        f"not a WCAG ratio — see contrast.luminance_drop.)"
                    )


def _comments(path: Path) -> dict[str, dict[str, str]]:
    """Collect the comment block immediately above each role assignment.

    Read from the text rather than from tomllib, which discards comments.
    The rationale is the most valuable content in the file and has to reach
    the generated targets.
    """
    out: dict[str, dict[str, str]] = {}
    appearance: str | None = None
    buffer: list[str] = []
    header = re.compile(r"^\[appearance\.([a-z]+)\.color\]")
    assignment = re.compile(r"^([a-z-]+)\s*=")

    for line in path.read_text().splitlines():
        stripped = line.strip()
        if match := header.match(stripped):
            appearance = match[1]
            out[appearance] = {}
            buffer = []
        elif stripped.startswith("#"):
            buffer.append(stripped.lstrip("# ").rstrip())
        elif match := assignment.match(stripped):
            if appearance is not None:
                out[appearance][match[1]] = " ".join(buffer)
            buffer = []
        elif not stripped:
            buffer = []
    return out


def load(path: Path) -> Tokens:
    with path.open("rb") as fh:
        data = tomllib.load(fh)
    validate(data)
    comments = _comments(path)
    return Tokens(
        appearances={
            name: Appearance(
                name=name,
                colors={
                    role: table["color"][role]["value"] for role in ROLES
                },
                comments=comments.get(name, {}),
            )
            for name, table in data["appearance"].items()
        }
    )
```

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `python3 -m unittest scripts.tests.test_model -v`
Expected: PASS, 10 tests. If `test_a_faint_that_fails_on_one_ground_is_an_error` does *not* raise, check `#7d76a0` really is above 4.5 on `#fdfcff` and below it on `#f3f2f9` — adjust the fixture until it is, because the test is worthless otherwise.

- [ ] **Step 6: Mutation-prove the validator**

Each `ValidationTests` case already breaks the source; now prove the *validator* is what catches them, not luck.

1. In `validate`, delete the `missing` check. Run.
   Expected: `test_a_missing_role_is_an_error` FAILS. Restore.
2. In `validate`, change `if low is not None and actual < low` to `actual < 0`.
   Run. Expected: the faint, signal and depth tests all FAIL. Restore.
3. In `validate`, delete the `drop_rule` block. Run.
   Expected: `test_a_scrim_that_paints_nothing_is_an_error` FAILS. Restore.

Record all three in the commit.

- [ ] **Step 7: Commit**

```bash
git add design/tokens.toml scripts/tokens/model.py scripts/tests/test_model.py
git commit -m "tokens: the source, and a validator that refuses to return

Sixteen roles x three appearances, with each colour carrying the contrast
contract it must satisfy. Validation raises on the first violation naming
the appearance, the role and the contract — there is no warning level,
because a warning is what three drifting themes already had.

The five deliberate breakages in the tests are the real palette failures
this project exists to prevent: a role missing from one appearance, a
faint that clears the reading surface and fails the roster behind it, an
amber at the text floor rather than the label floor, a collapsed depth
step, and a scrim that paints nothing.

Mutation-proven: removing the completeness check, the min comparison, or
the drop check each fails its own test."
```

---

## Task 3: The app CSS emitter

**Files:**
- Create: `scripts/tokens/emit_css.py`, `scripts/generate-tokens.py`, `scripts/tests/golden/app-tokens.css`
- Generate: `src/lib/tokens.css`
- Modify: `src/app.css`
- Test: `scripts/tests/test_emit.py`

**Interfaces:**
- Consumes: `Tokens`, `Appearance`, `ROLES` from Task 2.
- Produces: `emit_app_css(tokens: Tokens) -> str`. Role `surface-sunken` becomes CSS custom property `--color-surface-sunken`; every role is prefixed `--color-`. Later tasks add `emit_landing_css` and `emit_docs_css` to the same module.

- [ ] **Step 1: Write the failing test**

Append to `scripts/tests/test_emit.py` (create it):

```python
import unittest
from pathlib import Path

from scripts.tokens.emit_css import emit_app_css
from scripts.tokens.model import ROLES, load

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "design" / "tokens.toml"
GOLDEN = Path(__file__).parent / "golden"


class AppCssTests(unittest.TestCase):
    def setUp(self):
        self.css = emit_app_css(load(SOURCE))

    def test_it_says_it_is_generated(self):
        self.assertIn("Generated by scripts/generate-tokens.py", self.css)
        self.assertIn("do not edit", self.css.lower())

    def test_light_is_the_bare_root_and_defines_every_role(self):
        root = self.css.split("@media")[0]
        for role in ROLES:
            with self.subTest(role=role):
                self.assertIn(f"--color-{role}:", root)

    def test_no_colour_is_defined_only_inside_a_media_query(self):
        # A colour whose only definition is in the dark block leaves the
        # light theme with nothing. This is the rule app.css already
        # follows and the one a generator can enforce for free.
        root = self.css.split("@media")[0]
        for role in ROLES:
            with self.subTest(role=role):
                self.assertIn(f"--color-{role}:", root)

    def test_dark_is_a_prefers_color_scheme_block(self):
        self.assertIn("@media (prefers-color-scheme: dark)", self.css)

    def test_paper_is_an_attribute_selector_not_the_default(self):
        self.assertIn('[data-appearance="paper"]', self.css)
        root = self.css.split("@media")[0].split("[data-appearance")[0]
        self.assertNotIn("#faf8f3", root)

    def test_rationale_reaches_the_output(self):
        self.assertIn("2.92", self.css)

    def test_it_matches_the_golden_file(self):
        expected = (GOLDEN / "app-tokens.css").read_text()
        self.assertEqual(self.css, expected)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `python3 -m unittest scripts.tests.test_emit -v`
Expected: FAIL — `No module named 'scripts.tokens.emit_css'`

- [ ] **Step 3: Write the emitter**

`scripts/tokens/emit_css.py`:

```python
"""CSS emitters. Three targets share this module because they share a
syntax, not because they share a file.
"""

import textwrap

from .model import ROLES, Appearance, Tokens

HEADER = """\
/*
 * Generated by scripts/generate-tokens.py from design/tokens.toml.
 * Do not edit — edit the source and regenerate. CI fails on drift.
 */
"""


def _comment(text: str, indent: str) -> str:
    if not text:
        return ""
    wrapped = textwrap.wrap(text, width=72 - len(indent))
    body = "\n".join(f"{indent} * {line}" for line in wrapped)
    return f"{indent}/*\n{body}\n{indent} */\n"


def _block(appearance: Appearance, indent: str) -> str:
    out = []
    for role in ROLES:
        out.append(_comment(appearance.comments.get(role, ""), indent))
        out.append(f"{indent}--color-{role}: {appearance.colors[role]};\n")
    return "".join(out)


def emit_app_css(tokens: Tokens) -> str:
    light = tokens.appearances["light"]
    dark = tokens.appearances["dark"]
    paper = tokens.appearances["paper"]

    return (
        HEADER
        + "\n@theme {\n"
        + _block(light, "  ")
        + "}\n"
        + "\n@layer theme {\n"
        + "  @media (prefers-color-scheme: dark) {\n"
        + "    :root {\n"
        + _block(dark, "      ")
        + "    }\n  }\n"
        + '\n  /* Paper is what "light" means on a phone. Nothing selects it\n'
        + "   * on desktop; the binding lives in each native theme. */\n"
        + '  [data-appearance="paper"] {\n'
        + _block(paper, "    ")
        + "  }\n"
        + "}\n"
    )
```

`scripts/generate-tokens.py`:

```python
#!/usr/bin/env python3
"""Generate every design-token target from design/tokens.toml.

Run from the repo root. Writes nothing if validation fails, so a broken
palette cannot half-land across five surfaces.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.tokens.emit_css import emit_app_css  # noqa: E402
from scripts.tokens.model import TokenError, load  # noqa: E402

REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / "design" / "tokens.toml"


def main() -> int:
    try:
        tokens = load(SOURCE)
    except TokenError as error:
        print(f"design/tokens.toml is invalid:\n  {error}", file=sys.stderr)
        return 1

    outputs = {REPO / "src" / "lib" / "tokens.css": emit_app_css(tokens)}

    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        print(f"wrote {path.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: Generate, then snapshot as the golden file**

```bash
chmod +x scripts/generate-tokens.py
python3 scripts/generate-tokens.py
cp src/lib/tokens.css scripts/tests/golden/app-tokens.css
```

Read `src/lib/tokens.css` before accepting it. The golden file is only as good as this one reading — snapshotting output nobody looked at is how a generator's bug becomes permanently "expected".

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `python3 -m unittest scripts.tests.test_emit -v`
Expected: PASS, 7 tests.

- [ ] **Step 6: Wire `app.css` to the generated file**

In `src/app.css`, after the `@import "tailwindcss";` line, add:

```css
@import "./lib/tokens.css";
```

Then delete from `app.css`: the whole `@theme { ... }` colour block (the `--color-*` declarations only — **keep** `--font-*` and the `--text-*` type scale, which Task 7 moves), and the entire `@layer theme { @media (prefers-color-scheme: dark) { ... } }` block. Keep `@layer base`, the safe-area insets, the `user-select` discipline, the motion budget, `:focus-visible`, and the `@media (pointer: coarse)` 16px rule.

- [ ] **Step 7: Verify the app still builds and renders**

```bash
pnpm check
pnpm build
pnpm test
```

Expected: all clean. `pnpm build` failing with an unresolved `@import` means the path is wrong relative to `src/app.css`.

- [ ] **Step 8: Commit**

```bash
git add scripts/tokens/emit_css.py scripts/generate-tokens.py \
        scripts/tests/test_emit.py scripts/tests/golden/app-tokens.css \
        src/lib/tokens.css src/app.css
git commit -m "tokens: generate the app's CSS, and delete the hand-kept copy

app.css keeps everything that is behaviour — safe areas, user-select
discipline, the motion budget, the iOS 16px rule — and stops holding
colours. The generated file carries the rationale comments with it, so
the reason content-faint is what it is survives into the target.

The emitter enforces one rule for free that was previously a convention:
no colour is defined only inside a media query."
```

---

## Task 4: The Swift emitter

**Files:**
- Create: `scripts/tokens/emit_swift.py`, `scripts/tests/golden/ThemeTokens.swift`
- Generate: `apple/Supermessage/Generated/ThemeTokens.swift`
- Modify: `apple/Supermessage/Theme.swift`, `apple/project.yml`, 36 call sites
- Test: `scripts/tests/test_emit.py`, `apple/SupermessageKitTests/` (no change), a new `ThemeTokensTests`

**Interfaces:**
- Consumes: `Tokens` from Task 2.
- Produces: `emit_swift(tokens: Tokens) -> str`. Emits `enum ThemeTokens` with `static let light: Palette`, `.dark`, `.paper`, where `Palette` is a struct of sixteen `Color`-producing members named in lowerCamelCase: `surface`, `surfaceSunken`, `surfaceRaised`, `border`, `borderStrong`, `content`, `contentMuted`, `contentFaint`, `accent`, `accentContent`, `accentSoft`, `signal`, `signalSoft`, `danger`, `ok`, `scrim`.

- [ ] **Step 1: Write the failing test**

Append to `scripts/tests/test_emit.py`:

```python
from scripts.tokens.emit_swift import emit_swift


class SwiftTests(unittest.TestCase):
    def setUp(self):
        self.swift = emit_swift(load(SOURCE))

    def test_it_says_it_is_generated(self):
        self.assertIn("Generated by scripts/generate-tokens.py", self.swift)

    def test_all_three_palettes_exist(self):
        for name in ("light", "dark", "paper"):
            with self.subTest(name=name):
                self.assertIn(f"static let {name} = Palette(", self.swift)

    def test_roles_are_lower_camel_case(self):
        self.assertIn("surfaceSunken", self.swift)
        self.assertNotIn("surface-sunken", self.swift.split("//")[0])

    def test_no_fixed_point_sizes_leak_in(self):
        # Dynamic Type is the thing a naive generator destroys here.
        self.assertNotIn("Font.system(size:", self.swift)

    def test_the_scrim_carries_its_alpha(self):
        self.assertIn("opacity: 0.45", self.swift)
        self.assertIn("opacity: 0.7", self.swift)

    def test_it_matches_the_golden_file(self):
        expected = (GOLDEN / "ThemeTokens.swift").read_text()
        self.assertEqual(self.swift, expected)
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `python3 -m unittest scripts.tests.test_emit -v`
Expected: FAIL — `No module named 'scripts.tokens.emit_swift'`

- [ ] **Step 3: Write the emitter**

`scripts/tokens/emit_swift.py`:

```python
"""SwiftUI emitter.

Colours only. The faces stay in Theme.swift because they are text *styles*
(`Font.system(.body, design: .serif)`), not sizes — emitting a point size
here would silently cost every iOS user Dynamic Type.
"""

import textwrap

from .contrast import parse_rgba
from .model import ROLES, Appearance, Tokens

HEADER = """\
// Generated by scripts/generate-tokens.py from design/tokens.toml.
// Do not edit — edit the source and regenerate. CI fails on drift.

import SwiftUI
"""


def _camel(role: str) -> str:
    head, *rest = role.split("-")
    return head + "".join(part.capitalize() for part in rest)


def _srgb(hex_color: str) -> tuple[float, float, float]:
    h = hex_color.lstrip("#")
    return tuple(round(int(h[i:i + 2], 16) / 255, 4) for i in (0, 2, 4))


def _literal(value: str) -> str:
    if value.startswith("rgb"):
        r, g, b, alpha = parse_rgba(value)
        return (
            f"Color(.sRGB, red: {r / 255:.4f}, green: {g / 255:.4f}, "
            f"blue: {b / 255:.4f}, opacity: {alpha})"
        )
    r, g, b = _srgb(value)
    return f"Color(.sRGB, red: {r}, green: {g}, blue: {b}, opacity: 1)"


def _comment(text: str, indent: str) -> str:
    if not text:
        return ""
    return "".join(
        f"{indent}/// {line}\n" for line in textwrap.wrap(text, width=72)
    )


def _palette(appearance: Appearance) -> str:
    lines = [f"    static let {appearance.name} = Palette(\n"]
    for index, role in enumerate(ROLES):
        comma = "," if index < len(ROLES) - 1 else ""
        lines.append(f"        {_camel(role)}: {_literal(appearance.colors[role])}{comma}\n")
    lines.append("    )\n")
    return "".join(lines)


def emit_swift(tokens: Tokens) -> str:
    members = "".join(
        _comment(tokens.appearances["light"].comments.get(role, ""), "    ")
        + f"    let {_camel(role)}: Color\n"
        for role in ROLES
    )
    return (
        HEADER
        + "\n/// The sixteen colour roles, one value each.\n"
        + "struct Palette {\n"
        + members
        + "}\n"
        + "\n/// The three appearances. iOS binds `paper` to light and\n"
        + "/// `dark` to dark — see Theme.swift.\n"
        + "enum ThemeTokens {\n"
        + _palette(tokens.appearances["light"])
        + "\n"
        + _palette(tokens.appearances["dark"])
        + "\n"
        + _palette(tokens.appearances["paper"])
        + "}\n"
    )
```

Extend `scripts/generate-tokens.py`'s `outputs` dict:

```python
        REPO / "apple" / "Supermessage" / "Generated" / "ThemeTokens.swift":
            emit_swift(tokens),
```

and add `from scripts.tokens.emit_swift import emit_swift` to the imports.

- [ ] **Step 4: Generate and snapshot**

```bash
python3 scripts/generate-tokens.py
cp apple/Supermessage/Generated/ThemeTokens.swift scripts/tests/golden/ThemeTokens.swift
```

Read the generated Swift before accepting it.

- [ ] **Step 5: Run the emitter tests**

Run: `python3 -m unittest scripts.tests.test_emit -v`
Expected: PASS, 13 tests.

- [ ] **Step 6: Rewire `Theme.swift` onto the generated palette**

Replace the colour half of `Theme.swift`. **Keep** `ThemedFace`, `metaFace()`, `nameFace()`, `Theme.body`, `Theme.own`, `Theme.meta(dark:)`, `Theme.name(dark:)`, `Theme.code` and every doc comment on them — that modifier exists because static properties reading `UITraitCollection.current` left stale typography on screen until relaunch, and it is not regenerable.

```swift
/// The palette, resolved per appearance.
///
/// iOS binds **paper** to light and **dark** to dark: paper is what "light"
/// means on a phone. There is no picker, and `dynamic` needs to know
/// nothing beyond `userInterfaceStyle`.
private static func dynamic(_ member: KeyPath<Palette, Color>) -> Color {
    Color(
        UIColor { traits in
            let palette = traits.userInterfaceStyle == .dark
                ? ThemeTokens.dark
                : ThemeTokens.paper
            return UIColor(palette[keyPath: member])
        })
}

static let surface = dynamic(\.surface)
static let surfaceSunken = dynamic(\.surfaceSunken)
static let surfaceRaised = dynamic(\.surfaceRaised)
static let border = dynamic(\.border)
static let borderStrong = dynamic(\.borderStrong)
static let content = dynamic(\.content)
static let contentMuted = dynamic(\.contentMuted)
static let contentFaint = dynamic(\.contentFaint)
static let accent = dynamic(\.accent)
static let accentContent = dynamic(\.accentContent)
static let accentSoft = dynamic(\.accentSoft)
static let signal = dynamic(\.signal)
static let signalSoft = dynamic(\.signalSoft)
static let danger = dynamic(\.danger)
static let ok = dynamic(\.ok)
static let scrim = dynamic(\.scrim)
```

Delete the old `ground`, `sunken` and `hairline` definitions — they have zero call sites, confirmed by `grep -rn "Theme.ground\|Theme.sunken\|Theme.hairline" apple/`. Do not add call sites for the new surface roles in this task; that is P6.

- [ ] **Step 7: Register the generated directory with XcodeGen**

In `apple/project.yml`, add `Supermessage/Generated` to the `Supermessage` target's sources, then:

```bash
cd apple && xcodegen generate && cd ..
```

- [ ] **Step 8: Update the 36 call sites and build**

`Theme.accent` (18), `Theme.signal` (10), `Theme.danger` (7) and `Theme.ok` (1) keep their names, so they need no edit. Confirm with:

```bash
grep -rn "Theme\.\(ground\|sunken\|hairline\)" apple/
```

Expected: no output.

```bash
xcodebuild test -project apple/Supermessage.xcodeproj -scheme SupermessageKit \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
xcodebuild build -project apple/Supermessage.xcodeproj -scheme Supermessage \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
```

Expected: both succeed.

- [ ] **Step 9: Mutation-prove the Dynamic Type guard**

In `emit_swift.py`, add a line emitting `Font.system(size: 15)` into the output. Run `python3 -m unittest scripts.tests.test_emit -v`.
Expected: `test_no_fixed_point_sizes_leak_in` FAILS. Remove the line. This is the assertion protecting every iOS user's text scaling; it has to be shown to work.

- [ ] **Step 10: Commit**

```bash
git add scripts/tokens/emit_swift.py scripts/generate-tokens.py \
        scripts/tests/test_emit.py scripts/tests/golden/ThemeTokens.swift \
        apple/Supermessage/Generated/ThemeTokens.swift \
        apple/Supermessage/Theme.swift apple/project.yml \
        apple/Supermessage.xcodeproj
git commit -m "tokens: generate the iOS palette, and bind paper to light

Theme.swift keeps everything that is behaviour: ThemedFace exists because
static properties reading UITraitCollection.current left stale typography
on screen until relaunch, and that is not regenerable.

ground, sunken and hairline are deleted rather than renamed. They had
zero call sites — iOS paints its surfaces with SwiftUI defaults, which is
P6's problem, not this commit's. Pretending otherwise by renaming them
would have hidden that.

Mutation-proven: emitting a fixed point size fails the Dynamic Type test."
```

---

## Task 5: The Kotlin emitter

**Files:**
- Create: `scripts/tokens/emit_kotlin.py`, `scripts/tests/golden/GeneratedThemeTokens.kt`
- Generate: `android/app/src/main/kotlin/dev/supermessage/GeneratedThemeTokens.kt`
- Modify: `android/app/src/main/kotlin/dev/supermessage/Theme.kt`
- Test: `scripts/tests/test_emit.py`

**Interfaces:**
- Consumes: `Tokens` from Task 2.
- Produces: `emit_kotlin(tokens: Tokens) -> str`. Emits `object GeneratedThemeTokens` with `val light: SupermessageColorRoles`, `.dark`, `.paper`. `SupermessageColorRoles` is redefined in the generated file with all sixteen roles in lowerCamelCase, matching Task 4's names exactly.

- [ ] **Step 1: Write the failing test**

Append to `scripts/tests/test_emit.py`:

```python
from scripts.tokens.emit_kotlin import emit_kotlin


class KotlinTests(unittest.TestCase):
    def setUp(self):
        self.kt = emit_kotlin(load(SOURCE))

    def test_it_says_it_is_generated(self):
        self.assertIn("Generated by scripts/generate-tokens.py", self.kt)

    def test_all_three_palettes_exist(self):
        for name in ("light", "dark", "paper"):
            with self.subTest(name=name):
                self.assertIn(f"val {name} = SupermessageColorRoles(", self.kt)

    def test_colours_are_argb_longs(self):
        self.assertIn("Color(0xFFFDFCFF)", self.kt)

    def test_the_scrim_carries_its_alpha_in_the_argb(self):
        # 0.45 -> 0x73. A scrim emitted as 0xFF is opaque and hides the
        # roster instead of veiling it.
        self.assertIn("0x73221C38", self.kt.replace("Color(", "").upper())

    def test_role_names_match_the_swift_emitter(self):
        from scripts.tokens.emit_swift import emit_swift
        swift = emit_swift(load(SOURCE))
        for role in ROLES:
            camel = role.split("-")[0] + "".join(
                p.capitalize() for p in role.split("-")[1:]
            )
            with self.subTest(role=role):
                self.assertIn(camel, self.kt)
                self.assertIn(camel, swift)

    def test_it_matches_the_golden_file(self):
        expected = (GOLDEN / "GeneratedThemeTokens.kt").read_text()
        self.assertEqual(self.kt, expected)
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `python3 -m unittest scripts.tests.test_emit -v`
Expected: FAIL — `No module named 'scripts.tokens.emit_kotlin'`

- [ ] **Step 3: Write the emitter**

`scripts/tokens/emit_kotlin.py`:

```python
"""Jetpack Compose emitter.

Colours only. Faces and text styles stay in Theme.kt, for the same reason
they stay in Theme.swift: emitting `sp` values here would cost Android
users their font-size setting.
"""

import textwrap

from .contrast import parse_rgba
from .model import ROLES, Appearance, Tokens

HEADER = """\
// Generated by scripts/generate-tokens.py from design/tokens.toml.
// Do not edit — edit the source and regenerate. CI fails on drift.

package dev.supermessage

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
"""


def _camel(role: str) -> str:
    head, *rest = role.split("-")
    return head + "".join(part.capitalize() for part in rest)


def _argb(value: str) -> str:
    if value.startswith("rgb"):
        r, g, b, alpha = parse_rgba(value)
        a = round(alpha * 255)
    else:
        h = value.lstrip("#")
        r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
        a = 255
    return f"Color(0x{a:02X}{r:02X}{g:02X}{b:02X})"


def _comment(text: str, indent: str) -> str:
    if not text:
        return ""
    body = "\n".join(f"{indent} * {line}" for line in textwrap.wrap(text, 70))
    return f"{indent}/**\n{body}\n{indent} */\n"


def _palette(appearance: Appearance) -> str:
    lines = [f"    val {appearance.name} = SupermessageColorRoles(\n"]
    for role in ROLES:
        lines.append(f"        {_camel(role)} = {_argb(appearance.colors[role])},\n")
    lines.append("    )\n")
    return "".join(lines)


def emit_kotlin(tokens: Tokens) -> str:
    light = tokens.appearances["light"]
    members = "".join(
        _comment(light.comments.get(role, ""), "    ")
        + f"    val {_camel(role)}: Color,\n"
        for role in ROLES
    )
    return (
        HEADER
        + "\n/** The sixteen colour roles, one value each. */\n"
        + "@Immutable\ndata class SupermessageColorRoles(\n"
        + members
        + ")\n"
        + "\n/**\n * The three appearances. Android binds `paper` to light and\n"
        + " * `dark` to dark — see Theme.kt.\n */\n"
        + "object GeneratedThemeTokens {\n"
        + _palette(tokens.appearances["light"])
        + "\n"
        + _palette(tokens.appearances["dark"])
        + "\n"
        + _palette(tokens.appearances["paper"])
        + "}\n"
    )
```

Extend `generate-tokens.py`'s `outputs` and imports as in Task 4.

- [ ] **Step 4: Generate and snapshot**

```bash
python3 scripts/generate-tokens.py
cp android/app/src/main/kotlin/dev/supermessage/GeneratedThemeTokens.kt \
   scripts/tests/golden/GeneratedThemeTokens.kt
```

Read it before accepting it.

- [ ] **Step 5: Run the emitter tests**

Run: `python3 -m unittest scripts.tests.test_emit -v`
Expected: PASS, 19 tests.

- [ ] **Step 6: Rewire `Theme.kt`**

Delete the hand-written `SupermessageColorRoles` data class and its `companion object` with `light`/`dark` — both now come from the generated file. Keep `SupermessageTypography`, `SupermessageThemeFonts`, the two `CompositionLocal`s, the `SupermessageTheme` accessor object, and the `SupermessageTheme` composable with its Material bridge.

Change the composable's palette selection to bind paper:

```kotlin
@Composable
fun SupermessageTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // Android binds `paper` to light: paper is what "light" means on a
    // phone. The third appearance is not a user setting.
    val colors = if (darkTheme) GeneratedThemeTokens.dark else GeneratedThemeTokens.paper
```

and update the Material bridge to the new role names:

```kotlin
            primary = colors.accent,
            onPrimary = colors.accentContent,
            background = colors.surface,
            surface = colors.surface,
            surfaceVariant = colors.surfaceSunken,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.danger,
            onBackground = colors.content,
            onSurface = colors.content,
            onSurfaceVariant = colors.contentMuted,
```

`onBackground`, `onSurface` and `onSurfaceVariant` are new. They are what make the ninety `MaterialTheme.colorScheme` reads across the app resolve to the palette's text ramp instead of Material defaults — the cheapest possible down-payment on P6, and the reason the text ranks reach Android at all in P1.

- [ ] **Step 7: Update the five direct call sites and build**

`SupermessageTheme.colors.signal` (4) and `.ok` (1) keep their names. Confirm nothing references a deleted name:

```bash
cd android
./gradlew :app:compileDebugKotlin
./gradlew test :app:testDebugUnitTest
cd ..
```

Expected: compiles; all JVM tests pass. If `:kit:test` fails asking for a host core, run `cargo build -p supermessage-ffi` first — that is documented behaviour, not a regression.

- [ ] **Step 8: Mutation-prove the scrim alpha**

In `_argb`, change `a = round(alpha * 255)` to `a = 255`. Run `python3 -m unittest scripts.tests.test_emit -v`.
Expected: `test_the_scrim_carries_its_alpha_in_the_argb` FAILS. Restore. An opaque scrim hides the roster rather than veiling it, and nothing else in the suite would catch it.

- [ ] **Step 9: Commit**

```bash
git add scripts/tokens/emit_kotlin.py scripts/generate-tokens.py \
        scripts/tests/test_emit.py scripts/tests/golden/GeneratedThemeTokens.kt \
        android/app/src/main/kotlin/dev/supermessage/GeneratedThemeTokens.kt \
        android/app/src/main/kotlin/dev/supermessage/Theme.kt
git commit -m "tokens: generate the Android palette, and feed the text ramp in

The Material bridge gains onBackground, onSurface and onSurfaceVariant.
That is what makes the ninety MaterialTheme.colorScheme reads across the
app resolve to this palette's text ranks rather than Material defaults —
the cheapest down-payment on P6, and the reason the three-rank hierarchy
reaches Android at all in P1.

Mutation-proven: emitting the scrim at full alpha fails its test. An
opaque scrim hides the roster instead of veiling it and nothing else in
the suite would notice."
```

---

## Task 6: The two marketing surfaces

**Files:**
- Create: `scripts/tests/golden/landing-tokens.css`, `scripts/tests/golden/docs-tokens.css`
- Generate: `landing/src/styles/tokens.css`, `docs-site/src/styles/tokens.css`
- Modify: `scripts/tokens/emit_css.py`, `scripts/generate-tokens.py`, `landing/src/pages/index.astro`, `docs-site/src/styles/theme.css`
- Test: `scripts/tests/test_emit.py`

**Interfaces:**
- Consumes: `Tokens`.
- Produces: `emit_landing_css(tokens: Tokens) -> str` and `emit_docs_css(tokens: Tokens) -> str`. Both emit the same `--color-<role>` names as the app, so there is one vocabulary across all five surfaces. Docs emits under `html:root`, not `:root`.

- [ ] **Step 1: Write the failing test**

Append to `scripts/tests/test_emit.py`:

```python
from scripts.tokens.emit_css import emit_docs_css, emit_landing_css


class MarketingTests(unittest.TestCase):
    def test_landing_uses_the_same_role_names_as_the_app(self):
        landing = emit_landing_css(load(SOURCE))
        for role in ROLES:
            with self.subTest(role=role):
                self.assertIn(f"--color-{role}:", landing)

    def test_landing_no_longer_invents_its_own_names(self):
        landing = emit_landing_css(load(SOURCE))
        for legacy in ("--ink:", "--violet:", "--amber:", "--raised:", "--line:"):
            with self.subTest(legacy=legacy):
                self.assertNotIn(legacy, landing)

    def test_docs_uses_html_root_for_specificity(self):
        # Astro bundles this file before Starlight's props.css, so at equal
        # specificity every value here would do nothing — with a build that
        # succeeds and a page that looks untouched.
        docs = emit_docs_css(load(SOURCE))
        self.assertIn("html:root {", docs)
        self.assertNotIn("\n:root {", docs)

    def test_the_three_marketing_literals_survive(self):
        # The whole reason the product adopted this hue.
        landing = emit_landing_css(load(SOURCE))
        for literal in ("#151129", "#9d8ff0", "#e8a33d"):
            with self.subTest(literal=literal):
                self.assertIn(literal, landing)

    def test_landing_matches_the_golden_file(self):
        self.assertEqual(
            emit_landing_css(load(SOURCE)),
            (GOLDEN / "landing-tokens.css").read_text(),
        )

    def test_docs_matches_the_golden_file(self):
        self.assertEqual(
            emit_docs_css(load(SOURCE)),
            (GOLDEN / "docs-tokens.css").read_text(),
        )
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `python3 -m unittest scripts.tests.test_emit -v`
Expected: FAIL — `cannot import name 'emit_landing_css'`

- [ ] **Step 3: Write the two emitters**

Append to `scripts/tokens/emit_css.py`:

```python
def emit_landing_css(tokens: Tokens) -> str:
    """The landing page. Dark is its default; light is for a visitor whose
    system asks for it."""
    return (
        HEADER
        + "\n:root {\n"
        + _block(tokens.appearances["dark"], "  ")
        + "}\n"
        + "\n@media (prefers-color-scheme: light) {\n  :root {\n"
        + _block(tokens.appearances["light"], "    ")
        + "  }\n}\n"
    )


def emit_docs_css(tokens: Tokens) -> str:
    """The docs site.

    `html:root` rather than `:root`, and it is load-bearing: Astro bundles
    this file before Starlight's props.css, so at equal specificity every
    value here does nothing — with a build that succeeds and a page that
    looks untouched. `html:root` is (0,0,1,1) against Starlight's (0,0,1,0).
    """
    return (
        HEADER
        + "\nhtml:root {\n"
        + _block(tokens.appearances["dark"], "  ")
        + "}\n"
        + "\nhtml:root[data-theme='light'] {\n"
        + _block(tokens.appearances["light"], "  ")
        + "}\n"
    )
```

Extend `generate-tokens.py`'s `outputs` and imports.

- [ ] **Step 4: Generate and snapshot both**

```bash
python3 scripts/generate-tokens.py
cp landing/src/styles/tokens.css scripts/tests/golden/landing-tokens.css
cp docs-site/src/styles/tokens.css scripts/tests/golden/docs-tokens.css
```

Read both before accepting them.

- [ ] **Step 5: Run the emitter tests**

Run: `python3 -m unittest scripts.tests.test_emit -v`
Expected: PASS, 25 tests.

- [ ] **Step 6: Rewire the landing page**

In `landing/src/pages/index.astro`:

1. Add to the frontmatter (the `---` block at the top; create one if absent):
   ```
   import '../styles/tokens.css';
   ```
2. Delete the `:root { --ink: … --mono: … }` declarations for **colours only** (`--ink`, `--raised`, `--raised-2`, `--line`, `--text`, `--muted`, `--violet`, `--amber`). Keep `--display`, `--body`, `--mono` — type moves in Task 7.
3. Rename every usage:

   | Old | New |
   |---|---|
   | `var(--ink)` | `var(--color-surface-sunken)` |
   | `var(--raised)` | `var(--color-surface)` |
   | `var(--raised-2)` | `var(--color-surface-raised)` |
   | `var(--line)` | `var(--color-border)` |
   | `var(--text)` | `var(--color-content)` |
   | `var(--muted)` | `var(--color-content-muted)` |
   | `var(--violet)` | `var(--color-accent)` |
   | `var(--amber)` | `var(--color-signal)` |

   Also replace the bare literals `#fbfaff`, `#1a1433`, `#b2a6f6` and `#2a1c05` — the first two become `var(--color-accent-content)`, `#b2a6f6` becomes `var(--color-accent-soft)` on the hover rule, and `#2a1c05` becomes `var(--color-signal-soft)`.

4. Delete the page's own `@media (prefers-color-scheme: light)` colour overrides — the generated file now carries them.

Verify nothing was missed:

```bash
grep -n "var(--ink)\|var(--raised\|var(--line)\|var(--text)\|var(--muted)\|var(--violet)\|var(--amber)" landing/src/pages/index.astro
```

Expected: no output.

- [ ] **Step 7: Rewire the docs site**

In `docs-site/src/styles/theme.css`, add at the very top (before any rule):

```css
@import './tokens.css';
```

Then replace the hardcoded hex values in the `html:root` blocks with the generated roles, keeping the Starlight mapping and every explanatory comment:

```css
html:root {
  --sl-color-white: var(--color-content);
  --sl-color-gray-2: var(--color-content-muted);
  --sl-color-gray-3: var(--color-content-faint);
  --sl-color-gray-5: var(--color-surface-raised);
  --sl-color-gray-6: var(--color-surface);
  --sl-color-black: var(--color-surface-sunken);
  --sl-color-accent: var(--color-accent);
  --sl-color-accent-low: var(--color-accent-soft);
  --sm-amber: var(--color-signal);
}
```

Leave `--sl-color-gray-1`, `--sl-color-gray-4`, `--sl-color-gray-7` and `--sl-color-accent-high` as they are — Starlight interpolates them and the palette has no role for them.

- [ ] **Step 8: Build both sites and look at them**

```bash
cd landing && npm run build && cd ..
cd docs-site && npm run build && cd ..
```

Then `cd landing && npm run dev` and open `http://localhost:4325`. The page must look the way it does on `main` — this task changes *where the values live*, not the values. Do the same for docs on `:4324`, checking both themes with the site's own toggle.

If anything shifted, a rename was wrong. Find it before committing; a "close enough" marketing page is exactly the nearly-right the docs theme file already warns about.

- [ ] **Step 9: Commit**

```bash
git add scripts/tokens/emit_css.py scripts/generate-tokens.py \
        scripts/tests/test_emit.py scripts/tests/golden/landing-tokens.css \
        scripts/tests/golden/docs-tokens.css landing/src/styles/tokens.css \
        docs-site/src/styles/tokens.css landing/src/pages/index.astro \
        docs-site/src/styles/theme.css
git commit -m "tokens: the marketing surfaces join the same vocabulary

--ink, --violet and --amber become --color-surface-sunken,
--color-accent and --color-signal. Two names for one thing is how the
product and the site were free to drift; now there is one.

The docs emitter keeps html:root rather than :root, which is load-bearing
— Astro bundles this file before Starlight's props.css, so at equal
specificity every value would do nothing, with a build that succeeds and
a page that looks untouched.

Both sites were built and looked at. This moves where values live, not
what they are."
```

---

## Task 7: The type scale

**Files:**
- Modify: `design/tokens.toml`, `scripts/tokens/model.py`, all three emitters, `src/app.css`, `apple/Supermessage/Theme.swift`, `android/.../Theme.kt`, `landing/src/pages/index.astro`
- Test: `scripts/tests/test_model.py`, `scripts/tests/test_emit.py`

**Interfaces:**
- Consumes: `Tokens`.
- Produces: `Tokens.type: dict[str, TypeRole]`, where `TypeRole` has `.family: str` (one of `sans`, `serif`, `mono`), `.web: dict` (`size`, `line_height`, `weight`, optional `letter_spacing`), `.ios: str` (a `Font.TextStyle` case name), `.android: str` (a Material 3 type-scale name). Roles: `label`, `meta`, `ui`, `ui-lg`, `avatar`, `body`, `body-own`.

- [ ] **Step 1: Add the type table to `design/tokens.toml`**

```toml
# ----------------------------------------------------------------- type ----
# Structural, not decorative: serif is what an agent wrote, sans what the
# operator wrote, mono is data and sigils.
#
# Each role carries BOTH a web size and a native text *style*. That is not
# duplication — emitting a fixed point size on native would cost every iOS
# user Dynamic Type and every Android user their font-size setting, which
# they get for free today.

[type.label]
family = "mono"
web = { size = "0.625rem", line_height = 1.2, weight = 500, letter_spacing = "0.08em" }
ios = "caption2"
android = "labelSmall"

[type.meta]
family = "mono"
web = { size = "0.65625rem", line_height = 1.3, weight = 400 }
ios = "caption"
android = "labelMedium"

[type.ui]
family = "sans"
web = { size = "0.8125rem", line_height = 1.4 }
ios = "footnote"
android = "bodySmall"

[type.ui-lg]
family = "sans"
web = { size = "0.9375rem", line_height = 1.4, weight = 600 }
ios = "subheadline"
android = "titleSmall"

# The one rank that is not text. A 64px avatar circle needs a glyph sized to
# the circle; before this existed the room-info panel's fallback initial took
# the largest reading rank and a 64px circle held a 15px letter.
[type.avatar]
family = "sans"
web = { size = "1.5rem", line_height = 1.0, weight = 500 }
ios = "title2"
android = "headlineSmall"

[type.body]
family = "serif"
web = { size = "0.9375rem", line_height = 1.62, weight = 400 }
ios = "body"
android = "bodyLarge"

[type.body-own]
family = "sans"
web = { size = "0.875rem", line_height = 1.5, weight = 400 }
ios = "body"
android = "bodyMedium"
```

- [ ] **Step 2: Write the failing tests**

Append to `scripts/tests/test_model.py`:

```python
class TypeTests(unittest.TestCase):
    def test_every_role_has_a_family_and_all_three_expressions(self):
        for name, role in load(SOURCE).type.items():
            with self.subTest(role=name):
                self.assertIn(role.family, ("sans", "serif", "mono"))
                self.assertIn("size", role.web)
                self.assertTrue(role.ios)
                self.assertTrue(role.android)

    def test_a_native_expression_that_is_a_number_is_an_error(self):
        broken = copy.deepcopy(raw())
        broken["type"]["body"]["ios"] = 15
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("Dynamic Type", str(caught.exception))

    def test_body_is_serif_and_body_own_is_sans(self):
        # The structural rule the whole language rests on.
        types = load(SOURCE).type
        self.assertEqual(types["body"].family, "serif")
        self.assertEqual(types["body-own"].family, "sans")
```

Append to `scripts/tests/test_emit.py`:

```python
class TypeEmissionTests(unittest.TestCase):
    def test_css_emits_the_scale(self):
        css = emit_app_css(load(SOURCE))
        self.assertIn("--text-body: 0.9375rem", css)
        self.assertIn("--text-body--line-height: 1.62", css)

    def test_swift_emits_text_styles_not_sizes(self):
        from scripts.tokens.emit_swift import emit_swift
        swift = emit_swift(load(SOURCE))
        self.assertIn("Font.system(.body, design: .serif)", swift)
        self.assertNotIn("Font.system(size:", swift)

    def test_kotlin_emits_type_scale_names_not_sp(self):
        from scripts.tokens.emit_kotlin import emit_kotlin
        kt = emit_kotlin(load(SOURCE))
        self.assertIn("bodyLarge", kt)
        self.assertNotIn(".sp", kt)
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `python3 -m unittest scripts.tests.test_model scripts.tests.test_emit -v`
Expected: FAIL — `Tokens` has no attribute `type`.

- [ ] **Step 4: Implement**

In `model.py`, add:

```python
@dataclass(frozen=True)
class TypeRole:
    name: str
    family: str
    web: dict
    ios: str
    android: str


FAMILIES = ("sans", "serif", "mono")
```

Add `type: dict[str, TypeRole] = field(default_factory=dict)` to `Tokens`, populate it in `load`, and add to `validate`:

```python
    for name, spec in data.get("type", {}).items():
        if spec.get("family") not in FAMILIES:
            raise TokenError(
                f"type.{name}: family must be one of {FAMILIES}, "
                f"got {spec.get('family')!r}"
            )
        for platform in ("ios", "android"):
            if not isinstance(spec.get(platform), str):
                raise TokenError(
                    f"type.{name}.{platform} must be a text-style NAME, not "
                    f"{spec.get(platform)!r}. A number here flattens Dynamic "
                    f"Type and costs every native user their text scaling."
                )
```

In `emit_css.py`, emit each role into the `@theme` block as `--text-<role>`, `--text-<role>--line-height`, `--text-<role>--font-weight` and `--text-<role>--letter-spacing` where present, plus the three `--font-*` families copied from the existing `app.css` values.

In `emit_swift.py`, emit:

```python
_DESIGN = {"sans": "", "serif": ", design: .serif", "mono": ", design: .monospaced"}
```

and one `static let <camel> = Font.system(.<ios>{design})` per role.

In `emit_kotlin.py`, emit an object mapping each role to its Material type-scale name as a `String` constant, consumed by `Theme.kt`.

- [ ] **Step 5: Run the tests**

Run: `python3 -m unittest discover -s scripts/tests -t . -v`
Expected: PASS.

- [ ] **Step 6: Regenerate, refresh goldens, remove the hand-kept scales**

```bash
python3 scripts/generate-tokens.py
for f in app-tokens.css ThemeTokens.swift GeneratedThemeTokens.kt \
         landing-tokens.css docs-tokens.css; do
  case $f in
    app-tokens.css) src=src/lib/tokens.css ;;
    ThemeTokens.swift) src=apple/Supermessage/Generated/ThemeTokens.swift ;;
    GeneratedThemeTokens.kt) src=android/app/src/main/kotlin/dev/supermessage/GeneratedThemeTokens.kt ;;
    landing-tokens.css) src=landing/src/styles/tokens.css ;;
    docs-tokens.css) src=docs-site/src/styles/tokens.css ;;
  esac
  cp "$src" "scripts/tests/golden/$f"
done
```

Read each diff before accepting. Then delete the now-duplicated `--text-*` and `--font-*` block from `src/app.css`, and the `--display`/`--body`/`--mono` declarations from `index.astro`.

- [ ] **Step 7: Verify all four surfaces**

```bash
pnpm check && pnpm test && pnpm build
xcodebuild build -project apple/Supermessage.xcodeproj -scheme Supermessage \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
cd android && ./gradlew :app:compileDebugKotlin && cd ..
cd landing && npm run build && cd ..
cd docs-site && npm run build && cd ..
```

Expected: all clean.

- [ ] **Step 8: Mutation-prove the Dynamic Type guard in the validator**

Set `ios = 15` on `type.body` in `design/tokens.toml`. Run `python3 scripts/generate-tokens.py`.
Expected: exit 1, with a message naming `type.body.ios` and Dynamic Type, and **no file written**. Restore.

- [ ] **Step 9: Commit**

```bash
git add design/tokens.toml scripts/ src/ apple/ android/ landing/ docs-site/
git commit -m "tokens: the type scale, without flattening Dynamic Type

Each role carries a web rem size and a native text STYLE. That is the one
place a naive generator does real damage: emitting 15.0 into Theme.swift
costs every iOS user their text scaling, and it would look like it worked.

The validator rejects a number in the ios or android field, and says why.
Mutation-proven: setting type.body.ios = 15 exits 1 and writes nothing."
```

---

## Task 8: Radius, elevation, motion, and the derived breakpoints

**Files:**
- Modify: `design/tokens.toml`, `scripts/tokens/model.py`, `scripts/tokens/emit_css.py`, `scripts/tokens/emit_swift.py`, `scripts/tokens/emit_kotlin.py`
- Test: `scripts/tests/test_model.py`, `scripts/tests/test_emit.py`

**Interfaces:**
- Consumes: `Tokens`.
- Produces: `Tokens.scale: dict[str, dict]` holding `radius`, `elevation`, `motion` and `layout`. `Tokens.breakpoints: dict[str, int]`, computed — not read — from `layout`.

- [ ] **Step 1: Add the scales to `design/tokens.toml`**

```toml
# --------------------------------------------------------------- radius ----
# Named by role, not by size. `rounded-md` cannot be wrong because it says
# nothing; `radius-control` can. Values are the ones already in use, so this
# renames rather than redesigns.

[radius]
control = "6px"   # buttons, inputs, chips
card = "8px"      # the dispatch card, panels
pill = "9999px"   # avatars, badges
sharp = "0"       # full-bleed rows, dividers

# ------------------------------------------------------------ elevation ----
# Depth is the three-level surface ramp plus a hairline. Shadow means exactly
# one thing: floating over the scrim.

[elevation.overlay]
web = "0 8px 24px rgb(0 0 0 / 0.18)"
ios = { radius = 12, y = 4 }
android = "8dp"

# --------------------------------------------------------------- motion ----
# Almost none, deliberately. These three are the budget; whether a
# transition is added at all is still a design question.

[motion]
quick = "120ms"
settle = "200ms"
easing = "cubic-bezier(0.2, 0, 0, 1)"

# --------------------------------------------------------------- layout ----
# The pane widths, and the breakpoints DERIVED from them.
#
# +page.svelte carried this arithmetic in a comment and then wrote 1238 as a
# bare literal ten times. Change the roster width and the breakpoint was
# silently wrong in ten places while the comment still claimed it was
# derived. The comment is now the code.

[layout]
roster = 288
panel = 320
sheet = 630
rail = 55
```

- [ ] **Step 2: Write the failing test**

Append to `scripts/tests/test_model.py`:

```python
class LayoutTests(unittest.TestCase):
    def test_the_breakpoint_is_computed_not_read(self):
        tokens = load(SOURCE)
        layout = tokens.scale["layout"]
        self.assertEqual(
            tokens.breakpoints["panel-column"],
            layout["roster"] + layout["panel"] + layout["sheet"],
        )
        self.assertEqual(tokens.breakpoints["panel-column"], 1238)

    def test_the_rail_variant_adds_the_rail(self):
        tokens = load(SOURCE)
        self.assertEqual(
            tokens.breakpoints["panel-column-with-rail"],
            tokens.breakpoints["panel-column"] + tokens.scale["layout"]["rail"],
        )
        self.assertEqual(tokens.breakpoints["panel-column-with-rail"], 1293)

    def test_widening_the_roster_moves_the_breakpoint(self):
        # The entire point. If this passes with a hardcoded 1238 the
        # derivation is decorative.
        broken = copy.deepcopy(raw())
        broken["layout"]["roster"] = 304
        self.assertEqual(breakpoints_for(broken)["panel-column"], 1254)


class RadiusTests(unittest.TestCase):
    def test_radius_roles_are_the_four_named_ones(self):
        self.assertEqual(
            sorted(load(SOURCE).scale["radius"]),
            ["card", "control", "pill", "sharp"],
        )
```

Add `breakpoints_for` to the imports from `scripts.tokens.model`.

- [ ] **Step 3: Run it to make sure it fails**

Run: `python3 -m unittest scripts.tests.test_model -v`
Expected: FAIL — `cannot import name 'breakpoints_for'`

- [ ] **Step 4: Implement**

In `model.py`:

```python
def breakpoints_for(data: dict) -> dict[str, int]:
    """Derive the pane breakpoints from the pane widths.

    `+page.svelte` documented this arithmetic and then hardcoded its result
    ten times. Computing it is what stops the comment and the code drifting.
    """
    layout = data["layout"]
    base = layout["roster"] + layout["panel"] + layout["sheet"]
    return {
        "panel-column": base,
        "panel-column-with-rail": base + layout["rail"],
    }
```

Add `scale` and `breakpoints` to `Tokens` and populate both in `load`.

Emit into CSS as `--radius-*`, `--shadow-overlay`, `--duration-*`, `--ease-standard`, `--layout-*` and `--breakpoint-*`; into Swift as a `Metrics` enum with `CGFloat` values; into Kotlin as a `Metrics` object with `Dp` values.

- [ ] **Step 5: Run the tests**

Run: `python3 -m unittest discover -s scripts/tests -t . -v`
Expected: PASS.

- [ ] **Step 6: Regenerate and refresh goldens**

Same loop as Task 7 Step 6. Read each diff.

- [ ] **Step 7: Mutation-prove the derivation**

Change `roster = 288` to `roster = 304` in `design/tokens.toml`, regenerate, and confirm `--breakpoint-panel-column: 1254px` appears in `src/lib/tokens.css`. Restore, regenerate, confirm it reads 1238 again. If the value did not move, the derivation is decorative and the task is not done.

- [ ] **Step 8: Commit**

```bash
git add design/tokens.toml scripts/ src/lib/tokens.css \
        apple/Supermessage/Generated/ theme android
git commit -m "tokens: radius by role, one shadow, and a derived breakpoint

+page.svelte carried the arithmetic 1238 = 288 + 320 + 630 in a comment
and then wrote 1238 as a bare literal ten times. The comment is now the
code: change the roster width and the breakpoint follows.

Radius takes the values already in use, so this renames rather than
redesigns. Elevation is one token, for the one case that earns it.

Mutation-proven: widening the roster to 304 moves the emitted breakpoint
to 1254."
```

---

## Task 9: Adopt the new scales in the web app

**Files:**
- Modify: `src/routes/+page.svelte`, and every `src/lib/components/*.svelte` using `rounded-*` or `shadow-*`
- Test: `pnpm test`, `pnpm check`

**Interfaces:**
- Consumes: `--radius-*`, `--shadow-overlay`, `--breakpoint-*` from Task 8.
- Produces: nothing new.

- [ ] **Step 1: Replace the breakpoint literals**

In `src/routes/+page.svelte`, replace all ten occurrences of `1238` and both of `1293` with the generated custom properties. The `matchMedia` strings become:

```js
const PANEL_COLUMN = getComputedStyle(document.documentElement)
    .getPropertyValue("--breakpoint-panel-column").trim();
const PANEL_COLUMN_WITH_RAIL = getComputedStyle(document.documentElement)
    .getPropertyValue("--breakpoint-panel-column-with-rail").trim();
```

Keep the long explanatory comment above them verbatim — it is the reasoning, and it is now load-bearing documentation for a derived value rather than for a magic number. Add one sentence: that the number is now computed from the pane widths in `design/tokens.toml`.

- [ ] **Step 2: Run the tests to see what breaks**

Run: `pnpm test && pnpm check`
Expected: the panel-breakpoint tests in `timelinePane.test.ts` may fail if they assert the literal. If so, update them to read the same token — do **not** re-hardcode.

- [ ] **Step 3: Rename the radii**

```bash
grep -rn "rounded-md\|rounded-lg\|rounded-full" src/
```

Replace mechanically: `rounded-md` → `rounded-control`, `rounded-lg` → `rounded-card`, `rounded-full` → `rounded-pill`. Tailwind v4 picks these up from the `--radius-*` theme keys automatically.

- [ ] **Step 4: Reduce the shadows to one**

```bash
grep -rn "shadow-lg\|shadow-sm" src/
```

Every one of these is on an overlay. Replace with `shadow-overlay`. If any is *not* on an element floating over the scrim, delete it instead — depth comes from the surface ramp.

- [ ] **Step 5: Verify**

```bash
pnpm test && pnpm check && pnpm build
```

Expected: all clean, 380 frontend tests passing.

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "web: adopt the named radii, one shadow, and the derived breakpoint

Ten copies of 1238 become one token computed from the pane widths that
produce it. rounded-md becomes rounded-control and the six ad-hoc
shadows become the single overlay token — the five that were not on an
overlay are gone, because depth is the surface ramp."
```

---

## Task 10: The CI gate

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add the paths filter**

In the `Changed paths` job's `outputs`, add `tokens: ${{ steps.filter.outputs.tokens }}`. In `filters`, add:

```yaml
            tokens:
              - *workflow
              - 'design/tokens.toml'
              - 'scripts/tokens/**'
              - 'scripts/generate-tokens.py'
              - 'src/lib/tokens.css'
              - 'apple/Supermessage/Generated/ThemeTokens.swift'
              - 'android/app/src/main/kotlin/dev/supermessage/GeneratedThemeTokens.kt'
              - 'landing/src/styles/tokens.css'
              - 'docs-site/src/styles/tokens.css'
```

The generated paths are in the filter as well as the sources, so a hand-edited generated file also triggers the job that catches it.

- [ ] **Step 2: Add the job**

```yaml
  tokens:
    name: Design tokens
    needs: changes
    if: needs.changes.outputs.tokens == 'true'
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.14'

      - name: The contrast contracts hold, and the emitters agree
        run: python3 -m unittest discover -s scripts/tests -t . -v

      - name: Regenerate every target
        run: python3 scripts/generate-tokens.py

      - name: The checked-in tokens match the source that generates them
        # Five surfaces read these files and none of them would fail to
        # build against a stale one — the app would simply render last
        # week's palette, which is exactly how three themes drifted apart
        # without anybody noticing.
        run: |
          if ! git diff --quiet; then
            echo "::error::Generated design tokens are stale — run python3 scripts/generate-tokens.py and commit the result"
            git diff --stat
            exit 1
          fi
```

- [ ] **Step 3: Prove the gate catches drift**

```bash
sed -i '' 's/--color-accent: #5b43d4/--color-accent: #ff0000/' src/lib/tokens.css
python3 scripts/generate-tokens.py
git diff --quiet && echo "GATE BROKEN" || echo "gate works: drift detected"
git checkout src/lib/tokens.css
```

Expected: `gate works: drift detected`. A gate that has never caught anything is the same failure mode as a test that has never failed.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: fail on stale design tokens

Same posture as the two UniFFI binding jobs, for the same reason: five
surfaces read these files and none would fail to build against a stale
one. The app would just render last week's palette.

The generated paths are in the filter alongside the sources, so a
hand-edited generated file triggers the job that catches it.

Verified by hand-editing tokens.css and watching the diff check fire."
```

---

## Task 11: The rules document

**Files:**
- Create: `docs/design-language.md`
- Modify: `AGENTS.md`, `docs/superpowers/specs/2026-08-13-console-design.md`

- [ ] **Step 1: Write `docs/design-language.md`**

It carries what values cannot. Required sections, each stating the rule and the reason:

1. **The three faces.** Serif is what an agent wrote; sans is what the operator wrote; mono is data, sigils, timestamps and counts. Structural, not decorative — this is the identity that travels between platforms, and it is why `body` is serif and `body-own` is sans in `design/tokens.toml`.
2. **Amber means one thing.** A pending decision the reader owes an answer to. Not unread badges, not hover, not warnings, not the connection banner. Any other use is a review defect. Reach for `danger` or `accent` instead.
3. **`ok` is not amber.** A room that is working is good news.
4. **Depth is the surface ramp.** Three levels plus a hairline. Shadow means exactly one thing: floating over the scrim.
5. **Radius by role.** `control` for things you press or type into, `card` for panels and the dispatch card, `pill` for avatars and badges, `sharp` for full-bleed rows.
6. **Motion is almost none.** Two durations and one easing exist so that anything added is inside a budget, not so that things get added.
7. **Three appearances, two per platform.** Desktop binds light↔dark, mobile binds paper↔dark. Paper is what light means on a phone.
8. **How to change a value.** Edit `design/tokens.toml`, run `python3 scripts/generate-tokens.py`, commit the generated files. Never edit a generated file; CI will catch you, but the point is that the value would be wrong on four other surfaces.

- [ ] **Step 2: Mark the old spec superseded**

Add at the top of `docs/superpowers/specs/2026-08-13-console-design.md`:

```markdown
> **Superseded as authority by [`docs/design-language.md`](../../design-language.md)
> on 2026-09-12.** This document remains the record of how the language was
> arrived at, and most of the reasoning in the current one came from here.
> But it is desktop-web-shaped, and being a document three codebases read and
> re-derived by eye is the failure that `design/tokens.toml` exists to fix.
> For current values, read the TOML. For current rules, read the design
> language. For why any of it is the way it is, read on.
```

- [ ] **Step 3: Update `AGENTS.md`**

In the repository-layout block, replace the `src/app.css` line and add the new paths:

```
design/tokens.toml   — THE design source: 16 colour roles x 3 appearances, type,
                       radius, elevation, motion, layout. Generated into five
                       targets; never edit a generated file.
docs/design-language.md — the rules the tokens cannot carry
src/app.css          — behaviour only: safe areas, user-select, motion budget
src/lib/tokens.css   — generated, do not edit
```

Add to the "Build, test, and development commands" section:

```bash
python3 scripts/generate-tokens.py          # regenerate all five token targets
python3 -m unittest discover -s scripts/tests -t .   # token tests
```

- [ ] **Step 4: Commit**

```bash
git add docs/design-language.md AGENTS.md \
        docs/superpowers/specs/2026-08-13-console-design.md
git commit -m "docs: the rules the tokens cannot carry

A palette says what colours exist. It cannot say that amber means a
pending decision and nothing else, or that serif is what an agent wrote.
That is what this document is for, and it is the authority now.

The console-design spec stays as the record of how the language was
arrived at — most of the reasoning came from there. It is marked
superseded rather than deleted, and the note says why: being a document
three codebases read and re-derived by eye is the failure the TOML fixes."
```

---

## Task 12: Look at it

**Files:** none — this task produces evidence, not code.

The token maths can be entirely right and the result still wrong on screen. The current dark theme's depth bug measured correctly in isolation and failed across 1050px of field.

- [ ] **Step 1: Desktop, both appearances**

```bash
pnpm tauri:mcp
```

Drive the running webview with the Tauri MCP bridge and capture `webview_screenshot` for: the roster with a pending-decision room, a room with the dispatch card open, the room-info panel as a third column, and the panel as a scrim overlay at a narrow width. Then switch the OS to dark and repeat.

Check specifically: the sheet-on-field step is visible across the full width of the timeline; `content-faint` is readable on the roster, not only on the reading surface; the scrim veils the roster rather than erasing or barely touching it.

- [ ] **Step 2: iOS, Paper and Dark**

```bash
xcodebuild build -project apple/Supermessage.xcodeproj -scheme Supermessage \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
```

Run in the simulator, screenshot the roster and a room in both appearances. **Expect the grounds to still look like SwiftUI defaults** — that is P6, and confirming it here is the point: what should have changed is the accent, the amber and the dispatch card.

- [ ] **Step 3: Android, Paper and Dark**

```bash
./scripts/android-ci-parity.sh
cd android && ./gradlew :app:installDebug && cd ..
```

Screenshot the roster and a room in both appearances. Here the text ramp *should* have reached the app, via the `onSurface`/`onSurfaceVariant` bridge added in Task 5. If body text still looks like Material's default grey, that bridge is not wired.

- [ ] **Step 4: The two sites**

Build and open both. They must look the way they do on `main`.

- [ ] **Step 5: Record what was seen**

Write findings into the PR description: which appearance on which platform, what was checked, what was wrong. "Built successfully" is not evidence that it looks right.

- [ ] **Step 6: Commit any fixes, then open the PR**

```bash
git push -u origin spec/design-language-tokens
gh pr create --title "One palette, generated into five surfaces" \
  --body-file <the findings from Step 5>
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §2.1 one palette, three appearances | 2 |
| §2.2 sixteen roles, completeness | 2 |
| §2.3 generated not transcribed | 3–6 |
| §2.4 contrast contracts | 1, 2 |
| §2.5 paper binds to light on mobile | 4, 5 |
| §2.6 Dynamic Type not flattened | 7 |
| §4 the palette values | 2 (Global Constraints table) |
| §5.1 TOML with comments | 2 |
| §5.3 contracts, depth band, scrim drop | 1, 2 |
| §6 five targets, generated/hand-written split | 3–6 |
| §6.1 appearance binding | 4, 5 |
| §6.2 type | 7 |
| §6.3 CI | 10 |
| §7 radius, elevation, motion, spacing, layout | 8, 9 |
| §8 rules document | 11 |
| §9 verification, mutation-proving | every task; 12 for visual |
| §10 open questions | recorded, not implemented — correct |
| §11 non-goals | excluded, and named in "What P1 does NOT do" |

**Spacing** (§7, "declare the 4px scale, do not rename it") is deliberately not a task: Tailwind's scale already *is* the declaration, and adding a token that restates it would be the over-engineering the spec rules out. The constraint is recorded in `docs/design-language.md` in Task 11.

**Placeholder scan:** no TBDs, no "add error handling", no "similar to Task N". Every code step carries real code. Task 12 has no code because it produces evidence, which is stated.

**Type consistency:** `emit_app_css` / `emit_landing_css` / `emit_docs_css` / `emit_swift` / `emit_kotlin` are used with those names in Tasks 3–7 and in `generate-tokens.py`. `ROLES`, `TokenError`, `Tokens`, `Appearance`, `load`, `validate` are defined in Task 2 and used consistently after. `breakpoints_for` is defined and used in Task 8. Role names are lowerCamelCase on both native platforms, and Task 5 has a test asserting the two emitters agree — the exact `clearLayers`/`clearFullLayers` bug this check exists to catch.
