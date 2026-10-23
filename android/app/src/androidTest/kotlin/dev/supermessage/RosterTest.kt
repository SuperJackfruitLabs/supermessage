package dev.supermessage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RosterRow
import uniffi.supermessage_core.RosterSection

/**
 * `Roster` parses nothing and decides nothing — see that file's own KDoc —
 * so these tests pin only what it is responsible for: rendering
 * [RosterArrangement]'s answer in the order it was given, staying reactive
 * to a changing roster without any touch, and admitting what it is
 * withholding. None of them assert an ordering, grouping or state Roster
 * itself computed, because it never computes one.
 */
class RosterTest {

    @get:Rule val compose = createComposeRule()

    private fun row(id: String, name: String): RoomRow = RoomRow(
        room = RoomSummary(
            id = id, name = name, avatarUrl = null, unread = 0uL, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = null, runtime = null, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
        preview = null,
        affordance = RoomAffordance.COMPOSE,
    )

    private fun section(id: String, title: String, entryName: String, entryId: String = "!$entryName:x") =
        RosterSection(
            id = id, title = title, detail = null,
            rows = listOf(RosterRow(row = row(entryId, entryName), state = AgentState.ACTIVE, describesAgent = true)),
            attention = false,
        )

    /**
     * Sections and their rows render in the order the core returned them —
     * never re-sorted by this composable.
     *
     * The section ids are chosen so that an alphabetical sort would flip
     * them: "b-section" is given *before* "a-section", so a `Roster` that
     * secretly sorted by id (Step 6's mutation) would render Bravo above
     * Alpha, failing this assertion — the same failure this test is here to
     * catch.
     */
    @Test
    fun sectionsRenderInCoreOrder() {
        val sections = listOf(
            section(id = "b-section", title = "Needs you", entryName = "Alpha"),
            section(id = "a-section", title = "Idle", entryName = "Bravo"),
        )

        compose.setContent {
            Roster(sections = sections, invitations = emptyList(), invitationsOpen = false, onToggleInvitations = {}, now = Instant.now(), avatarUri = { null })
        }

        val alphaTop = compose.onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top
        val bravoTop = compose.onNodeWithText("Bravo").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "Alpha (first section) rendered at $alphaTop, Bravo (second section) at $bravoTop — " +
                "expected the core's own order, not a re-sort",
            alphaTop < bravoTop,
        )
    }

    /**
     * The list re-renders when the roster changes, with no touch — the
     * caller drives `sections` from a `StateFlow` via
     * `collectAsStateWithLifecycle` (Compose `State`, here stood in for by
     * `mutableStateOf`, the same read-triggers-recomposition contract),
     * never a value captured once and left stale.
     *
     * Asserts the arriving room was actually *displayed*, not merely that
     * the `sections` variable now holds it — the latter would pass even
     * against the bug this guards: iOS's avatars that only appeared on the
     * second scroll, because reading a cache after the fact succeeds
     * whether or not anything told the view to redraw.
     */
    @Test
    fun anArrivingRoomIsShownWithoutATouch() {
        var sections by mutableStateOf(listOf(section(id = "s1", title = "Idle", entryName = "Alpha")))

        compose.setContent {
            Roster(sections = sections, invitations = emptyList(), invitationsOpen = false, onToggleInvitations = {}, now = Instant.now(), avatarUri = { null })
        }

        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Bravo").assertDoesNotExist()

        // No click, no re-`setContent` — only the state a real StateFlow
        // collection would produce when a `RoomsDiff` arrives.
        sections = listOf(
            section(id = "s1", title = "Idle", entryName = "Alpha"),
            section(id = "s2", title = "Idle", entryName = "Bravo"),
        )
        compose.waitForIdle()

        compose.onNodeWithText("Bravo").assertIsDisplayed()
    }

    private fun invitation(id: String, name: String) = RosterRow(
        row = row(id, name).let {
            it.copy(
                room = it.room.copy(membership = Membership.INVITED),
                affordance = RoomAffordance.RESPOND_TO_INVITATION,
            )
        },
        state = AgentState.QUIET,
        describesAgent = false,
    )

    private fun rosterWithInvitations(open: Boolean, toggles: () -> Unit = {}) {
        compose.setContent {
            Roster(
                sections = listOf(section(id = "s1", title = "Idle", entryName = "Alpha")),
                invitations = listOf(invitation("!i1:x", "Lab Room"), invitation("!i2:x", "Ops Room")),
                invitationsOpen = open,
                onToggleInvitations = toggles,
                now = Instant.now(),
                avatarUri = { null },
            )
        }
    }

    /** Invitations are one row that opens in place, above the sections (`RoomListView.swift:122-136`). */
    @Test
    fun invitationsAreARowThatOpensInPlace() {
        var open by mutableStateOf(false)
        compose.setContent {
            Roster(
                sections = listOf(section(id = "s1", title = "Idle", entryName = "Alpha")),
                invitations = listOf(invitation("!i1:x", "Lab Room"), invitation("!i2:x", "Ops Room")),
                invitationsOpen = open,
                onToggleInvitations = { open = !open },
                now = Instant.now(),
                avatarUri = { null },
            )
        }
        compose.onNodeWithText("2 invitations").assertIsDisplayed()
        compose.onNodeWithText("Lab Room").assertDoesNotExist()
        compose.onNodeWithText("Ops Room").assertDoesNotExist()

        compose.onNodeWithTag("invitations-row").performClick()
        compose.waitForIdle()

        val rowBottom = compose.onNodeWithTag("invitations-row").fetchSemanticsNode().boundsInRoot.bottom
        val headerTop = compose.onNodeWithTag("section-header").fetchSemanticsNode().boundsInRoot.top
        for (name in listOf("Lab Room", "Ops Room")) {
            val top = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top
            assertTrue("$name at $top must sit below the row ($rowBottom)", top >= rowBottom)
            assertTrue("$name at $top must sit above the first header ($headerTop)", top < headerTop)
        }
    }

    @Test
    fun oneInvitationIsSingular() {
        assertEquals("1 invitation", invitationsLabel(1))
        assertEquals("3 invitations", invitationsLabel(3))
    }

    /** The toggle is the caller's; the row only reports the tap. */
    @Test
    fun tappingTheInvitationsRowAsksToToggle() {
        var toggles = 0
        rosterWithInvitations(open = false) { toggles++ }
        compose.onNodeWithTag("invitations-row").performClick()
        assertEquals(1, toggles)
    }

    /** No invitations, no row. */
    @Test
    fun noInvitationsShowsNoRow() {
        compose.setContent {
            Roster(
                sections = emptyList(), invitations = emptyList(), invitationsOpen = false,
                onToggleInvitations = {}, now = Instant.now(), avatarUri = { null },
            )
        }
        compose.onNodeWithTag("invitations-row").assertDoesNotExist()
    }
}
