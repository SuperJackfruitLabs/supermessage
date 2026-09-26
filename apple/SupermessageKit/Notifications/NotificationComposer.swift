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
        /// A decision that must be read before it is answered — a
        /// superpipeline gate, or a permission request whose answers do not
        /// map onto Allow once / Reject. Only "Open".
        case gate = "GATE"
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

    public init(
        id: String, roomId: String, eventId: String?, title: String, subtitle: String?,
        body: String, category: Category, allowOptionId: String? = nil,
        rejectOptionId: String? = nil
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
    }

    /// Whether this asks the reader for a decision rather than telling them
    /// something was said.
    public var isDecision: Bool { category != .message }
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

    public init(openRoomId: String?, appActive: Bool, timelineRoomId: String?) {
        self.openRoomId = openRoomId
        self.appActive = appActive
        self.timelineRoomId = timelineRoomId
    }
}

/// Which changes deserve a notification, and what it says.
///
/// **Nothing here reads message content the core did not already compose.**
/// A message's body is `TimelineRow.replyPreview` or the roster's
/// `RoomPreview.text`; a decision's is `CustomEventDecision.prompt`; titles
/// are `RoomIdentity.name` and `TimelineRow.senderName`. The only choice
/// made here is *whether* and *which category* — the host's job, since
/// notifications are a platform surface.
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
                        body: preview.text, category: .gate))
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

    static func notification(
        for row: TimelineRow, eventId: String, roomId: String, roomName: String
    ) -> LocalNotification? {
        let subtitle = row.senderName == roomName ? nil : roomName
        switch row.view {
        case let .customEvent(view, label, _):
            guard case let .rendered(_, _, _, decision?, _) = view else {
                // A card with nothing to decide — a turn, a run, a station
                // status — is context, not an interruption.
                return nil
            }
            if decision.subject != nil {
                return LocalNotification(
                    id: eventId, roomId: roomId, eventId: eventId, title: roomName,
                    subtitle: label, body: decision.prompt, category: .gate)
            }
            if let answers = permissionAnswers(decision) {
                return LocalNotification(
                    id: eventId, roomId: roomId, eventId: eventId, title: roomName,
                    subtitle: label, body: decision.prompt, category: .permission,
                    allowOptionId: answers.allow, rejectOptionId: answers.reject)
            }
            return LocalNotification(
                id: eventId, roomId: roomId, eventId: eventId, title: roomName,
                subtitle: label, body: decision.prompt, category: .gate)
        case .bubble, .emote:
            guard let text = row.replyPreview else { return nil }
            return message(row, eventId: eventId, roomId: roomId, subtitle: subtitle, body: text)
        case let .image(alt, _, _, caption):
            return message(
                row, eventId: eventId, roomId: roomId, subtitle: subtitle,
                body: caption ?? row.replyPreview ?? alt)
        case let .mediaFile(_, filename, _, _):
            return message(
                row, eventId: eventId, roomId: roomId, subtitle: subtitle,
                body: row.replyPreview ?? filename)
        // A failed turn is a message — the one other clients show as its
        // body — and the reader is waiting on the answer it replaces.
        case let .turnError(card):
            return message(
                row, eventId: eventId, roomId: roomId, subtitle: subtitle,
                body: row.replyPreview ?? card.headline)
        // What a voice note said, posted after the note. The note itself
        // already notified (or was the reader's own), so the transcript
        // arriving is not news worth a second interruption.
        case .voiceTranscript:
            return nil
        case .system, .unreadMarker, .placeholder, .dateDivider, .none:
            return nil
        }
    }

    private static func message(
        _ row: TimelineRow, eventId: String, roomId: String, subtitle: String?, body: String
    ) -> LocalNotification {
        LocalNotification(
            id: eventId, roomId: roomId, eventId: eventId, title: row.senderName,
            subtitle: subtitle, body: body, category: .message)
    }

    /// The two options a PERMISSION notification's actions send, or `nil`
    /// when this request does not offer both.
    ///
    /// A notification's actions are registered ahead of time as a fixed
    /// pair — "Allow once" and "Reject" — so they can only be offered when
    /// the request has an option that means each. The renderer hands the
    /// option's *name* back as its id (see `PermissionRequestRenderer`),
    /// and those names are ACP's: "Allow once", "Allow always", "Reject".
    /// "Always" is never taken for "once": a lock-screen tap must not grant
    /// more than the button said.
    public static func permissionAnswers(
        _ decision: CustomEventDecision
    ) -> (allow: String, reject: String)? {
        func normalised(_ s: String) -> String {
            s.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let allow = decision.options.first { normalised($0.id) == "allow once" }
            ?? decision.options.first {
                let n = normalised($0.id)
                return n.hasPrefix("allow") && !n.contains("always")
            }
        let reject = decision.options.first { normalised($0.id) == "reject" }
            ?? decision.options.first {
                let n = normalised($0.id)
                return (n.hasPrefix("reject") || n.hasPrefix("deny")) && !n.contains("always")
            }
        guard let allow, let reject else { return nil }
        return (allow.id, reject.id)
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
