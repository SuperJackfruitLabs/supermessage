import Foundation
import SupermessageFFI

// The roster, seen through the app's four destinations: Chats with its filter
// chips, the Needs you inbox, and the Agents directory.
//
// **None of this orders or groups anything.** Every function below starts from
// `RosterArrangement.sections`, which is `core::roster`, and only ever *keeps*
// or *drops* rows the core already arranged. The order a reader sees is the
// core's order in every destination, so the Chats list, the inbox and the
// agents tab cannot disagree about which room comes first.

/// The chips above the Chats list.
public enum RosterFilter: String, CaseIterable, Sendable {
    case all
    case unread
    case agents
    case needsYou

    public var title: String {
        switch self {
        case .all: return "All"
        case .unread: return "Unread"
        case .agents: return "Agents"
        case .needsYou: return "Needs you"
        }
    }

    /// Whether one of the core's rows passes this chip.
    public func admits(_ row: RosterRow) -> Bool {
        switch self {
        case .all: return true
        case .unread: return row.row.room.unread > 0
        case .agents: return row.describesAgent
        case .needsYou: return row.state == .needsYou
        }
    }

    /// The core's sections with the rows this chip does not admit removed.
    ///
    /// Order is untouched — within a section and between sections. A section
    /// left with no rows goes, because an empty heading is noise. A section
    /// that lost rows loses its `detail` too: the detail is the core's count
    /// of what it arranged ("3", "2 agents · 1 waiting"), and repeating it over
    /// a filtered subset would state a number that is no longer on screen.
    public func apply(_ sections: [RosterSection]) -> [RosterSection] {
        guard self != .all else { return sections }
        return sections.compactMap { section in
            let kept = section.rows.filter(admits)
            guard !kept.isEmpty else { return nil }
            var filtered = section
            filtered.rows = kept
            if kept.count != section.rows.count { filtered.detail = nil }
            return filtered
        }
    }
}

/// The Needs you inbox: what the reader owes an answer to, and what they have
/// been invited to.
///
/// Finishable by construction: a row leaves when the core stops saying it
/// needs you — the decision answered, the invitation accepted or declined —
/// so an empty inbox is a true statement rather than a filter someone set.
public struct NeedsYouInbox: Equatable, Sendable {
    /// Rooms whose state is `.needsYou`, in the core's order.
    public let decisions: [RosterRow]
    /// Rooms this account has been invited to, in the core's order.
    public let invitations: [RosterRow]

    /// What the tab's badge says.
    public var count: Int { decisions.count + invitations.count }
    public var isEmpty: Bool { count == 0 }

    /// Built from the flat roster, with invitations shown regardless of the
    /// roster's own "show invitations" preference: an inbox that hid them
    /// would not be finishable, it would be finished by pretending.
    public static func from(_ rows: [RoomRow], now: Date) -> NeedsYouInbox {
        let arranged = RosterArrangement.sections(
            rows, view: .recent, showsInvitations: true, now: now
        ).flatMap(\.rows)
        return NeedsYouInbox(
            decisions: arranged.filter {
                $0.state == .needsYou && $0.row.affordance != .respondToInvitation
            },
            invitations: arranged.filter { $0.row.affordance == .respondToInvitation })
    }
}

/// The Agents tab: every room the core says reads as an agent's.
public enum AgentDirectory {
    /// In the core's recency order. Invitations are left out — a room not yet
    /// joined has no status and no activity to report.
    public static func rows(_ rows: [RoomRow], now: Date) -> [RosterRow] {
        RosterArrangement.sections(rows, view: .recent, showsInvitations: false, now: now)
            .flatMap(\.rows)
            .filter(\.describesAgent)
    }
}

extension RosterArrangement {
    /// One room as the roster sees it — its state and whether it reads as an
    /// agent's — for a screen that shows one room rather than a list.
    ///
    /// Asked of the core through `sections` rather than recomputed, so the
    /// header of a room and its row in the roster cannot disagree.
    public static func rosterRow(for roomId: String, in rows: [RoomRow], now: Date) -> RosterRow? {
        guard let row = rows.first(where: { $0.room.id == roomId }) else { return nil }
        return sections([row], view: .recent, showsInvitations: true, now: now)
            .flatMap(\.rows)
            .first
    }
}

/// What a room's header says its agent is doing.
///
/// Working, Needs you, Active, Idle, Quiet. Two inputs, neither decided
/// here — the core's `AgentState` for the room, and whether `LiveStore` is
/// watching a turn being written right now. Only a live turn earns
/// "Working"; the core's `.active` (spoke within 15 minutes) is "Active".
///
/// **The same words as the roster's dot.** `.active` used to read "Idle"
/// here, so an agent that had just answered was green in the list and idle
/// in its own header — the 2026-09-24 screenshots, and why Guild agents
/// looked idle almost all the time.
public enum RoomStatus: String, CaseIterable, Sendable {
    case working
    case needsYou
    case active
    case idle
    case quiet

    public var word: String {
        switch self {
        case .working: return "Working"
        case .needsYou: return "Needs you"
        case .active: return "Active"
        case .idle: return "Idle"
        case .quiet: return "Quiet"
        }
    }

    /// The status to show, or `nil` for a room of people — "Idle" over a
    /// conversation between humans says nothing anyone wants to know.
    ///
    /// A pending decision outranks a live turn: the agent may still be
    /// writing, but what the reader needs to know is that it is waiting on
    /// them.
    public static func of(
        state: AgentState, describesAgent: Bool, turnInProgress: Bool
    ) -> RoomStatus? {
        guard describesAgent else { return nil }
        if state == .needsYou { return .needsYou }
        if turnInProgress { return .working }
        switch state {
        case .needsYou: return .needsYou
        case .active: return .active
        case .idle: return .idle
        case .quiet: return .quiet
        }
    }
}

/// What a trailing swipe's "Mute" does, given what the room is set to now.
public enum RoomToggles {
    /// Muted rooms go back to the account default rather than to "all
    /// messages": unmuting should restore what the reader had, and a room's
    /// own rule unset *is* what they had unless they set one.
    public static func nextNotificationMode(from current: NotificationMode) -> NotificationMode {
        current == .muted ? .default : .muted
    }
}
