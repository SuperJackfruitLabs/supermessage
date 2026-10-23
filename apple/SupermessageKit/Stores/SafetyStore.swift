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
/// ## Hidden messages are this device's, and one account's
///
/// Reporting a message hides it here at once. There is no Matrix protocol for
/// "hide this one event for me", so the set is local — kept in `UserDefaults`
/// so a reported message does not return on the next launch. The core does
/// the hiding: the session pushes the set to it (`hiddenEventsDidChange`, and
/// at every sign-in), and rows come back marked `hidden`.
///
/// The set is stored under the account that reported, in `safety.hiddenEvents.<userId>`,
/// and read back only for that account (`bind`). An account that ends without a
/// sign-out — the app deleted and reinstalled over a restored keychain, a
/// session the homeserver revoked — therefore leaves its ids where the next
/// account cannot see them. Older builds kept one device-wide set under
/// `safety.hiddenEvents`; `bind` hands it to the restored account once.
/// Mirrors Android's `SafetyStore` / `HiddenEventStorage`.
@MainActor
@Observable
public final class SafetyStore {
    /// User ids this account has blocked (`m.ignored_user_list`).
    public private(set) var blocked: Set<String> = []
    /// Event ids reported from this device, hidden from the timeline.
    public private(set) var hiddenEvents: Set<String> = []
    /// The account `hiddenEvents` belongs to; `nil` until one signs in.
    public private(set) var owner: String?
    /// Bumped by `bind` and `clear`. A write takes the value its caller saw
    /// before it started waiting and does nothing when that is no longer
    /// current: a report that finishes after a sign-out must not put its
    /// message id into the next account's set.
    public private(set) var generation = 0
    /// The report sheet to show, when one has been asked for. Set by the
    /// message menu (UIKit), read by `TimelineView` (SwiftUI) — this store is
    /// the one place both can reach.
    public var pendingReport: ReportSubject?

    /// Called with the whole set after it grows. The session pushes it to the
    /// core, which groups the timeline as if those rows were absent.
    @ObservationIgnored public var hiddenEventsDidChange: (@MainActor (Set<String>) -> Void)?

    @ObservationIgnored private let defaults: UserDefaults?
    /// The device-wide key older builds used. Also the prefix of every
    /// account's own key.
    static let legacyKey = "safety.hiddenEvents"

    static func key(for owner: String) -> String { "\(legacyKey).\(owner)" }

    /// `defaults: nil` keeps everything in memory, for tests and previews.
    public init(defaults: UserDefaults? = nil) {
        self.defaults = defaults
    }

    /// Load the hidden set of the account that just reached signed-in, and
    /// only that one's. `adoptLegacy` says the account was restored from the
    /// device: the old device-wide set is then assigned to it, once. After a
    /// fresh sign-in the old set belongs to nobody known and is dropped.
    /// With no `owner` (the account could not be read) nothing is hidden and
    /// nothing is saved, rather than guessing whose set this is.
    /// Bumps `generation`.
    public func bind(owner: String?, adoptLegacy: Bool) {
        generation += 1
        self.owner = owner
        let legacy = Set(defaults?.stringArray(forKey: Self.legacyKey) ?? [])
        defaults?.removeObject(forKey: Self.legacyKey)
        guard let owner else {
            hiddenEvents = []
            return
        }
        let stored = Set(defaults?.stringArray(forKey: Self.key(for: owner)) ?? [])
        let ids = adoptLegacy ? stored.union(legacy) : stored
        if ids != stored { defaults?.set(Array(ids), forKey: Self.key(for: owner)) }
        hiddenEvents = ids
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
    ///
    /// `generation` is the value read before the report began; a write for an
    /// account that has since signed out is dropped.
    public func hide(eventId: String, generation: Int? = nil) {
        if let generation, generation != self.generation { return }
        hiddenEvents.insert(eventId)
        if let owner { defaults?.set(Array(hiddenEvents), forKey: Self.key(for: owner)) }
        hiddenEventsDidChange?(hiddenEvents)
    }

    /// Forget everything — sign-out, including the signed-in account's stored set.
    public func clear() {
        generation += 1
        blocked = []
        hiddenEvents = []
        pendingReport = nil
        if let owner { defaults?.removeObject(forKey: Self.key(for: owner)) }
        owner = nil
    }

    /// Nothing is stored to restore: no account's set, nor the legacy one,
    /// outlives that.
    public func clearAll() {
        clear()
        guard let defaults else { return }
        for key in defaults.dictionaryRepresentation().keys
        where key == Self.legacyKey || key.hasPrefix(Self.legacyKey + ".") {
            defaults.removeObject(forKey: key)
        }
    }
}
