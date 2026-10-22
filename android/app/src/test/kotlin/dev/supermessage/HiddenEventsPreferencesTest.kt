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

    @Test
    fun savedIdsComeBackInANewInstance() {
        HiddenEventsPreferences(context).save(setOf("\$a", "\$b"))
        assertEquals(setOf("\$a", "\$b"), HiddenEventsPreferences(context).load())
    }

    @Test
    fun nothingSavedLoadsEmpty() {
        assertTrue(HiddenEventsPreferences(context).load().isEmpty())
    }

    @Test
    fun clearRemovesTheStoredKey() {
        val prefs = HiddenEventsPreferences(context)
        prefs.save(setOf("\$a"))
        prefs.clear()
        assertTrue(HiddenEventsPreferences(context).load().isEmpty())
        assertTrue(!context.getSharedPreferences("safety", Context.MODE_PRIVATE).contains("safety.hiddenEvents"))
    }

    /** iOS's key, so the two apps' stored sets mean the same thing. */
    @Test
    fun usesTheKeyIosUses() {
        HiddenEventsPreferences(context).save(setOf("\$a"))
        assertEquals(
            setOf("\$a"),
            context.getSharedPreferences("safety", Context.MODE_PRIVATE).getStringSet("safety.hiddenEvents", null),
        )
    }
}
