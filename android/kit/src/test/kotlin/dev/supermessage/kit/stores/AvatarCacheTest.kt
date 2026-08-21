package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.StagedFile
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * Ported from `apple/SupermessageKitTests/AvatarCacheTests.swift`.
 *
 * When an avatar is worth asking the core for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarCacheTest {

    private fun cache(): AvatarCache = AvatarCache(client = CoreClient(core = FakeCore()))

    /** "a room nobody has asked about is worth fetching" */
    @Test
    fun `fetches the first time`() {
        assertTrue(cache().shouldFetch("!a:x"))
    }

    /** "an avatar already held is not fetched again" */
    @Test
    fun `does not refetch what it has`() {
        val avatars = cache()
        avatars.remember("data:image/png;base64,AAAA", "!a:x")
        assertFalse(avatars.shouldFetch("!a:x"))
    }

    /** "a room with no avatar is not asked about twice" */
    @Test
    fun `remembers absence`() {
        // Otherwise every scroll past a room without a picture is another
        // round trip that can only come back empty.
        val avatars = cache()
        avatars.rememberAbsent("!a:x")
        assertFalse(avatars.shouldFetch("!a:x"))
    }

    /** "an evicted avatar is fetched again" */
    @Test
    fun `refetches after eviction`() {
        // The bug this pins. The platform cache this replaced evicts — under
        // memory pressure and at its count limit — and the old guard was a
        // separate set of every id ever *asked about*. An evicted avatar was
        // therefore never asked for again, and the row showed an empty circle
        // for the rest of the session. Because eviction is invisible, it
        // looked like avatars randomly failing to load.
        //
        // A count limit of one makes the eviction happen rather than waiting
        // for memory pressure. Note this must *not* use `clear()`, which wipes
        // the guard as well and so passes whether the bug is present or not.
        val avatars = AvatarCache(client = CoreClient(core = FakeCore()), countLimit = 1)
        avatars.remember("data:image/png;base64,AAAA", "!a:x")
        avatars.remember("data:image/png;base64,BBBB", "!b:x")

        assertNull("the count limit did not evict; test is inert", avatars.uri("!a:x"))
        assertTrue("an avatar that is gone must be fetchable again", avatars.shouldFetch("!a:x"))
    }

    /** "an arriving avatar tells the view to redraw" */
    @Test
    fun `arrival is observable`() = runTest {
        // Reported on iOS: no pictures on the first scroll, pictures on the
        // second, gone again after visiting a room. The storage there was an
        // `NSCache`, and `@Observable` cannot see through a reference type
        // mutated behind its back — bytes landed and nothing invalidated, so
        // a row only picked them up when something *else* forced a redraw.
        // Compose has the identical hazard with a `HashMap` mutated behind a
        // `State`; collecting the `StateFlow` here stands in for the view,
        // and it is what proves a *replacement*, not merely a mutation,
        // shipped.
        val avatars = cache()
        val seen = mutableListOf<Map<String, String>>()
        val job = launch { avatars.cache.collect { seen.add(it) } }
        runCurrent() // let the collector attach and receive the initial value first

        avatars.remember("data:image/png;base64,AAAA", "!a:x")
        advanceUntilIdle()

        assertEquals("an avatar arrived and no view was told", 2, seen.size)
        job.cancel()
    }

    /** "one fetch at a time for the same room" */
    @Test
    fun `does not stampede`() {
        // Every visible row asks on appear, and they all ask before the first
        // answer lands.
        val avatars = cache()
        avatars.beginFetch("!a:x")
        assertFalse(avatars.shouldFetch("!a:x"))
    }

    private class FakeCore : CoreInterface {
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
        override fun setRoomNotifications(roomId: String, mode: NotificationMode): Unit =
            throw NotImplementedError()
        override fun setRoomPinned(roomId: String, pinned: Boolean): Unit = throw NotImplementedError()
        override fun setTyping(roomId: String, typing: Boolean): Unit = throw NotImplementedError()
        override fun spaceSelect(spaceId: String?): Unit = throw NotImplementedError()
        override fun spacesList(): List<SpaceSummary> = throw NotImplementedError()
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean =
            throw NotImplementedError()
        override fun timelineResync(): TimelineSnapshot = throw NotImplementedError()
        override fun timelineSubscribe(roomId: String, sink: EventSink): Unit = throw NotImplementedError()
        override fun toggleReaction(roomId: String, eventId: String, key: String): Boolean =
            throw NotImplementedError()
    }
}
