package dev.supermessage.kit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.supermessage_core.TypingUserDto
import uniffi.supermessage_ffi.FfiEvent

/**
 * How this host proves the core's events survive the trip from the core's
 * thread to a single collector without being reordered, dropped, or blocked
 * on.
 *
 * **The rules themselves, restated from `apple/SupermessageKit/EventPump.swift`
 * and `apple/SupermessageKitTests/EventPumpTests.swift`:** `DiffEnvelope`
 * carries a `seq`, and the recovery logic depends on those arriving in
 * emission order. Exactly one consumer drains [EventPump.events], so arrival
 * order survives end to end — which is not automatic. The tempting
 * alternative, launching a coroutine per event inside `onEvent`, looks
 * equivalent and is not: coroutine dispatch order is not guaranteed, so under
 * load the diffs interleave, and applying them out of order corrupts the
 * reader's view in a way that presents as a rendering bug rather than a
 * threading one. "ten thousand events arrive in the order they were emitted"
 * below is the test that pins this.
 */
class EventPumpTest {

    /**
     * A typing record. The pump does not care what is inside one — these
     * tests are about ordering and delivery — so the label carries the
     * marker each case asserts on.
     */
    private fun typist(label: String) = TypingUserDto(userId = "@$label:x.org", displayName = label, label = label)

    private fun typingEvent(label: String) = FfiEvent.Typing(roomId = "!r:x.org", users = listOf(typist(label)))

    /**
     * The one the probe never ran.
     *
     * `DiffEnvelope` carries a `seq`, and the timeline's recovery logic is
     * built on those arriving in the order they were emitted. A pump that
     * spawned a task per event would reorder them under load and corrupt the
     * reader's view — and it would look like a rendering bug rather than a
     * threading one, which is why it is worth ten thousand events to pin.
     *
     * Real threads, not `runTest`'s virtual scheduler: [kotlinx.coroutines.test]'s
     * `TestCoroutineScheduler` runs launched coroutines in the deterministic
     * order they were queued, which would make a per-event `launch` look
     * ordered even though it is not. Only genuine OS-thread concurrency —
     * `runBlocking` here, with the producer on its own real [Thread] — gives
     * a per-event-launch mutation an actual chance to interleave, the same
     * reason Task 7's starvation test used `runBlocking` over `runTest`.
     */
    @Test
    fun `ten thousand events arrive in the order they were emitted`() = runBlocking {
        val pump = EventPump()
        val count = 10_000

        // Emitted from a background thread, because that is where the core
        // emits: the generated callback interface is invoked on whatever
        // thread called it, which in production is a tokio worker or a
        // matrix-sdk event handler — never the collector's own thread.
        val producer = Thread {
            for (seq in 0 until count) {
                pump.onEvent(typingEvent(seq.toString()))
            }
            pump.finish()
        }
        producer.start()

        val seen = pump.events
            .toList()
            .mapNotNull { event ->
                (event as? FfiEvent.Typing)?.users?.firstOrNull()?.label?.toIntOrNull()
            }
        producer.join()

        assertEquals("events were dropped", count, seen.size)
        assertEquals("events were reordered", (0 until count).toList(), seen)
    }

    /** the stream ends when the pump is finished */
    @Test
    fun `the stream ends when the pump is finished`() = runBlocking {
        val pump = EventPump()
        pump.finish()

        val received = pump.events.toList()

        assertEquals(0, received.size)
    }

    /**
     * an event emitted before anyone listens is not lost
     *
     * The buffer is unbounded on purpose. Dropping the oldest would drop a
     * diff envelope, and a dropped envelope is a gap the tracker cannot tell
     * from a lost one — recoverable, but only by a resync nobody asked for.
     */
    @Test
    fun `an event emitted before anyone listens is not lost`() = runBlocking {
        val pump = EventPump()
        pump.onEvent(typingEvent("early"))
        pump.finish()

        val seen = pump.events
            .toList()
            .flatMap { event -> (event as? FfiEvent.Typing)?.users?.map { it.label } ?: emptyList() }

        assertEquals(listOf("early"), seen)
    }
}
