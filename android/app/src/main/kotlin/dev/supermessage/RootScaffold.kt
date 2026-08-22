package dev.supermessage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.Session
import kotlinx.coroutines.launch

/**
 * The shell, gated on [Session.Phase] the way iOS gates at
 * `apple/Supermessage/RootView.swift:15-25`. This composable is phase-gating
 * logic and nothing else: what each phase actually shows is content the
 * caller supplies, not something this file builds or imports.
 *
 * `phase` defaults to [Session.Phase.SIGNED_IN] so every existing caller of
 * `RootScaffold()` — RootScaffoldTest's geometry tests among them — keeps
 * compiling and keeps seeing exactly what it saw before this gate existed.
 *
 * ## Why content slots, not per-screen parameters
 *
 * An earlier version of this signature carried five `login*` parameters —
 * `LoginScreen`'s whole contract, threaded through individually so
 * `RootScaffold` could build that screen itself. Task 6's roster content
 * would have added a comparable handful for the `SIGNED_IN` side, leaving
 * this function past a dozen parameters spanning two unrelated screens, all
 * defaulted only so old call sites kept compiling — defaults papering over
 * a widening seam rather than closing it. [signedOutContent] and
 * [listPaneContent] replace that: this file no longer imports `LoginScreen`
 * at all, and never will import whatever Task 6's roster content turns out
 * to be. It gates; it does not know what it is gating to.
 *
 * [signedOutContent] defaults to rendering nothing. Nothing in this
 * codebase relies on that default actually showing something —
 * `RootScaffoldTest` never drives [Session.Phase.SIGNED_OUT], and
 * `PhaseGateTest` supplies its own tagged stub rather than depending on
 * whatever `MainActivity`'s real content (`LoginScreen`) happens to render.
 * That is deliberate: a gate test should prove the gate opens the right
 * door, not re-assert what is behind it.
 *
 * [listPaneContent] defaults to today's "Roster" placeholder pane, because
 * unlike the sign-out screen, `SIGNED_IN`'s list pane already has a named
 * consumer on the horizon — Task 6. Confirmed, not assumed: running
 * `RootScaffoldTest` unmodified against this default still passes, because
 * its three geometry tests assert `pane-roster`'s bounds, not its content,
 * and the default reproduces the exact tag and label the old inline call
 * used to render. It takes `shellWidth` as a lambda parameter rather than
 * closing over the one computed inside [SignedIn]: a default parameter
 * expression is evaluated where the parameter is declared, not inside the
 * function body, so it cannot see a local computed deeper in — passing it
 * as an argument is what lets the default (and any real content Task 6
 * supplies) use it without `RootScaffold` needing to expose it any other
 * way.
 *
 * [detailPaneContent] follows the identical shape, added by Task 7 once the
 * timeline had a named consumer for it. It takes no `openDetail`: the
 * detail pane is already open by the time it is shown, so there is nowhere
 * further for it to navigate to. Its default reproduces today's
 * "Timeline" placeholder exactly — same tag, same label — so
 * `RootScaffoldTest`'s five geometry tests, which call bare `RootScaffold()`
 * (or override only `listPaneContent`) and assert on `pane-timeline`'s
 * bounds, keep passing unmodified.
 *
 * [extraPaneContent] follows the same shape, added by the pane-rule task
 * alongside the `openInfo` callback threaded into [listPaneContent] — the
 * third positional parameter, exactly the way [openDetail] was added to that
 * same slot without breaking the geometry tests, which never override it.
 * Its default reproduces the former hardcoded "Room info" placeholder
 * exactly, same tag `pane-info`, same label, so every test that asserted on
 * it before that task keeps passing unmodified. Wiring `openInfo` all the way
 * to the roster's `onOpenInfo` is what makes it possible, for the first time,
 * for `navigator.currentDestination` to actually become
 * `ListDetailPaneScaffoldRole.Extra` — which is exactly what turns the
 * `LaunchedEffect` below from dead code into a load-bearing guard. See its
 * own comment for what that means.
 *
 * This task adds [extraPaneContent]'s own second parameter — a plain `() ->
 * Unit` closer, not a third `openX` — the panel's own way back, the same
 * role [onBackFromDetail] plays for the detail pane's system-back case but
 * reached from an ordinary in-panel "Done" tap or a completed leave rather
 * than the back button. `RoomInfoPanel` calls it without knowing whether
 * what is on the other side is `navigator.navigateBack()`, this file's own
 * placeholder `Pane`, or anything else — the same boundary [openDetail] and
 * [openInfo] already draw. No existing test overrides [extraPaneContent], so
 * widening its signature here changes nothing RootScaffoldTest asserts on.
 *
 * [onBackFromDetail] is this task's fix for the room-is-a-trap defect: on a
 * one-pane shell with the detail pane as the current destination, system
 * back is intercepted (see the [BackHandler] inside [SignedIn]) rather than
 * exiting the app, and this callback fires alongside
 * `navigator.navigateBack()` so the caller can clear whatever selection put
 * the detail pane there — [dev.supermessage.kit.stores.RoomsStore.deselect]
 * in `MainActivity`'s case. This file does not call `deselect()` itself: it
 * does not know `RoomsStore` exists, the same boundary [listPaneContent] and
 * [detailPaneContent] already draw around what fills each pane. Defaults to
 * a no-op so every existing caller — `RootScaffoldTest`'s bare
 * `RootScaffold()` among them — keeps compiling and keeps its prior
 * behaviour.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RootScaffold(
    modifier: Modifier = Modifier,
    phase: Session.Phase = Session.Phase.SIGNED_IN,
    signedOutContent: @Composable () -> Unit = {},
    listPaneContent: @Composable (shellWidth: Dp, openDetail: () -> Unit, openInfo: () -> Unit) -> Unit =
        { shellWidth, _, _ ->
            Pane("pane-roster", "Roster", shellWidth)
        },
    detailPaneContent: @Composable (shellWidth: Dp) -> Unit = { shellWidth ->
        Pane("pane-timeline", "Timeline", shellWidth)
    },
    extraPaneContent: @Composable (shellWidth: Dp, closeInfo: () -> Unit) -> Unit = { shellWidth, _ ->
        Pane("pane-info", "Room info", shellWidth)
    },
    onBackFromDetail: () -> Unit = {},
) {
    when (phase) {
        Session.Phase.STARTING -> Starting(modifier)
        Session.Phase.SIGNED_OUT -> Box(modifier) { signedOutContent() }
        Session.Phase.SIGNED_IN ->
            SignedIn(modifier, listPaneContent, detailPaneContent, extraPaneContent, onBackFromDetail)
    }
}

@Composable
private fun Starting(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .testTag("phase-starting"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SignedIn(
    modifier: Modifier = Modifier,
    listPaneContent: @Composable (shellWidth: Dp, openDetail: () -> Unit, openInfo: () -> Unit) -> Unit,
    detailPaneContent: @Composable (shellWidth: Dp) -> Unit,
    extraPaneContent: @Composable (shellWidth: Dp, closeInfo: () -> Unit) -> Unit,
    onBackFromDetail: () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Captured into a local before the nested pane lambdas: their
        // receiver is ThreePaneScaffoldPaneScope, which shadows the implicit
        // BoxWithConstraintsScope receiver that `maxWidth` lives on — only an
        // explicit local (or receiver) crosses that boundary.
        val shellWidth = maxWidth
        val panes = paneCountFor(shellWidth)
        val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>(
            scaffoldDirective = directiveFor(panes),
        )
        // navigateTo is suspend on this adaptive version (confirmed against
        // ThreePaneScaffoldNavigator's actual signature, not assumed from the
        // navigateBack() call below) — a scope is what lets listPaneContent's
        // ordinary `() -> Unit` callback launch it.
        val scope = rememberCoroutineScope()

        // The fix for "a room is a trap" (Task 7): on a one-pane shell with
        // the detail pane as the current destination, the roster is not on
        // screen beside it — it is the previous screen, off the back stack
        // entirely unless this handler pops it back. On a two-pane-or-wider
        // shell the roster is already visible next to the detail pane, so
        // this must stay disabled there and let system back keep its default
        // behaviour (RootScaffoldTest's two-/three-pane geometry tests don't
        // exercise back at all, which is exactly why `panes == 1` has to be
        // part of `enabled` rather than gating only on `currentDestination`).
        BackHandler(
            enabled = panes == 1 &&
                navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail,
        ) {
            scope.launch { navigator.navigateBack() }
            onBackFromDetail()
        }

        // Rule 2: when the shell narrows past three panes, an open info pane
        // must go away rather than be laid out where it no longer fits. Until
        // this task, nothing called navigateTo(Extra, ...), so
        // currentDestination could never be Extra and this effect could never
        // fire; now that listPaneContent's openInfo callback (below) does,
        // it is load-bearing. Confirmed, not assumed: this file's own test,
        // narrowingBelowThreePanesStrandsAnOpenInfoPane, opens the info pane
        // at a three-pane width, narrows below ThreePaneWidth, and asserts
        // pane-info is gone; deleting this block was observed to fail that
        // test (see the commit message for the exact output), then restored.
        LaunchedEffect(panes) {
            if (panes < 3 && navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Extra) {
                navigator.navigateBack()
            }
        }

        // Gating extraPane below reads navigator.scaffoldValue, not `panes`
        // directly. ListDetailPaneScaffold in 1.2.0 composes whatever slot
        // lambda it is given even when that pane's PaneAdaptedValue is
        // Hidden — it lands in the tree at a degenerate zero-size bounds,
        // not omitted (confirmed by running the suite with extraPane passed
        // unconditionally: pane-info existed at 411dp and 840dp, failing the
        // existence contract Task 6 fixed). So the lambda must still be
        // withheld, exactly as the rule requires — but withheld based on
        // scaffoldValue, which is *derived from* `directive`, not from
        // `panes` a second time. That is what makes the Step 5 mutation
        // below observable: mutate the directive and this gate moves too.
        //
        // At panes == 2 this same scaffoldValue is *not* what decides
        // whether info shows — see [twoPaneScaffoldValue] just below for
        // why, and for the actual defect this investigation turned up
        // (info was never unreachable at two panes; it replaced the
        // timeline instead of sheeting over it).
        val extraIsShown = navigator.scaffoldValue[ListDetailPaneScaffoldRole.Extra] != PaneAdaptedValue.Hidden

        // Whether the caller has asked for info right now, independent of
        // pane count — `navigator.currentDestination` is the navigator's own
        // state, unaffected by whatever `value` this file feeds the
        // scaffold below.
        val infoRequested = navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Extra

        // The two-pane case, investigated rather than assumed: at
        // `maxHorizontalPartitions = 2`, ListDetailPaneScaffold does not
        // simply withhold a requested Extra destination the way "no room"
        // suggests — calculateThreePaneScaffoldValue (the function behind
        // navigator.scaffoldValue) instead grants Extra its own partition
        // by *replacing* Detail, the timeline, which disappears to
        // degenerate zero-size bounds. Confirmed by probing bounds directly
        // at 840dp with info requested: pane-info real, pane-timeline
        // Rect(0,0,0,0). Reachable, but not the "roster | timeline, info as
        // a bottom sheet" treatment the spec (see PaneLayout.kt) describes.
        //
        // So at panes == 2 this file stops handing navigator.scaffoldValue
        // to the scaffold at all and substitutes a value that always keeps
        // List and Detail expanded and Extra hidden — the scaffold never
        // sees Extra as a candidate partition here, regardless of
        // `currentDestination`. Info is shown instead via the
        // [ModalBottomSheet] below, layered over both panes rather than
        // occupying either one's partition.
        val twoPaneScaffoldValue = ThreePaneScaffoldValue(
            primary = PaneAdaptedValue.Expanded, // ListDetailPaneScaffoldRole.Detail
            secondary = PaneAdaptedValue.Expanded, // ListDetailPaneScaffoldRole.List
            tertiary = PaneAdaptedValue.Hidden, // ListDetailPaneScaffoldRole.Extra
        )

        // The one-pane case (Task 5's soft-lock defect): at
        // `maxHorizontalPartitions = 1`, `navigator.scaffoldValue` grants
        // Extra its own partition exactly the way it does at panes == 2 —
        // *replacing* whichever of List/Detail was on screen, both of which
        // degenerate to zero-size bounds. Unlike panes == 2, there is no
        // second partition to fall back to here: the scaffold is left with
        // Extra as its one Expanded role and no lambda to draw it (extraPane
        // above is withheld below panes == 3), so nothing renders at all —
        // a blank screen with an empty accessibility tree, reproduced twice
        // via `uiautomator dump` on the device, with no back handler to
        // recover it (BackHandler above only fires for Detail).
        //
        // `peekPreviousScaffoldValue()` is what a `navigateBack()` from here
        // would resolve to: whichever of List or Detail was the current
        // destination before `openInfo()` pushed Extra, Expanded, and Extra
        // itself Hidden — never both List and Detail at once the way
        // [twoPaneScaffoldValue] hardcodes, because at one partition only
        // one of them was ever on screen to begin with. Substituting it here
        // keeps that prior screen visible and real underneath the
        // [ModalBottomSheet] below, the same relationship [twoPaneScaffoldValue]
        // has with its own sheet.
        val onePaneScaffoldValue = if (panes == 1 && infoRequested) {
            navigator.peekPreviousScaffoldValue()
        } else {
            null
        }

        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = when {
                panes == 2 -> twoPaneScaffoldValue
                onePaneScaffoldValue != null -> onePaneScaffoldValue
                else -> navigator.scaffoldValue
            },
            listPane = {
                listPaneContent(
                    shellWidth,
                    { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) } },
                    { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Extra) } },
                )
            },
            detailPane = { detailPaneContent(shellWidth) },
            // panes == 3 only: at panes == 2 the value substitution above
            // already keeps Extra out of the partition layout, and the sheet
            // below is what shows info instead.
            extraPane = if (panes == 3 && extraIsShown) {
                { extraPaneContent(shellWidth) { scope.launch { navigator.navigateBack() } } }
            } else null,
        )

        // The sheet itself: at any width below three panes — widened from
        // `panes == 2` alone (Task 5's fix for the phone soft-lock: panes ==
        // 1 has exactly the same "no partition for Extra" shape panes == 2
        // does, just with one fewer partition to spare) — and only while
        // info is actually requested. Narrowing from three panes with info
        // open does not reach here showing a sheet, because the
        // LaunchedEffect above already calls navigateBack() first whenever
        // panes drops below 3 with Extra as the current destination (that is
        // [narrowingBelowThreePanesStrandsAnOpenInfoPane]'s scenario: an
        // open info pane collapsing away entirely, not reappearing as a
        // sheet). This branch only ever fires for a fresh openInfo() request
        // made while already at a one- or two-pane width.
        if (panes < 3 && infoRequested) {
            ModalBottomSheet(onDismissRequest = { scope.launch { navigator.navigateBack() } }) {
                extraPaneContent(shellWidth) { scope.launch { navigator.navigateBack() } }
            }
        }
    }
}

/**
 * The directive is built from our measured pane count, never from
 * calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()).
 *
 * That default ties pane count to the real window's WindowSizeClass, not to
 * the width this shell was actually given — the same substitution that put
 * iOS's info panel off the side of an iPad at 834 points. Verified, not
 * assumed: swapping it in here (Step 5's mutation) breaks
 * aTabletInLandscapeShowsAllThreeOnScreen on the reference device, because
 * calculatePaneScaffoldDirective's Expanded bucket (840dp and up, unbounded)
 * always resolves to 2 partitions on this adaptive version — never 3 — so a
 * forced-1200dp shell loses its third pane even though there is room. The
 * failure lands on a different one of Task 6's three tests than the 840dp
 * case this comment used to cite, and it undercounts rather than the
 * overcounts the iPad postmortem describes, but the mechanism is the same
 * substitution: window size class in place of measured width. See
 * PaneLayout.kt for the rule this directive is required to defer to.
 */
private fun directiveFor(panes: Int) = PaneScaffoldDirective.Default.copy(
    maxHorizontalPartitions = panes,
)

@Composable
private fun Pane(tag: String, label: String, shellWidth: Dp) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
            .testTag(tag)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text("shell $shellWidth", style = MaterialTheme.typography.bodySmall)
    }
}
