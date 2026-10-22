package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.RoomRow
import dev.supermessage.SupermessageTheme
import dev.supermessage.SwipeAction
import dev.supermessage.SwipeRowFrame
import dev.supermessage.revealedTrailingPx
import java.time.Instant
import uniffi.supermessage_core.AgentState

private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

@Composable
private fun SwipedRow(offset: (density: androidx.compose.ui.unit.Density) -> Float) {
    val density = LocalDensity.current
    PreviewGround {
        val colors = SupermessageTheme.colors
        SwipeRowFrame(
            offset = offset(density),
            leading = SwipeAction("Mark read", colors.accent) { true },
            trailing = listOf(
                SwipeAction("Mute", colors.contentMuted) { true },
                SwipeAction("Pin", colors.ok) { true },
            ),
        ) {
            RoomRow(
                row = PreviewFixtures.roomActive, avatarUri = null, state = AgentState.ACTIVE,
                `when` = "2m", describesAgent = true,
                onOpenInfo = null,
            )
        }
    }
}

/** A row pulled left: Mute and Pin revealed, no full swipe. */
@Preview(name = "Roster row, swiped trailing", showBackground = true, heightDp = 120)
@Composable
internal fun RosterRowSwipedTrailing() {
    SwipedRow { -revealedTrailingPx(2, it) }
}

/** A row pulled right: "Mark read" under it. */
@Preview(name = "Roster row, swiped leading", showBackground = true, heightDp = 120)
@Composable
internal fun RosterRowSwipedLeading() {
    SwipedRow { 260f }
}
