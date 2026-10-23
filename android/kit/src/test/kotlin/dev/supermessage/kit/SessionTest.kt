package dev.supermessage.kit

import dev.supermessage.kit.StubCore
import dev.supermessage.kit.stores.ConnectionStore
import dev.supermessage.kit.stores.InMemoryHiddenEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import uniffi.supermessage_core.Mentionable
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomMemberDto
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_core.TypingUserDto
import uniffi.supermessage_ffi.AttachmentSendOutcome
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
    companion object {
        @org.junit.BeforeClass
        @JvmStatic
        fun hostCore() = HostCore.ensureBuilt("SessionTest")
    }


    private fun row(id: String, sender: String = "@a:x", isOwn: Boolean = false): TimelineRow =
        TimelineRow(
            item = TimelineItemDto(
                id = id, eventId = id, kind = "message", msgtype = "m.text", detail = null,
                sender = sender, senderDisplayName = null, senderAvatar = null, body = "hi",
                formattedBody = null, media = null, customPayload = null, timestampMs = 0uL,
                isOwn = isOwn, sendState = null, replyTo = null, edited = false,
                reactions = emptyList(), readBy = emptyList(), editable = false, membershipSubject = null,
            ),
            view = ItemView.Bubble(muted = false, blocks = emptyList(), voice = null),
            senderName = sender,
            senderShort = sender,
            senderInitial = "?",
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

        // The homeserver's reason, not a generic "signed out" — this test used
        // to assert the latter, which meant a wrong password was reported to
        // someone standing at the sign-in screen as though their session had
        // expired.
        assertEquals("bad password", session.failure.value)
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

    /** Notifications are withdrawn wherever the sign-out happens, with no Activity collecting `phase`. */
    @Test
    fun `leaving SIGNED_IN runs the sign-out hooks once and a removed hook not at all`() = runTest {
        val session = sessionOf(FakeCore(restoreSessionResult = { true }), this)
        var ran = 0
        var removedRan = 0
        session.onSignedOut { ran++ }
        session.onSignedOut { removedRan++ }()
        session.start()
        advanceUntilIdle()
        assertEquals("signing in is not leaving", 0, ran)

        session.signOut()
        advanceUntilIdle()

        assertEquals(1, ran)
        assertEquals(0, removedRan)
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
     * "signing in again without an intervening signOut still drains events,
     * rather than orphaning the collector on a stale channel" — a distinct
     * defect from the one the sign-out/sign-in test above pins.
     * `EventPump.reset` only swaps the channel; it does not close the one it
     * replaces and does not touch whatever coroutine is already collecting
     * `events`. A `Session` that is already signed in and draining, asked to
     * `signIn` again — with no `signOut` in between, so `drainJob` is still
     * non-null and still active — used to leave the existing collector
     * suspended on the orphaned old channel forever, while
     * `beginDraining`'s `drainJob != null` guard skipped starting a new one
     * on the fresh channel `reset()` had just installed. Every event pushed
     * after that landed in a channel with no reader: no error, no crash,
     * just silence.
     */
    @Test
    fun `signing in again without signing out still drains events`() = runTest {
        val fake = FakeCore(restoreSessionResult = { false })
        val session = sessionOf(fake, this)

        session.signIn(homeserver = "https://x.example", username = "u", password = "p")
        advanceUntilIdle()
        assertEquals(Session.Phase.SIGNED_IN, session.phase.value)
        val firstDrain = session.drainJob
        assertNotNull("the first sign-in should have begun draining", firstDrain)
        assertTrue(firstDrain!!.isActive)

        session.pump.onEvent(FfiEvent.Connection(ConnectionState(state = "live", message = null)))
        advanceUntilIdle()
        assertEquals(
            "an event pushed after the first sign-in must reach connection",
            ConnectionStore.Connection.Live,
            session.connection.state.value,
        )

        // No signOut() here — the whole point of this test.
        session.signIn(homeserver = "https://x.example", username = "u", password = "p")
        advanceUntilIdle()

        session.pump.onEvent(FfiEvent.Connection(ConnectionState(state = "offline", message = null)))
        advanceUntilIdle()
        assertEquals(
            "an event pushed after re-signing in without signing out first must still reach connection",
            ConnectionStore.Connection.Offline,
            session.connection.state.value,
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
     * "a search that fails is not a search that found nothing" — the
     * defect this task fixes. `search` used to catch every exception and
     * hand back `emptyList()`, so a homeserver error rendered identically
     * to zero hits. It must now let the core's failure through — it is
     * `SearchPanel`, not `Session`, that turns this into something a
     * reader can tell apart from an empty list.
     */
    @Test
    fun `search does not swallow a core failure into an empty list`() = runTest {
        val fake = FakeCore(
            searchMessagesResult = { _, _ -> throw FfiException.Network("connection refused") },
        )
        val session = sessionOf(fake, this)

        try {
            session.search("pager")
            org.junit.Assert.fail("expected search to propagate the core's failure, not swallow it")
        } catch (e: FfiException.Network) {
            assertEquals("connection refused", e.detail)
        }
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

    @Test
    fun answerPermissionSendsTheOptionToTheRoom() = runTest {
        val fake = FakeCore(restoreSessionResult = { true })
        val session = sessionOf(fake, this)
        session.start()
        advanceUntilIdle()

        assertTrue(session.answerPermission("allow_once", "!r:x"))
        assertEquals(listOf("!r:x" to "allow_once"), fake.permissionAnswers)

        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun answerPermissionReturnsFalseWhenTheCoreRefuses() = runTest {
        val fake = FakeCore(
            restoreSessionResult = { true },
            sendPermissionAnswerResult = { _, _ -> throw FfiException.Network("offline") },
        )
        val session = sessionOf(fake, this)
        session.start()
        advanceUntilIdle()

        assertFalse(session.answerPermission("allow_once", "!r:x"))

        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun answerPermissionReturnsFalseWhenSignedOut() = runTest {
        val fake = FakeCore(restoreSessionResult = { false })
        val session = sessionOf(fake, this)
        session.start()
        advanceUntilIdle()

        assertFalse(session.answerPermission("allow_once", "!r:x"))
        assertTrue(fake.permissionAnswers.isEmpty())
    }

    /** The text carries the core's mentions, built from who the composer says could be addressed. */
    @Test
    fun sendReturnsSentAndCollectsMentions() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, backgroundScope)

        val result = session.send("hi @Atlas", "!r", mentioning = listOf(Mentionable("@atlas:x", "Atlas")))

        assertEquals(SendResult.Sent, result)
        assertEquals(listOf(Triple("!r", "hi @Atlas", listOf("@atlas:x"))), fake.sentMessages)
    }

    /** A failed attachment holds the text back: the reader asked for both. */
    @Test
    fun aFailedAttachmentHoldsTheTextBack() = runTest {
        val fake = FakeCore()
        fake.attachmentOutcome = AttachmentSendOutcome.Failed(FfiException.Network("down"), null)
        val session = sessionOf(fake, backgroundScope)
        session.staged.stage("/tmp/photo.jpg", "!r")

        val result = session.send("look at this", "!r")

        assertTrue(result is SendResult.AttachmentFailed)
        assertTrue(fake.sentMessages.isEmpty())
        assertEquals(listOf<String?>("look at this"), fake.sentCaptions)
    }

    /** With a file, the text rides as its caption and is not sent a second time. */
    @Test
    fun theTextRidesWithTheFileAsItsCaption() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, backgroundScope)
        session.staged.stage("/tmp/photo.jpg", "!r")

        assertEquals(SendResult.Sent, session.send("look", "!r"))
        assertTrue(fake.sentMessages.isEmpty())
        assertEquals(listOf<String?>("look"), fake.sentCaptions)
    }

    @Test
    fun blankWithNothingStagedIsNothingToSend() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, backgroundScope)

        assertEquals(SendResult.NothingToSend, session.send("   ", "!r"))
        assertTrue(fake.sentMessages.isEmpty())
    }

    // MARK: - Block and report

    private fun member(id: String, agent: Boolean) =
        RoomMemberDto(userId = id, displayName = null, avatarUrl = null, isIgnored = false, isAgent = agent)

    private fun message(eventId: String = "\$e", sender: String = "@s:x") =
        ReportSubject.Message("!r", eventId, sender, "Sam", isAgent = false)

    @Test
    fun ignoredUsersEventReplacesTheList() = runTest {
        val fake = FakeCore(restoreSessionResult = { true })
        val session = sessionOf(fake, this)
        session.start()
        session.safety.noteBlocked("@old")
        session.pump.onEvent(FfiEvent.IgnoredUsers(listOf("@a")))
        advanceUntilIdle()
        assertEquals(setOf("@a"), session.safety.blocked.value)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    /** Signing in twice must leave one listener: a single event applies once, in order. */
    @Test
    fun ignoredUsersEventsStillArriveAfterSigningInAgain() = runTest {
        val fake = FakeCore(restoreSessionResult = { true })
        val session = sessionOf(fake, this)
        session.start()
        session.signIn("https://x", "u", "p")
        advanceUntilIdle()
        session.pump.onEvent(FfiEvent.IgnoredUsers(listOf("@a")))
        advanceUntilIdle()
        session.pump.onEvent(FfiEvent.IgnoredUsers(listOf("@b")))
        advanceUntilIdle()
        assertEquals(setOf("@b"), session.safety.blocked.value)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun reportHidesOnlyAfterTheHomeserverHasIt() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)
        var hiddenWhileSending: Set<String>? = null
        fake.reportEventBody = { _, _, _ -> hiddenWhileSending = session.safety.hidden.value }

        val result = session.report(message(), "spam", alsoBlock = false)

        assertNull(result)
        assertEquals(emptySet<String>(), hiddenWhileSending)
        assertEquals(setOf("\$e"), session.safety.hidden.value)
        assertEquals(listOf("reportEvent(!r,\$e,spam)"), fake.safetyCalls)
    }

    /** Leaving the room while a report is on its way cancels the caller, not the report's consequences. */
    @Test
    fun aReportHidesTheMessageEvenIfItsCallerIsCancelled() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)
        val caller = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            session.report(message(), "spam", alsoBlock = false)
        }
        fake.reportEventBody = { _, _, _ -> caller.cancel() }
        caller.start()
        advanceUntilIdle()

        assertTrue(caller.isCancelled)
        assertEquals(setOf("\$e"), session.safety.hidden.value)
    }

    @Test
    fun aFailedReportHidesNothingAndBlocksNoOne() = runTest {
        val fake = FakeCore()
        fake.reportEventBody = { _, _, _ -> throw FfiException.Network("") }
        val session = sessionOf(fake, this)

        val result = session.report(message(), "spam", alsoBlock = true)

        assertEquals("Can't reach the homeserver.", result)
        assertTrue(session.safety.hidden.value.isEmpty())
        assertEquals(listOf("reportEvent(!r,\$e,spam)"), fake.safetyCalls)
    }

    @Test
    fun aBlockThatFailsAfterAReportSaysWhichHalf() = runTest {
        val fake = FakeCore()
        fake.ignoreUserBody = { throw FfiException.Refused("You can't block yourself.") }
        val session = sessionOf(fake, this)

        val result = session.report(message(), "spam", alsoBlock = true)

        assertEquals("Reported, but blocking didn't work: You can't block yourself.", result)
        assertEquals(setOf("\$e"), session.safety.hidden.value)
        assertTrue(session.safety.blocked.value.isEmpty())
    }

    @Test
    fun aReportAndBlockBlocksTheSender() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)
        assertNull(session.report(message(), "", alsoBlock = true))
        assertEquals(listOf("reportEvent(!r,\$e,)", "ignoreUser(@s:x)"), fake.safetyCalls)
        assertTrue(session.safety.isBlocked("@s:x"))
    }

    @Test
    fun anAlreadyBlockedSenderIsNotBlockedAgain() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)
        session.safety.apply(listOf("@s:x"))
        assertNull(session.report(message(), "", alsoBlock = true))
        assertEquals(listOf("reportEvent(!r,\$e,)"), fake.safetyCalls)
    }

    @Test
    fun aRoomReportNeverBlocks() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)
        assertNull(session.report(ReportSubject.Room("!r", "Lounge"), "why", alsoBlock = true))
        assertEquals(listOf("reportRoom(!r,why)"), fake.safetyCalls)
        assertTrue(session.safety.hidden.value.isEmpty())
    }

    @Test
    fun blockNotesOnlyWhatLanded() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)
        fake.ignoreUserBody = { throw FfiException.Refused("No.") }
        assertEquals("No.", session.block("@x"))
        assertTrue(session.safety.blocked.value.isEmpty())

        fake.ignoreUserBody = {}
        assertNull(session.block("@x"))
        assertEquals(setOf("@x"), session.safety.blocked.value)

        assertNull(session.unblock("@x"))
        assertTrue(session.safety.blocked.value.isEmpty())
    }

    @Test
    fun blockedUsersIsEmptyWhenTheListCannotBeRead() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)
        fake.ignoredUsersBody = { throw FfiException.NotReady() }
        assertTrue(session.blockedUsers().isEmpty())
        fake.ignoredUsersBody = { listOf(member("@a", false)) }
        assertEquals(listOf("@a"), session.blockedUsers().map { it.userId })
    }

    @Test
    fun signOutForgetsBlockedAndHidden() = runTest {
        val storage = InMemoryHiddenEvents()
        val fake = FakeCore(restoreSessionResult = { true })
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        assertTrue(session.start())
        session.safety.apply(listOf("@a"))
        session.safety.hide("\$b")
        assertEquals(setOf("\$b"), storage.load("@me:x"))
        session.signOut()
        assertTrue(session.safety.blocked.value.isEmpty())
        assertTrue(session.safety.hidden.value.isEmpty())
        assertTrue(storage.load("@me:x").isEmpty())
    }

    @Test
    fun aFailedRestoreForgetsBlockedAndHidden() = runTest {
        val storage = InMemoryHiddenEvents().apply { save("@me:x", setOf("\$old")); seedLegacy(setOf("\$older")) }
        val fake = FakeCore(restoreSessionResult = { false })
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        session.safety.apply(listOf("@a"))
        assertFalse(session.start())
        assertTrue(session.safety.blocked.value.isEmpty())
        assertTrue(session.safety.hidden.value.isEmpty())
        assertTrue(storage.load("@me:x").isEmpty())
        assertTrue(storage.takeLegacy().isEmpty())
    }

    @Test
    fun aRestoreThatThrowsKeepsWhatWasStored() = runTest {
        val storage = InMemoryHiddenEvents().apply { save("@me:x", setOf("\$old")) }
        val fake = FakeCore(restoreFails = true)
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        assertFalse(session.start())
        // A transient error says nothing about the stored account: its
        // reported messages stay stored for when it signs in again.
        assertEquals(setOf("\$old"), storage.load("@me:x"))
    }

    /** RF5: the first account's hidden messages must not show for whoever signs in next. */
    @Test
    fun aTransientRestoreErrorThenAnotherAccountSeesNothingHidden() = runTest {
        val storage = InMemoryHiddenEvents().apply { save("@me:x", setOf("\$old")) }
        val fake = FakeCore(restoreFails = true)
        fake.accountUserId = "@other:x"
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        assertFalse(session.start())
        session.signIn(homeserver = "https://x.example", username = "other", password = "p")
        assertEquals(Session.Phase.SIGNED_IN, session.phase.value)
        assertTrue(session.safety.hidden.value.isEmpty())
        session.safety.hide("\$new")
        assertEquals(setOf("\$old"), storage.load("@me:x"))
        session.signOut()
        assertEquals(setOf("\$old"), storage.load("@me:x"))
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun theSameAccountSigningInAfterARestoreErrorKeepsItsSet() = runTest {
        val storage = InMemoryHiddenEvents().apply { save("@me:x", setOf("\$old")) }
        val fake = FakeCore(restoreFails = true)
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        assertFalse(session.start())
        session.signIn(homeserver = "https://x.example", username = "me", password = "p")
        assertEquals(setOf("\$old"), session.safety.hidden.value)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun aRestoredSessionAdoptsTheOldDeviceWideSet() = runTest {
        val storage = InMemoryHiddenEvents().apply { seedLegacy(setOf("\$old")) }
        val fake = FakeCore(restoreSessionResult = { true })
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        assertTrue(session.start())
        assertEquals(setOf("\$old"), session.safety.hidden.value)
        assertEquals(setOf("\$old"), storage.load("@me:x"))
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun aRestoredSessionKeepsWhatWasHidden() = runTest {
        val storage = InMemoryHiddenEvents().apply { save("@me:x", setOf("\$old")) }
        val fake = FakeCore(restoreSessionResult = { true })
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        assertTrue(session.start())
        assertEquals(setOf("\$old"), session.safety.hidden.value)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    /** A report that lands after a sign-out must not put its id into the next account's store. */
    @Test
    fun aReportThatFinishesAfterSignOutHidesNothing() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)
        fake.reportEventBody = { _, _, _ -> runBlocking { session.signOut() } }
        assertNull(session.report(message(), "", alsoBlock = true))
        assertTrue(session.safety.hidden.value.isEmpty())
        assertTrue(session.safety.blocked.value.isEmpty())
    }

    // MARK: - Hidden events reach the core

    /** The account's stored set is pushed once it is signed in, and only its own. */
    @Test
    fun theStoredSetIsPushedWhenARestoreSignsIn() = runTest {
        val storage = InMemoryHiddenEvents().apply { save("@me:x", setOf("\$mine")); save("@other:x", setOf("\$theirs")) }
        val fake = FakeCore(restoreSessionResult = { true })
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        assertTrue(session.start())
        advanceUntilIdle()
        assertEquals(listOf(listOf("\$mine")), fake.hiddenPushes)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun theSetIsPushedAfterAFreshSignInToo() = runTest {
        val storage = InMemoryHiddenEvents().apply { save("@me:x", setOf("\$mine")) }
        val fake = FakeCore()
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        session.signIn(homeserver = "https://x.example", username = "me", password = "p")
        advanceUntilIdle()
        assertEquals(listOf(listOf("\$mine")), fake.hiddenPushes)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    /** A report pushes the whole set, the new id included. */
    @Test
    fun aReportPushesTheSetWithTheNewId() = runTest {
        val storage = InMemoryHiddenEvents().apply { save("@me:x", setOf("\$old")) }
        val fake = FakeCore(restoreSessionResult = { true })
        val session = Session(CoreClient(fake, Dispatchers.Unconfined), this, storage)
        session.start()
        advanceUntilIdle()
        assertNull(session.report(message("\$e"), "spam", alsoBlock = false))
        advanceUntilIdle()
        assertEquals(setOf("\$old", "\$e"), fake.hiddenPushes.last().toSet())
        assertEquals(2, fake.hiddenPushes.size)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    /** A report the homeserver refused hides nothing, so pushes nothing. */
    @Test
    fun aRefusedReportPushesNothing() = runTest {
        val fake = FakeCore()
        fake.reportEventBody = { _, _, _ -> throw FfiException.NotReady() }
        val session = sessionOf(fake, this)
        session.report(message(), "spam", alsoBlock = false)
        advanceUntilIdle()
        assertTrue(fake.hiddenPushes.isEmpty())
    }

    /** A push still waiting its turn when the account signs out is dropped, not handed to the next. */
    @Test
    fun aPushQueuedBeforeSignOutIsDropped() = runBlocking {
        val fake = FakeCore()
        val gate = java.util.concurrent.CountDownLatch(1)
        var first = true
        fake.hiddenPushBody = {
            val slow = synchronized(this) { first.also { first = false } }
            if (slow) gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = Session(CoreClient(fake, Dispatchers.IO), scope)
        session.report(message("\$a"), "", alsoBlock = false)
        session.report(message("\$b"), "", alsoBlock = false)
        session.signOut()
        gate.countDown()
        withTimeout(5_000) { while (fake.hiddenPushes.isEmpty()) delay(10) }
        delay(300)
        assertEquals(listOf(setOf("\$a")), fake.hiddenPushes.map { it.toSet() })
        scope.cancel()
    }

    /**
     * The first push is slow; the second must not overtake it. Real threads:
     * a single-threaded test dispatcher could not interleave them.
     */
    @Test
    fun aSlowFirstPushIsNotOvertakenByTheSecond() = runBlocking {
        val fake = FakeCore()
        val gate = java.util.concurrent.CountDownLatch(1)
        var first = true
        fake.hiddenPushBody = {
            val slow = synchronized(this) { first.also { first = false } }
            if (slow) gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = Session(CoreClient(fake, Dispatchers.IO), scope)
        session.report(message("\$a"), "", alsoBlock = false)
        session.report(message("\$b"), "", alsoBlock = false)
        Thread.sleep(300)
        gate.countDown()
        withTimeout(5_000) { while (fake.hiddenPushes.size < 2) delay(10) }
        assertEquals(listOf(setOf("\$a"), setOf("\$a", "\$b")), fake.hiddenPushes.map { it.toSet() })
        scope.cancel()
    }

    @Test
    fun reportSubjectForNeedsAnEventAndASender() = runTest {
        val fake = FakeCore()
        val session = sessionOf(fake, this)
        val ok = row("\$e", sender = "@s:x")
        assertNull(session.reportSubjectFor(ok.copy(item = ok.item.copy(eventId = null)), "!r"))
        assertNull(session.reportSubjectFor(ok.copy(item = ok.item.copy(sender = null)), "!r"))
        val subject = session.reportSubjectFor(ok, "!r") as ReportSubject.Message
        assertEquals(ReportSubject.Message("!r", "\$e", "@s:x", "@s:x", isAgent = false), subject)
    }

    @Test
    fun reportSubjectForAsksTheRoomWhoIsAnAgent() = runTest {
        val fake = FakeCore()
        fake.members = listOf(member("@agent_x:x", true), member("@s:x", false))
        val session = sessionOf(fake, this)
        val agent = session.reportSubjectFor(row("\$e", sender = "@agent_x:x"), "!r") as ReportSubject.Message
        assertTrue(agent.isAgent)
        val person = session.reportSubjectFor(row("\$e", sender = "@s:x"), "!r") as ReportSubject.Message
        assertFalse(person.isAgent)
        fake.roomInfoFails = true
        val unknown = session.reportSubjectFor(row("\$e", sender = "@agent_x:x"), "!r") as ReportSubject.Message
        assertFalse(unknown.isAgent)
    }


    // -- the turn mirror and the cast (M4 Task 1) ---------------------------------

    private val turnRoom = "!r:x.example"

    private fun foldedTurn(room: String = turnRoom): uniffi.supermessage_core.TurnSnapshot =
        uniffi.supermessage_ffi.foldTurn(
            room,
            listOf(
                uniffi.supermessage_core.TurnInput.Thought(1uL, "Checking.", false, 500uL),
                uniffi.supermessage_core.TurnInput.Tool(
                    uniffi.supermessage_core.TurnStep(
                        id = "c1", title = "Read a file", kind = null, status = "in_progress",
                        phase = uniffi.supermessage_core.ToolPhase.RUNNING, statusLabel = "Running",
                        locations = emptyList(), input = null, output = null, seq = 1uL, hasDetail = false,
                    ),
                    1_000uL,
                ),
            ),
        ) ?: error("the core folded no turn")

    private fun turnEvent(snapshot: uniffi.supermessage_core.TurnSnapshot, index: Int = 0) = FfiEvent.Turn(
        uniffi.supermessage_core.TurnUpdate(
            summary = snapshot.summary,
            step = uniffi.supermessage_core.TurnStepChange(index.toUInt(), snapshot.steps[index]),
        ),
    )

    @Test
    fun aTurnEventReachesTheLiveStore() = runTest {
        val session = sessionOf(FakeCore(restoreSessionResult = { true }), this)
        session.start()
        advanceUntilIdle()
        session.open(turnRoom)

        session.pump.onEvent(turnEvent(foldedTurn()))
        advanceUntilIdle()

        assertEquals(listOf("c1"), session.live.steps.value.map { it.id })
        assertTrue(session.live.inProgress.value)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun aToolEventNoLongerTouchesTheLiveStore() = runTest {
        val session = sessionOf(FakeCore(restoreSessionResult = { true }), this)
        session.start()
        advanceUntilIdle()
        session.open(turnRoom)

        session.pump.onEvent(
            FfiEvent.Tool(
                roomId = turnRoom, seq = 1uL, toolCallId = "c1", title = "Read a file", kind = null,
                status = "in_progress", phase = uniffi.supermessage_core.ToolPhase.RUNNING,
                statusLabel = "Running", locations = emptyList(), input = null, output = null,
            ),
        )
        advanceUntilIdle()

        assertTrue("steps arrive on the turn event only", session.live.steps.value.isEmpty())
        assertNull(session.live.summary.value)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun openRestoresTheRoomsTurn() = runTest {
        val fake = FakeCore(restoreSessionResult = { true })
        val snapshot = foldedTurn()
        fake.turnBody = { room -> snapshot.takeIf { room == turnRoom } }
        val session = sessionOf(fake, this)
        session.start()
        advanceUntilIdle()

        session.open(turnRoom)

        assertEquals(listOf("c1"), session.live.steps.value.map { it.id })
        assertEquals("Checking.", session.live.thought.value)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun aRefusedTurnFetchStillOpensTheRoom() = runTest {
        val fake = FakeCore(restoreSessionResult = { true })
        fake.turnBody = { throw FfiException.NotReady() }
        val session = sessionOf(fake, this)
        session.start()
        advanceUntilIdle()

        session.open(turnRoom)

        assertEquals(1, fake.timelineSubscribeCallCount)
        assertNull(session.live.summary.value)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun signOutForgetsTurnAndCast() = runTest {
        val session = sessionOf(FakeCore(restoreSessionResult = { true }), this)
        session.start()
        advanceUntilIdle()
        session.open(turnRoom)
        session.pump.onEvent(turnEvent(foldedTurn()))
        advanceUntilIdle()
        session.typing.recognise(
            uniffi.supermessage_core.RoomCast(
                roomId = turnRoom, people = emptyList(),
                counterpart = uniffi.supermessage_core.Counterpart("@atlas:x", "Atlas"),
                agentIds = listOf("@atlas:x"), addressee = null, mentionables = emptyList(),
            ),
            turnRoom,
        )
        session.live.setAgentName("Atlas", turnRoom)
        assertNotNull(session.live.summary.value)

        session.signOut()

        assertNull("the first account's turn survived sign-out", session.live.summary.value)
        assertTrue(session.live.steps.value.isEmpty())
        assertNull(session.live.agentName(turnRoom))
        // The next account opens the same room id: its typers must not be
        // named by the last account's cast.
        session.typing.focus(turnRoom)
        session.typing.handle(
            turnRoom,
            listOf(TypingUserDto(userId = "@atlas:x", displayName = "atlas-bot", label = "atlas-bot")),
        )
        assertTrue("the cast outlived sign-out", session.typing.typingAgents.isEmpty())
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    // -- the acknowledgement dock (M4 Task 5) -------------------------------------

    private fun sent() = uniffi.supermessage_core.Acknowledgement(
        uniffi.supermessage_core.AcknowledgementKind.SENT, listOf("Atlas"), "Sent to Atlas",
    )

    @Test
    fun acknowledgementAsksTheCoreWithTheRoomsFacts() = runTest {
        val fake = FakeCore(restoreSessionResult = { true })
        fake.ackBody = { sent() }
        val session = sessionOf(fake, this)

        val ack = session.acknowledgement(turnRoom, "@atlas:x", listOf("@atlas:x", "@quill:x"), listOf("Atlas"), true)

        assertEquals("Sent to Atlas", ack?.text)
        assertEquals(
            listOf(FakeCore.AckAsk(turnRoom, "@atlas:x", listOf("@atlas:x", "@quill:x"), listOf("Atlas"), true)),
            fake.ackAsks,
        )
    }

    @Test
    fun aRefusedAskIsNoDock() = runTest {
        val fake = FakeCore(restoreSessionResult = { true })
        fake.ackBody = { throw FfiException.RoomChanged("!a:x", "!b:x") }
        val session = sessionOf(fake, this)

        assertNull(session.acknowledgement("!a:x", null, emptyList(), emptyList(), false))
    }

    @Test
    fun signOutClearsTheDock() = runTest {
        val session = sessionOf(FakeCore(restoreSessionResult = { true }), this)
        session.start()
        advanceUntilIdle()
        session.open(turnRoom)
        session.dock.hold(turnRoom, sent())
        assertNotNull(session.dock.held.value)

        session.signOut()

        assertNull("the first account's dock survived sign-out", session.dock.held.value)
        session.drainJob?.cancel()
        advanceUntilIdle()
    }

    @Test
    fun aDockIsOnlyHeldForTheOpenRoom() = runTest {
        val session = sessionOf(FakeCore(restoreSessionResult = { true }), this)
        session.start()
        advanceUntilIdle()
        session.open("!a:x")
        session.dock.hold("!b:x", sent())
        assertNull("a late answer for another room is dropped", session.dock.held.value)
        session.dock.hold("!a:x", sent())
        assertNotNull(session.dock.held.value)
        session.open("!b:x")
        assertNull("opening another room drops it", session.dock.held.value)
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
        private val searchMessagesResult: (String, String?) -> List<SearchResultDto> =
            { _, _ -> throw NotImplementedError() },
        private val sendPermissionAnswerResult: (String, String) -> Unit = { _, _ -> },
        private val restoreFails: Boolean = false,
    ) : StubCore() {
        /** Calls the safety methods saw, in order. */
        val safetyCalls = mutableListOf<String>()
        var reportEventBody: (String, String, String) -> Unit = { _, _, _ -> }
        var reportRoomBody: (String, String) -> Unit = { _, _ -> }
        var ignoreUserBody: (String) -> Unit = {}
        var ignoredUsersBody: () -> List<RoomMemberDto> = { emptyList() }
        var members: List<RoomMemberDto> = emptyList()
        var roomInfoFails = false
        var accountUserId = "@me:x"

        override fun account() = AccountDto(userId = accountUserId, homeserver = "https://x.example")
        val roomInfoAsked = mutableListOf<String>()

        override fun reportEvent(roomId: String, eventId: String, reason: String) {
            safetyCalls += "reportEvent($roomId,$eventId,$reason)"
            reportEventBody(roomId, eventId, reason)
        }

        /** Every set the core was handed, in the order the pushes finished. */
        val hiddenPushes: MutableList<List<String>> = java.util.Collections.synchronizedList(mutableListOf())
        var hiddenPushBody: (List<String>) -> Unit = {}

        override fun setHiddenEvents(eventIds: List<String>) {
            hiddenPushBody(eventIds)
            hiddenPushes += eventIds
        }

        override fun reportRoom(roomId: String, reason: String) {
            safetyCalls += "reportRoom($roomId,$reason)"
            reportRoomBody(roomId, reason)
        }

        override fun ignoreUser(userId: String) {
            safetyCalls += "ignoreUser($userId)"
            ignoreUserBody(userId)
        }

        override fun unignoreUser(userId: String) {
            safetyCalls += "unignoreUser($userId)"
            ignoreUserBody(userId)
        }

        override fun ignoredUsers(): List<RoomMemberDto> = ignoredUsersBody()

        override fun roomInfo(roomId: String): RoomInfoDto {
            roomInfoAsked += roomId
            if (roomInfoFails) throw FfiException.NotReady()
            return RoomInfoDto(
                roomId = roomId, name = "Room", identity = RoomIdentity(null, "Room", null, "R"),
                topic = null, runtime = null, canonicalAlias = null, altAliases = emptyList(),
                activeMemberCount = members.size.toULong(), members = members,
                notifications = NotificationMode.DEFAULT, pinned = false,
            )
        }

        val permissionAnswers = mutableListOf<Pair<String, String>>()
        val sentMessages = mutableListOf<Triple<String, String, List<String>>>()
        var attachmentOutcome: AttachmentSendOutcome = AttachmentSendOutcome.Sent
        val sentCaptions = mutableListOf<String?>()

        var logoutCallCount = 0
            private set
        var timelineSubscribeCallCount = 0
            private set

        override fun login(homeserver: String, username: String, password: String, sink: EventSink): Unit =
            loginBody(homeserver, username, password, sink)

        override fun restoreSession(sink: EventSink): Boolean {
            if (restoreFails) throw FfiException.Store("x")
            return restoreSessionResult()
        }

        override fun logout() {
            logoutCallCount += 1
        }

        override fun roomsSnapshot(): RoomsSnapshot = roomsSnapshotResult()

        override fun spacesList(): List<SpaceSummary> = spacesListResult()

        override fun timelineSubscribe(roomId: String, sink: EventSink) {
            timelineSubscribeCallCount += 1
        }

        override fun sendPermissionAnswer(roomId: String, optionId: String) {
            sendPermissionAnswerResult(roomId, optionId)
            permissionAnswers += roomId to optionId
        }

        override fun joinRoom(roomId: String): Unit = joinRoomResult(roomId)

        override fun sendMessage(roomId: String, body: String, mentions: List<String>) {
            sentMessages += Triple(roomId, body, mentions)
        }

        var turnBody: (String) -> uniffi.supermessage_core.TurnSnapshot? = { null }

        /** The five arguments each ask for an acknowledgement carried. */
        data class AckAsk(
            val roomId: String, val addressee: String?, val agentIds: List<String>,
            val typingAgents: List<String>, val turnInProgress: Boolean,
        )
        val ackAsks = mutableListOf<AckAsk>()
        var ackBody: () -> uniffi.supermessage_core.Acknowledgement? = { null }

        override fun acknowledgement(
            roomId: String, addressee: String?, agentIds: List<String>,
            typingAgents: List<String>, turnInProgress: Boolean,
        ): uniffi.supermessage_core.Acknowledgement? {
            ackAsks += AckAsk(roomId, addressee, agentIds, typingAgents, turnInProgress)
            return ackBody()
        }

        override fun turn(roomId: String): uniffi.supermessage_core.TurnSnapshot? = turnBody(roomId)

        override fun setTyping(roomId: String, typing: Boolean) {}

        override fun attachmentStagePath(roomId: String, path: String): StagedFile =
            StagedFile(token = "tok-1", filename = "photo.jpg", sizeBytes = 10u, mime = "image/jpeg", width = null, height = null)

        override fun attachmentSend(roomId: String, token: String, caption: String?): AttachmentSendOutcome {
            sentCaptions += caption
            return attachmentOutcome
        }

        override fun searchMessages(term: String, roomId: String?): List<SearchResultDto> =
            searchMessagesResult(term, roomId)
    }
}
