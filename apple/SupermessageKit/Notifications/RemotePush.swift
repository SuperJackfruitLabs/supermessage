import Foundation

/// What a push from the AgentPod hub's gateway carries, read off its payload.
///
/// The pusher is `event_id_only` (`core::push`), so the payload names an event
/// and says nothing about it: `aps.alert` is a generic "New message",
/// `mutable-content` wakes the Notification Service Extension, and the ids sit
/// at the top level beside `aps` — `room_id`, `event_id`, `unread_count`. The
/// extension fetches and decrypts the event itself and replaces the text.
///
/// Shared as source with the extension, so the app's tests pin the reading.
public struct RemotePush: Equatable, Sendable {
    public let roomId: String
    public let eventId: String
    /// The account's unread count as the homeserver computed it, for the
    /// badge. `nil` when the gateway did not send one.
    public let unreadCount: Int?
    /// How the turn this event ended went, when the hub said: it adds
    /// `"turn": {"total": 7, "failed": 1}` to the push of an agent's answer
    /// (spec 2026-09-29, A5), because the turn card carrying the same counts
    /// is quiet and never pushed. Counts only. Relayed to the core as
    /// `NotificationDto.turn`; what they mean is `core::widget`'s.
    public let turn: Turn?

    public struct Turn: Equatable, Sendable {
        public let total: UInt32
        public let failed: UInt32

        public init(total: UInt32, failed: UInt32) {
            self.total = total
            self.failed = failed
        }
    }

    /// The payload keys, as the gateway writes them.
    public static let roomIdKey = "room_id"
    public static let eventIdKey = "event_id"
    public static let unreadCountKey = "unread_count"
    public static let turnKey = "turn"

    /// `nil` unless the payload names both a room and an event: a push with
    /// nothing to fetch keeps its own text.
    public init?(userInfo: [AnyHashable: Any]) {
        guard let roomId = userInfo[Self.roomIdKey] as? String, !roomId.isEmpty,
            let eventId = userInfo[Self.eventIdKey] as? String, !eventId.isEmpty
        else { return nil }
        self.roomId = roomId
        self.eventId = eventId
        switch userInfo[Self.unreadCountKey] {
        case let count as Int: unreadCount = count
        case let count as NSNumber: unreadCount = count.intValue
        case let text as String: unreadCount = Int(text)
        default: unreadCount = nil
        }
        turn = Self.turn(userInfo[Self.turnKey])
    }

    /// Both counts, non-negative, or nothing: a half-read turn is not one.
    private static func turn(_ value: Any?) -> Turn? {
        guard let counts = value as? [AnyHashable: Any],
            let total = count(counts["total"]), let failed = count(counts["failed"])
        else { return nil }
        return Turn(total: total, failed: failed)
    }

    private static func count(_ value: Any?) -> UInt32? {
        switch value {
        case let number as NSNumber:
            // A JSON `true` arrives as an NSNumber too; it is not a count.
            guard CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
            return UInt32(exactly: number.doubleValue)
        case let number as Int: return UInt32(exactly: number)
        default: return nil
        }
    }

    /// The keys the app's own notification handling reads
    /// (`NotificationKeys.response`), so a push the extension could not
    /// improve still opens its room when tapped. Answers are left out: only
    /// the core's decision may name the option an action sends.
    public var openingUserInfo: [String: String] {
        [NotificationKeys.roomId: roomId, NotificationKeys.eventId: eventId]
    }
}
