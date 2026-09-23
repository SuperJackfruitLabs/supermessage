import Foundation
import Observation

/// A request, from outside the view tree, to show a room.
///
/// A tapped notification arrives in the app delegate, which owns no
/// navigation. It writes the room here; whichever view owns navigation
/// observes `pendingRoomId`, opens the room, and calls `consume()`. Nothing
/// else moves the selection, so a notification cannot fight the reader for
/// it.
@MainActor
@Observable
public final class NotificationRouter {
    public static let shared = NotificationRouter()

    /// The room a notification asked to open, until somebody opens it.
    public private(set) var pendingRoomId: String?

    public init() {}

    public func request(roomId: String) {
        pendingRoomId = roomId
    }

    /// Take the pending room, leaving none. `nil` when there is none.
    @discardableResult
    public func consume() -> String? {
        defer { pendingRoomId = nil }
        return pendingRoomId
    }
}

/// The identifiers a notification carries, shared by the code that posts one
/// and the code that answers it.
public enum NotificationKeys {
    /// `userInfo` keys.
    public static let roomId = "sm.roomId"
    public static let eventId = "sm.eventId"
    public static let allowOptionId = "sm.allowOptionId"
    public static let rejectOptionId = "sm.rejectOptionId"

    /// `UNNotificationAction` identifiers.
    public static let allowAction = "sm.permission.allowOnce"
    public static let rejectAction = "sm.permission.reject"
    public static let openAction = "sm.open"

    /// The `userInfo` a notification is posted with.
    public static func userInfo(for note: LocalNotification) -> [String: String] {
        var info = [roomId: note.roomId]
        if let id = note.eventId { info[eventId] = id }
        if let id = note.allowOptionId { info[allowOptionId] = id }
        if let id = note.rejectOptionId { info[rejectOptionId] = id }
        return info
    }

    /// What an action on a delivered notification means.
    public enum Response: Equatable, Sendable {
        /// Open the room.
        case open(roomId: String)
        /// Send `optionId` into `roomId` as the answer to a permission
        /// request — a plain message, as every other client answers one.
        case answer(roomId: String, optionId: String)
        /// Nothing this app can act on.
        case ignore
    }

    /// Decode an action. `actionIdentifier` is the system's
    /// `UNNotificationDefaultActionIdentifier` for a plain tap, which lands
    /// in the default branch along with `openAction`.
    public static func response(actionIdentifier: String, userInfo: [AnyHashable: Any]) -> Response {
        guard let room = userInfo[roomId] as? String else { return .ignore }
        switch actionIdentifier {
        case allowAction:
            guard let option = userInfo[allowOptionId] as? String else { return .open(roomId: room) }
            return .answer(roomId: room, optionId: option)
        case rejectAction:
            guard let option = userInfo[rejectOptionId] as? String else { return .open(roomId: room) }
            return .answer(roomId: room, optionId: option)
        case "com.apple.UNNotificationDismissActionIdentifier":
            return .ignore
        default:
            return .open(roomId: room)
        }
    }
}
