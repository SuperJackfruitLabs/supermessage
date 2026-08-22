package dev.supermessage

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Roster, a readable timeline, and a panel, none squeezed to uselessness. */
val ThreePaneWidth: Dp = 1000.dp

/**
 * Roster beside a timeline. Below [ThreePaneWidth] the info panel has no
 * third partition to live in, so RootScaffold shows it as a sheet over the
 * two panes instead of squeezing it into either one — see `SignedIn`'s
 * `ModalBottomSheet` in RootScaffold.kt for where that lives. Before that
 * sheet existed, "no partition" did not mean "not shown" the way this
 * comment used to claim: ListDetailPaneScaffold satisfied a requested info
 * pane at two panes by replacing the timeline outright, not by withholding
 * it — reachable, just not the sheet the design called for.
 */
val TwoPaneWidth: Dp = 600.dp

/**
 * How many panes fit in [width], measured rather than inferred.
 *
 * Deliberately not derived from WindowWidthSizeClass: its Expanded bucket
 * starts at 840dp, and iOS found the info panel laid out off the side of an
 * iPad at 834 points. Measuring is the only honest answer to "is there room".
 */
fun paneCountFor(width: Dp): Int = when {
    width >= ThreePaneWidth -> 3
    width >= TwoPaneWidth -> 2
    else -> 1
}
