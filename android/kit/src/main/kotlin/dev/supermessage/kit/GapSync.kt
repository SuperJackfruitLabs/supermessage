package dev.supermessage.kit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A full snapshot for recovering from a gap.
 *
 * The subject travels with it for the same reason it travels on every
 * envelope — see [GapSync]'s note on subject filtering.
 */
data class Snapshot<T>(val subject: String, val seq: ULong, val items: List<T>)

/**
 * The gap → resync → reset sequencing every diff-backed store needs.
 *
 * Ported from `apple/SupermessageKit/GapSync.swift`, comments and all,
 * itself ported from `src/lib/stores/gapSync.ts`. It is factored out for the
 * reason it was there: the ordering hazards below are subtle enough that
 * they must be written, and tested, exactly once.
 *
 * ## Hazard 1 — a resync in flight
 *
 * `DiffTracker` returning [DiffOutcome.GAP] means a snapshot is needed. But
 * while that round trip is in flight the core keeps emitting on the same
 * channel, and applying those against the pre-reset tracker just rediscovers
 * the same gap and asks again, forever. So once a resync is in flight,
 * further envelopes are ignored until it lands; the tracker is then
 * hard-reset, and the next live envelope — guaranteed by the core to be
 * `seq + 1` — resumes normally.
 *
 * ## Hazard 2 — somebody else's subject
 *
 * A channel's sequence is monotonic per channel **and subject**, not per
 * channel alone. The timeline channel's subject is the focused room id, and
 * it changes under the store while a subscribe round trip is in flight. An
 * envelope — or a resync snapshot — belonging to a subject the store is no
 * longer showing is not a gap and not a duplicate: it is somebody else's
 * data, and the only correct thing to do is drop it.
 *
 * This one cost a real incident. Treating it as a gap resyncs off the
 * *previous* room's still-installed handle and installs that room's messages
 * under the new room's header, where they stay until the next room switch.
 *
 * The in-flight check appears twice — once in [handle], once in
 * [performResync] — and they are **individually redundant**: falsification
 * showed either one alone prevents the observable failure, and only removing
 * both fails a test. That is not an oversight to tidy up. The one in
 * [performResync] makes it safe to call from anywhere without relying on the
 * caller, and the one in [handle] avoids folding a batch that is about to be
 * discarded. The TypeScript called this belt and suspenders; keeping the
 * pair is deliberate, and knowing no single test can isolate them is the
 * point of saying so here.
 *
 * ## Hazard 3 — a resync that lands too late
 *
 * A resync issued under one subscription context can land after the context
 * has changed. Without the generation counter, a slow one rolls the new
 * room's state back to the old room's data.
 *
 * ## Confinement
 *
 * Swift's original is `@MainActor`, which the compiler enforces. Kotlin has
 * no equivalent, so this is a documented invariant instead of a checked one:
 * [handle], [resetForNewSubscription], [stop], [resume], [seed], and the
 * completion of a launched resync all read and write [resyncing],
 * [generation] and [stopped] without locking, so every call — including the
 * one the resync coroutine makes back into this instance when it lands —
 * must happen on the same single thread of execution. [scope] is what
 * supplies that thread, the same way `StreamingText`'s constructor takes
 * one: on Android that is `Dispatchers.Main.immediate`, and in a test it is
 * the dispatcher `runTest` hands out.
 *
 * ## Where this differs from `apple/SupermessageKit/GapSync.swift`
 *
 * [stop] is reversible here; on the Swift side it is not, and this is a
 * confirmed, not assumed, divergence: `GapSync.swift` has no counterpart to
 * [resume] at all, its own `resetForNewSubscription()` (`GapSync.swift:127`)
 * does not clear `stopped` either, and both
 * `apple/SupermessageKit/Stores/RoomsStore.swift` and
 * `.../TimelineStore.swift` build their one `GapSync` inside `init` and
 * never rebuild it — matching `Session.swift:53` and `:58` constructing
 * each store exactly once, inside a `Session` that itself lives for the
 * whole app process (`RootView.swift:12`). Both call `sync?.stop()` from
 * their own `clear()` (`RoomsStore.swift:87`, `TimelineStore.swift:132`) on
 * sign-out. So iOS carries the identical bug on **both** stores: after the
 * first sign-out in a process's life, a later sign-in's roster and timeline
 * stay permanently empty, silently — the same failure `EventPump.reset`
 * fixes for the pump, one layer up, on this platform only. See [resume]'s
 * own doc for the full reasoning behind the fix applied here.
 *
 * @param resync Fetches a full snapshot to recover from a gap.
 * @param accepts Whether an envelope carrying this subject is ours. Anything
 *   it rejects is dropped outright — not a gap, not a duplicate. Its default
 *   accepts everything, for a single-subject channel like the room list,
 *   where every envelope is by definition ours.
 * @param onUpdate Called with the new list whenever it changes.
 */
