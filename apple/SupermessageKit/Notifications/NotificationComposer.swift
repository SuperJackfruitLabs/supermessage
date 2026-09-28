import Foundation
import SupermessageFFI

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
        self.init(
            id: note.eventId, roomId: note.roomId, eventId: note.eventId, title: note.title,
            subtitle: note.subtitle, body: note.body, category: Category(note.category),
            allowOptionId: note.permission?.allowOptionId,
            rejectOptionId: note.permission?.rejectOptionId,
            gate: note.gate.map { Gate($0) })
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
}

extension LocalNotification.Gate {
    init(_ answers: GateAnswers) {
        self.init(gateId: answers.gateId, prompt: answers.prompt, optionIds: answers.optionIds)
    }
}

/// What the notifier needs to know about the app when it decides.
public struct NotificationContext: Equatable, Sendable {
    /// The room on screen, or `nil` on the roster.
    public var openRoomId: String?
    /// Whether the app is in the foreground and active.
    public var appActive: Bool
    /// The room the timeline store is subscribed to. That room's events are
    /// seen row by row, so the roster path leaves it alone rather than
    /// posting a second, vaguer notification for the same message.
    public var timelineRoomId: String?

    /// Whether this device receives remote pushes for the account — a
    /// pusher is registered. Then the Notification Service Extension shows
    /// what arrives while the app is away, and the app only adds what it
    /// sees first, in the foreground.
    public var remotePush: Bool

    public init(
        openRoomId: String?, appActive: Bool, timelineRoomId: String?, remotePush: Bool = false
    ) {
        self.openRoomId = openRoomId
        self.appActive = appActive
        self.timelineRoomId = timelineRoomId
        self.remotePush = remotePush
    }
}

/// Which changes deserve a notification, and what it says.
///
/// **Nothing here reads message content the core did not already compose.**
/// A message's body is `TimelineRow.replyPreview` or the roster's
/// `RoomPreview.text`; a decision's is `CustomEventDecision.prompt`; titles
/// are `RoomIdentity.name` and `TimelineRow.senderName`. The only choice
/// made here is *whether* — the host's job, since notifications are a
/// platform surface. *What* a row's notification says, including its
/// category and the answers its actions send, is the core's
/// (`core::notification`), shared with the Notification Service Extension.
public enum NotificationComposer {
    /// A room the reader is looking at, in an app they are looking at, does
    /// not notify: the message is already on screen.
    public static func isSuppressed(roomId: String, context: NotificationContext) -> Bool {
        context.appActive && context.openRoomId == roomId
    }

    // MARK: - The roster: rooms that are not subscribed

    /// Notifications for rooms whose roster row changed between two
    /// snapshots.
    ///
    /// Only a room present in both is compared. That is what makes the first
    /// roster after launch or sign-in (an empty `previous`) a baseline rather
    /// than a notification for every unread room in the account, and a room
    /// that has just appeared — joined, or brought in by a space switch — is
    /// not news either.
    public static func forRoster(
        previous: [RoomRow], next: [RoomRow], context: NotificationContext
    ) -> [LocalNotification] {
        // With remote push, every room's news arrives as a push, decided in
        // the extension. The roster knows no event ids, so a note from here
        // could never be matched to that push and would be its duplicate.
        guard !context.remotePush else { return [] }
        let before = Dictionary(
            previous.map { ($0.room.id, $0) }, uniquingKeysWith: { _, last in last })
        var out: [LocalNotification] = []
        for row in next {
            let id = row.room.id
            guard let old = before[id] else { continue }
            guard row.room.membership == .joined else { continue }
            guard id != context.timelineRoomId else { continue }
            guard !isSuppressed(roomId: id, context: context) else { continue }
            guard row.room.unread > old.room.unread else { continue }
            guard !row.room.lastMessageIsOwn else { continue }

            if let preview = row.preview, preview.pending {
                out.append(
                    LocalNotification(
                        id: "\(id)#pending#\(row.room.lastActivityMs ?? row.room.unread)",
                        roomId: id, eventId: nil, title: row.identity.name, subtitle: nil,
                        body: preview.text, category: .decision))
            } else if let text = row.preview?.text ?? row.room.lastMessage {
                out.append(
                    LocalNotification(
                        id: "\(id)#\(row.room.lastActivityMs ?? row.room.unread)",
                        roomId: id, eventId: nil, title: row.identity.name, subtitle: nil,
                        body: text, category: .message))
            }
        }
        return out
    }

    // MARK: - The subscribed room, row by row

    /// Notifications for new rows in the subscribed room.
    ///
    /// - Parameters:
    ///   - since: milliseconds since the epoch. Rows older than this are
    ///     history — what the subscription loaded, or back-pagination
    ///     fetched — and never notify.
    ///   - alreadyNotified: event ids posted before. A row is re-emitted
    ///     whenever anything about it changes (a reaction, a receipt), and
    ///     each of those must not be a new notification.
    public static func forTimeline(
        roomId: String, roomName: String, rows: [TimelineRow], since: UInt64,
        alreadyNotified: Set<String>, context: NotificationContext
    ) -> [LocalNotification] {
        guard !isSuppressed(roomId: roomId, context: context) else { return [] }
        // In the background with push, the push is the notification.
        guard context.appActive || !context.remotePush else { return [] }
        var out: [LocalNotification] = []
        for row in rows {
            guard !row.item.isOwn else { continue }
            // A local echo has no event id; a remote event always does.
            guard let eventId = row.item.eventId else { continue }
            guard !alreadyNotified.contains(eventId) else { continue }
            guard let at = row.item.timestampMs, at >= since else { continue }
            if let note = notification(for: row, eventId: eventId, roomId: roomId, roomName: roomName) {
                out.append(note)
            }
        }
        return out
    }

    /// The notification for one row, decided by the core
    /// (`core::notification::notification_for_row`) — the same function the
    /// Notification Service Extension's push goes through, so a local and a
    /// remote notification for one event cannot differ.
    static func notification(
        for row: TimelineRow, eventId: String, roomId: String, roomName: String
    ) -> LocalNotification? {
        notificationForRow(row: row, roomId: roomId, eventId: eventId, roomName: roomName)
            .map(LocalNotification.init(decided:))
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

    /// Whether a remote notification arriving in the foreground is shown.
    ///
    /// Not for the room on screen — the message is already there — and not
    /// for an event this app already posted locally from the open timeline.
    /// That local note used the event id as its identifier, which is also the
    /// push's collapse id, so it would only replace itself; showing the
    /// banner again would be the same news twice.
    public static func presentsRemote(
        roomId: String?, eventId: String?, context: NotificationContext,
        alreadyNotified: Set<String>
    ) -> Bool {
        if let roomId, isSuppressed(roomId: roomId, context: context) { return false }
        if let eventId, alreadyNotified.contains(eventId) { return false }
        return true
    }

    // MARK: - The room's own notification setting

    /// Whether a room's notification setting lets this through.
    ///
    /// A decision is always delivered: it is somebody waiting on the reader,
    /// and a muted chatty agent room is exactly where one gets lost. A plain
    /// message is held back in a muted room and in a mentions-only one —
    /// the second because telling a mention from a message is the core's
    /// decision and the roster does not carry it.
    public static func delivers(_ note: LocalNotification, mode: NotificationMode?) -> Bool {
        if note.isDecision { return true }
        switch mode {
        case .muted?, .mentionsOnly?: return false
        case .default?, .allMessages?, nil: return true
        }
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
