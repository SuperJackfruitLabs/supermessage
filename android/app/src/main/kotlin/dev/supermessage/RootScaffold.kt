package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.Session

/**
 * The shell, gated on [Session.Phase] the way iOS gates at
 * `apple/Supermessage/RootView.swift:15-25`: [Session.Phase.STARTING] shows a
 * progress indicator, [Session.Phase.SIGNED_OUT] shows [LoginScreen], and
 * [Session.Phase.SIGNED_IN] shows the panes below — unchanged.
 *
 * `phase` defaults to [Session.Phase.SIGNED_IN] so every existing caller of
 * `RootScaffold()` — RootScaffoldTest's geometry tests among them — keeps
 * compiling and keeps seeing exactly what it saw before this gate existed.
 *
 * The five `login*` parameters carry the [LoginScreen] contract — the
 * homeserver, its setter, the last failure, whether a sign-in is in flight,
 * and the sign-in callback — straight through with plain values and
 * defaults, deliberately not a [Session]. `MainActivity` is where those
 * values actually come from: it owns the `RosterPreferences` and the
 * `Session` this screen needs, and reaches down through `RootScaffold` with
 * nothing more than what [LoginScreen] itself asks for. That keeps this
 * composable, like [LoginScreen], free of `Session` and constructible with
 * only default values — which is what lets `PhaseGateTest`'s
 * `signedOutShowsLoginAndNoPanes` call `RootScaffold(phase =
 * Session.Phase.SIGNED_OUT)` with nothing else and still see the "login" tag.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RootScaffold(
    modifier: Modifier = Modifier,
    phase: Session.Phase = Session.Phase.SIGNED_IN,
    loginHomeserver: String = "",
    onLoginHomeserverChange: (String) -> Unit = {},
    loginFailure: String? = null,
    loginBusy: Boolean = false,
    onLoginSignIn: (username: String, password: String) -> Unit = { _, _ -> },
) {
    when (phase) {
        Session.Phase.STARTING -> Starting(modifier)
        Session.Phase.SIGNED_OUT -> Box(modifier.fillMaxSize().testTag("login")) {
            LoginScreen(
                homeserver = loginHomeserver,
                onHomeserverChange = onLoginHomeserverChange,
                failure = loginFailure,
                busy = loginBusy,
                onSignIn = onLoginSignIn,
            )
        }
        Session.Phase.SIGNED_IN -> SignedIn(modifier)
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

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SignedIn(modifier: Modifier = Modifier) {
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
        // placeholder never calls navigateTo(Extra, ...), so currentDestination
        // can never be Extra and this effect can never fire — verified by
        // deleting it and re-running paneCountFollowsAWidthChangeDuringComposition,
        // which still passed (that test's pane-count logic is driven by
        // scaffoldValue, not by navigation state; see its own comment). No
        // test exercises this block today, and none can until something
        // calls navigateTo(Extra, ...). It stays because when that arrives,
        // its absence would be the exact iOS fault: an opened pane stranded
        // by a narrowing it was never told about.
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
