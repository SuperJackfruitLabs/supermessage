import Foundation
import SupermessageFFI

/// Where the core's events enter this app, and the only place their order is
/// guaranteed.
///
/// ## What it does
///
/// `onEvent` does exactly one thing and returns. The core's contract requires
/// that: *"Implementations must not block: this is called from inside sync and
/// timeline processing, and a slow sink stalls the client rather than the
/// UI."* So the event goes into a queue and the core gets its thread back.
///
/// ## Why one stream and one consumer
///
/// `DiffEnvelope` carries a `seq`, and the timeline's recovery logic is built
/// on those arriving in the order they were emitted. Exactly one `@MainActor`
/// task drains `events`, so arrival order survives end to end.
///
/// The tempting alternative — `Task { @MainActor in handle(event) }` inside
/// `onEvent` — looks equivalent and is not. Task ordering is not guaranteed,
/// so under load the diffs interleave, and applying them out of order corrupts
/// the reader's view in a way that presents as a rendering bug rather than a
/// threading one. That is the failure this class exists to make impossible,
/// and there is a ten-thousand-event test that fails when it is reintroduced.
///
/// ## Why the buffer is unbounded
///
/// Dropping the oldest would drop a diff envelope, and a dropped envelope is a
/// gap the tracker cannot distinguish from a lost one. It is recoverable — that
/// is what `GapSync` is for — but only by a resync nobody asked for, over a
/// connection that is already the reason the app fell behind.
///
/// `@unchecked Sendable` because `AsyncStream.Continuation` is itself
/// thread-safe and the only stored state besides it is immutable.
public final class EventPump: CoreEventSink, @unchecked Sendable {
    /// The current stream. `var`, not `let`, because `reset` replaces it —
    /// read it at the point you drain, never hold it across a sign-out.
    public private(set) var events: AsyncStream<FfiEvent>
    private var continuation: AsyncStream<FfiEvent>.Continuation
    /// Guards the two properties against a `yield` racing a `reset`. The
    /// continuation is itself thread-safe; *swapping* it is not, and `onEvent`
    /// arrives on whatever thread the core is using.
    private let lock = NSLock()

    public init() {
        var escaping: AsyncStream<FfiEvent>.Continuation!
        events = AsyncStream(bufferingPolicy: .unbounded) { escaping = $0 }
        continuation = escaping
    }

    /// Give the pump a fresh channel after `finish`, so a signed-out session
    /// can sign back in.
    ///
    /// `finish()` is terminal — an `AsyncStream.Continuation` cannot be
    /// restarted — and `Session` holds one pump for its whole life, so without
    /// this a second sign-in drained a stream that had already completed and
    /// every event went into a dead continuation. No error, no crash: the app
    /// looked signed in and received nothing until it was force-quit (#28).
    ///
    /// Replacing the channel rather than the pump keeps this object's
    /// identity, which matters because the core holds it as a sink and has no
    /// idea a session ended.
    ///
    /// The caller must have finished the old stream and cancelled its drain
    /// task first. A collector still suspended on the old stream is not moved
    /// here — it is stranded on a channel nothing will ever write to again.
    public func reset() {
        lock.lock()
        defer { lock.unlock() }
        var escaping: AsyncStream<FfiEvent>.Continuation!
        events = AsyncStream(bufferingPolicy: .unbounded) { escaping = $0 }
        continuation = escaping
    }

    /// Called by the core, on the core's thread — a tokio worker or one of
    /// matrix-sdk's event handlers. Hands the event over and returns; it never
    /// waits for anyone.
    public func onEvent(event: FfiEvent) {
        lock.lock()
        let sink = continuation
        lock.unlock()
        // Yielded outside the lock: `yield` runs the stream's buffering, and
        // holding a lock across it would put core threads behind each other
        // for no reason. A yield into a channel `reset` has just replaced is
        // harmless — that channel is finished and nobody is reading it.
        sink.yield(event)
    }

    /// Ends the stream, on logout and on teardown. The drain task's `for await`
    /// finishes, and with it the task.
    public func finish() {
        lock.lock()
        let sink = continuation
        lock.unlock()
        sink.finish()
    }
}
