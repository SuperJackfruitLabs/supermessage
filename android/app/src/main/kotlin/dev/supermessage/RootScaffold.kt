package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
@Composable
fun RootScaffold(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Captured into a local before Row: BoxWithConstraintsScope and
        // RowScope are both @LayoutScopeMarker-guarded, so `maxWidth` is not
        // reachable by implicit receiver from inside the Row lambda below —
        // only an explicit local (or receiver) crosses that boundary.
        val shellWidth = maxWidth
        val panes = paneCountFor(shellWidth)
        Row(Modifier.fillMaxSize()) {
            // The roster is on screen at launch in every configuration. On a
            // phone it is the stack's root; on a tablet it sits beside the
            // timeline. It is never behind a toggle.
            Pane("pane-roster", "Roster", RosterWidth, shellWidth)
            if (panes >= 2) Pane("pane-timeline", "Timeline", null, shellWidth)
            if (panes >= 3) Pane("pane-info", "Room info", InfoWidth, shellWidth)
        }
    }
}

private val RosterWidth: Dp = 320.dp
private val InfoWidth: Dp = 320.dp

@Composable
private fun RowScope.Pane(tag: String, label: String, fixed: Dp?, shellWidth: Dp) {
    val sizing = if (fixed != null) Modifier.width(fixed) else Modifier.weight(1f)
    Column(
        Modifier
            .then(sizing)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
            .testTag(tag)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text("shell $shellWidth", style = MaterialTheme.typography.bodySmall)
    }
}
