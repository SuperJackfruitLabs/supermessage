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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.RelativeTime
import dev.supermessage.kit.RosterArrangement
import java.time.Instant
import uniffi.supermessage_core.RosterFilter
import uniffi.supermessage_core.RosterRow
import uniffi.supermessage_core.RosterSection
import uniffi.supermessage_ffi.spaceDisplayName

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
    onOpenRoom: (roomId: String) -> Unit = {},
    onOpenInfo: (roomId: String) -> Unit = {},
    onLoadAvatar: suspend (roomId: String) -> Unit = {},
    /** The swipe from the leading edge (iOS "Mark read"), or none for this row. */
    leadingSwipe: (RosterRow) -> SwipeAction? = { null },
    /** The swipe buttons from the trailing edge, or none for this row. */
    trailingSwipes: (RosterRow) -> List<SwipeAction> = { emptyList() },
    modifier: Modifier = Modifier,
    /** The chip in force. [sections] are the whole arrangement; the chip narrows them here. */
    filter: RosterFilter = RosterFilter.ALL,
    onFilter: (RosterFilter) -> Unit = {},
    /** Whether the roster has been read at all: an unread roster is not an empty account. */
    loaded: Boolean = true,
    /** The core's count of invitations withheld from [sections] (`RosterArrangement.hiddenInvitations`). */
    waitingInvitations: Int = invitations.size,
) {
    // The chips count the whole arrangement, so "Unread 2" stays 2 once it is chosen.
    val chips = remember(sections) { RosterArrangement.filterChips(sections) }
    val shown = remember(sections, filter) { RosterArrangement.filterSections(sections, filter) }
    // Whether the account has rooms at all, invitations included (iOS `!session.rooms.rooms.isEmpty`).
    val hasRooms = invitations.isNotEmpty() || sections.any { it.rows.isNotEmpty() }
    // A space or host section is named the way a person would say it (`spaceDisplayName`).
    val titles = remember(shown) { shown.associate { it.id to it.title?.let(::spaceDisplayName) } }

    @Composable
    fun RosterEntry(entry: RosterRow) {
        val roomId = entry.row.room.id
        SwipeActionsRow(leading = leadingSwipe(entry), trailing = trailingSwipes(entry)) { swipeSemantics ->
            RosterEntryRow(
                entry = entry,
                now = now,
                avatarUri = avatarUri(roomId),
                showsState = showsState,
                onOpenRoom = { onOpenRoom(roomId) },
                onOpenInfo = { onOpenInfo(roomId) },
                modifier = swipeSemantics,
            )
        }

        // Mirrors RoomListView.swift's `.task { await session.avatars.load(...) }`
        // on every appearance — including a row that scrolls back
        // into view, which is exactly the case the avatar cache's
        // own KDoc describes going stale without.
        LaunchedEffect(roomId) { onLoadAvatar(roomId) }
    }

    LazyColumn(modifier = modifier.testTag("roster-list")) {
        // Inside the scroll content on purpose: here when the reader arrives, gone
        // when they scroll, and above the invitations row (`RoomListView.swift:101-120`).
        if (hasRooms) {
            item(key = "filter-chips") { FilterChips(chips = chips, selected = filter, onSelect = onFilter) }
        }
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

        for (section in shown) {
            val title = titles[section.id]
            if (title != null) {
                stickyHeader(key = "header-${section.id}") {
                    SectionHeader(title = title, detail = section.detail, attention = section.attention)
                }
            }

            items(section.rows, key = { it.row.room.id }) { entry -> RosterEntry(entry) }
        }

        // What the list is empty of, in iOS's order (`RoomListView.swift:179-199`).
        val empty: (@Composable () -> Unit)? = when {
            !hasRooms -> if (loaded) {
                {
                    ContentUnavailable(
                        title = "No rooms yet",
                        description = "Rooms appear here as they sync.",
                        icon = Icons.Filled.Inbox,
                        modifier = Modifier.testTag("roster-empty-none"),
                    )
                }
            } else {
                null
            }
            shown.isEmpty() && filter != RosterFilter.ALL -> {
                {
                    val words = RosterArrangement.filterEmptyState(filter)
                    ContentUnavailable(
                        title = words.title,
                        description = words.description,
                        icon = Icons.Filled.FilterList,
                        modifier = Modifier.testTag("roster-empty-filter"),
                        action = {
                            TextButton(
                                onClick = { onFilter(RosterFilter.ALL) },
                                modifier = Modifier.testTag("roster-show-all"),
                            ) { Text("Show all") }
                        },
                    )
                }
            }
            shown.isEmpty() && !invitationsOpen -> {
                {
                    ContentUnavailable(
                        title = "Nothing but invitations",
                        description = "$waitingInvitations waiting — open Invitations above.",
                        icon = Icons.Filled.Email,
                        modifier = Modifier.testTag("roster-empty-invitations"),
                    )
                }
            }
            else -> null
        }
        if (empty != null) {
            item(key = "empty") {
                // Under the chips, which stay: the way back from a chip is one of them.
                Box(Modifier.fillParentMaxWidth().fillParentMaxHeight(if (hasRooms) 0.75f else 1f)) { empty() }
            }
        }
    }
}

/**
 * One tappable roster row: the two-line [RoomRow] with the tap that opens the
 * room. The roster, Needs you and (without the swipes) any other list of
 * rooms draw it, so a row looks and acts the same wherever it is listed.
 */
@Composable
internal fun RosterEntryRow(
    entry: RosterRow,
    now: Instant,
    avatarUri: String?,
    showsState: Boolean,
    onOpenRoom: () -> Unit,
    onOpenInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .clickable(onClickLabel = "Open ${entry.row.identity.name}", onClick = onOpenRoom)
            .padding(horizontal = 16.dp)
            .testTag("roster-row"),
    ) {
        RoomRow(
            row = entry.row,
            avatarUri = avatarUri,
            state = entry.state,
            `when` = RelativeTime.label(entry.row.room.lastActivityMs, now),
            showsState = showsState,
            describesAgent = entry.describesAgent,
            style = entry.avatar,
            onOpenInfo = onOpenInfo,
        )
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
