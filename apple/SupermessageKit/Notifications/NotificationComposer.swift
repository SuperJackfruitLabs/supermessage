import Foundation
import SupermessageFFI

// Shared with the Notification Service Extension as source, which does not
// compile Sendability.swift — so these conformances live here (as in
// CoreLocation.swift).
extension NotificationActionSpec: @retroactive @unchecked Sendable {}
extension NotificationContext: @retroactive @unchecked Sendable {}
extension NotificationTextInput: @retroactive @unchecked Sendable {}
extension PlannedNotification: @retroactive @unchecked Sendable {}

/// One local notification this app has decided to post.
///
/// A value, not a `UNNotificationRequest`: Kit imports no UI framework, and
/// the decision of *what* to say is the part worth testing. The app's
/// `LocalNotifier` turns this into a request and hands it to the system.
public struct LocalNotification: Equatable, Sendable {
    /// Which set of actions the notification offers.
    ///
    /// The raw values are the `UNNotificationCategory` identifiers, so a
    /// category registered under one of these is the one the system shows.
    public enum Category: String, Sendable, CaseIterable {
        /// An ordinary message. Tapping it opens the room.
        case message = "MESSAGE"
        /// An AgentPod permission request with an "Allow once" and a
        /// "Reject" answer. Both actions require the device to be unlocked.
        case permission = "PERMISSION"
        /// A superpipeline gate: Approve, Request changes (typed feedback),
        /// Reject and Open. Every answer requires the device to be unlocked,
        /// and an action the gate does not offer opens it instead — see
        /// `NotificationKeys.response`. Answering gates from a notification
        /// is the operator's decision of 2026-09-27; before it, gates only
        /// opened.
        case gate = "GATE"
        /// A decision that must be read in the app before it is answered — a
        /// pending request the roster cannot identify, a permission request
        /// whose answers do not map onto Allow once / Reject, a gate with
        /// nothing a notification can send — or an answer that did not land.
        /// Only "Open".
        case decision = "DECISION"
    }

    /// What a GATE notification's actions need to answer the gate with no
    /// room open: the same three things the card sends.
    public struct Gate: Equatable, Sendable {
        /// superpipeline's `gate_id` — `CustomEventDecision.subject`.
        public let gateId: String
        /// The gate's question; the core derives the sentence left in the
        /// room from it, as it does for the card.
        public let prompt: String
        /// Which of `approve`, `request_changes` and `reject` this gate
        /// offers. An action outside this set opens the room.
        public let optionIds: [String]

        public init(gateId: String, prompt: String, optionIds: [String]) {
            self.gateId = gateId
            self.prompt = prompt
            self.optionIds = optionIds
        }
    }

    /// The request identifier. Stable per event, so posting the same thing
    /// twice replaces rather than stacks.
    public let id: String
    public let roomId: String
    /// The event this is about, when it has one. A permission answer is a
    /// reply into the room, not a relation, so this is informational.
    public let eventId: String?
    public let title: String
    public let subtitle: String?
    public let body: String
    public let category: Category
    /// The option ids a PERMISSION notification's two actions send.
    /// Both set exactly when `category == .permission`.
    public let allowOptionId: String?
    public let rejectOptionId: String?
    /// Set exactly when `category == .gate`, and then `eventId` is the gate
    /// event's id — the decision references it.
    public let gate: Gate?

    public init(
        id: String, roomId: String, eventId: String?, title: String, subtitle: String?,
        body: String, category: Category, allowOptionId: String? = nil,
        rejectOptionId: String? = nil, gate: Gate? = nil
    ) {
        self.id = id
        self.roomId = roomId
        self.eventId = eventId
        self.title = title
        self.subtitle = subtitle
        self.body = body
        self.category = category
        self.allowOptionId = allowOptionId
        self.rejectOptionId = rejectOptionId
        self.gate = gate
    }

    /// Whether this asks the reader for a decision rather than telling them
    /// something was said.
    public var isDecision: Bool { category != .message }

    /// A notification the core decided. Its request identifier is the event
    /// id — the push gateway's `apns-collapse-id` too, so a local and a remote
    /// notification for one event replace each other rather than stacking.
    public init(decided note: NotificationDto) {
        self.init(planned: plannedFromDecided(note: note))
    }

    /// A notification the core planned (`core::notification_policy`).
    public init(planned note: PlannedNotification) {
        self.init(
            id: note.id, roomId: note.roomId, eventId: note.eventId, title: note.title,
            subtitle: note.subtitle, body: note.body, category: Category(note.category),
            allowOptionId: note.permission?.allowOptionId,
            rejectOptionId: note.permission?.rejectOptionId,
            gate: note.gate.map { Gate($0) })
    }

    /// The core's value for this notification, which its policy functions
    /// (`notificationDelivers`, `notificationUserInfo`) take. The permission
    /// answers are carried only when both ids are set.
    public var planned: PlannedNotification {
        PlannedNotification(
            id: id, roomId: roomId, eventId: eventId, title: title, subtitle: subtitle,
            body: body, category: category.core,
            permission: allowOptionId.flatMap { allow in
                rejectOptionId.map { PermissionAnswers(allowOptionId: allow, rejectOptionId: $0) }
            },
            gate: gate.map { GateAnswers(gateId: $0.gateId, prompt: $0.prompt, optionIds: $0.optionIds) })
    }
}

extension LocalNotification.Category {
    init(_ category: NotificationCategory) {
        switch category {
        case .message: self = .message
        case .permission: self = .permission
        case .gate: self = .gate
        case .decision: self = .decision
        }
    }

