import Foundation
import SupermessageFFI

/// How the roster is ordered and grouped.
///
/// Three arrangements rather than one, because a fleet is read for different
/// reasons: to find a room you have in mind, to answer whatever is waiting, or
/// to see how a machine is doing.
public enum RosterView: String, CaseIterable, Sendable {
    case recent
    case waiting
    case machine

    public var title: String {
        switch self {
        case .recent: return "Recent"
        case .waiting: return "Waiting"
        case .machine: return "Machine"
        }
    }
}

/// What an agent is doing, as far as the roster can honestly tell.
public enum AgentState: Equatable, Sendable {
    /// Owes the reader an answer. The core said so — `preview.pending`.
    case needsYou
    /// Spoke recently enough to count as active.
    case active
    /// Nothing lately, but within living memory.
    case idle
    /// Silent long enough that its absence is the fact.
    case quiet

    public var word: String {
        switch self {
        case .needsYou: return "needs you"
        case .active: return "active"
        case .idle: return "idle"
        case .quiet: return "quiet"
        }
    }
}

/// One section of the roster.
public struct RosterSection: Identifiable, Sendable {
    public let id: String
    /// `nil` for an arrangement that does not label its one section.
    public let title: String?
    /// A count the header may show — waiting rooms, agents on a host.
    public let detail: String?
    public let rows: [RoomRow]
    /// Whether this section is the one that wants attention.
    public let attention: Bool

    public init(
        id: String, title: String?, detail: String?, rows: [RoomRow], attention: Bool = false
    ) {
        self.id = id
        self.title = title
        self.detail = detail
        self.rows = rows
        self.attention = attention
    }
}

/// Turning a flat roster into the arrangement a reader chose.
///
/// Pure, and deliberately so: every rule here is a product decision about what
/// a fleet looks like, and each is worth a test that fails when it changes.
public enum RosterArrangement {
    /// How long without a word before a room reads as quiet rather than idle.
    ///
    /// A day is the shape of this work: an agent that said nothing since
    /// yesterday is between tasks, one that said nothing for three days has
    /// been left alone. Not a health check — the roster does not know whether
    /// a process is running, and must not imply that it does.
    public static let quietAfter: TimeInterval = 24 * 60 * 60
    /// Within this, a room counts as active rather than idle.
    public static let activeWithin: TimeInterval = 15 * 60

    /// What the roster may say about a room.
    ///
    /// `needsYou` outranks everything: a room that owes an answer is not
    /// described by how recently it spoke.
    public static func state(for row: RoomRow, now: Date) -> AgentState {
        if row.preview?.pending == true { return .needsYou }
        guard let ms = row.room.lastActivityMs else { return .quiet }
        let elapsed = now.timeIntervalSince1970 - Double(ms) / 1000
        if elapsed <= activeWithin { return .active }
        if elapsed <= quietAfter { return .idle }
        return .quiet
    }

    /// Whether a room is an invitation rather than a conversation.
    static func isInvitation(_ row: RoomRow) -> Bool {
        row.affordance == .respondToInvitation
    }

    /// Arrange `rows` for one view.
    ///
    /// `showsInvitations` is off by default in the app, and hiding them here
    /// rather than in a view keeps every arrangement agreeing about what the
    /// roster contains.
    public static func sections(
        _ rows: [RoomRow], view: RosterView, showsInvitations: Bool, now: Date
    ) -> [RosterSection] {
        let visible = showsInvitations ? rows : rows.filter { !isInvitation($0) }
        let byRecency = visible.sorted {
            ($0.room.lastActivityMs ?? 0) > ($1.room.lastActivityMs ?? 0)
        }

        switch view {
        case .recent:
            return [RosterSection(id: "recent", title: nil, detail: nil, rows: byRecency)]

        case .waiting:
            let waiting = byRecency.filter { state(for: $0, now: now) == .needsYou }
            let rest = byRecency.filter { state(for: $0, now: now) != .needsYou }
            var out: [RosterSection] = []
            if !waiting.isEmpty {
                out.append(
                    RosterSection(
                        id: "waiting", title: "Waiting on you", detail: "\(waiting.count)",
                        rows: waiting, attention: true))
            }
            if !rest.isEmpty {
                // Named only when something sits above it. On a quiet fleet
                // this is the whole roster, and "Everything else" would be
                // labelling the absence of a section.
                out.append(
                    RosterSection(
                        id: "rest", title: waiting.isEmpty ? nil : "Everything else",
                        detail: nil, rows: rest))
            }
            return out

        case .machine:
            var hosts: [String] = []
            var grouped: [String: [RoomRow]] = [:]
            for row in byRecency {
                // A room with no runtime is not an agent's. Filed under its own
                // heading rather than guessed at — see `parse_runtime`.
                let host = row.room.runtime?.host ?? "Elsewhere"
                if grouped[host] == nil { hosts.append(host) }
                grouped[host, default: []].append(row)
            }
            return hosts.map { host in
                let rows = grouped[host] ?? []
                let waiting = rows.filter { state(for: $0, now: now) == .needsYou }.count
                let agents = rows.count == 1 ? "1 agent" : "\(rows.count) agents"
                return RosterSection(
                    id: host,
                    title: host,
                    detail: waiting > 0 ? "\(agents) · \(waiting) waiting" : agents,
                    rows: rows,
                    attention: waiting > 0)
            }
        }
    }

    /// How many invitations are being withheld, for the picker to admit to.
    ///
    /// Hidden must never mean gone: a roster that silently drops a room you
    /// were invited to is a roster that lost it.
    public static func hiddenInvitations(_ rows: [RoomRow], showsInvitations: Bool) -> Int {
        showsInvitations ? 0 : rows.filter(isInvitation).count
    }
}
