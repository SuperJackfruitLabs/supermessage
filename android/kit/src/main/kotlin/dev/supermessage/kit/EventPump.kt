package dev.supermessage.kit

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiEvent

/**
 * Where the core's events enter this app, and the only place their order is
 * guaranteed.
 *
 * ## What it does
 *
 * [onEvent] does exactly one thing and returns. The core's contract requires
 * that: "Implementations must not block: this is called from inside sync and
 * timeline processing, and a slow sink stalls the client rather than the
 * UI." So the event goes into a queue and the core gets its thread back.
 *
 * ## Why one stream and one consumer
 *
 * `DiffEnvelope` carries a `seq`, and the timeline's recovery logic is built
 * on those arriving in the order they were emitted. Exactly one collector is
 * meant to drain [events] — `Session` owns that collector — so arrival order
 * survives end to end.
 *
 * The tempting alternative — launching a coroutine per event inside
 * [onEvent] — looks equivalent and is not. Coroutine dispatch order is not
 * guaranteed, so under load the diffs interleave, and applying them out of
 * order corrupts the reader's view in a way that presents as a rendering bug
 * rather than a threading one. That is the failure this class exists to make
 * impossible, and there is a ten-thousand-event test (`EventPumpTest`) that
 * fails when it is reintroduced.
 *
 * ## Why the buffer is unbounded
 *
 * Dropping the oldest would drop a diff envelope, and a dropped envelope is
 * a gap the tracker cannot distinguish from a lost one. It is recoverable —
 * that is what [GapSync] is for — but only by a resync nobody asked for,
 * over a connection that is already the reason the app fell behind. So
 * [Channel.UNLIMITED], never [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST]
 * or a fixed capacity.
 *
 * ## Why [reset] exists, and why it is not on the Swift side
 *
 * A [Channel] cannot be reopened once [Channel.close] has run — a fact this
 * class's own [finish] relies on to end the drain, and a fact that bit
 * `Session` the first time a sign-out/sign-in cycle was driven end to end:
 * `Session` holds one `EventPump` for its whole lifetime (matching
 * `apple/SupermessageKit/Session.swift:48`'s `private let pump = EventPump()`),
 * and re-registering that same, already-[finish]ed instance with the core on
 * a later sign-in produced a collector that completed immediately over a
 * dead channel — no error, no crash, just silence. [reset] is how `Session`
 * recovers without giving up the pump's own identity: replacing [channel]
 * with a fresh one lets every store that captured a reference to this
 * `EventPump` at construction time (chiefly `TimelineStore`, which holds
 * `sink: EventSink` for its own lifetime) go on using the *same* object,
 * while what that object drains starts over. iOS carries this same
 * single-pump-for-the-app's-lifetime shape and, as far as this port could
 * establish, the same latent bug — `EventPump.swift` has no equivalent of
 * [reset], so a real device today goes quiet the same way after a
 * sign-out/sign-in cycle without a process restart. This is therefore a
 * deliberate Android-side addition, not a mechanical translation of anything
 * in `EventPump.swift`.
 *
 * [channel] is `@Volatile` for exactly this replacement: [onEvent] runs on
 * whichever thread the core calls back on, which is never the thread
 * [reset] runs on (`Session`'s own confined thread of execution), so the
 * *reference* swap itself needs to be visible across threads even though
 * `Channel`'s own internals are already safe to call from any thread.
 *
 * ## Where this differs from `apple/SupermessageKit/EventPump.swift`
 *
 * Swift exposes an `AsyncStream` built from a `Continuation`. The Kotlin
 * equivalent of "one write side, one read side, unbounded" is a
 * [Channel] drained as a [Flow] via [receiveAsFlow] — there is no
 * `AsyncStream` analogue on this platform, so this is a same-shape
 * substitution rather than a design change. Swift's `finish()` also has no
 * `@unchecked Sendable` counterpart to justify here: `Channel` is already
 * safe to call from any thread, and this class holds no other mutable state
 * beyond [channel] itself for a marker like that to protect. [reset] has no
 * Swift counterpart at all — see the section above.
 */
class EventPump : EventSink {
    @Volatile
    private var channel = Channel<FfiEvent>(Channel.UNLIMITED)

    /**
     * Drain exactly once. A second collector would split, not duplicate,
     * the events.
     *
     * A computed property, not a stored one: after [reset] this must read
     * whichever [channel] is current at the moment collection actually
     * starts, not whichever one existed when [EventPump] was constructed.
     */
    val events: Flow<FfiEvent>
        get() = channel.receiveAsFlow()

    /**
     * Called by the core, on the core's thread — a tokio worker or one of
     * matrix-sdk's event handlers. Hands the event over and returns; it
     * never waits for anyone.
     *
     * [Channel.trySend], not `send`: `onEvent` is not a suspending function
     * and must not block, and with an [Channel.UNLIMITED] channel `trySend`
     * always succeeds — there is no capacity for it to fail against.
     */
    override fun onEvent(event: FfiEvent) {
        channel.trySend(event)
    }

    /**
     * Ends the stream, on logout and on teardown. The drain loop's
     * collection over [events] completes, and with it whatever coroutine
     * was collecting it.
     */
    fun finish() {
        channel.close()
    }

    /**
     * Replace a [finish]ed channel with a fresh one, so this same
     * [EventPump] instance can be handed back to the core — and this same
     * `events` [Flow] collected again — after a sign-out/sign-in cycle. See
     * this class's own KDoc for why this exists and why it is safe for every
     * store that already holds a reference to this pump.
     *
     * Safe to call even when nothing was ever [finish]ed: replacing an
     * unused, empty channel with another empty one is a no-op in every way
     * that matters.
     */
    fun reset() {
        channel = Channel(Channel.UNLIMITED)
    }
}
