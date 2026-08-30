package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
 * Ported from `apple/SupermessageKitTests/MediaCacheTests.swift`.
 *
 * The distinction this cache exists to keep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaCacheTest {

    /**
     * `Dispatchers.Unconfined`, not the default `Dispatchers.IO`: `image`
     * fires its fetch on `scope` without being awaited, and a call that hops
     * to a real thread pool cannot be driven by `advanceUntilIdle` — the same
     * reason `StagedAttachmentTest` builds its `CoreClient` this way.
     */
    private fun TestScope.media(core: CoreInterface, byteLimit: Int = 64 * 1024 * 1024): MediaCache =
        MediaCache(client = CoreClient(core = core, dispatcher = Dispatchers.Unconfined), scope = this, byteLimit = byteLimit)

    /** "nothing is known about an event nobody has asked about" */
    @Test
    fun `starts empty`() = runTest {
        // Crucially `hasFailed` is false, not true: a caller reading it
        // before any fetch has started must not conclude there is nothing to
        // show.
        val media = media(FakeCore())
        assertFalse(media.hasFailed("\$e:x"))
    }

    /** "loading and unrenderable are different answers" */
    @Test
    fun `loading is not failure`() = runTest {
        // Both report `image == null`, and a renderer that cannot tell them
        // apart shows either a spinner that never stops or a broken image
        // that was never given a chance. `hasFailed` is the whole
        // difference.
        val media = media(FakeCore())
        media.image("\$e:x")
        assertNull(media.image("\$e:x"))
        assertFalse("an in-flight fetch is not a failure", media.hasFailed("\$e:x"))

        media.markFailed("\$e:x")
        assertNull(media.image("\$e:x"))
        assertTrue(media.hasFailed("\$e:x"))
    }

    /** "an arriving picture tells the view to redraw" */
    @Test
    fun `arrival is observable`() = runTest {
        // The same fault the avatars had, and it would have shown the same
        // way: a mutable reference type changed behind an observer's back
        // invalidates nothing, so bytes landing leaves the row holding its
        // placeholder until some unrelated change forces a redraw. Collecting
        // the `StateFlow` here stands in for the view, and it is what proves
        // a *replacement*, not merely a mutation, shipped.
        val media = media(FakeCore())
        val seen = mutableListOf<Map<String, String>>()
        val job = launch { media.cache.collect { seen.add(it) } }
        runCurrent()

        media.remember("data:image/png;base64,AAAA", "\$e:x")
        advanceUntilIdle()

        assertEquals("a picture arrived and no view was told", 2, seen.size)
        job.cancel()
    }

    /** "a decoder's refusal is remembered" */
    @Test
    fun `mark failed sticks`() = runTest {
        // The one failure a fetch cannot catch: bytes arrived and the image
        // decoder refused them.
        val media = media(FakeCore())
        media.markFailed("\$e:x")
        assertTrue(media.hasFailed("\$e:x"))
    }

    /**
     * Not from Swift: `MediaCacheTests.swift` never exercises `byteLimit`
     * eviction — only `AvatarCacheTests.refetchesAfterEviction` pins a
     * count-limit eviction on the iOS side. Added here on the same shape, so
     * the bound this cache exists to enforce is not merely implemented but
     * actually tested.
     */
    @Test
    fun `an evicted picture is fetched again`() = runTest {
        val core = FakeCore()
        val media = media(core, byteLimit = 1)
        media.remember("data:image/png;base64,AAAA", "\$a:x")
        media.remember("data:image/png;base64,BBBB", "\$b:x")

        assertNull("the byte limit did not evict; test is inert", media.image("\$a:x"))
        advanceUntilIdle()

        assertTrue(
            "an evicted picture must be fetched again",
            core.mediaFetchCalls.contains("\$a:x"),
        )
    }

    private class FakeCore : CoreInterface {
        val mediaFetchCalls = mutableListOf<String>()

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
        override fun mediaFetch(eventId: String): String? {
            mediaFetchCalls.add(eventId)
            return null
        }
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
