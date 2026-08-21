package dev.supermessage.kit

import dev.supermessage.kit.stores.ConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import uniffi.supermessage_core.TypingUserDto
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiEvent
import uniffi.supermessage_ffi.FfiException
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.StagedFile
import uniffi.supermessage_ffi.TimelineDiffEnvelope
import uniffi.supermessage_ffi.TimelineDiffOp
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * `Session.swift` has no Swift test of its own — confirmed by grepping the
 * whole Swift test directory — so this is written new rather than ported.
 * It exercises the five behaviours the task brief calls out by name, each
 * one driven through a fake [CoreInterface] with no Rust, no network and no
 * device involved, plus one more (`opValues` in the timeline-diff handler)
 * flagged by the task as otherwise never exercised on either platform.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {

    private fun row(id: String, sender: String = "@a:x", isOwn: Boolean = false): TimelineRow =
        TimelineRow(
            item = TimelineItemDto(
                id = id, eventId = id, kind = "message", msgtype = "m.text", detail = null,
                sender = sender, senderDisplayName = null, senderAvatar = null, body = "hi",
                formattedBody = null, media = null, customPayload = null, timestampMs = 0uL,
                isOwn = isOwn, sendState = null, replyTo = null, edited = false,
                reactions = emptyList(), readBy = emptyList(), editable = false,
            ),
            view = ItemView.Bubble(muted = false, blocks = emptyList()),
            senderName = sender,
            senderShort = sender,
            membershipVerb = null,
            replyQuote = null,
            canReplyOrReact = true,
            replyPreview = null,
        )

    /**
     * "a sign-in failure sets `failure` and leaves `phase` recoverable" —
     * the app must not strand the reader on a dead screen. Swift's
     * `signIn` (`Session.swift:89`) never touches `phase` in its catch
     * arms, so whatever screen the reader was already on (the login
     * screen, here) is still the one they see.
     */
    @Test
    fun `a sign-in failure sets failure and leaves phase recoverable`() = runTest {
        val fake = FakeCore(
            restoreSessionResult = { false },
            loginBody = { _, _, _, _ -> throw FfiException.Auth("bad password") },
        )
        val session = sessionOf(fake, this)

        session.start()
        assertEquals(Session.Phase.SIGNED_OUT, session.phase.value)

        session.signIn(homeserver = "https://x.example", username = "u", password = "wrong")

        assertEquals("Signed out. Sign in again to continue.", session.failure.value)
        assertEquals(
            "a failed sign-in must leave the reader on the login screen, not strand them",
            Session.Phase.SIGNED_OUT,
            session.phase.value,
        )
        assertNull("a failed sign-in must not begin draining", session.drainJob)
    }

    /**
     * "events reach the right store in order" — a burst of `TimelineDiff`
     * envelopes with ascending `seq`, pushed through the pump, must land on
     * `TimelineStore` in that order. Real threads and a real dispatcher —
     * not `runTest`'s virtual scheduler — for the same reason
     * `EventPumpTest`'s ten-thousand-event test uses them: only genuine
     * concurrency gives a broken drain (two collectors, or a coroutine
     * launched per event) an actual chance to reorder or corrupt the
     * result, and `TestCoroutineScheduler` runs launched children in the
     * order they were queued regardless of whether the code under test
     * deserves that.
     */
    @Test
    fun `events reach the right store in order`() {
        val roomId = "!r:x.example"
        val count = 2_000
        val fake = FakeCore(restoreSessionResult = { true })
        val realScope = CoroutineScope(Dispatchers.Default)
        val session = sessionOf(fake, realScope)

        try {
            runBlocking {
                session.start()
                session.open(roomId)

                val producer = Thread {
                    for (seq in 1..count) {
                        val envelope = TimelineDiffEnvelope(
                            channel = "timeline",
                            subject = roomId,
                            seq = seq.toULong(),
                            ops = listOf(TimelineDiffOp.Append(listOf(row("m$seq")))),
                        )
                        session.pump.onEvent(FfiEvent.TimelineDiff(envelope))
                    }
                }
                producer.start()
                producer.join()

                withTimeout(15_000) {
                    while (session.timeline.items.value.size < count) delay(5)
                }
            }

            val ids = session.timeline.items.value.map { it.item.id }
            assertEquals((1..count).map { "m$it" }, ids)
        } finally {
            realScope.cancel()
        }
    }

    /**
     * "`signOut` finishes the pump and cancels the drain" — a leaked
     * collector after logout is a coroutine holding a dead session.
     * Beyond the job itself finishing, a fresh sign-in afterwards must
     * begin a *new* drain: `beginDraining`'s `guard drainTask == nil`
     * (`Session.swift:381`) would silently skip a second one if
     * `signOut` left the old reference in place.
     */
    @Test
    fun `signOut finishes the pump and cancels the drain`() = runTest {
        val fake = FakeCore(restoreSessionResult = { true })
        val session = sessionOf(fake, this)

        session.start()
        advanceUntilIdle()
        val firstDrain = session.drainJob
        assertNotNull("start() should have begun draining", firstDrain)
        assertTrue(firstDrain!!.isActive)

        session.signOut()
        advanceUntilIdle()

        assertEquals(Session.Phase.SIGNED_OUT, session.phase.value)
        assertNull("signOut must clear the drain job so a later login can start a fresh one", session.drainJob)
        assertTrue("the old drain job must actually finish, not leak", firstDrain.isCompleted)
        assertFalse(firstDrain.isActive)

        session.signIn(homeserver = "https://x.example", username = "u", password = "p")
        advanceUntilIdle()

        val secondDrain = session.drainJob
        assertNotNull("a fresh sign-in must begin draining again", secondDrain)
        assertTrue("the new drain must not be the same, dead job", firstDrain !== secondDrain)

        // Existing is not enough: `secondDrain` can be a distinct, "active"
        // job that is nonetheless collecting a permanently closed channel —
        // a Kotlin `Channel` cannot be reopened, so if `signIn` re-registers
        // the same, already-`finish()`-ed pump with the core, the second
        // drain completes immediately with zero elements and every later
        // `onEvent` silently no-ops. Prove the pipe is actually live: push an
        // event through the *same* `session.pump` reference used throughout
        // this test and confirm it reaches the store that owns it.
        //
        // `connection` first, in isolation: `ConnectionStore.apply` is not
        // backed by `GapSync` and `signOut` never touches it, so this proves
        // specifically that the pump itself is live again — reset and
        // re-registered — independent of whatever `rooms`/`timeline` do with
        // what arrives.
        session.pump.onEvent(FfiEvent.Connection(ConnectionState(state = "live", message = null)))
        advanceUntilIdle()
        assertEquals(
            "an event pushed after a sign-out/sign-in cycle must reach connection",
            ConnectionStore.Connection.Live,
            session.connection.state.value,
        )

        // Then `timeline` — the symptom a reader would actually notice: sign
        // out, sign back in, open a room, see nothing. `rooms` and
        // `timeline` are both backed by `GapSync`, whose `stop()` (called
        // from `RoomsStore.clear`/`TimelineStore.clear`, both invoked by the
        // `signOut` above) used to have no way back — a second, independent
        // defect from the pump one, found while first writing this test,
        // now fixed by `GapSync.resume` and `RoomsStore.resume` (see both
        // classes' KDoc). The pump alone reaching `connection` above is not
        // enough to call the sign-out/sign-in cycle actually recovered; this
        // is.
        val roomId = "!after-relogin:x.example"
        session.open(roomId)
        session.pump.onEvent(
            FfiEvent.TimelineDiff(
                TimelineDiffEnvelope(
                    channel = "timeline", subject = roomId, seq = 1uL,
                    ops = listOf(TimelineDiffOp.Append(listOf(row("m1")))),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(
            "an event pushed after a sign-out/sign-in cycle must still reach the timeline",
            listOf("m1"),
            session.timeline.items.value.map { it.item.id },
        )

        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    /**
     * "`open(roomId:)` is idempotent" — calling it again for the room
     * already open does nothing, which `TimelineStore.swift:77`
     * documents (`subscribeTo`'s `if roomId == _roomId.value { return }`).
     */
    @Test
    fun `open(roomId) for the room already open does nothing`() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)

        session.open("!a:x.example")
        session.open("!a:x.example")

        assertEquals(1, fake.timelineSubscribeCallCount)
    }

    /**
     * "a refused operation surfaces its refusal" rather than failing
     * silently — `Session.swift:329`'s `refusal` wrapper is the shape.
     * `ErrorPresenter` hands back the homeserver's own words for a
     * non-empty `FfiException.Network` detail, so the message asserted
     * here is not a placeholder — it is what a reader would actually see.
     */
    @Test
    fun `a refused operation surfaces its refusal, rather than failing silently`() = runTest {
        val fake = FakeCore(joinRoomResult = { throw FfiException.Network("connection refused") })
        val session = sessionOf(fake, this)

        val message = session.joinRoom("!a:x.example")

        assertEquals("connection refused", message)
    }

    /**
     * Exercises `opValues`, which the task notes has no direct test on
     * either platform even though `Session.swift:412` uses it via
     * `.flatMap(opValues)`. A message from someone typing clears their
     * notice; the same message from this account's own sender id must
     * not, since a reader's own send says nothing about who *else* is
     * writing (`Session.swift:405`'s comment on `spoke`).
     *
     * The second push below carries **two** items in a single `Append`, one
     * from each of two different typers, and asserts both notices clear.
     * That is deliberate, not incidental: a fixture with only one item per
     * op would still pass against an `opValues` that returned just
     * `values.first()` (or just `values.last()`) instead of flattening the
     * whole list — this shape requires every item in the op to actually
     * come through.
     */
    @Test
    fun `a message from someone typing clears their notice, but this account's own message does not`() = runTest {
        val roomId = "!r:x.example"
        val fake = FakeCore(restoreSessionResult = { true })
        val session = sessionOf(fake, this)

        session.start()
        advanceUntilIdle()
        session.open(roomId)
        session.typing.handle(
            roomId = roomId,
            users = listOf(
                TypingUserDto(userId = "@bob:x.example", displayName = "Bob", label = "Bob"),
                TypingUserDto(userId = "@carol:x.example", displayName = "Carol", label = "Carol"),
            ),
        )
        assertEquals(
            setOf("@bob:x.example", "@carol:x.example"),
            session.typing.typers.value.map { it.userId }.toSet(),
        )

        // This account's own message must not clear anyone else's notice —
        // it says nothing about who else is writing.
        session.pump.onEvent(
            FfiEvent.TimelineDiff(
                TimelineDiffEnvelope(
                    channel = "timeline", subject = roomId, seq = 1uL,
                    ops = listOf(TimelineDiffOp.Append(listOf(row("own-1", sender = "@me:x.example", isOwn = true)))),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(
            "an own message must not clear someone else's typing notice",
            setOf("@bob:x.example", "@carol:x.example"),
            session.typing.typers.value.map { it.userId }.toSet(),
        )

        // Bob's and Carol's messages, as two items of the *same* Append op,
        // are what clears both notices.
        session.pump.onEvent(
            FfiEvent.TimelineDiff(
                TimelineDiffEnvelope(
                    channel = "timeline", subject = roomId, seq = 2uL,
                    ops = listOf(
                        TimelineDiffOp.Append(
                            listOf(
                                row("bob-1", sender = "@bob:x.example", isOwn = false),
                                row("carol-1", sender = "@carol:x.example", isOwn = false),
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertTrue(
            "a message from each typer should clear their own notice",
            session.typing.typers.value.isEmpty(),
        )

        // `runTest` requires every job it launched to have finished by the
        // time the test body returns — `start()` began a drain that runs
        // forever until logout, so it has to be torn down explicitly here
        // rather than left dangling past the assertions above.
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    private fun sessionOf(fake: FakeCore, scope: CoroutineScope): Session =
        Session(client = CoreClient(core = fake, dispatcher = Dispatchers.Unconfined), scope = scope)

    /**
     * A [CoreInterface] tailored to what [Session] itself drives, in the
     * house pattern set by `StagedAttachmentTest`'s and `CoreClientTest`'s
     * own fakes rather than a third, shared one: every method [Session]
     * can reach is implemented, and the handful its tests actually
     * configure take a lambda; everything else throws, so a test that
     * accidentally depends on an unconfigured path fails loudly rather
     * than silently returning a default that happens to work.
     */
    private class FakeCore(
        private val loginBody: (String, String, String, EventSink) -> Unit = { _, _, _, _ -> },
        private val restoreSessionResult: () -> Boolean = { false },
        private val roomsSnapshotResult: () -> RoomsSnapshot = { RoomsSnapshot(seq = 0uL, rooms = emptyList()) },
        private val spacesListResult: () -> List<SpaceSummary> = { emptyList() },
        private val joinRoomResult: (String) -> Unit = {},
    ) : CoreInterface {
        var logoutCallCount = 0
            private set
        var timelineSubscribeCallCount = 0
            private set

        override fun login(homeserver: String, username: String, password: String, sink: EventSink): Unit =
            loginBody(homeserver, username, password, sink)

        override fun restoreSession(sink: EventSink): Boolean = restoreSessionResult()

        override fun logout() {
            logoutCallCount += 1
        }

        override fun roomsSnapshot(): RoomsSnapshot = roomsSnapshotResult()

        override fun spacesList(): List<SpaceSummary> = spacesListResult()

        override fun timelineSubscribe(roomId: String, sink: EventSink) {
            timelineSubscribeCallCount += 1
        }

        override fun joinRoom(roomId: String): Unit = joinRoomResult(roomId)

        override fun account(): AccountDto = throw NotImplementedError()
        override fun attachmentDiscard(token: String): Unit = throw NotImplementedError()
        override fun attachmentSend(roomId: String, token: String): Unit = throw NotImplementedError()
        override fun attachmentStagePath(roomId: String, path: String): StagedFile = throw NotImplementedError()
        override fun connectionState(): ConnectionState = throw NotImplementedError()
        override fun createRoom(name: String, invite: List<String>, isDirect: Boolean): String =
            throw NotImplementedError()
        override fun deleteMessage(roomId: String, eventId: String): Unit = throw NotImplementedError()
        override fun directRoomWith(userId: String): String? = throw NotImplementedError()
        override fun editMessage(roomId: String, eventId: String, body: String): Unit = throw NotImplementedError()
        override fun inviteUser(roomId: String, userId: String): Unit = throw NotImplementedError()
        override fun joinRoomByAlias(aliasOrId: String): String = throw NotImplementedError()
        override fun knownPeople(): List<PersonDto> = throw NotImplementedError()
        override fun leaveRoom(roomId: String): Unit = throw NotImplementedError()
        override fun markRoomRead(roomId: String): Unit = throw NotImplementedError()
        override fun mediaFetch(eventId: String): String? = throw NotImplementedError()
        override fun memberAvatar(mxcUri: String): String? = throw NotImplementedError()
        override fun roomAvatar(roomId: String): String? = throw NotImplementedError()
        override fun roomAvatarFull(roomId: String): String? = throw NotImplementedError()
        override fun roomInfo(roomId: String): RoomInfoDto = throw NotImplementedError()
        override fun roomInviter(roomId: String): String? = throw NotImplementedError()
        override fun searchMessages(term: String, roomId: String?): List<SearchResultDto> =
            throw NotImplementedError()
        override fun sendMessage(roomId: String, body: String, mentions: List<String>): Unit =
            throw NotImplementedError()
        override fun sendReply(roomId: String, body: String, inReplyTo: String): Unit = throw NotImplementedError()
        override fun setRoomNotifications(roomId: String, mode: NotificationMode): Unit =
            throw NotImplementedError()
        override fun setRoomPinned(roomId: String, pinned: Boolean): Unit = throw NotImplementedError()
        override fun setTyping(roomId: String, typing: Boolean): Unit = throw NotImplementedError()
        override fun spaceSelect(spaceId: String?): Unit = throw NotImplementedError()
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = throw NotImplementedError()
        override fun timelineResync(): TimelineSnapshot = throw NotImplementedError()
        override fun toggleReaction(roomId: String, eventId: String, key: String): Boolean =
            throw NotImplementedError()
    }
}
