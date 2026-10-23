package dev.supermessage.previews

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.AgentsPane
import dev.supermessage.NeedsYouPane
import dev.supermessage.SupermessageTheme
import dev.supermessage.kit.RosterArrangement
import java.time.Instant

/**
 * Needs you and Agents, over the iOS fleet (`NavigationRevampFixtures`).
 *
 * `now` is a fixed instant so the ages do not drift (see `RosterPreviews`).
 * The inbox and the directory are the core's answer for those rooms, called
 * here as the app calls them, not written out by hand.
 */
private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)
private val NOW_MS: ULong = NOW.toEpochMilli().toULong()

@Composable
private fun NeedsYouFrame(dark: Boolean = false, rows: List<uniffi.supermessage_core.RoomRow> = PreviewFixtures.fleet(NOW_MS)) {
    PreviewGround(dark = dark) {
        // The app's Surface sets this; a bare preview ground does not, and dark names would be black.
        CompositionLocalProvider(LocalContentColor provides SupermessageTheme.colors.content) {
            NeedsYouPane(
                inbox = RosterArrangement.needsYouInbox(rows, NOW),
                now = NOW,
                showsState = true,
                avatarUri = { null },
                onLoadAvatar = {},
                onOpenRoom = {},
                onOpenInfo = {},
            )
        }
    }
}

@Composable
private fun AgentsFrame(dark: Boolean = false, rows: List<uniffi.supermessage_core.RoomRow> = PreviewFixtures.fleet(NOW_MS)) {
    PreviewGround(dark = dark) {
        // The app's Surface sets this; a bare preview ground does not, and dark names would be black.
        CompositionLocalProvider(LocalContentColor provides SupermessageTheme.colors.content) {
            AgentsPane(
                agents = RosterArrangement.agentDirectory(rows, NOW),
                now = NOW,
                openRoomId = null,
                openRoomInProgress = false,
                avatarUri = { null },
                onLoadAvatar = {},
                onOpenRoom = {},
            )
        }
    }
}

@Preview(name = "Needs you", showBackground = true, heightDp = 480)
@Composable
internal fun NeedsYou() = NeedsYouFrame()

@Preview(name = "Needs you, dark", showBackground = true, heightDp = 480)
@Composable
internal fun NeedsYouDark() = NeedsYouFrame(dark = true)

@Preview(name = "All caught up", showBackground = true, heightDp = 480)
@Composable
internal fun AllCaughtUp() = NeedsYouFrame(rows = PreviewFixtures.caughtUp(NOW_MS))

@Preview(name = "Needs you, 2x", showBackground = true, heightDp = 900, fontScale = 2f)
@Composable
internal fun NeedsYouLargeText() = NeedsYouFrame()

@Preview(name = "Agents", showBackground = true, heightDp = 480)
@Composable
internal fun Agents() = AgentsFrame()

@Preview(name = "Agents, dark", showBackground = true, heightDp = 480)
@Composable
internal fun AgentsDark() = AgentsFrame(dark = true)

@Preview(name = "No agents yet", showBackground = true, heightDp = 480)
@Composable
internal fun NoAgentsYet() = AgentsFrame(rows = listOf(PreviewFixtures.roomBare))

@Preview(name = "Agents, 2x", showBackground = true, heightDp = 900, fontScale = 2f)
@Composable
internal fun AgentsLargeText() = AgentsFrame()
