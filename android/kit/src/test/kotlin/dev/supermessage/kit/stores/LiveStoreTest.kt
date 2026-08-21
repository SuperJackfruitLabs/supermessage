package dev.supermessage.kit.stores

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from `apple/SupermessageKitTests/LiveStoreTests.swift`.
 *
 * What survives the end of a turn, and what does not.
 *
 * Reported: "reasoning gets hidden as soon as the complete message is
 * delivered even when I am present in the room. I don't get enough time to
 * read the reasoning." The store threw away the reasoning and the tool
 * calls on `done`, which meant the record of *how* an agent reached its
 * answer was only ever on screen while the answer was still being written
 * — and gone by the time anyone had read the answer it belonged to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveStoreTest {
    private val room = "!r:x.org"

    // Swift's `store()` helper returns a bare `LiveStore()` because Swift's
    // pacing lives in the view (`LiveTurnView`), not the store — nothing to
    // inject. Android's `LiveStore` takes a `CoroutineScope` for the pacer
    // it now owns (see its KDoc), which only exists inside `runTest`, so
    // each test constructs it inline with `LiveStore(this)` instead of
    // through a shared helper.

    /** "reasoning outlives the turn that produced it" */
    @Test
    fun reasoningSurvivesDone() = runTest {
        val live = LiveStore(this)
        live.focus(room)
        live.handleThought(roomId = room, seq = 1uL, text = "Checking the logs first.", done = false)
        live.handleLive(roomId = room, seq = 1uL, text = "Looking…", done = false)

        live.handleLive(roomId = room, seq = 2uL, text = "", done = true)

        assertEquals("the reasoning was thrown away", "Checking the logs first.", live.thought.value)
        assertTrue(live.finished.value)
        assertTrue("the record disappeared along with the turn", live.isLive)
    }

    /** "the streamed answer goes, because the real message says it better" */
    @Test
    fun answerIsDroppedOnDone() = runTest {
        // The one thing that *should* go: it is about to arrive on the
        // timeline as a real message, and two copies of the same sentence
        // stacked on each other is what this avoids.
        val live = LiveStore(this)
        live.focus(room)
        live.handleLive(roomId = room, seq = 1uL, text = "Half an answ", done = false)
        live.handleLive(roomId = room, seq = 2uL, text = "", done = true)

        assertNull("the streamed answer outlived the message that replaces it", live.answer.value)
    }

    /** "a thought's own done does not hide it either" */
    @Test
    fun thoughtDoneKeepsTheText() = runTest {
        val live = LiveStore(this)
        live.focus(room)
        live.handleThought(roomId = room, seq = 1uL, text = "Two options here.", done = false)
        live.handleThought(roomId = room, seq = 2uL, text = "", done = true)

        assertEquals("Two options here.", live.thought.value)
        assertTrue(live.finished.value)
    }

    /** "tool calls outlive the turn too" */
    @Test
    fun toolsSurviveDone() = runTest {
        val live = LiveStore(this)
        live.focus(room)
        live.handleTool(
            roomId = room, seq = 1uL, toolCallId = "c1", title = "Run tests", kind = "execute",
            status = "completed", locations = listOf("crates/core"), input = "cargo test", output = "ok",
        )
        live.handleLive(roomId = room, seq = 1uL, text = "", done = true)

        assertEquals(1, live.tools.value.size)
        assertEquals("ok", live.tools.value[0].output)
    }

    /** "the next turn replaces the last one's record" */
    @Test
    fun aNewTurnClearsTheOldRecord() = runTest {
        // The record has to end somewhere, and this is where: it is
        // replaced, not expired. Otherwise two turns' reasoning would stack
        // up.
        val live = LiveStore(this)
        live.focus(room)
        live.handleThought(roomId = room, seq = 1uL, text = "First turn's thinking.", done = false)
        live.handleLive(roomId = room, seq = 1uL, text = "", done = true)

        live.handleLive(roomId = room, seq = 1uL, text = "Second turn…", done = false)

        assertNull("the last turn's reasoning survived into the next one", live.thought.value)
        assertFalse(live.finished.value)
        assertEquals("Second turn…", live.answer.value)
    }

    /** "a turn's record does not follow the reader into another room" */
    @Test
    fun focusClearsIt() = runTest {
        val live = LiveStore(this)
        live.focus(room)
        live.handleThought(roomId = room, seq = 1uL, text = "Room one's thinking.", done = false)
        live.handleLive(roomId = room, seq = 1uL, text = "", done = true)

        live.focus("!other:x.org")

        assertFalse("one room's turn showed up under another room's name", live.isLive)
        assertNull(live.thought.value)
    }

    /** "a tool row with nothing behind it does not pretend otherwise" */
    @Test
    fun detailIsOptional() {
        // Every harness today reports title, kind and status and nothing
        // else. A disclosure triangle opening onto an empty box says there
        // is something to see.
        val bare = LiveStore.ToolCall(
            id = "c1", title = "Read a file", status = "completed", kind = null, locations = emptyList(),
            input = null, output = null,
        )
        assertFalse(bare.hasDetail)

        val touched = LiveStore.ToolCall(
            id = "c2", title = "Read a file", status = "completed", kind = null,
            locations = listOf("src/main.rs"), input = null, output = null,
        )
        assertTrue(touched.hasDetail)
    }

    /** "a later report on the same call replaces it rather than stacking" */
    @Test
    fun toolUpdatesMerge() = runTest {
        val live = LiveStore(this)
        live.focus(room)
        live.handleTool(
            roomId = room, seq = 1uL, toolCallId = "c1", title = "Run tests", kind = null,
            status = "in_progress", locations = emptyList(), input = null, output = null,
        )
        live.handleTool(
            roomId = room, seq = 2uL, toolCallId = "c1", title = "Run tests", kind = null,
            status = "completed", locations = emptyList(), input = null, output = "3 passed",
        )

        assertEquals("one call produced two rows", 1, live.tools.value.size)
        assertEquals("completed", live.tools.value[0].status)
        assertEquals("3 passed", live.tools.value[0].output)
    }

    /**
     * The rule this file exists for, made concrete for the part
     * `LiveStoreTests.swift` never had to cover: unlike Swift, where the
     * pacer lives in the view and never touches the store under test,
     * Android's `LiveStore` owns the [StreamingText] instance itself (see
     * its KDoc). A store that fed it the raw text but never actually
     * called through would still pass every test above, because they all
     * read [LiveStore.answer] — the raw field — never [LiveStore.stream].
     *
     * This one drives the reveal through the store and checks it is
     * genuinely paced: nothing lands before a tick of virtual time passes,
     * and draining the whole thing costs virtual time rather than none.
     */
    @Test
    fun theStreamedAnswerIsPacedNotDumped() = runTest {
        val live = LiveStore(this)
        live.focus(room)
        val answer = "x".repeat(50) // backlog 50 -> batch(50) == 2 chars/tick

        live.handleLive(roomId = room, seq = 1uL, text = answer, done = false)
        // Launching the pacer doesn't run it.
        assertEquals("", live.stream.text)

        testScheduler.runCurrent()
        assertTrue(
            "the whole backlog landed without a single tick of virtual time passing " +
                "— LiveStore is handing the raw text to a UI rather than the paced one",
            live.stream.text.length in 1 until answer.length,
        )

        testScheduler.advanceUntilIdle()
        assertEquals(answer, live.stream.text)
        assertTrue(
            "the full reveal consumed zero virtual time — LiveStore never actually " +
                "routed through the pacer",
            testScheduler.currentTime > 0,
        )
    }

    /** "the pacer's own record does not outlive the turn it belonged to either" */
    @Test
    fun doneDrainsAndClearsThePacer() = runTest {
        val live = LiveStore(this)
        live.focus(room)
        live.handleLive(roomId = room, seq = 1uL, text = "Half an answ", done = false)
        live.handleLive(roomId = room, seq = 2uL, text = "", done = true)
        testScheduler.advanceUntilIdle()

        assertEquals("", live.stream.text)
        assertEquals(0, live.stream.revealed)
    }
}
