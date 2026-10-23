package dev.supermessage.kit.stores

import dev.supermessage.kit.HostCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uniffi.supermessage_core.ToolPhase
import uniffi.supermessage_core.TurnEnding
import uniffi.supermessage_core.TurnInput
import uniffi.supermessage_core.TurnSnapshot
import uniffi.supermessage_core.TurnStep
import uniffi.supermessage_core.TurnStepChange
import uniffi.supermessage_core.TurnUpdate
import uniffi.supermessage_ffi.foldTurn

/**
 * Ported from `apple/SupermessageKitTests/LiveStoreTests.swift` (after M1b C5).
 *
 * The store mirrors the core's turn record. Its fixtures are folded by the
 * **real** `foldTurn`, so a turn here is what the core would say, not what a
 * test author imagines it says; only the revision and turn id are then set by
 * hand where a test needs two turns or two revisions of one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveStoreTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun hostCore() = HostCore.ensureBuilt("LiveStoreTest")
    }

    private val room = "!r:x.org"
    private val other = "!other:x.org"

    private fun step(id: String, seq: ULong = 1uL, phase: ToolPhase = ToolPhase.RUNNING, title: String = id) =
        TurnStep(
            id = id, title = title, kind = null, status = "in_progress", phase = phase, statusLabel = "Running",
            locations = emptyList(), input = null, output = null, seq = seq, hasDetail = false,
        )

    /** The core's fold of a turn that has run `inputs`. */
    private fun fold(roomId: String = room, vararg inputs: TurnInput): TurnSnapshot =
        foldTurn(roomId, inputs.toList()) ?: error("the core folded no turn")

    private fun tool(id: String, seq: ULong = 1uL, at: ULong = 1_000uL) = TurnInput.Tool(step(id, seq), at)

    /** A snapshot's summary at a chosen turn and revision. */
    private fun TurnSnapshot.at(turnId: ULong, revision: ULong): TurnSnapshot =
        copy(summary = summary.copy(turnId = turnId, revision = revision))

    private fun update(snapshot: TurnSnapshot, index: Int? = null) = TurnUpdate(
        summary = snapshot.summary,
        step = index?.let { TurnStepChange(it.toUInt(), snapshot.steps[it]) },
    )

    private fun store(): LiveStore = LiveStore().also { it.select(room) }

    @Test
    fun anUpdateForAnotherRoomIsIgnored() {
        val live = store()
        live.apply(update(fold(other, tool("a")), index = 0))

        assertNull("another room's turn reached this one", live.summary.value)
        assertTrue(live.steps.value.isEmpty())
        assertFalse(live.isLive)
    }

    @Test
    fun anOlderRevisionDoesNotRewindTheCard() {
        val live = store()
        val snap = fold(room, tool("a"))
        live.apply(update(snap.at(1uL, 5uL)))
        live.apply(update(snap.at(1uL, 4uL)))

        assertEquals("the card went back to an older revision", 5uL, live.summary.value?.revision)
    }

    @Test
    fun aNewTurnDropsTheLastOnesStepsReasoningAndAnswer() {
        val live = store()
        val first = fold(room, tool("a"))
        live.apply(update(first.at(1uL, 1uL), index = 0))
        live.handleThought(room, 1uL, "Checking the logs first.", done = false)
        live.handleLive(room, 1uL, "Half an answer", done = false)

        live.apply(update(fold(room, tool("b")).at(2uL, 2uL)))

        assertEquals(2uL, live.summary.value?.turnId)
        assertTrue("the last turn's steps survived", live.steps.value.isEmpty())
        assertNull("the last turn's reasoning survived", live.thought.value)
        assertNull("the last turn's answer survived", live.answer.value)
    }

    @Test
    fun aStepChangeLandsAtTheIndexTheCoreGave() {
        val live = store()
        val snap = fold(room, tool("a"), tool("b", at = 2_000uL))
        live.apply(update(snap.at(1uL, 1uL), index = 0))
        live.apply(update(snap.at(1uL, 2uL), index = 1))
        assertEquals(listOf("a", "b"), live.steps.value.map { it.id })

        val changed = snap.steps[0].copy(title = "a, renamed", seq = 2uL)
        live.apply(TurnUpdate(snap.at(1uL, 3uL).summary, TurnStepChange(0u, changed)))

        assertEquals("index 0 appended instead of replacing", 2, live.steps.value.size)
        assertEquals("a, renamed", live.steps.value[0].title)
    }

    @Test
    fun openingARoomRestoresItsTurnFromTheCore() = runTest {
        val live = LiveStore()
        val finished = fold(
            room,
            TurnInput.Thought(1uL, "Two options here.", false, 500uL),
            tool("a"),
            TurnInput.Answer(1uL, true, 2_000uL),
        )

        live.focus(room) { finished }

        assertTrue("a finished turn did not restore as finished", live.finished.value)
        assertEquals(TurnEnding.DONE, live.summary.value?.endedBy)
        assertEquals("Two options here.", live.thought.value)
        assertEquals(listOf("a"), live.steps.value.map { it.id })
        assertNull(live.answer.value)
        assertFalse(live.inProgress.value)
    }

    @Test
    fun aRestoreForARoomLeftMeanwhileIsDropped() = runTest {
        val live = LiveStore()
        val reply = CompletableDeferred<TurnSnapshot?>()
        val first = launch { live.focus("!a:x") { reply.await() } }
        testScheduler.advanceUntilIdle()

        live.focus("!b:x") { null }
        reply.complete(fold("!a:x", tool("a")))
        first.join()

        assertNull("a turn of the room left behind showed", live.summary.value)
        assertTrue(live.steps.value.isEmpty())
    }

    @Test
    fun aRestoreForARoomLeftAndReturnedToIsStillDropped() = runTest {
        // A, then B, then A again: the room ids match, the visit does not.
        val live = LiveStore()
        val stale = CompletableDeferred<TurnSnapshot?>()
        val first = launch { live.focus("!a:x") { stale.await() } }
        testScheduler.advanceUntilIdle()

        live.focus("!b:x") { null }
        live.focus("!a:x") { null }
        stale.complete(fold("!a:x", tool("old")))
        first.join()

        assertTrue("an answer to an earlier visit was shown", live.steps.value.isEmpty())
    }

    @Test
    fun theStreamedAnswerComesFromTheLiveChannelAndGoesOnDone() {
        val live = store()
        live.handleLive(room, 1uL, "Half an answ", done = false)
        assertEquals("Half an answ", live.answer.value)
        live.handleLive(room, 0uL, "older", done = false)
        assertEquals("a late delta rewound the text", "Half an answ", live.answer.value)

        live.handleLive(room, 2uL, "", done = true)
        assertNull("the streamed answer outlived the message that replaces it", live.answer.value)
    }

    @Test
    fun reasoningAndStepsOutliveTheTurn() {
        val live = store()
        val snap = fold(room, tool("a"), TurnInput.Answer(1uL, true, 2_000uL)).at(1uL, 3uL)
        live.handleThought(room, 1uL, "Checking the logs first.", done = false)
        live.apply(update(snap, index = 0))
        live.handleThought(room, 2uL, "", done = true)

        assertTrue(live.finished.value)
        assertEquals("the reasoning was thrown away", "Checking the logs first.", live.thought.value)
        assertEquals(1, live.steps.value.size)
    }

    @Test
    fun theHeadersNameIsKeptPerRoom() {
        val live = LiveStore()
        live.setAgentName("Atlas", "!a:x")
        live.setAgentName("Bodhi", "!b:x")
        live.select("!b:x")

        assertEquals("Atlas", live.agentName("!a:x"))
        assertEquals("Bodhi", live.agentName("!b:x"))
        assertNull(live.agentName("!c:x"))
    }

    @Test
    fun clearForgetsTheTurnAndTheNames() {
        val live = store()
        live.setAgentName("Atlas", room)
        live.apply(update(fold(room, tool("a")), index = 0))
        live.handleLive(room, 1uL, "text", done = false)

        live.clear()

        assertNull(live.summary.value)
        assertNull(live.answer.value)
        assertTrue(live.steps.value.isEmpty())
        assertFalse(live.isLive)
        assertNull(live.agentName(room))
        // And nothing is focused: a late update for the old room is ignored.
        live.apply(update(fold(room, tool("a")), index = 0))
        assertNull(live.summary.value)
    }

    // -- an update that arrives while focus waits on the snapshot ----------

    @Test
    fun aStepPastAGapWaitsForTheSnapshotAndNeverLandsOutOfPlace() {
        val live = store()
        val snap = fold(room, tool("a"), tool("b", at = 2_000uL), tool("c", at = 3_000uL)).at(1uL, 4uL)

        live.apply(update(snap, index = 2))
        assertTrue("a step was appended past a gap", live.steps.value.isEmpty())

        live.restore(snap.copy(steps = snap.steps.take(2)).at(1uL, 3uL))
        assertEquals(listOf("a", "b", "c"), live.steps.value.map { it.id })
        assertEquals("the older snapshot rewound the summary", 4uL, live.summary.value?.revision)
    }

    @Test
    fun theSnapshotMergesByStepSeqSoALaterReportSurvives() {
        val live = store()
        val snap = fold(room, tool("a")).at(1uL, 2uL)
        live.apply(TurnUpdate(snap.summary, TurnStepChange(0u, snap.steps[0].copy(seq = 5uL, title = "newer"))))

        live.restore(snap)

        assertEquals("newer", live.steps.value[0].title)
    }

    @Test
    fun aGapAsksTheCoreForTheRecordAgain() = runTest {
        val live = LiveStore(this)
        val whole = fold(room, tool("a"), tool("b", at = 2_000uL), tool("c", at = 3_000uL)).at(1uL, 4uL)
        var asked = 0
        live.focus(room) { asked += 1; if (asked == 1) null else whole }

        live.apply(update(whole, index = 2))
        live.apply(update(whole, index = 2))
        testScheduler.advanceUntilIdle()

        assertEquals("a burst of gaps should ask once more, not per update", 2, asked)
        assertEquals(listOf("a", "b", "c"), live.steps.value.map { it.id })
    }

    @Test
    fun aGapThatArrivesWhileTheRecordIsBeingFetchedIsLookedAtAgain() = runTest {
        val live = LiveStore(this)
        val upToC = fold(room, tool("a"), tool("b", at = 2_000uL), tool("c", at = 3_000uL)).at(1uL, 4uL)
        val upToE = fold(
            room, tool("a"), tool("b", at = 2_000uL), tool("c", at = 3_000uL), tool("d", at = 4_000uL),
            tool("e", at = 5_000uL),
        ).at(1uL, 6uL)
        val inFlight = CompletableDeferred<TurnSnapshot?>()
        var asked = 0
        live.focus(room) {
            asked += 1
            when (asked) { 1 -> null; 2 -> inFlight.await(); else -> upToE }
        }

        live.apply(update(upToC, index = 2))
        testScheduler.advanceUntilIdle()
        // The re-fetch is under way; step e lands past a gap too (d is missing).
        live.apply(update(upToE, index = 4))
        // The answer was taken before step e existed.
        inFlight.complete(upToC)
        testScheduler.advanceUntilIdle()

        assertEquals("the second gap must ask again", 3, asked)
        assertEquals(listOf("a", "b", "c", "d", "e"), live.steps.value.map { it.id })
    }

    @Test
    fun aSkippedStepDoesNotAdvanceTheRevision() {
        val live = store()
        val snap = fold(room, tool("a"), tool("b", at = 2_000uL)).at(1uL, 0uL)
        // Revision 7 carries step 1, which cannot land (step 0 was never seen).
        live.apply(TurnUpdate(snap.summary.copy(revision = 7uL), TurnStepChange(1u, snap.steps[1])))
        // Revision 6 carried step 0 and arrives late: it must not be refused as stale.
        live.apply(TurnUpdate(snap.summary.copy(revision = 6uL), TurnStepChange(0u, snap.steps[0])))

        assertEquals(listOf("a", "b"), live.steps.value.map { it.id })
        assertEquals("the summary went back to the older revision", 7uL, live.summary.value?.revision)
    }
}
