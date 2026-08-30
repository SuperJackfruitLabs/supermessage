package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiException
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.StagedFile
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * [InvitationView], the port of
 * `apple/Supermessage/Panels/InvitationView.swift`.
 *
 * [joinRoom]/[leaveRoom] are driven through a real [Session] backed by a
 * fake [CoreInterface] — the same house pattern `NewRoomTest` uses — never
 * this device's own, real, signed-in session.
 */
class InvitationTest {
    @get:Rule val compose = createComposeRule()

    private fun sessionOf(core: FakeCore): Session =
        Session(client = CoreClient(core = core, dispatcher = Dispatchers.Unconfined), scope = CoroutineScope(Dispatchers.Unconfined))

    /**
     * `Session::room_inviter` is asked once, per room, and named on screen —
     * the thing you would want before accepting, and the one thing
     * `InvitationView.swift` added over what came before it.
     */
    @Test
    fun anInvitationNamesItsInviter() {
        val fake = FakeCore(roomInviterResult = { "@cody:example.org" })
        val session = sessionOf(fake)

        compose.setContent {
            InvitationView(
                roomId = "!room:example.org",
                roomName = "Ops Room",
                inviter = session::inviter,
                joinRoom = session::joinRoom,
                leaveRoom = session::leaveRoom,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("invitation-inviter").assertIsDisplayed()
        assertEquals(listOf("!room:example.org"), fake.roomInviterCalls)
    }

    /** Accepting joins the invited room — `Session.joinRoom`, not a re-derived membership write. */
    @Test
    fun acceptingJoinsTheRoom() {
        val fake = FakeCore(
            roomInviterResult = { null },
            joinRoomResult = { },
            roomsSnapshotResult = { RoomsSnapshot(seq = 0uL, rooms = emptyList()) },
            spacesListResult = { emptyList() },
        )
        val session = sessionOf(fake)

        compose.setContent {
            InvitationView(
                roomId = "!room:example.org",
                roomName = "Ops Room",
                inviter = session::inviter,
                joinRoom = session::joinRoom,
                leaveRoom = session::leaveRoom,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("invitation-accept").performClick()
        compose.waitForIdle()

        assertEquals(listOf("!room:example.org"), fake.joinRoomCalls)
        assertEquals(0, fake.leaveRoomCalls.size)
        compose.onNodeWithTag("invitation-failure").assertDoesNotExist()
    }

    /** Declining leaves the invited room, rather than joining it. */
    @Test
    fun decliningLeavesTheRoom() {
        val fake = FakeCore(roomInviterResult = { null }, leaveRoomResult = { })
        val session = sessionOf(fake)

        compose.setContent {
            InvitationView(
                roomId = "!room:example.org",
                roomName = "Ops Room",
                inviter = session::inviter,
                joinRoom = session::joinRoom,
                leaveRoom = session::leaveRoom,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("invitation-decline").performClick()
        compose.waitForIdle()

        assertEquals(listOf("!room:example.org"), fake.leaveRoomCalls)
        assertEquals(0, fake.joinRoomCalls.size)
    }

    /** A refusal shows inline, so accepting again is still an option. */
    @Test
    fun aRefusedAcceptShowsInline() {
        val fake = FakeCore(
            roomInviterResult = { null },
            joinRoomResult = { throw FfiException.Network("no") },
        )
        val session = sessionOf(fake)

        compose.setContent {
            InvitationView(
                roomId = "!room:example.org",
                roomName = "Ops Room",
                inviter = session::inviter,
                joinRoom = session::joinRoom,
                leaveRoom = session::leaveRoom,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("invitation-accept").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("invitation-failure").assertIsDisplayed()
    }

    private class FakeCore(
        private val roomInviterResult: (String) -> String? = { throw NotImplementedError() },
        private val joinRoomResult: (String) -> Unit = { throw NotImplementedError() },
        private val leaveRoomResult: (String) -> Unit = { throw NotImplementedError() },
        private val roomsSnapshotResult: () -> RoomsSnapshot = { RoomsSnapshot(seq = 0uL, rooms = emptyList()) },
        private val spacesListResult: () -> List<SpaceSummary> = { emptyList() },
    ) : CoreInterface {
        val roomInviterCalls = mutableListOf<String>()
        val joinRoomCalls = mutableListOf<String>()
        val leaveRoomCalls = mutableListOf<String>()

        override fun roomInviter(roomId: String): String? {
            roomInviterCalls += roomId
            return roomInviterResult(roomId)
        }

        override fun joinRoom(roomId: String) {
            joinRoomCalls += roomId
            joinRoomResult(roomId)
        }

        override fun leaveRoom(roomId: String) {
            leaveRoomCalls += roomId
            leaveRoomResult(roomId)
        }

        override fun roomsSnapshot(): RoomsSnapshot = roomsSnapshotResult()
        override fun spacesList(): List<SpaceSummary> = spacesListResult()

        override fun account(): AccountDto = throw NotImplementedError()
        override fun attachmentDiscard(token: String): Unit = throw NotImplementedError()
        override fun attachmentSend(roomId: String, token: String): Unit = throw NotImplementedError()
        override fun attachmentStagePath(roomId: String, path: String): StagedFile = throw NotImplementedError()
        override fun connectionState(): ConnectionState = throw NotImplementedError()
        override fun createRoom(name: String, invite: List<String>, isDirect: Boolean): String = throw NotImplementedError()
        override fun deleteMessage(roomId: String, eventId: String): Unit = throw NotImplementedError()
        override fun directRoomWith(userId: String): String? = throw NotImplementedError()
        override fun editMessage(roomId: String, eventId: String, body: String): Unit = throw NotImplementedError()
        override fun inviteUser(roomId: String, userId: String): Unit = throw NotImplementedError()
        override fun joinRoomByAlias(aliasOrId: String): String = throw NotImplementedError()
        override fun knownPeople(): List<PersonDto> = throw NotImplementedError()
        override fun login(homeserver: String, username: String, password: String, sink: EventSink): Unit =
            throw NotImplementedError()
        override fun logout(): Unit = throw NotImplementedError()
        override fun markRoomRead(roomId: String): Unit = throw NotImplementedError()
        override fun mediaFetch(eventId: String): String? = throw NotImplementedError()
        override fun memberAvatar(mxcUri: String): String? = throw NotImplementedError()
        override fun restoreSession(sink: EventSink): Boolean = throw NotImplementedError()
        override fun roomAvatar(roomId: String): String? = throw NotImplementedError()
        override fun roomAvatarFull(roomId: String): String? = throw NotImplementedError()
        override fun roomInfo(roomId: String): RoomInfoDto = throw NotImplementedError()
        override fun searchMessages(term: String, roomId: String?): List<SearchResultDto> = throw NotImplementedError()
        override fun sendMessage(roomId: String, body: String, mentions: List<String>): Unit = throw NotImplementedError()
        override fun sendReply(roomId: String, body: String, inReplyTo: String): Unit = throw NotImplementedError()
        override fun sendGateDecision(
            roomId: String,
            gateId: String,
            optionId: String,
            comment: String?,
            inReplyTo: String,
            prompt: String,
        ): Unit = throw NotImplementedError()
        override fun setRoomNotifications(roomId: String, mode: NotificationMode): Unit = throw NotImplementedError()
        override fun setRoomPinned(roomId: String, pinned: Boolean): Unit = throw NotImplementedError()
        override fun setTyping(roomId: String, typing: Boolean): Unit = throw NotImplementedError()
        override fun spaceSelect(spaceId: String?): Unit = throw NotImplementedError()
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = throw NotImplementedError()
        override fun timelineResync(): TimelineSnapshot = throw NotImplementedError()
        override fun timelineSubscribe(roomId: String, sink: EventSink): Unit = throw NotImplementedError()
        override fun toggleReaction(roomId: String, eventId: String, key: String): Boolean = throw NotImplementedError()
    }
}
