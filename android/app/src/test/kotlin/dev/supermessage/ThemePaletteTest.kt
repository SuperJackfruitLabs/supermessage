package dev.supermessage

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which palette a [ThemeChoice] resolves to, at the token level. */
class ThemePaletteTest {
    private val accents = listOf<String?>(null) + GeneratedAccents.names

    @Test
    fun theHousePalettesAreTheDefaults() {
        assertEquals(GeneratedThemeTokens.paper, ThemeChoice(AppearanceMode.LIGHT, DarkStyle.BLACK, null).palette(false))
        assertEquals(GeneratedThemeTokens.black, ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, null).palette(true))
        assertEquals(GeneratedThemeTokens.dark, ThemeChoice(AppearanceMode.DARK, DarkStyle.TINTED, null).palette(true))
    }

    @Test
    fun anAccentTakesItsOwnPalettePerAppearance() {
        assertEquals(GeneratedAccents.blue["dark"], ThemeChoice(AppearanceMode.DARK, DarkStyle.TINTED, "blue").palette(true))
        assertEquals(GeneratedAccents.teal["black"], ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, "teal").palette(true))
        assertEquals(GeneratedAccents.pink["paper"], ThemeChoice(AppearanceMode.LIGHT, DarkStyle.BLACK, "pink").palette(false))
    }

    @Test
    fun anUnknownAccentFallsBackToTheHousePalette() {
        assertEquals(GeneratedThemeTokens.black, ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, "mauve").palette(true))
        assertEquals(GeneratedThemeTokens.paper, ThemeChoice(AppearanceMode.LIGHT, DarkStyle.TINTED, "mauve").palette(false))
        assertNull(accentSet("mauve"))
    }

    /** Black plus any accent is still black: the accent moves accent roles, never the ground. */
    @Test
    fun blackStaysBlackWithEveryAccent() {
        for (accent in accents) {
            val p = ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, accent).palette(true)
            assertEquals("surface for $accent", Color.Black, p.surface)
            if (accent != null) {
                assertNotEquals("accent for $accent", GeneratedThemeTokens.black.accent, p.accent)
            }
        }
    }

    /** Tinted is not black, light is not dark, for every accent. */
    @Test
    fun theThreeAppearancesStayDistinctWithEveryAccent() {
        for (accent in accents) {
            val light = ThemeChoice(AppearanceMode.LIGHT, DarkStyle.TINTED, accent).palette(false).surface
            val tinted = ThemeChoice(AppearanceMode.DARK, DarkStyle.TINTED, accent).palette(true).surface
            val black = ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, accent).palette(true).surface
            assertNotEquals("tinted vs black for $accent", tinted, black)
            assertNotEquals("light vs black for $accent", light, black)
            assertNotEquals("light vs tinted for $accent", light, tinted)
        }
    }

    @Test
    fun thePeersFollowTheAppearance() {
        // GeneratedPeers.black and .dark hold equal colours today, so Black's
        // set cannot be told from Tinted's by value; light versus dark can.
        assertEquals(GeneratedPeers.black, ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, null).peers(true))
        assertEquals(GeneratedPeers.dark, ThemeChoice(AppearanceMode.DARK, DarkStyle.TINTED, null).peers(true))
        assertEquals(GeneratedPeers.paper, ThemeChoice(AppearanceMode.LIGHT, DarkStyle.BLACK, null).peers(false))
    }

    @Test
    fun theModeDecidesWhetherItIsDark() {
        assertEquals(true, ThemeChoice(AppearanceMode.SYSTEM).resolvesDark(true))
        assertEquals(false, ThemeChoice(AppearanceMode.SYSTEM).resolvesDark(false))
        assertEquals(false, ThemeChoice(AppearanceMode.LIGHT).resolvesDark(true))
        assertEquals(true, ThemeChoice(AppearanceMode.DARK).resolvesDark(false))
    }
}
