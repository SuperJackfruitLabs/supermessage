package dev.supermessage

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Roster, a readable timeline, and a panel, none squeezed to uselessness. */
val ThreePaneWidth: Dp = 1000.dp

/**
 * Roster beside a timeline. Below [ThreePaneWidth] the info panel has no
 * pane to live in — RootScaffold withholds it entirely rather than squeeze
 * it in, and today that just means "not shown". A sheet over the two panes
 * is the intended future treatment, not something that exists yet.
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
