// Compiled into TWO targets: SupermessageKit, which writes it, and the
// SupermessageWidgets extension, which reads it. Foundation only.

import Foundation

/// What the widgets show, as the app last saw it.
///
/// Written by the app into the App Group's shared defaults and read by the
/// widget extension, which has no core of its own and must not: a widget
/// process that opened the Matrix store would be a second client on the same
/// account. So the app decides, and the widget only draws.
public struct WidgetSnapshot: Codable, Equatable, Sendable {
    public struct Agent: Codable, Equatable, Sendable, Identifiable {
        public var id: String
        public var name: String
        /// The core's word for the state — "needs you", "active", "idle",
        /// "quiet" (`AgentState.word`).
        public var state: String
        /// Whether that state is "needs you".
        public var needsYou: Bool
        /// Whether that state is "active".
        public var active: Bool
        public var lastActivity: Date?

        public init(
            id: String, name: String, state: String, needsYou: Bool, active: Bool,
            lastActivity: Date?
        ) {
            self.id = id
            self.name = name
            self.state = state
            self.needsYou = needsYou
            self.active = active
            self.lastActivity = lastActivity
        }
    }

    public var signedIn: Bool
    /// Rooms that owe someone an answer from the reader.
    public var needsYou: Int
    /// Agent rooms, most recently active first, bounded.
    public var agents: [Agent]
    public var updatedAt: Date

    public init(signedIn: Bool, needsYou: Int, agents: [Agent], updatedAt: Date) {
        self.signedIn = signedIn
        self.needsYou = needsYou
        self.agents = agents
        self.updatedAt = updatedAt
    }

    public static let empty = WidgetSnapshot(
        signedIn: false, needsYou: 0, agents: [], updatedAt: .distantPast)

    /// Equal apart from when it was taken — whether writing it again would
    /// tell the widget anything new.
    public func sameContent(as other: WidgetSnapshot) -> Bool {
        signedIn == other.signedIn && needsYou == other.needsYou && agents == other.agents
    }
}

/// Where the snapshot lives.
///
/// **An App Group needs an entitlement and a provisioning profile that
/// grants it.** Without them `containerURL` is `nil`, and `UserDefaults(suiteName:)`
/// silently falls back to a store private to each process — the app would
/// write where the widget never reads. So both sides check the container
/// first and treat its absence as "no data", which the widget draws as an
/// honest empty state rather than as a count of zero.
public enum WidgetSnapshotStore {
    public static let appGroup = "group.dev.supermessage.ios"
    static let key = "widget.snapshot.v1"

    /// Whether this process has the App Group at all.
    public static var isAvailable: Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) != nil
    }

    private static var defaults: UserDefaults? {
        guard isAvailable else { return nil }
        return UserDefaults(suiteName: appGroup)
    }

    /// The last snapshot written, or `nil` when there is none or no group.
    public static func read() -> WidgetSnapshot? {
        guard let data = defaults?.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    /// Store `snapshot`. Returns whether it was written.
    @discardableResult
    public static func write(_ snapshot: WidgetSnapshot) -> Bool {
        guard let defaults, let data = try? JSONEncoder().encode(snapshot) else { return false }
        defaults.set(data, forKey: key)
        return true
    }
}

/// `supermessage://open?room=<id>` — what a widget or a Live Activity opens.
///
/// A query item rather than a path: a Matrix room id carries `!` and `:`,
/// and a path component would need its own escaping rules to hold them.
/// Here, in the shared file, so the extension that builds the link and the
/// app that reads it cannot disagree about its shape.
public enum AppLink {
    public static let scheme = "supermessage"

    public static func room(_ roomId: String) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "open"
        components.queryItems = [URLQueryItem(name: "room", value: roomId)]
        return components.url
    }

    /// The room `url` opens, or `nil` when it is not one of these links.
    public static func roomId(in url: URL) -> String? {
        guard url.scheme == scheme,
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            components.host == "open",
            let room = components.queryItems?.first(where: { $0.name == "room" })?.value,
            !room.isEmpty
        else { return nil }
        return room
    }
}
