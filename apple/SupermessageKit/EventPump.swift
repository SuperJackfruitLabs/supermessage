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
    public let events: AsyncStream<FfiEvent>
    private let continuation: AsyncStream<FfiEvent>.Continuation

    public init() {
        var escaping: AsyncStream<FfiEvent>.Continuation!
        events = AsyncStream(bufferingPolicy: .unbounded) { escaping = $0 }
        continuation = escaping
    }

    /// Called by the core, on the core's thread — a tokio worker or one of
    /// matrix-sdk's event handlers. Hands the event over and returns; it never
    /// waits for anyone.
    public func onEvent(event: FfiEvent) {
        continuation.yield(event)
    }

    /// Ends the stream, on logout and on teardown. The drain task's `for await`
    /// finishes, and with it the task.
    public func finish() {
        continuation.finish()
    }
}
