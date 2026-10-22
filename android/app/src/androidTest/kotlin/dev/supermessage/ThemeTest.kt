package dev.supermessage

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/**
 * `SupermessageTheme` carries one idea across from
 * `apple/Supermessage/Theme.swift`: typography here is structural, not
 * decorative. Serif for what an agent wrote, sans for what the operator
 * wrote, mono for data and sigils. These tests assert the resolved
 * [FontFamily] Compose actually lays a `Text` out with — not a colour, and
 * not a screenshot — because colour shifts with dynamic theming and dark
 * mode while the face assignment is the one invariant this task guarantees.
 *
 * `onTextLayout` is what makes this a real assertion rather than a
 * tautology: it reads back the [androidx.compose.ui.text.TextLayoutResult]
 * Compose produced after merging the role's `FontFamily` through
 * `SupermessageTheme`'s composition local and into the actual `Text` render,
 * the same path Task 2's adoption of this theme will rely on.
 */
class ThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun agentMessageRendersInTheOneConversationFace() {
        var resolved: FontFamily? = null
        compose.setContent {
            SupermessageTheme {
                Text(
                    text = "an agent wrote this",
                    fontFamily = SupermessageTheme.typography.body,
                    onTextLayout = { resolved = it.layoutInput.style.fontFamily },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(FontFamily.SansSerif, resolved)
    }

    @Test
    fun operatorMessageRendersSans() {
        var resolved: FontFamily? = null
        compose.setContent {
            SupermessageTheme {
                Text(
                    text = "the operator wrote this",
                    fontFamily = SupermessageTheme.typography.own,
                    onTextLayout = { resolved = it.layoutInput.style.fontFamily },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(FontFamily.SansSerif, resolved)
    }

    @Test
    fun codeSpanRendersMono() {
        var resolved: FontFamily? = null
        compose.setContent {
            SupermessageTheme {
                Text(
                    text = "dev.agentpod.turn.v1",
                    fontFamily = SupermessageTheme.typography.code,
                    onTextLayout = { resolved = it.layoutInput.style.fontFamily },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(FontFamily.Monospace, resolved)
    }

    /**
     * One conversational voice (docs/design-language.md §1, 2026-09-23):
     * what an agent wrote and what you wrote share a face, and both come
     * from the generated tokens rather than a literal here. Who said it is
     * carried by the sender's card and badge.
     */
    @Test
    fun ownAndBodyShareTheGeneratedFace() {
        assertEquals(ThemeType.body, SupermessageThemeFonts.body)
        assertEquals(ThemeType.bodyOwn, SupermessageThemeFonts.own)
        assertEquals(SupermessageThemeFonts.body, SupermessageThemeFonts.own)
    }

    /**
     * The appearance this app binds to light is **paper**, and it is a real
     * appearance rather than dark recoloured: every one of the sixteen
     * roles differs.
     *
     * Written out rather than reflected over, and that is deliberate twice.
     * `KClass.members` needs kotlin-reflect, which is not on Android and
     * throws at run time rather than failing to compile. And the previous
     * version of this test named *seven* roles by hand, so the nine added
     * when the palette moved to design/tokens.toml would have gone
     * unchecked while the test kept passing — the exact shape AGENTS.md
     * warns about. Completeness itself is enforced where it belongs, by the
     * generator, which refuses to emit an appearance missing a role.
     */
    @Test
    fun paperAndDarkPalettesAreDistinctInEveryRole() {
        val paper = GeneratedThemeTokens.paper
        val dark = GeneratedThemeTokens.dark
        assertNotEquals("surface", paper.surface, dark.surface)
        assertNotEquals("surfaceSunken", paper.surfaceSunken, dark.surfaceSunken)
        assertNotEquals("surfaceRaised", paper.surfaceRaised, dark.surfaceRaised)
        assertNotEquals("border", paper.border, dark.border)
        assertNotEquals("borderStrong", paper.borderStrong, dark.borderStrong)
        assertNotEquals("content", paper.content, dark.content)
        assertNotEquals("contentMuted", paper.contentMuted, dark.contentMuted)
        assertNotEquals("contentFaint", paper.contentFaint, dark.contentFaint)
        assertNotEquals("accent", paper.accent, dark.accent)
        assertNotEquals("accentContent", paper.accentContent, dark.accentContent)
        assertNotEquals("accentSoft", paper.accentSoft, dark.accentSoft)
        assertNotEquals("signal", paper.signal, dark.signal)
        assertNotEquals("signalSoft", paper.signalSoft, dark.signalSoft)
        assertNotEquals("danger", paper.danger, dark.danger)
        assertNotEquals("ok", paper.ok, dark.ok)
        assertNotEquals("scrim", paper.scrim, dark.scrim)
    }

    /**
     * The binding: `paper` is what light means on a phone, so the composable
     * reaches for it rather than for the palette named `light` — which
     * exists for desktop and must never appear on Android.
     */
    @Test
    fun lightOnAndroidMeansPaper() {
        assertNotEquals(
            GeneratedThemeTokens.light.surface, GeneratedThemeTokens.paper.surface)
    }

    private fun readSurfaceAndPrimary(choice: ThemeChoice): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
        var surface = androidx.compose.ui.graphics.Color.Unspecified
        var primary = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            SupermessageTheme(choice) {
                surface = SupermessageTheme.colors.surface
                primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
            }
        }
        compose.waitForIdle()
        return surface to primary
    }

    /** Dark chosen on a phone that is light paints dark. (The emulator is light.) */
    @Test
    fun choosingDarkPaintsTheSurfaceDarkOnALightPhone() {
        val (surface, _) = readSurfaceAndPrimary(ThemeChoice(AppearanceMode.DARK, DarkStyle.TINTED, null))
        assertEquals(GeneratedThemeTokens.dark.surface, surface)
    }

    @Test
    fun blackIsTrueBlack() {
        val (surface, _) = readSurfaceAndPrimary(ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, null))
        assertEquals(androidx.compose.ui.graphics.Color.Black, surface)
    }

    @Test
    fun theAccentReachesMaterialPrimary() {
        val (_, primary) = readSurfaceAndPrimary(ThemeChoice(AppearanceMode.LIGHT, DarkStyle.TINTED, "green"))
        assertEquals(GeneratedAccents.green["paper"]!!.accent, primary)
    }

    /** Black with an accent: the Material background is black and the primary is the accent's black one. */
    @Test
    fun blackWithAnAccentStaysBlackInMaterial() {
        var background = androidx.compose.ui.graphics.Color.Unspecified
        var primary = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            SupermessageTheme(ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, "pink")) {
                background = androidx.compose.material3.MaterialTheme.colorScheme.background
                primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
            }
        }
        compose.waitForIdle()
        assertEquals(androidx.compose.ui.graphics.Color.Black, background)
        assertEquals(GeneratedAccents.pink["black"]!!.accent, primary)
    }
}
