package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RuntimeDto
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.StagedFile
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * [NewRoomPanel], the port of `apple/Supermessage/Panels/NewRoomPanel.swift`.
 *
 * `openConversation` and `joinByAlias` are driven through a real [Session]
 * backed by a fake [CoreInterface] — never the real, signed-in core this
 * device holds — the same house pattern `SessionViewModelTest` already
 * establishes for `:app`. This is what lets
 * [aDirectRoomIsReusedNotRecreated] tell `directRoomWith` and `createRoom`
 * apart for real, rather than merely asserting on a fake that could not
 * distinguish them either.
 */
class NewRoomTest {
    @get:Rule val compose = createComposeRule()

    private fun person(userId: String, name: String, runtime: RuntimeDto? = null) =
        PersonDto(userId = userId, name = name, runtime = runtime, avatarUrl = null)

    private fun sessionOf(core: FakeCore): Session =
        Session(client = CoreClient(core = core, dispatcher = Dispatchers.Unconfined), scope = CoroutineScope(Dispatchers.Unconfined))

    /**
     * `peopleMatching` — the core's own matcher, not a `.contains()`
     * re-derived here — matches on more than a person's display name (see
     * `supermessage-core::people::matching`). A query that only hits an
     * agent's *host* still has to surface that row; a panel that filtered
     * locally on `name` alone would not. This is the test the brief's
     * mandated mutation targets — see this task's report for the actual
     * failure output from filtering locally instead.
     */
    @Test
    fun matchingReachesThroughToTheCoresOwnMatcher() {
        val ganesha = person("@ganesha:x.org", "Ganesha", runtime = RuntimeDto(harness = "OpenClaw", host = "Ashram"))
        val alice = person("@alice:x.org", "Alice")

        compose.setContent {
            NewRoomPanel(
                onOpen = {},
                onClose = {},
                loadPeople = { listOf(ganesha, alice) },
                openConversation = { throw NotImplementedError() },
                joinByAlias = { throw NotImplementedError() },
            )
        }
        compose.waitForIdle()

        // "ashram" matches nobody's *name* — it is Ganesha's host, not
        // Alice's — so only a call through to the core's real matcher
        // (which also checks runtime.host) surfaces Ganesha here.
        compose.onNodeWithTag("new-room-query").performTextInput("ashram")
        compose.waitForIdle()

        compose.onNodeWithText("Ganesha").assertIsDisplayed()
        compose.onNodeWithText("Alice").assertDoesNotExist()
    }

