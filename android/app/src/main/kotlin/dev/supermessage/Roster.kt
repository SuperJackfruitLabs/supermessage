package dev.supermessage

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.RelativeTime
import java.time.Instant
import uniffi.supermessage_core.RosterRow
import uniffi.supermessage_core.RosterSection

/**
 * The roster, laid out the way `apple/Supermessage/Rooms/RoomListView.swift`
 * lays it out: a `LazyColumn`, one sticky header per titled section, one
 * [RoomRow] per entry.
 *
 * **This composable parses nothing and decides nothing.** [sections] and
 * [invitations] arrive already arranged — by
 * `dev.supermessage.kit.RosterArrangement`, from the caller (`MainActivity`'s
 * `listPaneContent`) — and [now] arrives already ticking from that same
 * caller's own `LaunchedEffect`. Nothing here sorts, filters, groups, or
 * derives a state: doing any of that here would be exactly the defect this
 * architecture exists to prevent — a host re-deciding what the core already
 * decided, and a second opinion about what a roster looks like.
 *
 * Each entry's [uniffi.supermessage_core.AgentState] is read off
 * `entry.state`, never recomputed via `RosterArrangement.state` — that
 * function's own KDoc warns it is a boundary crossing per visible room per
 * re-render, and [sections] already carries the answer.
 *
 * Rows are keyed by `entry.row.room.id` (never by list index): that stable
 * key is what stops a room visually jumping — or worse, a `LazyColumn`
 * mis-recycling one row's composed state onto another room — when the list
 * reorders under it, which a roster does constantly as rooms speak.
 *
 * Tapping a row opens it ([onOpenRoom]); tapping its avatar asks about it
 * ([onOpenInfo]) — [RoomRow] exposes no click handler of its own for the
 * former, by design (see that file's own KDoc), so this is where the tap
 * target for "open the conversation" is added, wrapping the row exactly as
 * `RoomListView.swift` relies on `List`'s own row tap to do the same job.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Roster(
    sections: List<RosterSection>,
    invitations: List<RosterRow>,
    invitationsOpen: Boolean,
    onToggleInvitations: () -> Unit,
    now: Instant,
    avatarUri: (roomId: String) -> String?,
    showsState: Boolean = true,
    hidesHost: Boolean = false,
    onOpenRoom: (roomId: String) -> Unit = {},
    onOpenInfo: (roomId: String) -> Unit = {},
    onLoadAvatar: suspend (roomId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    @Composable
    fun RosterEntry(entry: RosterRow) {
        val roomId = entry.row.room.id
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Open ${entry.row.identity.name}") { onOpenRoom(roomId) }
                .padding(horizontal = 16.dp)
                .testTag("roster-row"),
        ) {
            RoomRow(
                row = entry.row,
                avatarUri = avatarUri(roomId),
                state = entry.state,
                `when` = RelativeTime.label(entry.row.room.lastActivityMs, now),
                showsState = showsState,
                hidesHost = hidesHost,
                describesAgent = entry.describesAgent,
                onOpenInfo = { onOpenInfo(roomId) },
            )
        }

        // Mirrors RoomListView.swift's `.task { await session.avatars.load(...) }`
        // on every appearance — including a row that scrolls back
        // into view, which is exactly the case the avatar cache's
        // own KDoc describes going stale without.
        LaunchedEffect(roomId) { onLoadAvatar(roomId) }
    }

    LazyColumn(modifier = modifier.testTag("roster")) {
        // Invitations are one row that opens in place (`RoomListView.swift:122-136`),
        // not a preference: the core's sections never carry them, and the open
        // state is deliberately not remembered.
        if (invitations.isNotEmpty()) {
            item(key = "invitations-row") {
                InvitationsRow(count = invitations.size, open = invitationsOpen, onToggle = onToggleInvitations)
            }
            if (invitationsOpen) {
                items(invitations, key = { "invitation-${it.row.room.id}" }) { entry -> RosterEntry(entry) }
            }
        }

        for (section in sections) {
            val title = section.title
            if (title != null) {
                stickyHeader(key = "header-${section.id}") {
                    SectionHeader(title = title, detail = section.detail, attention = section.attention)
                }
            }

            items(section.rows, key = { it.row.room.id }) { entry -> RosterEntry(entry) }
        }
    }
}

/** "1 invitation" or "N invitations" (`RoomListView.swift:355-390`). */
internal fun invitationsLabel(count: Int): String = if (count == 1) "1 invitation" else "$count invitations"

/** The envelope, the count and a chevron that turns over when open. */
@Composable
internal fun InvitationsRow(count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (open) "Hides them" else "Shows them", onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("invitations-row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Email,
            contentDescription = null,
            tint = SupermessageTheme.colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            invitationsLabel(count),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = SupermessageTheme.colors.contentMuted,
            modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f),
        )
    }
}

/**
 * A section heading: what it is, how much of it, and whether it wants you —
 * `SectionHeader` in `RoomListView.swift`. Only rendered for a section whose
 * [title] is non-null, matching the core's own convention that an
 * arrangement with one unlabeled section carries `title = null`.
 */
@Composable
private fun SectionHeader(title: String, detail: String?, attention: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("section-header"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
