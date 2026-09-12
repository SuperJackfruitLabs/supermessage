import unittest

from scripts.tokens.contrast import (
    composite,
    contrast_ratio,
    luminance_drop,
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


if __name__ == "__main__":
    unittest.main()
