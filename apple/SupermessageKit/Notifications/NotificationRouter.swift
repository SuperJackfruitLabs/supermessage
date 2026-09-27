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

/// An answer given from a notification, ready to send with no room open.
///
/// See `NotificationAnswerer`, which sends it.
public enum NotificationAnswer: Equatable, Sendable {
    /// An AgentPod permission request: `optionId` goes into the room as a
    /// plain message, as every other client answers one.
    case permission(roomId: String, optionId: String)
    /// A superpipeline gate: a `dev.superpipeline.gate.decision.v1`
    /// referencing `gateEventId`, exactly what the card sends.
    case gate(
        roomId: String, gateEventId: String, gateId: String, optionId: String, comment: String?,
        prompt: String)

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

    /// superpipeline's `GateDecision` ids — the only three its resolution
    /// endpoint accepts, and the only three the core will send
    /// (`GATE_OPTION_IDS`).
    public static let approveOption = "approve"
    public static let requestChangesOption = "request_changes"
    public static let rejectOption = "reject"

    /// The `userInfo` a notification is posted with.
    public static func userInfo(for note: LocalNotification) -> [String: String] {
        var info = [roomId: note.roomId]
        if let id = note.eventId { info[eventId] = id }
        if let id = note.allowOptionId { info[allowOptionId] = id }
        if let id = note.rejectOptionId { info[rejectOptionId] = id }
        if let gate = note.gate {
            info[gateId] = gate.gateId
            info[gatePrompt] = gate.prompt
            info[gateOptions] = gate.optionIds.joined(separator: " ")
        }
        return info
    }

    /// What an action on a delivered notification means.
    public enum Response: Equatable, Sendable {
        /// Open the room.
        case open(roomId: String)
        /// Send this answer — no room needs to be open.
        case answer(NotificationAnswer)
        /// Nothing this app can act on.
        case ignore
    }

    /// Decode an action. `actionIdentifier` is the system's
    /// `UNNotificationDefaultActionIdentifier` for a plain tap, which lands
    /// in the default branch along with `openAction`. `userText` is what the
    /// reader typed into a text-input action, when the action was one.
    ///
    /// Anything that cannot be answered exactly as the button said — an
    /// option the notification did not carry, a gate that does not offer it,
    /// "Request changes" with nothing typed — opens the room instead. A tap
    /// that silently does something other than its label is the one outcome
    /// worse than asking the reader to look.
    public static func response(
        actionIdentifier: String, userInfo: [AnyHashable: Any], userText: String? = nil
    ) -> Response {
        guard let room = userInfo[roomId] as? String else { return .ignore }
        switch actionIdentifier {
        case allowAction:
            guard let option = userInfo[allowOptionId] as? String else { return .open(roomId: room) }
            return .answer(.permission(roomId: room, optionId: option))
        case rejectAction:
            guard let option = userInfo[rejectOptionId] as? String else { return .open(roomId: room) }
            return .answer(.permission(roomId: room, optionId: option))
        case approveGateAction:
            return gate(approveOption, comment: nil, room: room, userInfo: userInfo)
        case rejectGateAction:
            return gate(rejectOption, comment: nil, room: room, userInfo: userInfo)
        case requestChangesAction:
            // Feedback is the whole point of this answer — superpipeline
            // hands it to the rework. Sent empty, the card goes back with no
            // reason, so an empty reply is read as "let me look" instead.
            let comment = userText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !comment.isEmpty else { return .open(roomId: room) }
            return gate(requestChangesOption, comment: comment, room: room, userInfo: userInfo)
        case "com.apple.UNNotificationDismissActionIdentifier":
            return .ignore
        default:
            return .open(roomId: room)
        }
    }

    private static func gate(
        _ option: String, comment: String?, room: String, userInfo: [AnyHashable: Any]
    ) -> Response {
        guard let event = userInfo[eventId] as? String,
            let gate = userInfo[gateId] as? String, !gate.isEmpty,
            let offered = userInfo[gateOptions] as? String,
            offered.split(separator: " ").contains(Substring(option))
        else { return .open(roomId: room) }
        let prompt = userInfo[gatePrompt] as? String ?? ""
        return .answer(
            .gate(
                roomId: room, gateEventId: event, gateId: gate, optionId: option, comment: comment,
                prompt: prompt))
    }
}
