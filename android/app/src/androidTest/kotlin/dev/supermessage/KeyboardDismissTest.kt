package dev.supermessage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
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

    @get:Rule val compose = createComposeRule()

    /** Records every call, standing in for `SoftwareKeyboardController.hide()`. */
    private class RecordingHide {
        var calls = 0
            private set
        val fn: () -> Unit = { calls++ }
    }

    /**
     * A fake [SoftwareKeyboardController], provided via
     * [CompositionLocalProvider] so [aDownwardDragReReadsImeVisibilityRatherThanCachingTheFirstComposition]
     * observes exactly what `dismissKeyboardOnDrag()`'s `hideKeyboard` lambda
     * does, without depending on a real IME actually being shown — this
     * headless emulator has no compositor to raise one (see this file's own
     * class doc). [show] is never asserted on: nothing under test calls it.
     */
    private class RecordingSoftwareKeyboardController(private val hide: RecordingHide) :
        SoftwareKeyboardController {
        override fun show() {}
        override fun hide() = hide.fn()
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

    /**
     * Defect A, driven through [Modifier.dismissKeyboardOnDrag] itself rather
     * than [KeyboardDismissOnDragConnection] directly — the shape the four
     * tests above deliberately do NOT take, and the reason all four of them
     * missed this: they exercise the connection's own decision logic, but
     * none of them touches the modifier factory that builds one, which is
     * the only broken part (`imeVisible` captured as a plain `Boolean`,
     * `remember`'s key excluding it, so the connection holds composition
     * #1's value forever).
     *
     * This headless emulator has no compositor to raise a real IME (see the
     * class doc above), so the state `dismissKeyboardOnDrag()` reads is made
     * injectable — an `isImeVisible: @Composable () -> Boolean` parameter
     * defaulting to the real `WindowInsets.isImeVisible` — and driven here
     * via a plain `mutableStateOf` instead. `hideKeyboard` is verified
     * through a real `LocalSoftwareKeyboardController` substitution
     * ([RecordingSoftwareKeyboardController]), not a lambda threaded into
     * the connection, so this test observes exactly what the modifier itself
     * wires up.
     *
     * The scenario: `imeVisible` is `false` at first composition (matching
     * the bug's own description — "the connection holds the first
     * composition's false forever"), flips to `true` in a later
     * recomposition with no change to `LocalSoftwareKeyboardController`
     * (so a `remember` keyed only on the controller would not rebuild the
     * connection), and only then is a downward drag performed. Against the
     * bug, the connection still reads the stale `false` and never calls
     * `hide()`; a modifier that re-reads `isImeVisible` on every drag calls
     * it once.
     */
    @Test
    fun theModifierReReadsImeVisibilityRatherThanCachingTheFirstComposition() {
        val hide = RecordingHide()
        val controller = RecordingSoftwareKeyboardController(hide)
        var imeVisible by mutableStateOf(false)

        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides controller) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .dismissKeyboardOnDrag(isImeVisible = { imeVisible })
                        .testTag("dismiss-test-list"),
                ) {
                    items(50) { i -> Text("row $i", Modifier.fillMaxSize().height(64.dp)) }
                }
            }
        }

        // First composition captured `false` — the bug's exact failure mode.
        // Flip it afterward, with no change to the keyboard controller, so a
        // `remember` keyed only on that controller would not rebuild the
        // connection and would keep answering with the stale value.
        imeVisible = true
        compose.waitForIdle()

        compose.onNodeWithTag("dismiss-test-list").performTouchInput { swipeDown() }
        compose.waitForIdle()

        // Not assertEquals(1, ...): a real swipeDown() gesture reports
        // several intermediate onPreScroll deltas rather than one, so a
        // modifier that re-reads correctly calls hide() more than once —
        // assertTrue(> 0) is the meaningful bar here, the same shape
        // theRuleDistinguishesDragDirectionRatherThanFiringOnAny above uses.
        assertTrue(
            "a downward drag after isImeVisible flipped true post-composition must hide the keyboard",
            hide.calls > 0,
        )
    }
}
