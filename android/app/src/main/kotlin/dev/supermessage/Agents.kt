package dev.supermessage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.RelativeTime
import java.time.Instant
import uniffi.supermessage_core.RoomStatus
import uniffi.supermessage_core.RosterRow
import uniffi.supermessage_ffi.roomStatus

/**
 * Agents: every room the core says reads as an agent's, with what each is
 * doing and when it last did anything (`Agents/AgentsView.swift:11-63`).
 *
 * A directory, not a second roster: [agents] is `RosterArrangement.agentDirectory`'s
 * answer in the core's recency order, and nothing here sorts or filters it.
 * Only the open room's turn is visible to the app, so only that room can say
 * Working ([openRoomId], [openRoomInProgress]); every other status is the core's.
 */
@Composable
fun AgentsPane(
    agents: List<RosterRow>,
    now: Instant,
    openRoomId: String?,
    openRoomInProgress: Boolean,
    avatarUri: (String) -> String?,
    onLoadAvatar: (String) -> Unit,
    onOpenRoom: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("agents-list")) {
            items(agents, key = { it.row.room.id }) { entry ->
                val roomId = entry.row.room.id
                AgentRow(
                    entry = entry,
                    avatarUri = avatarUri(roomId),
                    status = agentStatus(entry, openRoomId, openRoomInProgress),
                    `when` = RelativeTime.label(entry.row.room.lastActivityMs, now),
                    onOpen = { onOpenRoom(roomId) },
                )
                LaunchedEffect(roomId) { onLoadAvatar(roomId) }
            }
        }
        if (agents.isEmpty()) {
            ContentUnavailable(
                title = "No agents yet",
                description = "Rooms with an agent in them appear here.",
                icon = Sparkle,
                modifier = Modifier.testTag("no-agents"),
            )
        }
    }
}

/**
 * The status an agent's row states: the core's, from its state, plus the one
 * live fact the app has, whether the open room's turn is running now
 * (`AgentsView.swift:25-27`, `:58-63`).
 */
internal fun agentStatus(entry: RosterRow, openRoomId: String?, openRoomInProgress: Boolean): RoomStatus? =
    roomStatus(entry.state, true, entry.row.room.id == openRoomId && openRoomInProgress)

/** One agent: face, name, role, what it is doing, when it last spoke. One element, one tap. */
@Composable
private fun AgentRow(
    entry: RosterRow,
    avatarUri: String?,
    status: RoomStatus?,
    `when`: String,
    onOpen: () -> Unit,
) {
    val colors = SupermessageTheme.colors
    val identity = entry.row.identity
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent-row-${entry.row.room.id}")
            .clickable(onClickLabel = "Open ${identity.name}", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomAvatar(
            roomId = entry.row.room.id,
            initial = identity.initial,
            avatarUri = avatarUri,
            describesAgent = true,
            style = entry.avatar,
            state = entry.state,
            size = 44.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    identity.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (`when`.isNotEmpty()) {
                    Text(
                        `when`,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.contentFaint,
                        maxLines = 1,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val role = identity.role
                if (!role.isNullOrEmpty()) {
                    Text(
                        role,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (status != null) StatusPill(status, compact = true)
            }
        }
    }
}
