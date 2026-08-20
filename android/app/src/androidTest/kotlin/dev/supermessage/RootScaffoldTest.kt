package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
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
 * assert assertIsDisplayed() and check the reported bounds, never merely
 * assertExists().
 *
 * Box(Modifier.size(...)) is not enough to force these widths: size()
 * coerces into the parent's incoming constraints, so on a screen narrower
 * than the requested width (this test's phone is 411dp; the 1200dp case is
 * wider than every AVD here) the Box would silently clamp to the real screen
 * and the test would measure the wrong width without failing. requiredSize()
 * ignores the incoming constraints and forces the measurement instead; the
 * content is allowed to overflow the physical screen because these
 * assertions are about the composition's own geometry, not about pixels a
 * user could touch.
 */
class RootScaffoldTest {

    @get:Rule val compose = createComposeRule()

    private fun shellOfWidth(width: Int) {
        compose.setContent {
            Box(Modifier.requiredSize(width.dp, 800.dp)) { RootScaffold() }
        }
    }

    @Test
    fun aPhoneShowsTheRosterAndNoInfoPane() {
        shellOfWidth(411)
        // The roster is the stack's root, and it is on screen at launch —
        // not behind a toggle nobody has reason to look for.
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }

    @Test
    fun aTabletInPortraitShowsTwoPanesAndNoInfoPane() {
        shellOfWidth(840)
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("pane-timeline").assertIsDisplayed()
        // The regression: at 840dp the default directive would place three.
        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }

    @Test
    fun aTabletInLandscapeShowsAllThreeOnScreen() {
        shellOfWidth(1200)
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
        compose.onNodeWithTag("pane-timeline").assertIsDisplayed()
        compose.onNodeWithTag("pane-info").assertIsDisplayed()

        // Bounds, not presence: the iPad fault was an on-tree, off-screen pane.
        val info = compose.onNodeWithTag("pane-info")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "info pane starts at ${info.left}, outside the 1200dp shell",
            info.left >= 0f && info.right <= 1200f * compose.density.density)
    }
}
