package dev.supermessage

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Rule 4 — the keyboard comes down on drag.
 *
 * On iOS the keyboard had no way down for weeks: it is one of the four
 * timeline rules this project tracks, and the only one Phase A deliberately
 * left unbuilt, because with no composer there was no IME to dismiss and any
 * implementation would have been untestable dead code. Phase B owns it
 * explicitly, and it belongs to the *screen* — this modifier, applied to the
 * scrollable shell around both the timeline and the composer — rather than
 * to either of them individually, so that both agree about it without either
 * needing to know the other exists.
 *
 * A **downward** drag over the scrollable area (`available.y > 0` in
 * [NestedScrollConnection.onPreScroll]'s pre-scroll coordinate space) hides
 * the IME, but only when it is actually showing — [isImeVisible] (in
 * production, [WindowInsets.isImeVisible]) is read fresh on every drag
 * rather than cached, so a drag that starts after the keyboard was already
 * dismissed some other way is a no-op instead of a redundant `hide()`. That
 * freshness is why the value is threaded through [rememberUpdatedState]
 * rather than closed over directly: [KeyboardDismissOnDragConnection] is
 * built once via a keyless [remember] (nothing here needs a new connection
 * instance per recomposition — a `NestedScrollConnection`'s identity is not
 * what changes), but the lambda it holds reads `imeVisibleState.value`, a
 * mutable holder [rememberUpdatedState] repoints to the latest
 * `isImeVisible()` result on every recomposition. Keying the `remember` on
 * `isImeVisible`'s result instead — capturing the value directly, as a
 * previous version of this function did — is exactly the bug
 * [KeyboardDismissTest.theModifierReReadsImeVisibilityRatherThanCachingTheFirstComposition]
 * exists to catch: with a plain captured `Boolean` and a `remember` keyed
 * on something else (there, `keyboardController`), the connection holds
 * whichever value was true at first composition forever, and `hideKeyboard`
 * is never called again no matter how the real IME's visibility changes
 * afterward. An **upward** drag never dismisses it: scrolling back
 * toward older history while composing a reply is not a request to close the
 * keyboard, and treating every drag direction as "dismiss" is exactly the
 * mutation [KeyboardDismissOnDragConnectionTest] (see
 * `KeyboardDismissTest.kt`) exists to catch.
 *
 * The `Offset.Zero` this always returns from `onPreScroll` matters as much as
 * the dismissal itself: this connection never consumes scroll distance, so
 * the list beneath it keeps scrolling exactly as it would with no keyboard
 * involved at all. It observes the gesture; it does not steal it.
 *
 * [isImeVisible] defaults to the real [WindowInsets.isImeVisible] for every
 * production call site (Timeline.kt's own `dismissKeyboardOnDrag()` takes no
 * argument), and exists as a parameter at all so
 * [KeyboardDismissTest.theModifierReReadsImeVisibilityRatherThanCachingTheFirstComposition]
 * can drive it deterministically: this headless emulator has no compositor
 * to raise a real IME, so there is no way to observe this modifier
 * re-reading a *real* keyboard's visibility from an instrumented test on
 * this device, but the modifier's own re-read behaviour — the actual bug —
 * is independent of where the `Boolean` comes from, and is exactly what an
 * injected, test-controlled source lets that test drive and assert on.
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.dismissKeyboardOnDrag(
    isImeVisible: @Composable () -> Boolean = { WindowInsets.isImeVisible },
): Modifier = composed {
    val keyboardController = LocalSoftwareKeyboardController.current
    val controllerState = rememberUpdatedState(keyboardController)
    val imeVisibleState = rememberUpdatedState(isImeVisible())
    val connection = remember {
        KeyboardDismissOnDragConnection(
            isImeVisible = { imeVisibleState.value },
            hideKeyboard = { controllerState.value?.hide() },
        )
    }
    this.then(Modifier.nestedScroll(connection))
}

/**
 * The rule's actual logic, split out from [dismissKeyboardOnDrag] so it can
 * be constructed and driven directly in a test — fed a downward and an
 * upward scroll delta and asserted on what it does — independent of whether
 * a real IME can be shown deterministically on the device the test runs on.
 * [isImeVisible] and [hideKeyboard] are lambdas rather than a captured
 * [WindowInsets] / `SoftwareKeyboardController` pair for exactly that reason:
 * nothing about this class's own behaviour needs a running composition.
 */
internal class KeyboardDismissOnDragConnection(
    private val isImeVisible: () -> Boolean,
    private val hideKeyboard: () -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y > 0f && isImeVisible()) {
            hideKeyboard()
        }
        return Offset.Zero
    }
}
