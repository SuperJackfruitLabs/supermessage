import Foundation
import Observation
import SupermessageFFI

/// The message being rewritten, per room.
///
/// Separate from `ReplyTarget` rather than a mode on it, because they are not
/// alternatives to each other in the composer's logic: a reply is addressed to
/// someone else's message and sends something new, an edit replaces one of
/// your own. Merging them would make the composer's send path ask "which kind
/// of pending thing is this" on every keystroke.
///
/// Like `ReplyTarget`, the body is a **snapshot** taken when the edit was
/// started. If the message changes underneath — an edit from another device,
/// say — the text the reader is part-way through typing must not be replaced
/// beneath their cursor.
@MainActor
@Observable
public final class EditTarget {
    public struct Pending: Equatable {
        public let eventId: String
        /// What the message said when the edit began, to seed the composer.
        public let body: String
    }

    private var targets: [String: Pending] = [:]

    public init() {}

    public func pending(for roomId: String) -> Pending? {
        targets[roomId]
    }

    /// Begin editing a row, if it is one this account may rewrite.
    ///
    /// Returns the text the composer should start from, or `nil` when the row
    /// cannot be edited — so a caller cannot enter an edit mode that has
    /// nothing to edit. `editable` is the SDK's answer (see
    /// `TimelineItemDto::editable`), never inferred here from `isOwn`.
    @discardableResult
    public func start(_ row: TimelineRow, in roomId: String) -> String? {
        guard row.item.editable, let eventId = row.item.eventId else { return nil }
        let body = row.item.body ?? ""
        targets[roomId] = Pending(eventId: eventId, body: body)
        return body
    }

    public func cancel(_ roomId: String) {
        targets.removeValue(forKey: roomId)
    }

    public func clearAll() {
        targets.removeAll()
    }
}
