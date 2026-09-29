// Compiled into THREE targets: SupermessageKit and the Notification Service
// Extension, which write it through `WidgetSnapshotWriter`, and the
// SupermessageWidgets extension, which only reads it. Foundation only.

import Foundation

/// What the widgets show: the core's `widget::WidgetSnapshot`, decoded.
///
/// **A mirror, not a second model.** The core writes this JSON — every rule
/// about what counts as a decision, what an agent last said, when it goes
/// idle and which answers a button may send is `core::widget` — and this
/// only reads it back, so the widget extension, which has no core, can draw
/// it. `WidgetSnapshotContractTests` decodes the core's own output with this
/// type, so a field renamed on one side fails a test rather than a widget.
public struct WidgetSnapshot: Codable, Equatable, Sendable {
    public enum DecisionKind: String, Codable, Sendable {
        case permission, gate, open
    }

    public struct Option: Codable, Equatable, Sendable, Identifiable {
        public var id: String
        public var label: String
        /// A widget button may send it. Request changes needs words.
        public var inline: Bool
        /// It refuses rather than grants: the quieter button.
        public var declines: Bool

        public init(id: String, label: String, inline: Bool, declines: Bool) {
            self.id = id
            self.label = label
            self.inline = inline
            self.declines = declines
        }
    }

    public struct Answered: Codable, Equatable, Sendable {
        public var optionId: String
        public var atMs: UInt64
        /// "Sent: Approve · waiting for the board" — never the outcome.
        public var line: String

        public init(optionId: String, atMs: UInt64, line: String) {
            self.optionId = optionId
            self.atMs = atMs
            self.line = line
        }
    }

    public struct Decision: Codable, Equatable, Sendable, Identifiable {
        public var kind: DecisionKind
        public var roomId: String
        public var eventId: String
        public var agent: String
        public var asker: String?
        public var question: String
        public var options: [Option]
        public var gateId: String?
        public var prompt: String
        public var askedAtMs: UInt64
        public var answered: Answered?

        public var id: String { eventId }
        public var askedAt: Date { .init(milliseconds: askedAtMs) }
        /// The answers a widget button may send, in the card's order.
        public var buttons: [Option] { answered == nil ? options.filter(\.inline) : [] }

        public init(
            kind: DecisionKind, roomId: String, eventId: String, agent: String,
            asker: String? = nil, question: String, options: [Option], gateId: String? = nil,
            prompt: String = "", askedAtMs: UInt64, answered: Answered? = nil
        ) {
            self.kind = kind
            self.roomId = roomId
            self.eventId = eventId
            self.agent = agent
            self.asker = asker
            self.question = question
            self.options = options
            self.gateId = gateId
            self.prompt = prompt
            self.askedAtMs = askedAtMs
            self.answered = answered
        }
    }

    /// What an agent did since the app was last opened, most telling first.
    public enum Outcome: String, Codable, Sendable {
        case failed, finished, said
    }

    public struct Agent: Codable, Equatable, Sendable, Identifiable {
        public var roomId: String
        public var name: String
        public var lastActivityMs: UInt64?
        public var line: String?
        public var step: String?
        public var rosterNeedsYou: Bool
        public var outcome: Outcome?
        public var outcomeLine: String?
        public var outcomeAtMs: UInt64?
        public var unread: UInt32
        public var countedEventId: String?

        public var id: String { roomId }
        public var lastActivity: Date? { lastActivityMs.map(Date.init(milliseconds:)) }

        public init(
            roomId: String, name: String, lastActivityMs: UInt64?, line: String?,
            step: String? = nil, rosterNeedsYou: Bool = false, outcome: Outcome? = nil,
            outcomeLine: String? = nil, outcomeAtMs: UInt64? = nil, unread: UInt32 = 0,
            countedEventId: String? = nil
        ) {
            self.roomId = roomId
            self.name = name
            self.lastActivityMs = lastActivityMs
            self.line = line
            self.step = step
            self.rosterNeedsYou = rosterNeedsYou
            self.outcome = outcome
            self.outcomeLine = outcomeLine
            self.outcomeAtMs = outcomeAtMs
            self.unread = unread
            self.countedEventId = countedEventId
        }
    }

