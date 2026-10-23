package dev.supermessage

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.supermessage.RoomHarness.raiseKeyboard
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The composer sits on the keyboard (the owner's report on a OnePlus 8T: a blank band the
 * keyboard's height between the two).
 *
 * The real soft keyboard, in [WindowLikeMainActivity]: edge to edge, with the soft-input
 * mode the manifest declares for [MainActivity], both set before the window is attached
 * (so the declaration is what is under test). Positions are on the *screen*: the system
 * pans a window by scrolling its view root, which moves everything in it, and a check made
 * in the window's own coordinates would never see that.
 *
 * Without `adjustResize` the window is panned by about the keyboard's height on top of the
 * composer's own `imePadding()`: measured 804px (gap 312dp) at 1080x2400 on both the API 36
 * and the API 34 emulator. Run it on API 34 too: the owner's phone is OxygenOS 14.
 *
 * Expected: the composer's capsule ends its own bottom padding (6dp) above the keyboard's
 * top edge, give or take a few dp.
 */
class KeyboardGapTest {
    @get:Rule val compose = createAndroidComposeRule<WindowLikeMainActivity>()

    private fun host(): View = compose.activity.window.decorView
        .findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private class Reading(val capsuleBottom: Float, val imeTop: Float, val imeHeight: Int, val hostTop: Int, val window: String)

    /** Where the capsule ends and the keyboard begins, both in screen pixels. */
    private fun read(): Reading? {
        val node = compose.onAllNodes(hasTestTag("composer-capsule"), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull() ?: return null
        var out: Reading? = null
        compose.runOnUiThread {
            val view = host()
            val insets = ViewCompat.getRootWindowInsets(view) ?: return@runOnUiThread
            if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return@runOnUiThread
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime <= 0) return@runOnUiThread
            val at = IntArray(2).also(view::getLocationOnScreen)
            val windowBottom = compose.activity.windowManager.currentWindowMetrics.bounds.bottom
            val attrs = compose.activity.window.attributes
            @Suppress("DEPRECATION")
            val legacy = view.rootWindowInsets?.systemWindowInsetBottom
            val window = "attrs.softInputMode=0x${Integer.toHexString(attrs.softInputMode)}, legacy system-window bottom=$legacy, " +
                "decor ${compose.activity.window.decorView.height}px of ${windowBottom}px, in window y=${IntArray(2).also(view::getLocationInWindow)[1]}, " +
                "decor on screen y=${IntArray(2).also(compose.activity.window.decorView::getLocationOnScreen)[1]}"
            out = Reading(at[1] + node.boundsInRoot.bottom, (windowBottom - ime).toFloat(), ime, at[1], window)
        }
        return out
    }

    @Test
    fun theComposerSitsOnTheKeyboard() {
        val core = RoomHarness.FakeCore(RoomHarness.history(30))
        val session = RoomHarness.session(core)
        val prefs = RoomHarness.prefs()
        val mode = RoomHarness.mainActivitySoftInputMode(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.fillMaxSize()) { AppRoot(session = session, prefs = prefs) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(RoomHarness.NAME).performScrollTo().performClick()
        compose.waitForIdle()

        // A tap focuses the field, as a finger does; the explicit, repeated show is for a device
        // that starts with a hardware keyboard attached (the CI AVD), where a tap alone may not.
        compose.raiseKeyboard()

        // The keyboard is up and the layout has stopped moving (its inset animation is over).
        var last: Reading? = null
        var steadyFor = 0
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                Thread.sleep(100)
                val now = read()
                val prev = last
                steadyFor = if (now != null && prev != null && abs(now.capsuleBottom - prev.capsuleBottom) < 0.5f &&
                    now.imeHeight == prev.imeHeight) steadyFor + 1 else 0
                last = now
                steadyFor >= 5
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the soft keyboard never settled on screen (last reading: ${last?.let { "${it.capsuleBottom}/${it.imeTop}" }})", e)
        }
        val r = last!!
        val dp = compose.density.density
        val gap = r.imeTop - r.capsuleBottom
        val describe = "capsule ends at ${r.capsuleBottom}px, keyboard starts at ${r.imeTop}px " +
            "(keyboard ${r.imeHeight}px, window drawn from y=${r.hostTop}, declared softInputMode=0x${Integer.toHexString(mode)}; ${r.window}): " +
            "gap ${gap / dp}dp"
        assertTrue("the composer is under the keyboard: $describe", gap >= 2f * dp)
        assertTrue("there is a band between the composer and the keyboard: $describe", gap <= 10f * dp)
    }
}
