import Foundation
import Observation
import SupermessageFFI

// Shared with the Notification Service Extension as source, which does not
// compile Sendability.swift (see CoreLocation.swift). The response crosses from
// the app delegate's nonisolated method into `respond`; the answer is sent
// from inside a `@Sendable` deadline closure.
extension NotificationAnswer: @retroactive @unchecked Sendable {}
extension NotificationResponse: @retroactive @unchecked Sendable {}

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

extension NotificationAnswer {
    /// The room the answer goes to. The type is the core's
    /// (`core::notification_policy::NotificationAnswer`); see
    /// `NotificationAnswerer`, which sends it.
    public var roomId: String {
        switch self {
        case let .permission(roomId, _): roomId
        case let .gate(roomId, _, _, _, _, _): roomId
        }
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
    /// A gate's `gate_id`, its question, and the option ids it offers
    /// (space-separated — superpipeline's ids contain no spaces).
    public static let gateId = "sm.gateId"
    public static let gatePrompt = "sm.gatePrompt"
    public static let gateOptions = "sm.gateOptions"

    /// `UNNotificationAction` identifiers.
    public static let allowAction = "sm.permission.allowOnce"
    public static let rejectAction = "sm.permission.reject"
    public static let openAction = "sm.open"
    public static let approveGateAction = "sm.gate.approve"
    public static let rejectGateAction = "sm.gate.reject"
    /// A text-input action: what the reader types is the gate's comment.
    public static let requestChangesAction = "sm.gate.requestChanges"

    /// The `userInfo` a notification is posted with
    /// (`core::notification_policy::user_info`).
    public static func userInfo(for note: LocalNotification) -> [String: String] {
        notificationUserInfo(note: note.planned)
    }

    /// Decode an action. `actionIdentifier` is the system's
    /// `UNNotificationDefaultActionIdentifier` for a plain tap. `userText` is
    /// what the reader typed into a text-input action, when the action was one.
    ///
    /// Anything that cannot be answered exactly as the button said opens the
    /// room instead; the rule is the core's
    /// (`core::notification_policy::response`).
    public static func response(
        actionIdentifier: String, userInfo: [AnyHashable: Any], userText: String? = nil
    ) -> NotificationResponse {
        var strings: [String: String] = [:]
        for (key, value) in userInfo {
            if let key = key as? String, let value = value as? String { strings[key] = value }
        }
        return notificationResponse(actionId: actionIdentifier, userInfo: strings, userText: userText)
    }
}
