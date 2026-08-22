package dev.supermessage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

    // createAndroidComposeRule<ComponentActivity>(), not the bare
    // createComposeRule() the other tests here were written against —
    // functionally identical (createComposeRule() is exactly this call
    // underneath), but with the concrete rule type exposed rather than the
    // narrower ComposeContentTestRule interface, so `compose.activity` below
    // is reachable. No existing test's behaviour changes.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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

    /**
     * Tapping in the list pane opens the detail pane on a one-pane shell.
     *
     * The roster is not involved: this pins the SHELL's contract — that list-pane
     * content is given a way to navigate — independently of what fills it. Before
     * this existed, `onOpenRoom` updated state and nothing moved, which is
     * invisible on a tablet (both panes always shown) and total on a phone.
     */
    @Test
    fun tappingInTheListPaneOpensTheDetailPaneOnAPhone() {
        compose.setContent {
            Box(Modifier.requiredSize(411.dp, 800.dp).testTag(ShellTag)) {
                RootScaffold(
                    listPaneContent = { _, openDetail, _ ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("list-pane-tap-target")
                                .clickable(onClick = openDetail)
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag("list-pane-tap-target").performClick()
        compose.waitForIdle()

        // 411dp fits every device this suite runs on (see the class doc), so
        // assertIsDisplayed() here is meaningful and still device-independent.
        compose.onNodeWithTag("pane-timeline").assertIsDisplayed()
    }

    /**
     * System back from an open detail pane on a one-pane (phone) shell
     * returns to the roster, rather than exiting the app — the defect this
     * task exists to fix. `RoomsStore.deselect()` is not reachable from this
     * generic scaffold test (it lives in `:kit`, and `RootScaffold` never
     * imports it — see the class doc on [onBackFromDetail]'s KDoc), so the
     * caller-supplied callback stands in for it here, the same way
     * [tappingInTheListPaneOpensTheDetailPaneOnAPhone] stands in for the
     * roster with a plain tap target.
     *
     * Drives the press through the real
     * [androidx.activity.ComponentActivity] back dispatcher
     * ([compose]'s own activity — `createComposeRule()` launches one), not a
     * fake in-test callback: that is what actually exercises the
     * [androidx.activity.compose.BackHandler] registered inside
     * `RootScaffold`, rather than merely calling a Kotlin lambda directly.
     */
    @Test
    fun systemBackFromTheDetailPaneOnAPhoneReturnsToTheRoster() {
        var backFired = 0
        compose.setContent {
            Box(Modifier.requiredSize(411.dp, 800.dp).testTag(ShellTag)) {
                RootScaffold(
                    // Tagged "pane-roster" on the outer content, not just
                    // "list-pane-tap-target" on the clickable target inside
                    // it — without that outer tag this stand-in list pane
                    // has no way for the reappearing-roster assertion below
                    // to find it at all, the same tag the default
                    // listPaneContent this override replaces already carries.
                    listPaneContent = { _, openDetail, _ ->
                        Box(Modifier.fillMaxSize().testTag("pane-roster")) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag("list-pane-tap-target")
                                    .clickable(onClick = openDetail)
                            )
                        }
                    },
                    onBackFromDetail = { backFired++ },
                )
            }
        }

        compose.onNodeWithTag("list-pane-tap-target").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pane-timeline").assertIsDisplayed()

        // Run on the UI thread rather than the instrumentation thread this
        // test method itself runs on: OnBackPressedDispatcher expects to be
        // driven from there, the same thread performClick()'s synthetic
        // touch above was already dispatched on.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(1, backFired)
        // Not assertDoesNotExist()-on-timeline-then-assertIsDisplayed()-on-
        // roster alone: on a one-pane shell only the current destination's
        // pane is actually laid out, so the roster reappearing here (rather
        // than merely existing off-screen, the iPad fault this suite's own
        // class doc warns about) is real evidence the app came back to it.
        compose.onNodeWithTag("pane-roster").assertIsDisplayed()
    }

    /**
     * The other half of the same defect: on a two-pane (or wider) shell the
     * roster is already on screen beside the detail pane, so back must keep
     * its default platform behaviour rather than being intercepted for no
     * reason. Checked via [androidx.activity.OnBackPressedDispatcher.hasEnabledCallbacks],
     * not by actually pressing back — pressing back here, correctly
     * unhandled, would finish the underlying test activity, which is not a
     * distinction this suite needs to survive to make its point.
     */
    @Test
    fun systemBackDoesNotInterceptOnATwoPaneShell() {
        compose.setContent {
            Box(Modifier.requiredSize(840.dp, 800.dp).testTag(ShellTag)) {
                RootScaffold(
                    listPaneContent = { _, openDetail, _ ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("list-pane-tap-target")
                                .clickable(onClick = openDetail)
                        )
                    },
                )
            }
        }

        // Navigate to Detail exactly as the phone test does, so the only
        // variable left between the two tests is pane count, not whether
        // there is a current destination to intercept back from.
        compose.onNodeWithTag("list-pane-tap-target").performClick()
        compose.waitForIdle()

        assertTrue(
            "back was intercepted on a two-pane shell, where the roster is already visible",
            !compose.activity.onBackPressedDispatcher.hasEnabledCallbacks(),
        )
    }

    /**
     * The iPad incident (issue #26), reproduced as a regression test rather
     * than left as a comment: a room-info panel laid out off the side of a
     * window it no longer fit in. Opens the info pane for real — via
     * `listPaneContent`'s `openInfo` callback, the same way `openDetail` is
     * driven by [tappingInTheListPaneOpensTheDetailPaneOnAPhone] — at a
     * three-pane (1200dp) width, confirms it is actually on screen
     * ([assertWithinShell], not `assertExists()`; see the class doc), then
     * narrows below [ThreePaneWidth] to a two-pane (840dp) width and asserts
     * it is gone.
     *
     * This is the test [RootScaffold]'s `LaunchedEffect(panes)` exists for.
     * Before this task nothing could ever drive `currentDestination` to
     * `Extra`, so that effect was dead code by its own admission; opening the
     * pane through a real `openInfo()` call is what makes it load-bearing,
     * and this is the test that would fail without it.
     *
     * Confirmed by deleting that `LaunchedEffect` and re-running this test:
     * it failed (pane-info was still present after narrowing). Restored
     * afterward — see the commit this test was added in for the exact
     * failure output.
     */
    @Test
    fun narrowingBelowThreePanesStrandsAnOpenInfoPane() {
        var width by mutableStateOf(1200.dp)
        compose.setContent {
            Box(Modifier.requiredSize(width, 800.dp).testTag(ShellTag)) {
                RootScaffold(
                    listPaneContent = { _, _, openInfo ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("list-pane-open-info-target")
                                .clickable(onClick = openInfo)
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag("list-pane-open-info-target").performClick()
        compose.waitForIdle()
        assertWithinShell("pane-info")

        width = 840.dp
        compose.waitForIdle()

        compose.onNodeWithTag("pane-info").assertDoesNotExist()
    }

    /**
     * The gap PaneLayout.kt's own comment used to flag: below
     * [ThreePaneWidth] there is no Extra partition for room info to live in
     * as a third partition alongside the other two. Investigating that
     * comment turned up a real behaviour worth pinning directly rather than
     * assuming: `ListDetailPaneScaffold` at `maxHorizontalPartitions = 2`
     * does not simply drop the request — it satisfies `navigateTo(Extra)` by
     * *replacing* the detail (timeline) pane with it, the same way it would
     * on a phone shell with one partition. Confirmed by probing bounds
     * directly: at 840dp with info requested, `pane-info` lands at real,
     * on-screen bounds while `pane-timeline` degenerates to
     * `Rect(0,0,0,0)` — present in the tree, laid out nowhere.
     *
     * So the tap is not dead (the original hypothesis this task set out to
     * check), but what happens is still not the treatment the spec
     * describes — "roster | timeline, info as a bottom sheet"
     * (`docs/superpowers/specs/2026-08-20-android-scaffold-design.md:181`) —
     * because the timeline disappears rather than staying put underneath an
     * overlay. This test asserts the actual spec: at a two-pane width with
     * info requested, both `pane-timeline` and `pane-info` are on screen
     * *at once* — proof info arrived as a sheet over the two panes, not in
     * place of one of them. Requests info at a fresh two-pane (840dp) width
     * — never having been at three panes first, which is
     * [narrowingBelowThreePanesStrandsAnOpenInfoPane]'s scenario, not this
     * one. Geometry via [assertWithinShell], not `assertExists()` — that
     * would not have caught the iPad fault and would not catch this one
     * either (it would have passed against today's replace-the-timeline
     * behaviour too).
     */
    /**
     * Defect 1 (soft-lock, Task 5): at `panes == 1`, the sheet fallback used
     * to stop short at `panes == 2` — a phone-width shell asking for room
     * info got neither an Extra partition (that only exists at `panes ==
     * 3`) nor the sheet (gated to `panes == 2` alone), so
     * `ListDetailPaneScaffold` was handed a scaffold value with Extra
     * Expanded and no lambda to draw it: a blank screen with an empty
     * accessibility tree, reproduced twice via `uiautomator dump` on the
     * device, with no back handler to recover it.
     *
     * Mirrors [aTabletInPortraitShowsRoomInfoAsASheetOverBothPanes]'s shape
     * at a phone width instead of a tablet's — geometry via
     * [assertWithinShell], not `assertExists()`, for the same reason that
     * test gives: a pane present in the tree at degenerate `Rect(0,0,0,0)`
     * bounds would pass `assertExists()` and still be the bug.
     */
    @Test
    fun aPhoneShowsRoomInfoAsASheetWhenRequested() {
        compose.setContent {
            Box(Modifier.requiredSize(411.dp, 800.dp).testTag(ShellTag)) {
                RootScaffold(
                    listPaneContent = { _, _, openInfo ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("list-pane-open-info-target")
                                .clickable(onClick = openInfo)
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag("list-pane-open-info-target").performClick()
        compose.waitForIdle()

        assertWithinShell("pane-info")
    }

    @Test
    fun aTabletInPortraitShowsRoomInfoAsASheetOverBothPanes() {
        compose.setContent {
            Box(Modifier.requiredSize(840.dp, 800.dp).testTag(ShellTag)) {
                RootScaffold(
                    listPaneContent = { _, _, openInfo ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("list-pane-open-info-target")
                                .clickable(onClick = openInfo)
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag("list-pane-open-info-target").performClick()
        compose.waitForIdle()

        assertWithinShell("pane-info")
        assertWithinShell("pane-timeline")
    }
}
