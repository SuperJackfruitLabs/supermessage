package dev.supermessage

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.remember
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
 * the IME, but only when it is actually showing — [WindowInsets.isImeVisible]
 * is read fresh on every drag rather than cached, so a drag that starts after
 * the keyboard was already dismissed some other way is a no-op instead of a
 * redundant `hide()`. An **upward** drag never dismisses it: scrolling back
 * toward older history while composing a reply is not a request to close the
 * keyboard, and treating every drag direction as "dismiss" is exactly the
 * mutation [KeyboardDismissOnDragConnectionTest] (see
 * `KeyboardDismissTest.kt`) exists to catch.
 *
 * The `Offset.Zero` this always returns from `onPreScroll` matters as much as
 * the dismissal itself: this connection never consumes scroll distance, so
 * the list beneath it keeps scrolling exactly as it would with no keyboard
 * involved at all. It observes the gesture; it does not steal it.
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.dismissKeyboardOnDrag(): Modifier = composed {
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val connection = remember(keyboardController) {
        KeyboardDismissOnDragConnection(
            isImeVisible = { imeVisible },
            hideKeyboard = { keyboardController?.hide() },
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
