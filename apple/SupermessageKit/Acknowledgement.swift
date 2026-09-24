import Foundation
import SupermessageFFI

/// What the dock above the composer says after the reader has written to an
/// agent (A3): never a silent gap between sending and the answer.
///
/// ```
/// send ──▶ Sent to Atlas ──(reacts · types · starts a turn)──▶ Atlas is on it…
///                 │                                                  │
///                 └──────────────(Atlas's message lands)─────────────┴──▶ nothing
/// ```
///
/// Derived, not stored: every input is already on screen somewhere — the
/// timeline, the typing notices, the live turn — so a state machine that kept
/// its own copy could only drift from them. Leaving the room and coming back
/// gives the same answer, because it is recomputed from the same facts.
public enum Acknowledgement: Equatable, Sendable {
    case sent(to: String)
    /// Who is working, by name — usually one.
    case onIt([String])

    /// The dock's sentence.
    public var text: String {
        switch self {
        case let .sent(name): return "Sent to \(name)"
        case let .onIt(names):
            switch names.count {
            case 0: return "On it…"
            case 1: return "\(names[0]) is on it…"
            case 2: return "\(names[0]) and \(names[1]) are on it…"
            default: return "\(names[0]) and \(names.count - 1) others are on it…"
            }
        }
    }

    /// What the dock says, or `nil` for nothing.
    ///
    /// - Parameters:
    ///   - rows: the timeline, oldest first.
    ///   - addressee: who a message here is for (`RoomCast.addressee`). `nil`
    ///     in a room with no single agent, which never says "Sent to" — it
    ///     can only name agents that are visibly typing.
    ///   - agentIds: the room's agents, to tell their replies from anyone
    ///     else's. Empty when not yet known, and then any other member's
    ///     message counts as the answer — the dock errs towards going away.
    ///   - sentThisSession: whether the reader has sent here since signing in
    ///     (`TypingStore.hasSent`). An unanswered message from last week is
    ///     not news.
    ///   - typingAgents: the agents whose typing notice is up, by name
    ///     (`TypingStore.typingAgents`).
    ///   - turnInProgress: a live turn is streaming and has not finished.
    public static func state(
        rows: [TimelineRow], addressee: String?, agentIds: Set<String>,
        sentThisSession: Bool, typingAgents: [String], turnInProgress: Bool
    ) -> Acknowledgement? {
        // Working says itself whether or not the reader just sent: this is
        // the sentence that replaced "Atlas is typing…". In a room of several
        // agents there is no addressee, and whoever is typing is named.
        // **Not while a live turn is on screen.** Its card already says who
        // is working, for how long, and on what; a pill saying "Krishna is on
        // it…" under it made four "working" signals at once (2026-09-24). The
        // pill stays for an agent that only sends typing notices.
        if turnInProgress { return nil }
        if let addressee, !typingAgents.isEmpty { return .onIt([addressee]) }
        if addressee == nil, !typingAgents.isEmpty { return .onIt(typingAgents) }
        guard let addressee, sentThisSession, let lastOwn = rows.lastIndex(where: { $0.item.isOwn }) else {
            return nil
        }
        let answered = rows[rows.index(after: lastOwn)...].contains { row in
            guard !row.item.isOwn, let sender = row.item.sender, row.membershipVerb == nil else {
                return false
            }
            return agentIds.isEmpty || agentIds.contains(sender)
        }
        if answered { return nil }
        return reactedToByAnyoneElse(rows[lastOwn]) ? .onIt([addressee]) : .sent(to: addressee)
    }

    /// Whether someone other than the reader has reacted to `row`. A reaction
    /// on the reader's own message, in an agent's room, is the agent saying
    /// it has seen it — 👀 is the usual one.
    static func reactedToByAnyoneElse(_ row: TimelineRow) -> Bool {
        row.item.reactions.contains { $0.count > ($0.byMe ? 1 : 0) }
    }
}
