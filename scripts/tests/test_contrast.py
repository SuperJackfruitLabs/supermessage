import unittest

from scripts.tokens.contrast import (
    composite,
    contrast_ratio,
    luminance_drop,
    region_luminance_drop,
    parse_rgba,
    relative_luminance,
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
        # roster drops its light by ~3x; a WCAG ratio reports well under
        # that for the same pair and would pass a scrim that paints almost
        # nothing.
        drop = luminance_drop("#f3f2f9", "rgb(34 28 56 / 0.45)")
        self.assertGreater(drop, 3.0)
        veiled = composite("#f3f2f9", parse_rgba("rgb(34 28 56 / 0.45)"))
        self.assertLess(contrast_ratio("#f3f2f9", veiled), 3.0)

    def test_a_scrim_that_paints_nothing_shows_a_drop_of_one(self):
        self.assertAlmostEqual(
            luminance_drop("#f3f2f9", "rgb(243 242 249 / 0.45)"), 1.0, places=6
        )


class RegionDropTests(unittest.TestCase):
    """The scrim's real check. A single flat colour is not enough.

    Each theme's scrim is built from the ramp end that contrasts with what
    is behind it, so each one fails a single-surface check in the opposite
    direction — and a check that reports 1.00x for a working scrim would
    have made this whole contract worthless.
    """

    LIGHT = ("#f3f2f9", "#221c38", "rgb(34 28 56 / 0.45)")
    DARK = ("#151129", "#f4f2fb", "rgb(21 17 41 / 0.70)")
    PAPER = ("#f2eee6", "#22192e", "rgb(34 25 46 / 0.45)")

    def test_the_single_surface_check_fails_in_both_directions(self):
        # This is the bug that made the region instrument necessary, pinned
        # so nobody "simplifies" region_luminance_drop back into this.
        self.assertAlmostEqual(luminance_drop("#f4f2fb", self.DARK[2]), 9.53, places=1)
        self.assertAlmostEqual(luminance_drop("#151129", self.DARK[2]), 1.00, places=2)
        self.assertAlmostEqual(luminance_drop("#f3f2f9", self.LIGHT[2]), 3.02, places=1)
        self.assertAlmostEqual(luminance_drop("#221c38", self.LIGHT[2]), 1.00, places=2)

    def test_every_appearance_veils_its_region(self):
        for name, case in (
            ("light", self.LIGHT),
            ("dark", self.DARK),
            ("paper", self.PAPER),
        ):
            with self.subTest(appearance=name):
                self.assertGreater(region_luminance_drop(*case), 2.5)

    def test_the_verdict_barely_moves_with_the_text_fraction(self):
        # If the answer depended on a number nobody can measure, the
        # instrument would be a dressed-up guess.
        for name, case in (
            ("light", self.LIGHT),
            ("dark", self.DARK),
            ("paper", self.PAPER),
        ):
            for fraction in (0.05, 0.10, 0.15, 0.25, 0.40):
                with self.subTest(appearance=name, text=fraction):
                    self.assertGreater(
                        region_luminance_drop(*case, text_fraction=fraction), 2.5
                    )

    def test_a_transparent_scrim_paints_nothing_in_every_appearance(self):
        for name, (ground, content, _) in (
            ("light", self.LIGHT),
            ("dark", self.DARK),
            ("paper", self.PAPER),
        ):
            with self.subTest(appearance=name):
                self.assertLess(
                    region_luminance_drop(ground, content, "rgb(0 0 0 / 0.02)"), 1.5
                )

    def test_sunken_over_sunken_is_caught_where_it_actually_bites(self):
        """The historical failure, and why it is light-specific.

        `app.css` records it: "a wash of sunken over sunken is 1.0:1 and
        paints nothing, the same failure the header's hover state had
        before it was inverted." In a light appearance the region's light
        is its ground, so a ground-coloured wash does nothing — 1.03x here.

        In a *dark* appearance the same construction works, because the
        region's light is its text: a ground-coloured wash over bright text
        veils it properly (2.98x). Asserting both is what stops someone
        "fixing" the dark scrim to match a rule that only applies to light.
        """
        ground, content, _ = self.LIGHT
        self.assertLess(
            region_luminance_drop(ground, content, "rgb(243 242 249 / 0.45)"), 1.1
        )

        ground, content, _ = self.DARK
        self.assertGreater(
            region_luminance_drop(ground, content, "rgb(21 17 41 / 0.45)"), 2.5
        )


if __name__ == "__main__":
    unittest.main()