class GapSync<T>(
    private val scope: CoroutineScope,
    private val resync: suspend () -> Snapshot<T>,
    private val accepts: (String) -> Boolean = { true },
    private val onUpdate: (List<T>) -> Unit,
) {
    private val tracker = DiffTracker<T>()
    private var resyncing = false
    private var generation = 0
    private var stopped = false

    /** Fold one envelope in, or recover. */
    fun handle(subject: String, seq: ULong, ops: List<DiffOp<T>>) {
        if (stopped) return
        // Somebody else's subject — the previous room's stream, still
        // emitting while this store's subscribe round trip is in flight.
        if (!accepts(subject)) return
        // A resync is already in flight; ignore until it lands and resets.
        if (resyncing) return

        if (tracker.apply(ops, seq) == DiffOutcome.GAP) {
            val currentGeneration = generation
            scope.launch { performResync(currentGeneration) }
            return
        }
        onUpdate(tracker.items)
    }

    /**
     * Fetch a snapshot now, without waiting for a gap to reveal one is
     * needed.
     *
     * The channel only speaks when something *changes*. A store built after
     * the core has already emitted its opening state therefore starts empty
     * and stays empty until the next change, which in a quiet account is
     * minutes. It is not a gap — no envelope ever arrived to be out of
     * sequence with — so nothing would ever ask.
     *
     * On iOS this is not an edge case. It is what happens on **every return
     * from background**: the app was suspended, its sockets died, and the
     * channel has nothing to say until something changes.
     */
    suspend fun seed() {
        performResync(generation)
    }

    /**
     * Hard-reset for a new subscription context — a room switch, where the
     * core restarts the sequence at 1. Publishes an empty list immediately.
     *
     * Calls [resume] first: a room switch is one of the two moments this
     * instance may need to recover from a prior [stop] (the other is
     * [RoomsStore.resume], for a store with no subscription context of its
     * own) — see [resume]'s own doc for why that specifically needs a
     * generation bump too, not just clearing the flag. Bumping generation a
     * second time immediately afterward is redundant but harmless: only
     * inequality against a resync's captured value is ever tested, never the
     * exact number.
     */
    fun resetForNewSubscription() {
        resume()
        generation += 1
        tracker.reset(items = emptyList(), seq = 0uL)
        onUpdate(tracker.items)
    }

    /**
     * Stop until [resume] undoes it, on logout or teardown.
     *
     * Reversible, unlike `apple/SupermessageKit/GapSync.swift`'s equivalent
     * — see [resume]'s doc for why, and this class's own KDoc for the
     * incident this class exists to prevent in the first place, which
     * [stop] is what protects: a resync launched before a deliberate
     * teardown landing afterward and repopulating what that teardown just
     * emptied.
     */
    fun stop() {
        stopped = true
    }

    /**
     * Undo [stop]: let this instance accept envelopes and resyncs again, for
     * a new subscription context or a new session beginning on top of a
     * torn-down one.
     *
     * ## Why this exists at all
     *
     * `RoomsStore` and `TimelineStore` are each built once, inside `Session`,
     * for the whole app process's lifetime — matching
     * `apple/SupermessageKit/Session.swift:48`'s single, never-rebuilt
     * `pump`, the same shape that made [EventPump.reset] necessary. [stop]
     * used to have no way back, which meant the *first* sign-out in a
     * process's life left every store built on this class permanently inert
     * — not just against diffs (`handle`, gated at the top by `stopped`),
     * but against `seed` too (`performResync` gates on the same flag), so a
     * later sign-in's roster and timeline stayed empty forever, silently,
     * with no error to notice. `Session`'s `SessionTest` proves this on the
     * timeline route end to end.
     *
     * ## Why [generation] is bumped here too
     *
     * Clearing [stopped] alone is not enough. A resync launched before
     * [stop] can still be genuinely in flight — a slow `resync()` call that
     * has not yet returned — and simply flipping [stopped] back to `false`
     * would let it land later with its *original* captured generation still
     * matching [generation], and `performResync`'s guard would wave it
     * straight through into a session it has nothing to do with. Bumping
     * [generation] here is the same protection Hazard 3 already documents
     * for [resetForNewSubscription], applied to the same hazard at a
     * different boundary: a session boundary instead of a room-subscription
     * one.
     *
     * ## Why [resyncing] is deliberately left alone
     *
     * A stale resync genuinely still in flight when this is called has not
     * finished, so [resyncing] is still `true`. Forcing it back to `false`
     * here would let a *second* resync start alongside the first —
     * Hazard 1's mutual-exclusion invariant broken by the very method meant
     * to recover from teardown. The old one's own `finally` clears
     * [resyncing] whenever it actually completes, whether or not [resume]
     * was ever called, and the generation bump above is what makes its
     * result harmless if it lands after this.
     *
     * **The practical cost is not as narrow as "self-healing within one
     * round trip" would suggest, and that phrase used to appear here.** A
     * `seed` called immediately after [resume], while that stale resync is
     * still resolving, does not queue behind it or retry once it clears —
     * `performResync`'s own `if (resyncing) return` guard makes it a no-op,
     * full stop. The stale resync's snapshot then lands and is correctly
     * discarded (`generation != this.generation`), but nothing re-issues the
     * seed that was actually needed: the caller gets a store that is empty,
     * or holds only what it had before, until *something else* asks again —
     * the next envelope that happens to arrive as a fresh gap, or a caller
     * invoking `seed` a second time. `RoomsStore.resume`'s own caller,
     * `Session`, avoids the worst of this by calling [resume] before the
     * pump is even handed back to the core, so a stale resync from a prior
     * session is rarely still in flight by the time anything asks again —
     * but "rarely" is not "never", and nothing here forces a retry if it is.
     *
     * ## Where callers reach this
     *
     * [resetForNewSubscription] calls it directly — every `subscribeTo` a
     * room hits it, including the very first one after a sign-in, so
     * `TimelineStore` needed no change at all. `RoomsStore`, which has no
     * subscription context to reset for, exposes its own
     * [RoomsStore.resume] that calls this directly instead — see that
     * method's doc for why `Session` calls it proactively, before the pump
     * is even handed back to the core, rather than waiting for `seed` to
     * get around to it.
     */
    fun resume() {
        generation += 1
        stopped = false
    }

    private suspend fun performResync(generation: Int) {
        // Belt and braces over `handle`'s own check, so this is safe to call
        // from anywhere without relying on it.
        if (resyncing) return
        resyncing = true
        try {
            // Mirrors Swift's `try? await resync()`, with one deliberate
            // difference: a `CancellationException` is rethrown rather than
            // swallowed. Swift's `try?` cannot tell a cancelled Task from any
            // other failure, but Kotlin's cooperative cancellation depends on
            // that exception reaching the coroutine machinery — catching it
            // here would let this coroutine report as having completed
            // normally when its scope was actually torn down.
            val snapshot =
                try {
                    resync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return
                }
            // A newer subscription context started while this was in flight;
            // its result belongs to a context that no longer exists.
            if (stopped || generation != this.generation) return
            // And belt-and-braces over that: the core serves a resync out of
            // whichever subscription is *currently* installed, which during a
            // room switch is still the previous room's. Its generation may
            // well match ours, so the subject is the only thing that can say
            // this is not our data.
            if (!accepts(snapshot.subject)) return

            tracker.reset(items = snapshot.items, seq = snapshot.seq)
            onUpdate(tracker.items)
        } finally {
            resyncing = false
        }
    }
}
