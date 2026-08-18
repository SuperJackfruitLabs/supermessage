import Foundation

/// Carries core events onto the main actor, in order.
///
/// **Order is a correctness requirement, not a nicety.** The diff envelopes
/// carry `seq`, and the timeline's recovery logic in the Rust core is built on
/// them arriving in the order they were emitted. UniFFI invokes this callback
/// on whatever thread emitted — a tokio worker, or one of matrix-sdk's event
/// handlers — so the events are funnelled through one serial queue before
/// being hopped to the main actor.
///
/// The obvious-looking alternative, `Task { @MainActor in ... }` straight from
/// `onEvent`, is wrong: each `Task` is scheduled independently and they can
/// run out of order. That would corrupt the room list in a way that looks like
/// a rendering bug and would be extremely hard to trace back to here.
final class Sink: EventSink {
    private let queue = DispatchQueue(label: "dev.supermessage.native.events")
    private let deliver: @Sendable (FfiEvent) -> Void

    init(deliver: @escaping @Sendable (FfiEvent) -> Void) {
        self.deliver = deliver
    }

    func onEvent(event: FfiEvent) {
        // `sync` on a serial queue: this must not return before the event is
        // enqueued in order, and the core must not be blocked for longer than
        // that. The main-actor hop inside keeps UI work off this queue.
        queue.async { [deliver] in
            deliver(event)
        }
    }
}
