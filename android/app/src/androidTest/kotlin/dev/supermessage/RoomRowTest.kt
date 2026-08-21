package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RuntimeDto

/**
 * One roster row, laid out exactly as
 * `apple/Supermessage/Rooms/RoomRowView.swift` describes it: this view
 * parses nothing and composes nothing, it lays out what the core already
 * decided. So these tests pin layout and visibility rules only — never a
 * derivation this view has no business making.
 */
class RoomRowTest {
    @get:Rule val compose = createComposeRule()

    /** Shaped like `RoomsStoreTest.row(...)`, extended with what this view reads. */
    private fun row(
        name: String = "Ganesha",
        preview: RoomPreview? = RoomPreview(text = "hey there", pending = false),
        affordance: RoomAffordance = RoomAffordance.COMPOSE,
        unread: ULong = 0uL,
        role: String? = null,
        runtime: RuntimeDto? = null,
        initial: String = "G",
    ): RoomRow = RoomRow(
        room = RoomSummary(
            id = "!a:x", name = name, avatarUrl = null, unread = unread, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = null, runtime = runtime, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = name, role = role, initial = initial),
        preview = preview,
        affordance = affordance,
    )

    /** The name and the preview line are the core's, and both show up. */
    @Test
    fun theNameAndPreviewRender() {
        compose.setContent {
            RoomRow(
                row = row(name = "Ganesha", preview = RoomPreview(text = "hey there", pending = false)),
                avatarUri = null,
                state = AgentState.ACTIVE,
                `when` = "2m",
            )
        }
        compose.onNodeWithText("Ganesha").assertIsDisplayed()
        compose.onNodeWithText("hey there").assertIsDisplayed()
    }

    /** An invitation gets the badge — nothing else does. */
    @Test
    fun theInvitationBadgeShowsForAnInvitation() {
        compose.setContent {
            RoomRow(
                row = row(affordance = RoomAffordance.RESPOND_TO_INVITATION),
                avatarUri = null,
                state = AgentState.QUIET,
                `when` = "",
            )
        }
        compose.onNodeWithText("Invitation").assertIsDisplayed()
    }

    /** An ordinary, composable room draws no invitation badge. */
    @Test
    fun theInvitationBadgeIsAbsentForAnOrdinaryRoom() {
        compose.setContent {
            RoomRow(
                row = row(affordance = RoomAffordance.COMPOSE),
                avatarUri = null,
                state = AgentState.ACTIVE,
                `when` = "",
            )
        }
        compose.onNodeWithText("Invitation").assertDoesNotExist()
    }

    /** A reader can turn the state dot off. */
    @Test
    fun theStateDotShowsByDefault() {
        compose.setContent {
            RoomRow(row = row(), avatarUri = null, state = AgentState.NEEDS_YOU, `when` = "", showsState = true)
        }
        compose.onNodeWithTag("state-dot").assertIsDisplayed()
    }

    /** `showsState = false` hides the dot rather than merely recoloring it. */
    @Test
    fun showsStateFalseHidesTheDot() {
        compose.setContent {
            RoomRow(row = row(), avatarUri = null, state = AgentState.NEEDS_YOU, `when` = "", showsState = false)
        }
        compose.onNodeWithTag("state-dot").assertDoesNotExist()
    }

    /** A null avatar still draws a row — the sigil fallback, not a blank. */
    @Test
    fun aNullAvatarRendersTheSigilFallbackRow() {
        compose.setContent {
            RoomRow(row = row(initial = "G"), avatarUri = null, state = AgentState.IDLE, `when` = "")
        }
        compose.onNodeWithTag("avatar").assertIsDisplayed()
        compose.onNodeWithText("G").assertIsDisplayed()
    }

    /**
     * The avatar is its own tap target: tapping it asks about the room, not
     * about the conversation. This view has no other click handler of its
     * own — the rest of the row's tap-to-open-the-conversation behaviour
     * belongs to whoever places this row in a list.
     */
    @Test
    fun tappingTheAvatarAsksAboutTheRoom() {
        var calls = 0
        compose.setContent {
            RoomRow(row = row(), avatarUri = null, state = AgentState.ACTIVE, `when` = "", onOpenInfo = { calls++ })
        }
        compose.onNodeWithTag("avatar").performClick()
        assertEquals(1, calls)
    }

    /**
     * A malformed `data:` URI must not take the row down — it decodes to
     * `null` and falls back to the sigil, exactly like a missing avatar.
     * Covers both failure shapes `decodeDataUri()` guards against: no comma
     * to split on, and a comma followed by bytes that are not valid Base64.
     */
    @Test
    fun aMalformedAvatarUriFallsBackRatherThanCrashing() {
        compose.setContent {
            RoomRow(row = row(initial = "G"), avatarUri = "not-a-data-uri", state = AgentState.IDLE, `when` = "")
        }
        compose.onNodeWithTag("avatar").assertIsDisplayed()
        compose.onNodeWithText("G").assertIsDisplayed()
    }

    @Test
    fun aDataUriWithInvalidBase64FallsBackRatherThanCrashing() {
        compose.setContent {
            RoomRow(
                row = row(initial = "G"),
                avatarUri = "data:image/png;base64,***not base64***",
                state = AgentState.IDLE,
                `when` = "",
            )
        }
        compose.onNodeWithTag("avatar").assertIsDisplayed()
        compose.onNodeWithText("G").assertIsDisplayed()
    }
}
