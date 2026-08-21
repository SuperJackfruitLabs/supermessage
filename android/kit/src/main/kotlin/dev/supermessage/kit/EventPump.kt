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
 * meant to drain [events] — `Session` (a later task) owns that collector —
 * so arrival order survives end to end.
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
 * ## Where this differs from `apple/SupermessageKit/EventPump.swift`
 *
 * Swift exposes an `AsyncStream` built from a `Continuation`. The Kotlin
 * equivalent of "one write side, one read side, unbounded" is a
 * [Channel] drained as a [Flow] via [receiveAsFlow] — there is no
 * `AsyncStream` analogue on this platform, so this is a same-shape
 * substitution rather than a design change. Swift's `finish()` also has no
 * `@unchecked Sendable` counterpart to justify here: `Channel` is already
 * safe to call from any thread, and this class holds no other mutable state
 * for a marker like that to protect.
 */
class EventPump : EventSink {
    private val channel = Channel<FfiEvent>(Channel.UNLIMITED)

    /** Drain exactly once. A second collector would split, not duplicate, the events. */
    val events: Flow<FfiEvent> = channel.receiveAsFlow()

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
}
