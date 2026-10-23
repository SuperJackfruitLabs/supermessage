package dev.supermessage

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HiddenEventsPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = context.getSharedPreferences("safety", Context.MODE_PRIVATE)

    @Test
    fun savedIdsComeBackInANewInstance() {
        HiddenEventsPreferences(context).save("@a:x", setOf("\$a", "\$b"))
        assertEquals(setOf("\$a", "\$b"), HiddenEventsPreferences(context).load("@a:x"))
    }

    @Test
    fun nothingSavedLoadsEmpty() {
        assertTrue(HiddenEventsPreferences(context).load("@a:x").isEmpty())
    }

    @Test
    fun aSetIsReadBackOnlyForItsOwnAccount() {
        val store = HiddenEventsPreferences(context)
        store.save("@a:x", setOf("\$a"))
        assertTrue(store.load("@b:x").isEmpty())
    }

    @Test
    fun clearRemovesOnlyThatAccountsSet() {
        val store = HiddenEventsPreferences(context)
        store.save("@a:x", setOf("\$a"))
        store.save("@b:x", setOf("\$b"))
        store.clear("@a:x")
        assertTrue(store.load("@a:x").isEmpty())
        assertEquals(setOf("\$b"), store.load("@b:x"))
    }

    @Test
    fun clearAllRemovesEverySetAndTheLegacyKey() {
        val store = HiddenEventsPreferences(context)
        prefs.edit().putStringSet("safety.hiddenEvents", setOf("\$old")).apply()
        store.save("@a:x", setOf("\$a"))
        store.clearAll()
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun takeLegacyHandsOverTheDeviceWideSetOnce() {
        prefs.edit().putStringSet("safety.hiddenEvents", setOf("\$old")).apply()
        val store = HiddenEventsPreferences(context)
        assertEquals(setOf("\$old"), store.takeLegacy())
        assertTrue(store.takeLegacy().isEmpty())
        assertTrue(!prefs.contains("safety.hiddenEvents"))
    }
}
