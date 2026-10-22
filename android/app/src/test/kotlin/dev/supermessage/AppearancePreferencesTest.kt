package dev.supermessage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppearancePreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun prefs(scope: TestScope) =
        AppearancePreferences(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("appearance.preferences_pb") })

    @Test
    fun theDefaultIsSystemTintedViolet() = runTest {
        assertEquals(ThemeChoice(AppearanceMode.SYSTEM, DarkStyle.TINTED, null), prefs(this).choice.first())
    }

    @Test
    fun eachSetterRoundTrips() = runTest {
        val p = prefs(this)
        p.setMode(AppearanceMode.DARK)
        assertEquals(AppearanceMode.DARK, p.choice.first().mode)
        p.setDarkStyle(DarkStyle.BLACK)
        assertEquals(DarkStyle.BLACK, p.choice.first().darkStyle)
        p.setAccent("teal")
        assertEquals("teal", p.choice.first().accent)
        assertEquals(ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, "teal"), p.choice.first())
    }

    @Test
    fun anUnknownModeFallsBackToSystem() = runTest {
        val p = prefs(this)
        p.setRawForTest(mode = "sepia", darkStyle = "navy")
        assertEquals(AppearanceMode.SYSTEM, p.choice.first().mode)
        assertEquals(DarkStyle.TINTED, p.choice.first().darkStyle)
    }

    @Test
    fun emptyAccentIsViolet() = runTest {
        val p = prefs(this)
        p.setAccent("pink")
        p.setAccent(null)
        assertNull(p.choice.first().accent)
        p.setRawForTest(accent = "")
        assertNull(p.choice.first().accent)
    }
}
