package dev.supermessage

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Defect B — `enableEdgeToEdge()` with no insets handling anywhere.
 *
 * `MainActivity.onCreate` calls `enableEdgeToEdge()`, which draws app content
 * behind the status bar, the navigation bar and the IME rather than letting
 * the system reserve space for them. Nothing in `app/src/main` applied
 * `imePadding`, `safeDrawing`, or `systemBarsPadding` to compensate (a grep
 * across the module for all three, plus `windowSoftInputMode`, returned
 * nothing) — so the roster header renders under the status bar, and the
 * composer renders under the soft keyboard once one is shown.
 *
 * ## Why both tests below drive real, non-synthetic insets rather than a
 * fake `WindowInsets` seam
 *
 * Unlike Defect A, no injectable parameter was added to production code
 * here — [RootScaffold] and [Composer] were not changed to accept a
 * test-controlled inset source. That was unnecessary: this test module
 * targets `compileSdk`/`targetSdk` 36, and Android enforces edge-to-edge
 * unconditionally for apps targeting API 35+, regardless of whether
 * `enableEdgeToEdge()` is called — confirmed by probing
 * `WindowInsets.systemBars` on a bare, unmodified `ComponentActivity` under
 * `createAndroidComposeRule` *before* calling `enableEdgeToEdge()` at all,
 * and observing non-zero top/bottom insets already. So
 * [theRosterHeaderClearsTheStatusBarInsets] needs no simulation at all: the
 * real status bar inset this device reports is what it asserts against.
 *
 * The soft keyboard is the one inset this headless emulator genuinely cannot
 * raise — there is no compositor to show a real IME, matching
 * `KeyboardDismissTest`'s own class doc. [theComposerClearsASimulatedImeInset]
 * says so plainly and, rather than falling back to an injectable seam,
 * dispatches a real `WindowInsetsCompat` carrying a non-zero `ime()` inset
 * directly onto the Compose host view via [ViewCompat.dispatchApplyWindowInsets]
 * — the same entry point a real IME's `WindowInsetsAnimation` would drive.
 * `Modifier.imePadding()` (and every other `*Padding()` in
 * `androidx.compose.foundation.layout.WindowInsetsPadding_androidKt`) reads
 * from exactly that dispatch path, so what this test asserts on — the real
 * [Composer] composable's bounds shifting up in response to a real inset
 * dispatch — is the modifier chain actually wired up, not merely present in
 * source.
 */
class EdgeToEdgeTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The header defect: `enableEdgeToEdge()` draws content behind the
     * status bar, and nothing pads the shell to compensate, so
     * `pane-roster` used to start at root `y = 0` — underneath the status
     * bar rather than below it. Reads the real `WindowInsets.systemBars` top
     * inset (this device's actual status bar height, not a stand-in value)
     * in the same composition, and asserts `pane-roster`'s own top bound
     * clears it.
     */
    @Test
    fun theRosterHeaderClearsTheStatusBarInsets() {
        var statusBarTopPx = -1f
        compose.setContent {
            val density = LocalDensity.current
            statusBarTopPx = WindowInsets.systemBars.getTop(density).toFloat()
            Box(Modifier.fillMaxSize()) { RootScaffold() }
        }
        compose.waitForIdle()

        // Sanity: a device/emulator reporting zero here would make this
        // test meaningless (nothing to clear) rather than prove anything —
        // see this file's class doc for why this device does not.
        assertTrue("expected a non-zero status bar inset to test against", statusBarTopPx > 0f)

        val rosterTop = compose.onNodeWithTag("pane-roster").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "pane-roster's top ($rosterTop) must clear the status bar inset ($statusBarTopPx)",
            rosterTop >= statusBarTopPx,
        )
    }

    /**
     * The composer defect: with no `imePadding()` anywhere, the composer
     * stayed pinned to the bottom of the (edge-to-edge) window when a
     * keyboard rose to cover exactly that area. This headless emulator has
     * no compositor to raise a real IME (see the class doc's "why" section),
     * so a real `ime()` inset is dispatched directly onto the Compose host
     * view instead — the production [Composer] composable itself, not a
     * stand-in, run inside a `Column` shaped like `MainActivity`'s own
     * detail pane (a weighted filler above, the composer below with no
     * weight), which is what lets padding added to the composer's own
     * bottom actually show up as the composer's bounds shifting up rather
     * than being absorbed by unconstrained extra space.
     */
    @Test
    fun theComposerClearsASimulatedImeInset() {
        var text by mutableStateOf("")
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().testTag("filler-above-composer"))
                Composer(
                    text = text,
                    onTextChange = { text = it },
                    onSend = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.waitForIdle()

        val beforeBottom = compose.onNodeWithTag("composer-text").fetchSemanticsNode().boundsInRoot.bottom

        val hostView = compose.activity.window.decorView
            .findViewById<ViewGroup>(android.R.id.content)
            .getChildAt(0)
        val imeHeightPx = Insets.of(0, 0, 0, 600)
        val withIme = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), imeHeightPx)
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(hostView, withIme) }
        compose.waitForIdle()

        val afterBottom = compose.onNodeWithTag("composer-text").fetchSemanticsNode().boundsInRoot.bottom

        assertTrue(
            "composer-text's bottom bound ($afterBottom) must move above where it stood " +
                "before the simulated IME inset ($beforeBottom) by roughly the IME's height",
            afterBottom <= beforeBottom - 500f,
        )
    }
}
