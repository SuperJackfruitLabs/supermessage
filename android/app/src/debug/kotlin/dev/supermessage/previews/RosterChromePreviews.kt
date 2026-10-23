package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.supermessage.FilterChips
import dev.supermessage.Roster
import dev.supermessage.SpaceMenu
import java.time.Instant
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RosterFilter
import uniffi.supermessage_core.RosterFilterChip
import uniffi.supermessage_ffi.spaceDisplayName
import uniffi.supermessage_core.SpaceSummary

private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

private fun space(id: String, name: String, children: ULong, membership: Membership = Membership.JOINED) =
    SpaceSummary(
        id = id, name = name, avatarUrl = null, childCount = children, membership = membership,
        identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
        displayName = name,
    )

private val threeSpaces = listOf(
    space("!ops:x", "Ops", 3uL),
    space("!lab:x", "Lab", 0uL, Membership.INVITED),
    space("!home:x", "Home", 7uL),
)

/** The title with three spaces, one an invitation, one selected. */
@Preview(name = "Space menu, three spaces", showBackground = true, widthDp = 320, heightDp = 64)
@Composable
internal fun SpaceMenuThreeSpaces() {
    PreviewGround {
        SpaceMenu(
            spaces = threeSpaces, selectedId = "!ops:x", allCount = 12,
            isInvitation = { it.membership == Membership.INVITED }, onSelect = {},
        )
    }
}

/** Most accounts have no spaces: the title is only a title. */
@Preview(name = "Space menu, none", showBackground = true, widthDp = 320, heightDp = 64)
@Composable
internal fun SpaceMenuNone() {
    PreviewGround {
        SpaceMenu(spaces = emptyList(), selectedId = null, allCount = 0, isInvitation = { false }, onSelect = {})
    }
}

/** The invitations row, closed: a count and a chevron. */
@Preview(name = "Invitations row, closed", showBackground = true, heightDp = 320)
@Composable
internal fun InvitationsRowClosed() {
    PreviewGround {
        Roster(
            sections = PreviewFixtures.rosterSections.take(1),
            invitations = PreviewFixtures.rosterInvitations,
            invitationsOpen = false,
            onToggleInvitations = {},
            now = NOW,
            avatarUri = { null },
        )
    }
}

/** The same row open: the invitation drawn directly under it, the chevron turned over. */
@Preview(name = "Invitations row, open", showBackground = true, heightDp = 320)
@Composable
internal fun InvitationsRowOpen() {
    PreviewGround {
        Roster(
            sections = PreviewFixtures.rosterSections.take(1),
            invitations = PreviewFixtures.rosterInvitations,
            invitationsOpen = true,
            onToggleInvitations = {},
            now = NOW,
            avatarUri = { null },
        )
    }
}

/** The Chats toolbar: the account's initial at the leading edge, compose at the trailing, the title between. */
@Preview(name = "Chats toolbar", showBackground = true, widthDp = 411, heightDp = 72)
@Composable
internal fun ChatsToolbarPreview() {
    PreviewGround {
        dev.supermessage.ChatsToolbar(
            accountInitial = "R",
            onAccount = {},
            onCompose = {},
            title = { androidx.compose.material3.Text("Chats", style = androidx.compose.material3.MaterialTheme.typography.titleLarge) },
        )
    }
}

private val appleChips = listOf(
    RosterFilterChip(RosterFilter.ALL, "All", 5u),
    RosterFilterChip(RosterFilter.UNREAD, "Unread", 2u),
)

/** The chips as iOS previews them: All 5, Unread 2 (`RosterFilterChips.swift:60-71`). */
@Preview(name = "Filter chips", showBackground = true, widthDp = 411, heightDp = 64)
@Composable
internal fun FilterChipsPreview() {
    PreviewGround { FilterChips(appleChips, RosterFilter.ALL, {}) }
}

/** Unread chosen: the accent capsule, its count in the same ink. */
@Preview(name = "Filter chips, Unread selected", showBackground = true, widthDp = 411, heightDp = 64)
@Composable
internal fun FilterChipsUnreadSelected() {
    PreviewGround { FilterChips(appleChips, RosterFilter.UNREAD, {}) }
}

/** At text 2.0 the row scrolls rather than wrapping. */
@Preview(name = "Filter chips, text 2.0", showBackground = true, widthDp = 411, heightDp = 96)
@Composable
internal fun FilterChipsLargeText() {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
        PreviewGround { FilterChips(appleChips, RosterFilter.UNREAD, {}) }
    }
}

/** Unread chosen over a roster with nothing unread: the way back is "Show all". */
@Preview(name = "Roster, nothing unread", showBackground = true, heightDp = 420)
@Composable
internal fun RosterNothingUnread() {
    PreviewGround {
        Roster(
            sections = PreviewFixtures.rosterSections.map { section ->
                section.copy(rows = section.rows.map { it.copy(row = it.row.copy(room = it.row.room.copy(unread = 0uL))) })
            },
            invitations = emptyList(), invitationsOpen = false, onToggleInvitations = {},
            now = NOW, avatarUri = { null },
            filter = RosterFilter.UNREAD,
        )
    }
}

/** Every room filtered away and the invitations row still closed. */
@Preview(name = "Roster, only invitations", showBackground = true, heightDp = 420)
@Composable
internal fun RosterOnlyInvitations() {
    PreviewGround {
        Roster(
            sections = emptyList(), invitations = PreviewFixtures.rosterInvitations,
            invitationsOpen = false, onToggleInvitations = {}, now = NOW, avatarUri = { null },
        )
    }
}

/** A generated id and a `.local` host, as a person would say them. */
@Preview(name = "Space menu, readable names", showBackground = true, widthDp = 320, heightDp = 64)
@Composable
internal fun SpaceMenuReadableNames() {
    val spaces = listOf(
        space("!rt:x", "9247e5c1-aaaa-bbbb-cccc-0123456789ab", 3uL).copy(displayName = spaceDisplayName("9247e5c1-aaaa-bbbb-cccc-0123456789ab")),
        space("!mac:x", "Rakeshs-MacBook-Pro.local", 7uL).copy(displayName = spaceDisplayName("Rakeshs-MacBook-Pro.local")),
    )
    PreviewGround {
        SpaceMenu(
            spaces = spaces, selectedId = "!rt:x", allCount = 10,
            isInvitation = { false }, onSelect = {},
        )
    }
}
