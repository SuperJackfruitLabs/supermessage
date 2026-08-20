package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Geometry, not existence.
 *
 * A test once asserted the room-info panel existed while it was laid out off
 * the side of an iPad — present in the tree, invisible on the screen. So these
 * assert real bounds, never merely assertExists().
 *
 * Box(Modifier.size(...)) is not enough to force these widths: size()
 * coerces into the parent's incoming constraints, so on a screen narrower
 * than the requested width the Box would silently clamp to the real screen
 * and the test would measure the wrong width without failing. requiredSize()
 * ignores the incoming constraints and forces the measurement instead; the
 * content is allowed to overflow the physical screen because these
 * assertions are about the composition's own geometry, not about pixels a
 * user could touch.
 *
 * That overflow cuts the other way for `assertIsDisplayed()`, though: it
 * does not check the forced shell size, it checks the node's bounds against
 * the *real* window. Force an 840dp or 1200dp shell onto a device whose real
 * window is narrower — the tablet AVD is 800dp in portrait, the phone AVD is
 * 411dp in portrait and 914dp in landscape — and a pane laid out correctly
 * inside the shell can still land outside the physical window, failing
 * assertIsDisplayed() for a reason that has nothing to do with the app. So
 * the wide cases below assert each pane's bounds lie inside the *shell's*
 * bounds (via [assertWithinShell]), not the device's. 411dp is the one
 * exception: it fits inside every device this suite runs on, so
 * assertIsDisplayed() there is both meaningful and device-independent, and
 * it additionally covers the case a bounds check alone would miss — a pane
 * inside the shell but occluded or off the physical screen.
 */
class RootScaffoldTest {

    @get:Rule val compose = createComposeRule()

    private companion object {
        const val ShellTag = "test-shell"
    }

    private fun shellOfWidth(width: Int) {
        compose.setContent {
            Box(Modifier.requiredSize(width.dp, 800.dp).testTag(ShellTag)) {
                RootScaffold()
            }
        }
    }

    /**
     * Asserts [tag]'s node was actually laid out (non-zero width) and that
     * its bounds fall entirely inside the shell's own bounds — the container
     * under test, not the emulator's physical screen. This is the iPad
     * fault, stated directly: a pane present in the tree but positioned
     * outside its container.
     *
     * Bounds are read via `boundsInRoot`, which is relative to the real
     * window, not the shell. When the shell is forced wider than the real
     * window, an ancestor centers the overflow, shifting the shell's own
     * origin to a negative root x — so pane bounds are compared against the
     * *shell's* reported bounds, not an assumed `0..shellWidth` span, or
     * this check would itself depend on device width.
     */
    private fun assertWithinShell(tag: String) {
        val shell = compose.onNodeWithTag(ShellTag).fetchSemanticsNode().boundsInRoot
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag was not laid out: $bounds", bounds.width > 0f)
        assertTrue(
            "$tag bounds $bounds fall outside shell bounds $shell",
            bounds.left >= shell.left && bounds.right <= shell.right)
    }

    @Test
    fun aPhoneShowsTheRosterAndNoInfoPane() {
        shellOfWidth(411)
        // The roster is the stack's root, and it is on screen at launch —
        // not behind a toggle nobody has reason to look for. 411dp fits
        // every device this suite runs on, so assertIsDisplayed() here is
        // meaningful and still device-independent.
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }

    @Test
    fun aTabletInPortraitShowsTwoPanesAndNoInfoPane() {
        shellOfWidth(840)
        assertWithinShell("pane-roster")
        assertWithinShell("pane-timeline")
        // The regression: at 840dp the default directive would place three.
        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }

    @Test
    fun aTabletInLandscapeShowsAllThreeOnScreen() {
        shellOfWidth(1200)
        assertWithinShell("pane-roster")
        assertWithinShell("pane-timeline")
        assertWithinShell("pane-info")
    }

    @Test
    fun narrowingCollapsesAnOpenInfoPane() {
        var width by mutableStateOf(1200.dp)
        compose.setContent {
            Box(Modifier.size(width, 800.dp)) { RootScaffold() }
        }
        compose.onNodeWithTag("pane-info").assertIsDisplayed()

        // The rotation. iOS left the inspector laid out at x=850.5 on a screen
        // 834 points wide: present in the tree, off the side of the screen.
        width = 840.dp
        compose.waitForIdle()

        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }
}
