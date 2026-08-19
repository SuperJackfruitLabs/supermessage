import Foundation
import Observation
import SupermessageFFI

/// The message a reply is being composed against, per room.
///
/// Everything on it is a **snapshot** taken when the reply was started, not a
/// live binding: if the parent is redacted, or scrolls out of the locally
/// materialised timeline, the preview must not change or disappear under the
/// person writing. Sending still works — the core resolves the parent by id,
/// fetching from the homeserver when it is not cached.
@MainActor
@Observable
public final class ReplyTarget {
    public struct Pending: Equatable {
        public let eventId: String
        public let sender: String
        public let excerpt: String?
    }

    private var targets: [String: Pending] = [:]

    public init() {}

    public func pending(for roomId: String) -> Pending? {
        targets[roomId]
    }

    /// Start a reply from a row.
    ///
    /// Every field comes off the row rather than being derived here: the
    /// attribution chain and the excerpt's bounding are the core's, so the
    /// composer shows exactly what the timeline showed.
    public func start(_ row: TimelineRow, in roomId: String) {
        targets[roomId] = Pending(
            eventId: row.item.id, sender: row.senderName, excerpt: row.replyPreview)
    }

    public func cancel(_ roomId: String) {
        targets.removeValue(forKey: roomId)
    }

    public func clearAll() {
        targets.removeAll()
    }
}
