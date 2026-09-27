import Foundation
import Observation
import SupermessageFFI

/// Something a reader can report — a message, a room, or someone.
///
/// Carries what the report sheet needs to word itself without asking the core
/// again: a name to say, and whether the subject is an agent (the core's
/// decision, from `RoomMemberDto.isAgent` or the sender id — never re-derived
/// from a display name here).
public enum ReportSubject: Identifiable, Equatable, Sendable {
    /// A message in a room. `senderId` is who wrote it, so the sheet can offer
    /// to block them in the same step.
    case message(roomId: String, eventId: String, senderId: String, senderName: String, isAgent: Bool)
    /// A room — joined, or only invited to.
    case room(roomId: String, name: String)
    /// A person or an agent, rather than anything they said.
    case user(userId: String, name: String, isAgent: Bool)

    public var id: String {
        switch self {
        case let .message(roomId, eventId, _, _, _): "message:\(roomId):\(eventId)"
        case let .room(roomId, _): "room:\(roomId)"
        case let .user(userId, _, _): "user:\(userId)"
        }
    }

    /// Who could be blocked along with the report, if anyone. A room has no
    /// one to block; a message has its sender; a person is themselves.
    public var blockable: (userId: String, name: String, isAgent: Bool)? {
        switch self {
        case let .message(_, _, senderId, senderName, isAgent): (senderId, senderName, isAgent)
        case .room: nil
        case let .user(userId, name, isAgent): (userId, name, isAgent)
        }
    }
}

/// Who this account has blocked, and which messages it has reported and hidden.
///
/// ## Blocked is the core's list, not this store's
///
/// `blocked` is replaced wholesale by `FfiEvent.ignoredUsers`, which the core
/// sends when the session starts and after every change from any device. The
/// only local write is optimistic — `noteBlocked`/`noteUnblocked` right after
/// the call succeeds — so a menu does not offer Block again in the second
/// before the homeserver echoes the new list back. The echo then overwrites
/// it either way.
///
/// ## Hidden messages are this device's
///
/// Reporting a message hides it here at once. There is no Matrix protocol for
/// "hide this one event for me", so the set is local — kept in `UserDefaults`
/// so a reported message does not return on the next launch, and cleared on
/// sign-out with everything else the account left behind.
@MainActor
@Observable
public final class SafetyStore {
    /// User ids this account has blocked (`m.ignored_user_list`).
    public private(set) var blocked: Set<String> = []
    /// Event ids reported from this device, hidden from the timeline.
    public private(set) var hiddenEvents: Set<String>
    /// The report sheet to show, when one has been asked for. Set by the
    /// message menu (UIKit), read by `TimelineView` (SwiftUI) — this store is
    /// the one place both can reach.
    public var pendingReport: ReportSubject?

    @ObservationIgnored private let defaults: UserDefaults?
    static let hiddenKey = "safety.hiddenEvents"

    /// `defaults: nil` keeps everything in memory, for tests and previews.
    public init(defaults: UserDefaults? = nil) {
        self.defaults = defaults
        hiddenEvents = Set(defaults?.stringArray(forKey: Self.hiddenKey) ?? [])
    }

    /// The core's list, replacing whatever was here.
    public func apply(ignored userIds: [String]) {
        blocked = Set(userIds)
    }

    public func isBlocked(_ userId: String) -> Bool { blocked.contains(userId) }

    /// A block the homeserver has accepted but not yet echoed back.
    public func noteBlocked(_ userId: String) { blocked.insert(userId) }

    /// An unblock the homeserver has accepted but not yet echoed back.
    public func noteUnblocked(_ userId: String) { blocked.remove(userId) }

    /// Hide a message this device has reported.
    public func hide(eventId: String) {
        hiddenEvents.insert(eventId)
        defaults?.set(Array(hiddenEvents), forKey: Self.hiddenKey)
    }

    /// The rows a timeline should draw: everything but what was reported here.
    ///
    /// Keyed on the **event** id. A local echo has none and cannot have been
    /// reported (the menu does not offer it), so it always passes.
    public func visible(_ rows: [TimelineRow]) -> [TimelineRow] {
        guard !hiddenEvents.isEmpty else { return rows }
        return rows.filter { row in
            guard let eventId = row.item.eventId else { return true }
            return !hiddenEvents.contains(eventId)
        }
    }

    /// Forget everything — sign-out.
    public func clear() {
        blocked = []
        hiddenEvents = []
        pendingReport = nil
        defaults?.removeObject(forKey: Self.hiddenKey)
    }
}