    /// One row of the recap: an agent that did something since the app was
    /// last opened. The core orders them — failed, finished, said, then
    /// newest first — and leaves out every agent that did nothing.
    public struct Recap: Codable, Equatable, Sendable, Identifiable {
        public var roomId: String
        public var name: String
        public var outcome: Outcome
        /// "2 of 7 steps failed", "Finished · 7 steps", or what it said.
        public var line: String
        /// Pushes from it since then.
        public var unread: UInt32
        public var atMs: UInt64

        public var id: String { roomId }
        public var at: Date { .init(milliseconds: atMs) }

        public init(
            roomId: String, name: String, outcome: Outcome, line: String, unread: UInt32,
            atMs: UInt64
        ) {
            self.roomId = roomId
            self.name = name
            self.outcome = outcome
            self.line = line
            self.unread = unread
            self.atMs = atMs
        }
    }

    public enum Tone: String, Codable, Sendable {
        case needsYou, working, active, idle, quiet
    }

    public struct AgentState: Codable, Equatable, Sendable {
        public var word: String
        public var tone: Tone

        public init(word: String, tone: Tone) {
            self.word = word
            self.tone = tone
        }
    }

    /// What to draw from `fromMs` until the next frame.
    public struct Frame: Codable, Equatable, Sendable {
        public var fromMs: UInt64
        public var needsYou: UInt32
        public var needsYouCount: String
        public var needsYouLine: String
        public var working: UInt32
        public var pulse: String
        /// One per `agents`, in order.
        public var states: [AgentState]
        /// Who the pulse counts as working, by name ("Atlas is working"), or
        /// `nil` when nobody is — what a recap with nothing in it says
        /// instead of "Nothing new" under an "N working" header.
        public var busy: String?

        public var from: Date { .init(milliseconds: fromMs) }

        public init(
            fromMs: UInt64, needsYou: UInt32, needsYouCount: String, needsYouLine: String,
            working: UInt32, pulse: String, states: [AgentState], busy: String? = nil
        ) {
            self.fromMs = fromMs
            self.needsYou = needsYou
            self.needsYouCount = needsYouCount
            self.needsYouLine = needsYouLine
            self.working = working
            self.pulse = pulse
            self.states = states
            self.busy = busy
        }
    }

    /// The schema this build reads. Another is treated as no snapshot.
    public static let schemaVersion: UInt32 = 3

    /// How long a change that is not a decision waits for a reload, and how
    /// often the widgets' timeline asks again on its own — the core's
    /// `widget::RELOAD_EVERY_MS`.
    public static let reloadEvery: TimeInterval = 15 * 60

    public var schema: UInt32
    public var revision: UInt64
    public var updatedAtMs: UInt64
    public var rosterAtMs: UInt64
    public var reloadedAtMs: UInt64
    /// When the app was last opened; `0` until it has been.
    public var openedAtMs: UInt64
    public var signedIn: Bool
    public var decisions: [Decision]
    public var overflow: Bool
    public var agents: [Agent]
    public var rosterWaiting: UInt32
    public var frames: [Frame]
    public var recap: [Recap]

    /// When the recap is from, or `nil` before the app was first opened.
    public var openedAt: Date? { openedAtMs == 0 ? nil : .init(milliseconds: openedAtMs) }

    public init(
        schema: UInt32 = WidgetSnapshot.schemaVersion, revision: UInt64 = 1, updatedAtMs: UInt64 = 0,
        rosterAtMs: UInt64 = 0, reloadedAtMs: UInt64 = 0, openedAtMs: UInt64 = 0, signedIn: Bool,
        decisions: [Decision], overflow: Bool = false, agents: [Agent], rosterWaiting: UInt32 = 0,
        frames: [Frame], recap: [Recap] = []
    ) {
        self.schema = schema
        self.revision = revision
        self.updatedAtMs = updatedAtMs
        self.rosterAtMs = rosterAtMs
        self.reloadedAtMs = reloadedAtMs
        self.openedAtMs = openedAtMs
        self.signedIn = signedIn
        self.decisions = decisions
        self.overflow = overflow
        self.agents = agents
        self.rosterWaiting = rosterWaiting
        self.frames = frames
        self.recap = recap
    }