    var core: NotificationCategory {
        switch self {
        case .message: .message
        case .permission: .permission
        case .gate: .gate
        case .decision: .decision
        }
    }
}

extension LocalNotification.Gate {
    init(_ answers: GateAnswers) {
        self.init(gateId: answers.gateId, prompt: answers.prompt, optionIds: answers.optionIds)
    }
}

/// Which changes deserve a notification, and what it says: the core's
/// `core::notification_policy`, with `LocalNotification` as the Swift value.
public enum NotificationComposer {
    /// Notifications for rooms whose roster row changed between two
    /// snapshots (`core::notification_policy::notifications_for_roster`).
    public static func forRoster(
        previous: [RoomRow], next: [RoomRow], context: NotificationContext
    ) -> [LocalNotification] {
        notificationsForRoster(previous: previous, next: next, context: context)
            .map(LocalNotification.init(planned:))
    }

    /// Notifications for new rows in the subscribed room
    /// (`core::notification_policy::notifications_for_timeline`).
    ///
    /// `since` is milliseconds since the epoch; `alreadyNotified` is the
    /// event ids posted before.
    public static func forTimeline(
        roomId: String, roomName: String, rows: [TimelineRow], since: UInt64,
        alreadyNotified: Set<String>, context: NotificationContext
    ) -> [LocalNotification] {
        notificationsForTimeline(
            roomId: roomId, roomName: roomName, rows: rows, sinceMs: since,
            alreadyNotified: Array(alreadyNotified), context: context
        ).map(LocalNotification.init(planned:))
    }

    /// The two options a PERMISSION notification's actions send, or `nil`
    /// when this request does not offer both. The core's rule
    /// (`core::notification::permission_answers`): "Always" is never taken
    /// for "once".
    public static func permissionAnswers(
        _ decision: CustomEventDecision
    ) -> (allow: String, reject: String)? {
        notificationPermissionAnswers(decision: decision).map {
            (allow: $0.allowOptionId, reject: $0.rejectOptionId)
        }
    }

    /// What a GATE notification can answer, or `nil` when it can answer
    /// nothing and should only open. The core's rule
    /// (`core::notification::gate_answers`).
    public static func gateAnswers(
        _ decision: CustomEventDecision, gateId: String
    ) -> LocalNotification.Gate? {
        notificationGateAnswers(decision: decision, gateId: gateId).map { LocalNotification.Gate($0) }
    }

    /// Whether a remote notification arriving in the foreground is shown
    /// (`core::notification_policy::presents_remote`).
    public static func presentsRemote(
        roomId: String?, eventId: String?, context: NotificationContext,
        alreadyNotified: Set<String>
    ) -> Bool {
        notificationPresentsRemote(
            roomId: roomId, eventId: eventId, context: context,
            alreadyNotified: Array(alreadyNotified))
    }

    /// Whether a room's notification setting lets this through
    /// (`core::notification_policy::delivers`).
    public static func delivers(_ note: LocalNotification, mode: NotificationMode?) -> Bool {
        notificationDelivers(note: note.planned, mode: mode)
    }
}

// MARK: - A push the Notification Service Extension decided

/// What the Notification Service Extension does with a push, once the core
/// has decided it (`Core.notificationFor`).
///
/// A value, so the choice is testable here; the extension only turns it into
/// `UNNotificationContent`. The rule it encodes: **never a blank
/// notification.** TestFlight build 30 emptied every suppressed push — an
/// agent's reaction, a turn card — and iOS showed each one anyway, as a
/// notification with no text, because an extension without Apple's
/// filtering entitlement cannot drop a push.
public enum RemotePresentation: Equatable, Sendable {
    /// Drop the push. Only when the extension holds
    /// `com.apple.developer.usernotifications.filtering`, where an empty
    /// content is how a push is dropped.
    case drop
    /// Say what happened in one line, quietly — passive, no sound, lowest
    /// relevance — because the push cannot be dropped. `title` is `nil` when
    /// the push's own should stay (the core did not know the room).
    case quiet(title: String?, body: String)
    /// Show what the core decided.
    case show(LocalNotification)

    /// The body a suppressed push says when the core gave it no line. The
    /// core always does; this is only never-blank's last resort.
    public static let lastResortBody = "Quiet activity"

    public init(_ note: NotificationDto, canFilter: Bool) {
        guard note.suppress != nil else {
            self = .show(LocalNotification(decided: note))
            return
        }
        if canFilter {
            self = .drop
            return
        }
        let body = note.fallbackBody.flatMap { $0.isEmpty ? nil : $0 } ?? Self.lastResortBody
        self = .quiet(title: note.fallbackTitle, body: body)
    }
}

/// Whether this build's Notification Service Extension may drop a push.
///
/// Read from the extension's Info.plist key `SMNotificationFiltering`, which
/// is the `SM_NSE_FILTERING` build setting — set only by
/// `SupermessageNotificationService/nse-filtering.yml`, the include that also
/// adds the filtering entitlement. Off unless it says YES: claiming to filter
/// without the entitlement would bring the blank notifications back.
public enum NotificationFiltering {
    public static let infoKey = "SMNotificationFiltering"

    public static func isEnabled(infoDictionary: [String: Any]?) -> Bool {
        switch infoDictionary?[infoKey] {
        case let flag as Bool: return flag
        case let text as String:
            return ["yes", "true", "1"].contains(text.trimmingCharacters(in: .whitespaces).lowercased())
        default: return false
        }
    }
}
