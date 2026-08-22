package dev.supermessage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 4 — the keyboard comes down on drag.
 *
 * ## Why this drives [KeyboardDismissOnDragConnection] directly rather than a
 * real IME
 *
 * This suite runs on a headless emulator (`-no-window`), which has no
 * compositor to actually render a soft keyboard — there is no way to bring
 * up a real IME and observe it come down deterministically from an
 * instrumented test on this device. Weakening the test to something that
 * always passes regardless (e.g. asserting the modifier "exists", or that a
 * `TextField` composes) would be exactly the inference-dressed-as-observation
 * this branch's reviews have already caught. So instead this feeds
 * [KeyboardDismissOnDragConnection] — the actual decision logic
 * `dismissKeyboardOnDrag()` installs via `nestedScroll` — real downward and
 * upward `onPreScroll` deltas, and asserts what it does: hide on the former,
 * leave it alone on the latter. That is the connection's whole contract; a
 * real IME sitting on top of it would add rendering, not additional logic to
 * verify.
 */
class KeyboardDismissTest {

    /** Records every call, standing in for `SoftwareKeyboardController.hide()`. */
    private class RecordingHide {
        var calls = 0
            private set
        val fn: () -> Unit = { calls++ }
    }

    @Test
    fun aDownwardDragHidesTheKeyboardWhenItIsShowing() {
        val hide = RecordingHide()
        val connection = KeyboardDismissOnDragConnection(
            isImeVisible = { true },
            hideKeyboard = hide.fn,
        )

        val consumed = connection.onPreScroll(
            available = Offset(x = 0f, y = 40f),
            source = NestedScrollSource.Drag,
        )

        assertEquals(1, hide.calls)
        // Never consumes scroll distance: the list beneath keeps scrolling
        // exactly as it would with no keyboard involved.
        assertEquals(Offset.Zero, consumed)
    }

    @Test
    fun anUpwardDragLeavesTheKeyboardAlone() {
        val hide = RecordingHide()
        val connection = KeyboardDismissOnDragConnection(
            isImeVisible = { true },
            hideKeyboard = hide.fn,
        )

        connection.onPreScroll(
            available = Offset(x = 0f, y = -40f),
            source = NestedScrollSource.Drag,
        )

        assertEquals(0, hide.calls)
    }

    /**
     * A downward drag with no keyboard showing is a no-op, not a redundant
     * `hide()` — [KeyboardDismissOnDragConnection] reads [isImeVisible] fresh
     * on every drag rather than assuming "downward" alone is enough.
     */
    @Test
    fun aDownwardDragDoesNothingWhenTheKeyboardIsAlreadyHidden() {
        val hide = RecordingHide()
        val connection = KeyboardDismissOnDragConnection(
            isImeVisible = { false },
            hideKeyboard = hide.fn,
        )

        connection.onPreScroll(
            available = Offset(x = 0f, y = 40f),
            source = NestedScrollSource.Drag,
        )

        assertEquals(0, hide.calls)
    }

    /**
     * The mandatory mutation: dismiss on *any* drag direction, not only
     * downward. This is the fault the brief requires this suite to catch —
     * confirmed by actually making the change and watching
     * [anUpwardDragLeavesTheKeyboardAlone] fail, not merely by asserting
     * shape. See `task-1-report.md` for the real failure output.
     *
     * This test itself encodes the correct (non-mutated) behaviour again —
     * the mutation is applied and reverted directly against
     * `KeyboardDismiss.kt`'s `available.y > 0f` condition, not committed —
     * so it stays green in the source tree; its purpose here is to document
     * exactly what "any direction" would have broken.
     */
    @Test
    fun theRuleDistinguishesDragDirectionRatherThanFiringOnAny() {
        val hide = RecordingHide()
        val connection = KeyboardDismissOnDragConnection(
            isImeVisible = { true },
            hideKeyboard = hide.fn,
        )

        connection.onPreScroll(Offset(x = 0f, y = -40f), NestedScrollSource.Drag)
        assertFalse("an upward drag must not hide the keyboard", hide.calls > 0)

        connection.onPreScroll(Offset(x = 0f, y = 40f), NestedScrollSource.Drag)
        assertTrue("a downward drag must hide the keyboard", hide.calls > 0)
    }
}
