package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shell, and the one decision it makes.
 *
 * Panes are placeholders in this pass: each reports its own measured width, so
 * the adaptation is visible and testable before there is any real data.
 *
 * The width is measured here, at the top, because this is the only place that
 * knows the window's width — a pane reports its own.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RootScaffold(modifier: Modifier = Modifier) {
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

        // Rule 2: when the shell narrows past three panes, an open info pane
        // must go away rather than be laid out where it no longer fits. This
        // placeholder never calls navigateTo(Extra, ...), so it is currently
        // defensive rather than load-bearing — but it is what keeps a future
        // "open info" navigation from stranding the pane the way iOS did.
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
        val extraIsShown = navigator.scaffoldValue[ListDetailPaneScaffoldRole.Extra] != PaneAdaptedValue.Hidden

        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = { Pane("pane-roster", "Roster", shellWidth) },
            detailPane = { Pane("pane-timeline", "Timeline", shellWidth) },
            extraPane = if (extraIsShown) {
                { Pane("pane-info", "Room info", shellWidth) }
            } else null,
        )
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
 * task-7-report.md for the verification trail and PaneLayout.kt for the
 * rule this directive is required to defer to.
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
