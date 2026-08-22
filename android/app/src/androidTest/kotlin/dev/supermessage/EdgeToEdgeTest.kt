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
import dev.supermessage.kit.Session
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
     * Fix 4's blocker 1, the other reported symptom: `systemBarsPadding()`
     * used to live only inside `SignedIn` (on `ListDetailPaneScaffold`'s own
     * modifier), so `STARTING` and `SIGNED_OUT` — the very first thing a new
     * user sees — got no status-bar clearance at all. Mirrors
     * [theRosterHeaderClearsTheStatusBarInsets] exactly, but against
     * `RootScaffold(phase = SIGNED_OUT)`'s own content instead of the
     * default `SIGNED_IN` phase that test already covers — the arm this
     * task's fix moved the padding out to cover for the first time.
     */
    @Test
    fun theSignedOutContentClearsTheStatusBarInset() {
        var statusBarTopPx = -1f
        compose.setContent {
            val density = LocalDensity.current
            statusBarTopPx = WindowInsets.systemBars.getTop(density).toFloat()
            Box(Modifier.fillMaxSize()) {
                RootScaffold(
                    phase = Session.Phase.SIGNED_OUT,
                    signedOutContent = {
                        Box(Modifier.fillMaxSize().testTag("signed-out-content"))
                    },
                )
            }
        }
        compose.waitForIdle()

        assertTrue("expected a non-zero status bar inset to test against", statusBarTopPx > 0f)

        val contentTop = compose.onNodeWithTag("signed-out-content").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "signed-out-content's top ($contentTop) must clear the status bar inset ($statusBarTopPx)",
            contentTop >= statusBarTopPx,
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

    /**
     * Dispatches a real, non-synthetic 500px `ime()` inset onto the Compose
     * host view backing [compose]'s current content — the same mechanism
     * [theComposerClearsASimulatedImeInset] uses and this file's class doc
     * explains the "why" of. 500px, not 600px: this is the figure the
     * re-review's brief for this task names explicitly.
     */
    private fun dispatchSimulatedImeInset() {
        val hostView = compose.activity.window.decorView
            .findViewById<ViewGroup>(android.R.id.content)
            .getChildAt(0)
        val imeHeightPx = Insets.of(0, 0, 0, 500)
        val withIme = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), imeHeightPx)
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(hostView, withIme) }
        compose.waitForIdle()
    }

    /**
     * Blocker 1 (fix 4), first half: `RootScaffold`'s `SIGNED_OUT` arm
     * (`LoginScreen`'s three-field, non-scrollable, centred column) got no
     * `imePadding()` at all — `Composer.kt:206` was the one and only call
     * site in `app/src/main` before this task. This is the very first
     * screen a new user sees, so it is the one most exposed to the defect.
     *
     * Driven through [RootScaffold] itself (`phase = SIGNED_OUT`), not
     * `LoginScreen` composed in isolation: the fix this task makes lives on
     * `RootScaffold`'s `SIGNED_OUT` arm, and a test that bypassed
     * `RootScaffold` could pass against a `LoginScreen` with no padding of
     * its own, having never exercised the arm that actually needs it.
     *
     * Asserts the sign-in button's bottom bound clears the simulated IME
     * inset — window height minus the 500px dispatched above — the same
     * "stays within the visible window" contract
     * [theComposerClearsASimulatedImeInset] already pins for the composer.
     */
    @Test
    fun theSignInFieldsStayAboveASimulatedImeInset() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                RootScaffold(
                    phase = Session.Phase.SIGNED_OUT,
                    signedOutContent = {
                        LoginScreen(
                            homeserver = "",
                            onHomeserverChange = {},
                            failure = null,
                            busy = false,
                            onSignIn = { _, _ -> },
                        )
                    },
                )
            }
        }
        compose.waitForIdle()

        // LoginScreen's column is centred, not bottom-anchored, so an
        // absolute "clears window-bottom-minus-inset" check (the form
        // [theComposerClearsASimulatedImeInset] uses) would pass here
        // whether or not any padding exists at all — centred content this
        // far from a modest 500px inset never gets near that line either
        // way. What actually distinguishes "padding applied somewhere
        // above this screen" from "nothing applied at all" is movement:
        // with no imePadding() anywhere on this arm, dispatching the inset
        // changes nothing (Compose does not re-lay-out unpadded content
        // just because an inset value changed); with it, the available
        // height shrinks and centred content moves up. Confirmed, not
        // assumed: this test failed with `afterBottom == beforeBottom`
        // (zero movement) before this task's fix — see its own commit for
        // the exact numbers.
        val beforeBottom = compose.onNodeWithTag("sign-in").fetchSemanticsNode().boundsInRoot.bottom

        dispatchSimulatedImeInset()

        val afterBottom = compose.onNodeWithTag("sign-in").fetchSemanticsNode().boundsInRoot.bottom

        assertTrue(
            "sign-in's bottom bound ($afterBottom) must move up from where it stood before " +
                "the simulated IME inset ($beforeBottom) — no movement means nothing on this " +
                "screen reacted to the inset at all",
            afterBottom <= beforeBottom - 50f,
        )
    }

    /**
     * Blocker 1 (fix 4), second half: `NewRoomPanel` — the sheet-hosted
     * panel behind the new-room query field (`NewRoom.kt:142-145`), only
     * reachable at all since fix 2 — got no `imePadding()` either. Driven
     * directly against [NewRoomPanel] (the house pattern [NewRoomTest]
     * already uses), rather than through the `ModalBottomSheet`
     * `RootScaffold` hosts it in: Material3 1.4's `ModalBottomSheet` is
     * `ModalBottomSheetDialogWrapper`-backed — a real, separate `Dialog`
     * window — confirmed by decompiling `material3-android-1.4.0.aar`, not
     * assumed, after a first version of this test drove the sheet directly
     * and the dispatched inset never reached it (`new-room-empty`'s bounds
     * were byte-identical before and after). Driving [NewRoomPanel] directly
     * is what actually lands the dispatched inset on it.
     *
     * Asserts on `new-room-empty` — the empty-state message below the query
     * field — not the field itself: the field is the *first* thing in
     * `NewRoomPanel`'s column, so its own bounds never reach anywhere near
     * the bottom of the panel regardless of `imePadding()` (confirmed by
     * probing it directly: identical bounds before and after the dispatch,
     * fixed or not — asserting on it would be exactly the "passes against
     * the defect it names" shape this task's brief warns against). What
     * does sit at the panel's bottom, and does move, is whatever fills the
     * rest of it below the field — confirmed here with an empty
     * `loadPeople` result, landing on `new-room-empty`.
     */
    @Test
    fun theNewRoomResultsClearASimulatedImeInset() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                NewRoomPanel(
                    onOpen = {},
                    onClose = {},
                    loadPeople = { emptyList() },
                    openConversation = { throw NotImplementedError() },
                    joinByAlias = { throw NotImplementedError() },
                )
            }
        }
        compose.waitForIdle()

        dispatchSimulatedImeInset()

        val windowBottomPx = compose.activity.window.decorView.height.toFloat()
        val emptyBottom = compose.onNodeWithTag("new-room-empty").fetchSemanticsNode().boundsInRoot.bottom

        assertTrue(
            "new-room-empty's bottom bound ($emptyBottom) must clear the simulated IME inset " +
                "(window bottom $windowBottomPx minus 500px)",
            emptyBottom <= windowBottomPx - 500f,
        )
    }
}
