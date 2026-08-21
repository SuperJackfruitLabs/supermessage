package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
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
        // Two is correct here regardless of which directive is in force: on
        // adaptive 1.2.0, calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
        // also gives two at 840dp — its Expanded bucket is hardcoded to two
        // partitions and never reaches three through that entrypoint at any
        // width. The default directive's real fault on this version is an
        // undercount at wider shells, not an overcount here; see
        // aTabletInLandscapeShowsAllThreeOnScreen below and RootScaffold.kt's
        // directiveFor() for where that actually bites. This assertion still
        // matters because it pins paneCountFor's own rule (two below 1000dp),
        // independent of what the default directive would have said.
        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }

    @Test
    fun aTabletInLandscapeShowsAllThreeOnScreen() {
        shellOfWidth(1200)
        assertWithinShell("pane-roster")
        assertWithinShell("pane-timeline")
        assertWithinShell("pane-info")
    }

    /**
     * Pane count follows a width change made *during* a composition's life,
     * not just at first composition.
     *
     * The name this test carried before named a mechanism it never tested:
     * it reads as proof that rotating with the info pane open collapses it
     * (§4.2 rule 2's stranding fault), but pane-info's visibility here is
     * driven entirely by scaffoldValue[Extra], which is derived from
     * directiveFor(panes) — i.e. from paneCountFor(shellWidth) — every
     * recomposition. Nothing in this app calls navigateTo(Extra, ...), so
     * RootScaffold's currentDestination can never be Extra, and its
     * LaunchedEffect/navigateBack() never runs. Confirmed by deleting that
     * LaunchedEffect entirely and re-running this test: it still passed.
     *
     * What it does earn its keep for: it is the only one of these four tests
     * that changes width mid-composition rather than only at first
     * composition, so it would catch a stale-`remember`/directive-not-
     * recomputing bug in the paneCountFor -> directiveFor -> scaffoldValue
     * pipeline. It does not, and cannot yet, regression-test rule 2's real
     * mechanism — an *opened* info pane surviving a narrowing after a user
     * action put it there — because there is no way to open the info pane
     * independently of width in this placeholder. That gap stays open until
     * real navigateTo(Extra, ...) exists to open it.
     */
    @Test
    fun paneCountFollowsAWidthChangeDuringComposition() {
        var width by mutableStateOf(1200.dp)
        compose.setContent {
            Box(Modifier.requiredSize(width, 800.dp).testTag(ShellTag)) { RootScaffold() }
        }
        assertWithinShell("pane-info")

        width = 840.dp
        compose.waitForIdle()

        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }
}
