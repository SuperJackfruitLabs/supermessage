import Foundation
import SupermessageFFI

/// Which arrangement the reader chose.
///
/// A Swift enum in front of the core's `RosterChoice` rather than the core's
/// type directly, for two reasons that are both about the host: `@AppStorage`
/// needs a `rawValue` to persist, and the picker needs `CaseIterable` to
/// enumerate. The *rules* are not here — see `RosterArrangement`.
public enum RosterChoice: String, CaseIterable, Sendable {
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

    /// The core's spelling of the same choice.
    var core: SupermessageFFI.RosterView {
        switch self {
        case .recent: return .recent
        case .waiting: return .waiting
        case .machine: return .machine
        }
    }
}

extension AgentState {
    /// What the roster says out loud.
    ///
    /// The words are the core's — `AgentState::word` — repeated here because
    /// a `&'static str` on a Rust enum does not cross a UniFFI boundary. If
    /// they ever diverge, the core is right.
    public var word: String {
        switch self {
        case .needsYou: return "needs you"
        case .active: return "active"
        case .idle: return "idle"
        case .quiet: return "quiet"
        }
    }
}

/// Turning a flat roster into the arrangement a reader chose.
///
/// **Every rule moved to `core::roster`.** They are product decisions about
/// what a fleet looks like — how long silence takes to become quiet, which
/// room outranks which, what a section is called when it is the only one —
/// and two hosts each holding a copy is two clients that disagree about what
/// a roster is, which is exactly what happened. What is left here is the
/// call, and the two host-shaped conveniences above it.
public enum RosterArrangement {
    /// What the roster may say about a room.
    ///
    /// Rarely needed on its own: `sections` already carries each row's state,
    /// so a list should read it there rather than asking per row.
    public static func state(for row: RoomRow, now: Date) -> AgentState {
        rosterState(row: row, nowMs: milliseconds(now))
    }

    /// Arrange `rows` for one view.
    public static func sections(
        _ rows: [RoomRow], view: RosterChoice, showsInvitations: Bool, now: Date
    ) -> [RosterSection] {
        rosterSections(
            rows: rows, view: view.core, showsInvitations: showsInvitations,
            nowMs: milliseconds(now))
    }

    /// How many invitations are being withheld, for the picker to admit to.
    public static func hiddenInvitations(_ rows: [RoomRow], showsInvitations: Bool) -> Int {
        Int(rosterHiddenInvitations(rows: rows, showsInvitations: showsInvitations))
    }

    private static func milliseconds(_ date: Date) -> UInt64 {
        UInt64(max(0, date.timeIntervalSince1970 * 1000))
    }
}