    /// The frame for `date`: the last one that has begun, or the first when
    /// none has (a clock behind the writer's).
    public func frame(at date: Date) -> Frame? {
        let at = UInt64(max(0, date.timeIntervalSince1970 * 1000))
        return frames.last(where: { $0.fromMs <= at }) ?? frames.first
    }

    /// The decisions still owed, then the ones sent and waiting — the order a
    /// widget lists them in.
    public var listedDecisions: [Decision] {
        decisions.filter { $0.answered == nil } + decisions.filter { $0.answered != nil }
    }

    /// Decode what the core wrote, or `nil` for anything else.
    public static func decode(_ data: Data) -> WidgetSnapshot? {
        guard let snapshot = try? JSONDecoder().decode(WidgetSnapshot.self, from: data),
            snapshot.schema == schemaVersion
        else { return nil }
        return snapshot
    }
}

extension Date {
    init(milliseconds: UInt64) {
        self.init(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }
}

/// Where the snapshot lives: `<App Group>/widgets/snapshot.json`.
///
/// **A file, not the group's shared defaults.** Two processes write it — the
/// app and the Notification Service Extension — and each write is a read,
/// a merge in the core, and a write back. Defaults cannot hold a lock across
/// that; a file beside a lock file can (`WidgetSnapshotWriter`). Readers take
/// no lock: every write replaces the file atomically, so a reader sees one
/// whole snapshot or the one before it.
///
/// **An App Group needs an entitlement and a provisioning profile that
/// grants it.** Without them there is no container, and every reader treats
/// that as "no data", drawn as an honest empty state rather than a count of
/// zero.
public enum WidgetSnapshotStore {
    public static let appGroup = "group.dev.supermessage.ios"

    /// Whether this process has the App Group at all.
    public static var isAvailable: Bool { directory() != nil }

    /// `<App Group>/widgets`, created if missing; `nil` without the group.
    public static func directory() -> URL? {
        guard
            let container = FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: appGroup)
        else { return nil }
        let directory = container.appendingPathComponent("widgets", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    public static func snapshotURL(in directory: URL) -> URL {
        directory.appendingPathComponent("snapshot.json")
    }

    /// The stored JSON in `directory`, or `nil` when there is none.
    public static func readJSON(in directory: URL) -> String? {
        guard let data = try? Data(contentsOf: snapshotURL(in: directory)) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// The last snapshot written, or `nil` when there is none or no group.
    public static func read(in directory: URL? = directory()) -> WidgetSnapshot? {
        guard let directory, let data = try? Data(contentsOf: snapshotURL(in: directory)) else {
            return nil
        }
        return WidgetSnapshot.decode(data)
    }
}

/// `supermessage://open?room=<id>[&event=<id>]` — what a widget or a Live
/// Activity opens.
///
/// A query item rather than a path: a Matrix room id carries `!` and `:`,
/// and a path component would need its own escaping rules to hold them.
/// Here, in the shared file, so the extension that builds the link and the
/// app that reads it cannot disagree about its shape.
public enum AppLink {
    public static let scheme = "supermessage"

    public static func room(_ roomId: String) -> URL? {
        link(roomId, event: nil)
    }

    /// The room, naming the card the link is about.
    public static func decision(roomId: String, eventId: String) -> URL? {
        link(roomId, event: eventId)
    }

    private static func link(_ roomId: String, event: String?) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "open"
        components.queryItems =
            [URLQueryItem(name: "room", value: roomId)]
            + (event.map { [URLQueryItem(name: "event", value: $0)] } ?? [])
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
