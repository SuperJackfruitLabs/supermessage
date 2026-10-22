package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.Roster
import dev.supermessage.SpaceMenu
import java.time.Instant
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomIdentity
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
