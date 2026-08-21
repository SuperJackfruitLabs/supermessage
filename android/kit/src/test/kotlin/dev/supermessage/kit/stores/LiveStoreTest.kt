package dev.supermessage.kit.stores

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
class LiveStoreTest {
    private val room = "!r:x.org"

    private fun store(): LiveStore {
        val live = LiveStore()
        live.focus(room)
        return live
    }

    /** "reasoning outlives the turn that produced it" */
    @Test
    fun reasoningSurvivesDone() {
        val live = store()
        live.handleThought(roomId = room, seq = 1uL, text = "Checking the logs first.", done = false)
        live.handleLive(roomId = room, seq = 1uL, text = "Looking…", done = false)

        live.handleLive(roomId = room, seq = 2uL, text = "", done = true)

        assertEquals("the reasoning was thrown away", "Checking the logs first.", live.thought.value)
        assertTrue(live.finished.value)
        assertTrue("the record disappeared along with the turn", live.isLive)
    }

    /** "the streamed answer goes, because the real message says it better" */
    @Test
    fun answerIsDroppedOnDone() {
        // The one thing that *should* go: it is about to arrive on the
        // timeline as a real message, and two copies of the same sentence
        // stacked on each other is what this avoids.
        val live = store()
        live.handleLive(roomId = room, seq = 1uL, text = "Half an answ", done = false)
        live.handleLive(roomId = room, seq = 2uL, text = "", done = true)

        assertNull("the streamed answer outlived the message that replaces it", live.answer.value)
    }

    /** "a thought's own done does not hide it either" */
    @Test
    fun thoughtDoneKeepsTheText() {
        val live = store()
        live.handleThought(roomId = room, seq = 1uL, text = "Two options here.", done = false)
        live.handleThought(roomId = room, seq = 2uL, text = "", done = true)

        assertEquals("Two options here.", live.thought.value)
        assertTrue(live.finished.value)
    }

    /** "tool calls outlive the turn too" */
    @Test
    fun toolsSurviveDone() {
        val live = store()
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
    fun aNewTurnClearsTheOldRecord() {
        // The record has to end somewhere, and this is where: it is
        // replaced, not expired. Otherwise two turns' reasoning would stack
        // up.
        val live = store()
        live.handleThought(roomId = room, seq = 1uL, text = "First turn's thinking.", done = false)
        live.handleLive(roomId = room, seq = 1uL, text = "", done = true)

        live.handleLive(roomId = room, seq = 1uL, text = "Second turn…", done = false)

        assertNull("the last turn's reasoning survived into the next one", live.thought.value)
        assertFalse(live.finished.value)
        assertEquals("Second turn…", live.answer.value)
    }

    /** "a turn's record does not follow the reader into another room" */
    @Test
    fun focusClearsIt() {
        val live = store()
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
    fun toolUpdatesMerge() {
        val live = store()
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
}
