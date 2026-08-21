package dev.supermessage.kit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Paces an agent's answer onto the screen.
 *
 * **The network must not decide the animation speed.** A model that emits
 * twenty tokens in one frame and then pauses produces bursts — half a
 * paragraph appearing at once, then nothing — and a slow model produces a
 * stutter. Both look like a fault in the app rather than in the model.
 *
 * So deltas go into a buffer and are revealed on this type's own clock, a
 * few characters per tick, faster when it is falling behind. What the caller
 * reads is [text]; what arrived is `pending`, and the gap between them is
 * what keeps the reveal steady whatever the model does.
 *
 * The core already de-duplicates and orders the stream (`live::accept`), and
 * each delta is the **whole answer so far** rather than an increment — so
 * [accept] takes the full text and works out what is new, rather than
 * appending.
 *
 * [scope] is where the reveal loop runs. It must be confined to a single
 * thread of execution — the way Swift's original confines this type to
 * `@MainActor` — because [accept] and the loop both read and write [text]
 * and `pending` without locking; a `CoroutineScope` backed by a UI-thread or
 * test dispatcher (never a multi-threaded one) is what keeps that safe. The
 * caller supplies it rather than this type hardcoding one, so a test can
 * inject a virtual-time scope instead of actually waiting on a clock.
 */
class StreamingText(private val scope: CoroutineScope) {

    /** What is on screen. */
    var text: String = ""
        private set

    /**
     * How many characters of [text] are new enough to still be animating in.
     *
     * A caller fades exactly these. Without it the whole paragraph would
     * re-animate on every tick — the trap with a naive transition that
     * animates far more of the string than intended.
     */
    var revealed: Int = 0
        private set

    private var pending: String = ""
    private var job: Job? = null

    /**
     * Accept the answer as it stands. Idempotent: the same text twice does
     * nothing.
     */
    fun accept(full: String) {
        if (full == text + pending) return

        // A stream that rewrote its history rather than extending it — a
        // resend after a reconnect, say. Nothing sensible can be animated
        // out of that, so it lands whole.
        if (!full.startsWith(text)) {
            finish(full)
            return
        }

        pending = full.substring(text.length)
        start()
    }

    /**
     * The turn ended. Drain whatever is left immediately: the reader is now
     * waiting on an animation rather than on a model.
     */
    fun finish(full: String? = null) {
        job?.cancel()
        job = null
        text = full ?: (text + pending)
        pending = ""
        revealed = 0
    }

    fun clear() {
        job?.cancel()
        job = null
        text = ""
        pending = ""
        revealed = 0
    }

    private fun start() {
        if (job != null) return
        job = scope.launch {
            while (pending.isNotEmpty()) {
                val take = batch(backlog = pending.length)
                text += pending.substring(0, take)
                pending = pending.substring(take)
                revealed = take
                delay(tick)
            }
            revealed = 0
            job = null
        }
    }

    companion object {
        /** How long between reveals. Short enough to read as motion rather
         * than as steps, long enough that each tick is a frame's worth of
         * work. */
        val tick: Duration = 20.milliseconds

        /**
         * How many characters to reveal this tick.
         *
         * Grows with the backlog so a fast model is not held to a crawl and
         * a slow one is not made to look sluggish. The reveal stays smooth
         * either way, because the *rate* changes rather than the rhythm.
         */
        fun batch(backlog: Int): Int {
            val size = when {
                backlog < 20 -> 1
                backlog < 100 -> 2
                backlog < 400 -> 4
                else -> 12
            }
            return minOf(size, backlog)
        }
    }
}
