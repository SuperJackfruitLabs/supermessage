package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomIdentity
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
 * `SpacesStore.swift` has no Swift test — confirmed by grepping the whole
 * Swift test directory — so this is written new rather than ported. The
 * rules pinned below come from the source's own doc comments: "not
 * diff-driven", "a rail that cannot load is not worth an alert", and "an
 * invitation is not a filter".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpacesStoreTest {

    private fun space(
        id: String,
        name: String,
        membership: Membership = Membership.JOINED,
    ): SpaceSummary = SpaceSummary(
        id = id, name = name, avatarUrl = null, childCount = 0uL, membership = membership,
        identity = RoomIdentity(glyph = null, name = name, role = null, initial = "X"),
    )

    /** `spacesList` populates `spaces`, refreshed rather than diff-driven. */
    @Test
    fun `refresh replaces the space list from a plain call`() = runTest {
        val fake = FakeCore(spacesResult = { listOf(space("!s:x", "Engineering")) })
        val store = SpacesStore(CoreClient(core = fake))

        store.refresh()

        assertEquals(listOf("Engineering"), store.spaces.value.map { it.name })
    }

    /**
     * "A rail that cannot load is not worth an alert: the roster still
     * works unfiltered" — a `NotReady` failure (the one `isWorthSurfacing`
     * rejects) is swallowed rather than surfaced.
     */
    @Test
    fun `a not-yet-ready failure on refresh is swallowed, not surfaced`() = runTest {
        val fake = FakeCore(spacesResult = { throw FfiException.NotReady() })
        val store = SpacesStore(CoreClient(core = fake))

        store.refresh()

        assertTrue(store.spaces.value.isEmpty())
        assertNull(store.failure.value)
    }

    /** A failure worth surfacing produces `ErrorPresenter`'s message for it. */
    @Test
    fun `a worth-surfacing failure on refresh is reported through ErrorPresenter`() = runTest {
        val fake = FakeCore(spacesResult = { throw FfiException.Store("disk full") })
        val store = SpacesStore(CoreClient(core = fake))

        store.refresh()

        assertEquals("Couldn't read this device's local store.", store.failure.value)
    }

    /** Selecting a space asks the core, then remembers the choice. */
    @Test
    fun `selecting a space tells the core and remembers the choice`() = runTest {
        val fake = FakeCore()
        val store = SpacesStore(CoreClient(core = fake))

        store.select("!s:x")

        assertEquals(listOf("!s:x"), fake.selectedSpaceIds)
        assertEquals("!s:x", store.selectedId.value)
    }

    /** `nil` is "All rooms", a real choice, not merely an absent one. */
    @Test
    fun `selecting null clears the filter and is still a real choice the core hears`() = runTest {
        val fake = FakeCore()
        val store = SpacesStore(CoreClient(core = fake))
        store.select("!s:x")

        store.select(null)

        assertEquals(listOf("!s:x", null), fake.selectedSpaceIds)
        assertNull(store.selectedId.value)
    }

    /**
     * A refusal from the core on `select` is surfaced, and — unlike
     * `refresh` — never silently dropped: switching the filter is a request
     * the reader made just now, not a background refresh.
     */
    @Test
    fun `a refused select is reported through ErrorPresenter, and the selection does not move`() = runTest {
        val fake = FakeCore(selectResult = { throw FfiException.UnknownSpace("!gone:x") })
        val store = SpacesStore(CoreClient(core = fake))

        store.select("!gone:x")

        assertEquals("That space is no longer in your account.", store.failure.value)
        assertNull(store.selectedId.value)
    }

    /** "An invitation is not a filter" — `isInvitation` reads membership alone. */
    @Test
    fun `isInvitation is true only for an invited space`() = runTest {
        val store = SpacesStore(CoreClient(core = FakeCore()))
        val joined = space("!s:x", "Engineering", membership = Membership.JOINED)
        val invited = space("!t:x", "Design", membership = Membership.INVITED)

        assertFalse(store.isInvitation(joined))
        assertTrue(store.isInvitation(invited))
    }

    /** `selectedName` reads the selected space's parsed identity, not its raw name. */
    @Test
    fun `selectedName resolves through the selected space's identity`() = runTest {
        val fake = FakeCore(spacesResult = { listOf(space("!s:x", "Engineering")) })
        val store = SpacesStore(CoreClient(core = fake))
        store.refresh()

        store.select("!s:x")

        assertEquals("Engineering", store.selectedName)
    }

    /** `nil` selected reads as no name at all, never a crash on an empty list. */
    @Test
    fun `selectedName is null when nothing is selected`() = runTest {
        val store = SpacesStore(CoreClient(core = FakeCore()))

        assertNull(store.selectedName)
    }

    /** `clear` drops the list, the selection and any lingering failure. */
    @Test
    fun `clear is thorough`() = runTest {
        val fake = FakeCore(
            spacesResult = { listOf(space("!s:x", "Engineering")) },
            selectResult = { throw FfiException.UnknownSpace("!gone:x") },
        )
        val store = SpacesStore(CoreClient(core = fake))
        store.refresh()
        store.select("!gone:x")

        store.clear()

        assertTrue(store.spaces.value.isEmpty())
        assertNull(store.selectedId.value)
        assertNull("a stale failure outlived clear()", store.failure.value)
    }

    private class FakeCore(
        private val spacesResult: () -> List<SpaceSummary> = { emptyList() },
        private val selectResult: (() -> Unit)? = null,
    ) : CoreInterface {
        val selectedSpaceIds = mutableListOf<String?>()

        override fun spaceSelect(spaceId: String?) {
            selectResult?.invoke()
            selectedSpaceIds.add(spaceId)
        }

        override fun spacesList(): List<SpaceSummary> = spacesResult()

        override fun account(): AccountDto = throw NotImplementedError()
        override fun attachmentDiscard(token: String): Unit = throw NotImplementedError()
        override fun attachmentSend(roomId: String, token: String): Unit = throw NotImplementedError()
        override fun attachmentStagePath(roomId: String, path: String): StagedFile =
            throw NotImplementedError()
        override fun connectionState(): ConnectionState = throw NotImplementedError()
        override fun createRoom(name: String, invite: List<String>, isDirect: Boolean): String =
            throw NotImplementedError()
        override fun deleteMessage(roomId: String, eventId: String): Unit = throw NotImplementedError()
        override fun directRoomWith(userId: String): String? = throw NotImplementedError()
        override fun editMessage(roomId: String, eventId: String, body: String): Unit =
            throw NotImplementedError()
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
        override fun roomsSnapshot(): RoomsSnapshot = throw NotImplementedError()
        override fun searchMessages(term: String, roomId: String?): List<SearchResultDto> =
            throw NotImplementedError()
        override fun sendMessage(roomId: String, body: String, mentions: List<String>): Unit =
            throw NotImplementedError()
        override fun sendReply(roomId: String, body: String, inReplyTo: String): Unit =
            throw NotImplementedError()
        override fun sendGateDecision(
            roomId: String,
            gateId: String,
            optionId: String,
            comment: String?,
            inReplyTo: String,
            prompt: String,
        ): Unit = throw NotImplementedError()
        override fun setRoomNotifications(roomId: String, mode: NotificationMode): Unit =
            throw NotImplementedError()
        override fun setRoomPinned(roomId: String, pinned: Boolean): Unit = throw NotImplementedError()
        override fun setTyping(roomId: String, typing: Boolean): Unit = throw NotImplementedError()
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean =
            throw NotImplementedError()
        override fun timelineResync(): TimelineSnapshot = throw NotImplementedError()
        override fun timelineSubscribe(roomId: String, sink: EventSink): Unit = throw NotImplementedError()
        override fun toggleReaction(roomId: String, eventId: String, key: String): Boolean =
            throw NotImplementedError()
    }
}
