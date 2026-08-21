package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiEvent
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.StagedFile
import uniffi.supermessage_ffi.TimelineDiffEnvelope
import uniffi.supermessage_ffi.TimelineDiffOp
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * Ported from `apple/SupermessageKitTests/TimelinePaginationTests.swift` and
 * `TimelineRevisionTests.swift`, which are the tests for [TimelineStore]
 * under two other names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineStoreTest {

    private fun row(id: String): TimelineRow =
        TimelineRow(
            item = TimelineItemDto(
                id = id, eventId = id, kind = "message", msgtype = "m.text", detail = null,
                sender = "@a:x", senderDisplayName = null, senderAvatar = null, body = "hi",
                formattedBody = null, media = null, customPayload = null, timestampMs = 0uL,
                isOwn = false, sendState = null, replyTo = null, edited = false,
                reactions = emptyList(), readBy = emptyList(), editable = false,
            ),
            view = ItemView.Bubble(muted = false, blocks = emptyList()),
            senderName = "@a:x",
            senderShort = "@a:x",
            membershipVerb = null,
            replyQuote = null,
            canReplyOrReact = true,
            replyPreview = null,
        )

    // MARK: - Pagination
    //
    // What `paginate_backwards` actually returns, and what the store does
    // with it. `matrix_sdk_ui::Timeline::paginate_backwards` documents its
    // `bool` as "whether we hit the start of the timeline" — true means
    // there is nothing older left. The store read it as "there is more",
    // which is the opposite, so the first successful page (which does not
    // reach the start of a long room) switched pagination off permanently
    // and no history older than the initial screen could ever load.

    /** "a fresh room starts out willing to fetch history" */
    @Test
    fun `starts willing`() = runTest {
        val timeline = TimelineStore(client = CoreClient(core = FakeCore()), sink = FakeSink(), scope = this)
        assertTrue(timeline.canPaginate.value)
    }

    /** "a page that did not reach the start leaves more to fetch" */
    @Test
    fun `keeps going mid history`() = runTest {
        val timeline = TimelineStore(client = CoreClient(core = FakeCore()), sink = FakeSink(), scope = this)
        // The ordinary case in any room with real history: twenty older
        // messages arrived and there are more behind them.
        timeline.applyPaginationResult(reachedStart = false)
        assertTrue(timeline.canPaginate.value)
    }

    /** "reaching the start of the room stops further requests" */
    @Test
    fun `stops at the start`() = runTest {
        val timeline = TimelineStore(client = CoreClient(core = FakeCore()), sink = FakeSink(), scope = this)
        timeline.applyPaginationResult(reachedStart = true)
        assertFalse(timeline.canPaginate.value)
    }

    // MARK: - Revision
    //
    // The counter that lets the list tell "new token" from "new message".
    // The timeline view re-runs on every observable update, and while an
    // agent is writing that is many times a second — the live turn is
    // observable state and any read of it re-runs the update. Without a way
    // to answer "did the history actually change" in constant time, every
    // one of those updates re-ran the grouping pass over the whole room and
    // rebuilt every visible row. That was the jitter.

    /** "a fresh store has a revision to compare against" */
    @Test
    fun `starts at zero`() = runTest {
        val timeline = TimelineStore(client = CoreClient(core = FakeCore()), sink = FakeSink(), scope = this)
        assertEquals(0uL, timeline.revision.value)
    }

    /** "replacing the history moves the revision" */
    @Test
    fun `changes on write`() = runTest {
        // `clear()` replaces `items`, which is a change like any other: a
        // list that skipped the rebuild here would keep drawing the
        // previous room's messages.
        val timeline = TimelineStore(client = CoreClient(core = FakeCore()), sink = FakeSink(), scope = this)
        val before = timeline.revision.value
        timeline.clear()
        assertTrue("a write left the revision behind", timeline.revision.value != before)
    }

    /** "the revision only ever moves forward" */
    @Test
    fun `never goes backwards`() = runTest {
        // The comparison is `!=` at the call site, but a counter that
        // wrapped or reset would eventually collide with a value already
        // applied, and the list would skip a rebuild it needed. Monotonic
        // is what makes the comparison safe.
        val timeline = TimelineStore(client = CoreClient(core = FakeCore()), sink = FakeSink(), scope = this)
        val seen = mutableListOf(timeline.revision.value)
        repeat(3) {
            timeline.clear()
            seen.add(timeline.revision.value)
        }
        assertEquals("the revision went backwards", seen.sorted(), seen)
        assertEquals("the revision repeated a value", seen.toSet().size, seen.size)
    }

    /**
     * Not from Swift, added per this task's brief: the whole point of
     * [TimelineStore.revision] is telling "history changed" apart from
     * "something else changed" in constant time. A test that only checks it
     * moved on *some* write cannot catch a second write site to `items` that
     * forgets to bump it, nor a bump firing for unrelated state — exactly
     * the gap the mutation in step 5 targets.
     */
    @Test
    fun `revision moves by exactly one per item replacement and is untouched by pagination flags`() =
        runTest {
            val timeline = TimelineStore(client = CoreClient(core = FakeCore()), sink = FakeSink(), scope = this)
            val start = timeline.revision.value

            timeline.clear() // exactly one replacement of items
            assertEquals(start + 1uL, timeline.revision.value)

            val afterClear = timeline.revision.value
            timeline.applyPaginationResult(reachedStart = false)
            timeline.applyPaginationResult(reachedStart = true)
            assertEquals(
                "pagination flags moved the revision",
                afterClear,
                timeline.revision.value,
            )
        }

    /**
     * Not from Swift: the timeline's subject changes mid-subscribe — see
     * `TimelineStore.swift`'s note on `accepts` — so it must be a real
     * predicate rather than `GapSync`'s single-subject default. An envelope
     * for a room this store is no longer showing must be dropped rather
     * than applied.
     */
    @Test
    fun `an envelope for a different room is dropped rather than applied`() = runTest {
        val timeline = TimelineStore(client = CoreClient(core = FakeCore()), sink = FakeSink(), scope = this)
        timeline.subscribeTo("!a:x")

        timeline.handle(
            TimelineDiffEnvelope(
                channel = "sm://timeline/diff", subject = "!other:x", seq = 1uL,
                ops = listOf(TimelineDiffOp.Reset(values = listOf(row("\$1")))),
            ),
        )
        assertEquals(emptyList<TimelineRow>(), timeline.items.value)

        timeline.handle(
            TimelineDiffEnvelope(
                channel = "sm://timeline/diff", subject = "!a:x", seq = 1uL,
                ops = listOf(TimelineDiffOp.Reset(values = listOf(row("\$1")))),
            ),
        )
        assertEquals(listOf(row("\$1")), timeline.items.value)
    }

    private class FakeSink : EventSink {
        override fun onEvent(event: FfiEvent) {}
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
        // A no-op, not a throw: `an envelope for a different room is
        // dropped rather than applied` calls `subscribeTo`, and `Error` —
        // what `NotImplementedError` is — is not caught by the store's
        // `catch (e: Exception)`, which mirrors Swift's `try?` and is not
        // meant to swallow every `Throwable`.
        override fun timelineSubscribe(roomId: String, sink: EventSink): Unit = Unit
        override fun toggleReaction(roomId: String, eventId: String, key: String): Boolean =
            throw NotImplementedError()
    }
}