    /**
     * Opening a person the account has no direct room with yet creates one
     * — named for them, never blank, since `PersonDto.name` is what
     * `Session.openConversation` hands `createRoom` as the room's name.
     */
    @Test
    fun openingANewPersonCreatesARoomNamedForThem() {
        val alice = person("@alice:x.org", "Alice")
        val fake = FakeCore(directRoomWithResult = { null }, createRoomResult = { _, _, _ -> "!new:x.org" })
        val session = sessionOf(fake)
        var opened: String? = null

        compose.setContent {
            NewRoomPanel(
                onOpen = { opened = it },
                onClose = {},
                loadPeople = { listOf(alice) },
                openConversation = session::openConversation,
                joinByAlias = { throw NotImplementedError() },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("new-room-person-@alice:x.org").performClick()
        compose.waitForIdle()

        assertEquals("!new:x.org", opened)
        assertEquals(listOf("@alice:x.org"), fake.directRoomWithCalls)
        assertEquals(1, fake.createRoomCalls.size)
        val (name, invite, isDirect) = fake.createRoomCalls.single()
        assertEquals("a room created for a known person is named for them, not left blank", "Alice", name)
        assertEquals(listOf("@alice:x.org"), invite)
        assertTrue(isDirect)
    }

    /**
     * A person this account already shares a direct room with reuses it —
     * `directRoomWith`, never a second `createRoom` for the same pair. The
     * core distinguishes a direct room from a group of one; a panel that
     * called `createRoom` unconditionally would not.
     */
    @Test
    fun aDirectRoomIsReusedNotRecreated() {
        val alice = person("@alice:x.org", "Alice")
        val fake = FakeCore(
            directRoomWithResult = { "!existing:x.org" },
            createRoomResult = { _, _, _ -> error("createRoom must not run when a direct room already exists") },
        )
        val session = sessionOf(fake)
        var opened: String? = null

        compose.setContent {
            NewRoomPanel(
                onOpen = { opened = it },
                onClose = {},
                loadPeople = { listOf(alice) },
                openConversation = session::openConversation,
                joinByAlias = { throw NotImplementedError() },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("new-room-person-@alice:x.org").performClick()
        compose.waitForIdle()

        assertEquals("!existing:x.org", opened)
        assertEquals(0, fake.createRoomCalls.size)
    }

    /**
     * A [CoreInterface] tailored to what this file drives, in the house
     * pattern `SessionViewModelTest` and `:kit`'s `SessionTest` already
     * set: every method the fake does not configure throws, so a test
     * that accidentally depends on an unconfigured path fails loudly.
     */
    private class FakeCore(
        private val directRoomWithResult: (String) -> String? = { throw NotImplementedError() },
        private val createRoomResult: (String, List<String>, Boolean) -> String = { _, _, _ -> throw NotImplementedError() },
        // Session.createRoom() unconditionally reconciles the roster after a
        // successful write (`load()` — see Session.kt) — a real effect of a
        // real `Session`, not something this test's own code triggers, so
        // both need an answer whenever createRoom is expected to run.
        private val roomsSnapshotResult: () -> RoomsSnapshot = { RoomsSnapshot(seq = 0uL, rooms = emptyList()) },
        private val spacesListResult: () -> List<SpaceSummary> = { emptyList() },
    ) : CoreInterface {
        val directRoomWithCalls = mutableListOf<String>()
        val createRoomCalls = mutableListOf<Triple<String, List<String>, Boolean>>()

        override fun directRoomWith(userId: String): String? {
            directRoomWithCalls += userId
            return directRoomWithResult(userId)
        }

        override fun createRoom(name: String, invite: List<String>, isDirect: Boolean): String {
            createRoomCalls += Triple(name, invite, isDirect)
            return createRoomResult(name, invite, isDirect)
        }

        override fun roomsSnapshot(): RoomsSnapshot = roomsSnapshotResult()
        override fun spacesList(): List<SpaceSummary> = spacesListResult()

        override fun account(): AccountDto = throw NotImplementedError()
        override fun attachmentDiscard(token: String): Unit = throw NotImplementedError()
        override fun attachmentSend(roomId: String, token: String): Unit = throw NotImplementedError()
        override fun attachmentStagePath(roomId: String, path: String): StagedFile = throw NotImplementedError()
        override fun connectionState(): ConnectionState = throw NotImplementedError()
        override fun deleteMessage(roomId: String, eventId: String): Unit = throw NotImplementedError()
        override fun editMessage(roomId: String, eventId: String, body: String): Unit = throw NotImplementedError()
        override fun inviteUser(roomId: String, userId: String): Unit = throw NotImplementedError()
        override fun joinRoom(roomId: String): Unit = throw NotImplementedError()
        override fun joinRoomByAlias(aliasOrId: String): String = throw NotImplementedError()
        override fun knownPeople(): List<PersonDto> = throw NotImplementedError()
        override fun leaveRoom(roomId: String): Unit = throw NotImplementedError()
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
        override fun roomInviter(roomId: String): String? = throw NotImplementedError()
        override fun searchMessages(term: String, roomId: String?): List<SearchResultDto> = throw NotImplementedError()
        override fun sendMessage(roomId: String, body: String, mentions: List<String>): Unit = throw NotImplementedError()
        override fun sendReply(roomId: String, body: String, inReplyTo: String): Unit = throw NotImplementedError()
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
