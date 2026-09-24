import copy
import tomllib
import unittest
from pathlib import Path

from scripts.tokens.contrast import contrast_ratio
from scripts.tokens.model import (
    ROLES,
    TokenError,
    Tokens,
    breakpoints_for,
    load,
    validate,
)

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "design" / "tokens.toml"


def raw() -> dict:
    with SOURCE.open("rb") as fh:
        return tomllib.load(fh)


class RealSourceTests(unittest.TestCase):
    def test_the_committed_source_validates(self):
        self.assertIsInstance(load(SOURCE), Tokens)

    def test_all_three_appearances_are_present(self):
        self.assertEqual(sorted(load(SOURCE).appearances), ["dark", "light", "paper"])

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
        """The exact shape of the failure the cool-slate ramp shipped with.

        `#726d86` clears the reading surface at 4.84 and fails the roster
        behind it at 4.45. A fixture that failed on *every* ground would
        pass this test while proving nothing about the multi-ground clause,
        so the margins are asserted first — the window between the two
        grounds is only 1.088 wide and easy to fall out of.
        """
        broken = copy.deepcopy(raw())
        colors = broken["appearance"]["light"]["color"]

        self.assertGreater(contrast_ratio("#726d86", colors["surface"]["value"]), 4.5)
        self.assertLess(
            contrast_ratio("#726d86", colors["surface-sunken"]["value"]), 4.5
        )

        colors["content-faint"]["value"] = "#726d86"
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("content-faint", str(caught.exception))
        self.assertIn("surface-sunken", str(caught.exception))

    def test_a_signal_at_only_the_text_floor_is_an_error(self):
        # Legal for body text, not enough for a 10px label on the one
        # element that must not be missed.
        broken = copy.deepcopy(raw())
        broken["appearance"]["light"]["color"]["signal"]["value"] = "#9c5c0a"
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("signal", str(caught.exception))

    def test_a_collapsed_depth_step_is_an_error(self):
        # Too shallow: the sheet stops reading as a sheet. This is the bug
        # the previous dark theme shipped with, at 1.035.
        broken = copy.deepcopy(raw())
        broken["appearance"]["dark"]["color"]["surface-sunken"]["value"] = "#1e1736"
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("surface-sunken", str(caught.exception))
        self.assertIn("at least", str(caught.exception))

    def test_a_gaping_depth_step_is_also_an_error(self):
        """The band has two edges, and only one of them was tested.

        Found by mutation: replacing the `max` comparison with `> 999` left
        the whole suite green, which means nothing exercised the upper
        edge. A field far darker than its sheet is not depth, it is two
        unrelated surfaces — and a generator that only checks one side of a
        band is not checking a band.
        """
        broken = copy.deepcopy(raw())
        broken["appearance"]["dark"]["color"]["surface-sunken"]["value"] = "#000000"
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("surface-sunken", str(caught.exception))
        self.assertIn("at most", str(caught.exception))

    def test_a_scrim_that_paints_nothing_is_an_error(self):
        # Sunken over sunken in a light appearance: the failure app.css
        # records, and one a WCAG check would not catch either.
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

class TypeTests(unittest.TestCase):
    def test_every_role_has_a_family_and_all_three_expressions(self):
        for name, role in load(SOURCE).type.items():
            with self.subTest(role=name):
                self.assertIn(role.family, ("sans", "serif", "mono"))
                self.assertIn("size", role.web)
                self.assertTrue(role.ios)
                self.assertTrue(role.android)

    def test_a_numeric_native_expression_is_an_error(self):
        """The one place a naive generator does real damage.

        A number here becomes a fixed point size in Theme.swift, and every
        iOS user loses Dynamic Type. It would look correct to whoever made
        the change, which is exactly why it has to fail loudly.
        """
        broken = copy.deepcopy(raw())
        broken["type"]["body"]["ios"] = 15
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("Dynamic Type", str(caught.exception))

    def test_a_numeric_android_expression_is_also_an_error(self):
        broken = copy.deepcopy(raw())
        broken["type"]["body"]["android"] = 15
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("type.body.android", str(caught.exception))

    def test_an_unknown_family_is_an_error(self):
        broken = copy.deepcopy(raw())
        broken["type"]["body"]["family"] = "cursive"
        with self.assertRaises(TokenError):
            validate(broken)

    def test_the_conversation_has_one_voice(self):
        # Agent and operator text share a face; the serif is kept for the
        # long-form reading view only, and mono never sets prose or labels.
        types = load(SOURCE).type
        self.assertEqual(types["body"].family, "sans")
        self.assertEqual(types["body-own"].family, "sans")
        self.assertEqual(types["longread"].family, "serif")
        for role in ("label", "meta"):
            with self.subTest(role=role):
                self.assertNotEqual(types[role].family, "mono")

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
        self.assertEqual(tokens.breakpoints["panel-column-with-rail"], 1294)

    def test_widening_the_roster_moves_the_breakpoint(self):
        """The entire point of deriving it.

        If this passes against a hardcoded 1238 then the derivation is
        decorative and the comment in +page.svelte was right to be a
        comment.
        """
        broken = copy.deepcopy(raw())
        broken["layout"]["roster"] = 304
        self.assertEqual(breakpoints_for(broken)["panel-column"], 1254)
        self.assertEqual(breakpoints_for(broken)["panel-column-with-rail"], 1310)


class ScaleTests(unittest.TestCase):
    def test_radius_roles_are_the_four_named_ones(self):
        self.assertEqual(
            sorted(load(SOURCE).scale["radius"]),
            ["card", "control", "pill", "sharp"],
        )

    def test_there_is_exactly_one_elevation(self):
        # Depth is the surface ramp. Shadow means one thing only.
        self.assertEqual(list(load(SOURCE).scale["elevation"]), ["overlay"])

    def test_motion_has_two_durations_and_one_easing(self):
        motion = load(SOURCE).scale["motion"]
        self.assertEqual(sorted(motion), ["easing", "quick", "settle"])


class BrandTests(unittest.TestCase):
    def test_an_icon_ground_must_be_a_role_not_a_value(self):
        """How the iOS icon kept a retired palette's grounds: they were
        literals in a script, and nothing checked them against anything."""
        broken = copy.deepcopy(raw())
        broken["brand"]["ground"]["ink"] = {"appearance": "dark", "role": "slate"}
        with self.assertRaises(TokenError) as caught:
            validate(broken)
        self.assertIn("brand.ground.ink", str(caught.exception))

    def test_a_ground_resolves_to_the_role_it_names(self):
        tokens = load(SOURCE)
        self.assertEqual(
            tokens.brand.ground["ink"],
            tokens.appearances["dark"].colors["surface-sunken"],
        )

    def test_a_mark_stop_must_be_plain_hex(self):
        # Written verbatim into SVG and Android XML, neither of which takes
        # rgb().
        broken = copy.deepcopy(raw())
        broken["brand"]["mark"]["coral"]["to"] = "rgb(255 132 137)"
        with self.assertRaises(TokenError):
            validate(broken)

    def test_the_mark_has_exactly_its_three_shapes(self):
        broken = copy.deepcopy(raw())
        del broken["brand"]["mark"]["overlap"]
        with self.assertRaises(TokenError):
            validate(broken)


if __name__ == "__main__":
    unittest.main()
