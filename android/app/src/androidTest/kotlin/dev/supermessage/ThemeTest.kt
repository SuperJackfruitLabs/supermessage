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
    fun agentMessageRendersSerif() {
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
        assertEquals(FontFamily.Serif, resolved)
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
     * `own` and `body` must be distinguishable faces, not the same face
     * wearing two names — the exact mistake the brief's mandated mutation
     * (making `own` serif too) reproduces.
     */
    @Test
    fun ownAndBodyAreDifferentFaces() {
        assertNotEquals(SupermessageThemeFonts.body, SupermessageThemeFonts.own)
    }

    /**
     * Every semantic colour role is defined for both appearances, and dark
     * is not merely light copied over — see `Theme.swift`'s own MARK:
     * Colour, "two palettes, one per appearance, rather than one palette
     * adapted."
     */
    @Test
    fun lightAndDarkPalettesAreDistinctPerRole() {
        val light = SupermessageColorRoles.light
        val dark = SupermessageColorRoles.dark
        assertNotEquals(light.ground, dark.ground)
        assertNotEquals(light.sunken, dark.sunken)
        assertNotEquals(light.hairline, dark.hairline)
        assertNotEquals(light.accent, dark.accent)
        assertNotEquals(light.signal, dark.signal)
        assertNotEquals(light.danger, dark.danger)
        assertNotEquals(light.ok, dark.ok)
    }
}
