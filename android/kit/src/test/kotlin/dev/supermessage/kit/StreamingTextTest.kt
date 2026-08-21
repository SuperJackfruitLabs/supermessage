package dev.supermessage.kit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pacing behind the streaming reveal.
 *
 * The rule this exists to hold: **the network does not decide the animation
 * speed.** A model that emits twenty tokens in one frame and then pauses
 * would otherwise dump half a paragraph at once and then stall, which reads
 * as a fault in the app rather than in the model.
 */
class StreamingTextTest {

    /** "a small backlog reveals a character at a time" */
    @Test
    fun slowStream() {
        assertEquals(1, StreamingText.batch(backlog = 1))
        assertEquals(1, StreamingText.batch(backlog = 19))
    }

    /** "a bigger backlog reveals faster, so a quick model is not held back" */
    @Test
    fun fastStream() {
        assertEquals(2, StreamingText.batch(backlog = 50))
        assertEquals(4, StreamingText.batch(backlog = 200))
        assertEquals(12, StreamingText.batch(backlog = 5_000))
    }

    /** "a batch never overruns what is actually waiting" */
    @Test
    fun neverOverruns() {
        // The substring that reveals a batch would throw past the end.
        for (backlog in listOf(0, 1, 2, 3)) {
            assertTrue(StreamingText.batch(backlog = backlog) <= backlog)
        }
    }

    /** "the same text twice changes nothing" */
    @Test
    fun idempotent() = runTest {
        val s = StreamingText(this)
        s.accept("Hello")
        s.finish()
        val before = s.text
        s.accept("Hello")
        assertEquals(before, s.text)
    }

    /** "finishing drains whatever was still waiting" */
    @Test
    fun finishDrains() = runTest {
        // The turn has ended, so the reader is waiting on an animation rather
        // than on a model — the rest should land at once.
        val s = StreamingText(this)
        s.accept("The whole answer, arriving in one go.")
        s.finish()
        assertEquals("The whole answer, arriving in one go.", s.text)
        // nothing should still be animating once it has landed
        assertEquals(0, s.revealed)
    }

    /** "a stream that rewrites itself lands whole rather than animating nonsense" */
    @Test
    fun rewriteLandsWhole() = runTest {
        // A resend after a reconnect: the new text is not an extension of
        // what is on screen, so there is no meaningful "new" part to fade in.
        val s = StreamingText(this)
        s.accept("First attempt")
        s.finish()
        s.accept("Completely different text")
        assertEquals("Completely different text", s.text)
    }

    /** "clearing forgets the turn entirely" */
    @Test
    fun clearing() = runTest {
        val s = StreamingText(this)
        s.accept("Something")
        s.clear()
        assertTrue(s.text.isEmpty())
        assertEquals(0, s.revealed)
    }

    /**
     * The rule this whole file exists for, made concrete: the reveal is
     * paced on [StreamingText.tick], not dumped onto the screen the instant
     * a delta arrives.
     *
     * None of the tests above — ported faithfully from
     * StreamingTextTests.swift — actually exercise this. Every one of them
     * either calls `finish()` (defined to drain immediately) before reading
     * `text`, or never gives the reveal loop a chance to run at all. A
     * pacer that stopped pacing entirely would still pass every test above.
     *
     * This one runs the loop as far as it can go without any virtual time
     * passing (`runCurrent`, no `advanceTimeBy`) and checks that only one
     * batch landed — the rest is genuinely waiting on the clock, not just
     * on the test not having asked for it yet. It then drains the whole
     * reveal and checks that doing so actually cost virtual time. Both
     * checks fail together if [StreamingText.tick] is ever zeroed out: with
     * no delay to wait on, a single `runCurrent()` drains the entire
     * backlog in one pass, and the whole reveal costs no time at all.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pacesTheRevealOverTicks() = runTest {
        val s = StreamingText(this)
        val answer = "x".repeat(50) // backlog 50 -> batch(50) == 2 chars/tick

        s.accept(answer)
        // The loop hasn't run at all yet: launch alone doesn't run it.
        assertEquals("", s.text)

        // Everything the loop can do without any virtual time elapsing: one
        // batch, landing it right at the tick boundary it must then wait on.
        testScheduler.runCurrent()
        assertEquals(2, s.text.length)
        assertTrue(
            "the whole backlog landed without a single tick of virtual " +
                "time passing — the reveal is no longer paced",
            s.text.length < answer.length,
        )

        // Let the rest happen, however long it takes.
        testScheduler.advanceUntilIdle()
        assertEquals(answer, s.text)
        assertTrue(
            "the full reveal consumed zero virtual time — the pacing " +
                "constant has been zeroed out",
            testScheduler.currentTime > 0,
        )

        s.finish()
    }
}
