import Foundation
import SupermessageFFI

/// The widget snapshot for a roster.
///
/// Every judgement is the core's: which rooms are agents
/// (`RosterRow.describesAgent`), what state each is in, and whether it needs
/// the reader (`AgentState.needsYou`, which is `preview.pending`). This only
/// counts and bounds.
public enum WidgetSummary {
    /// How many agents a widget can list. The large family fits six rows;
    /// smaller families show a prefix.
    public static let agentLimit = 6

    public static func snapshot(rows: [RoomRow], now: Date) -> WidgetSnapshot {
        // Once each: a room listed under two sections is still one room.
        var seen = Set<String>()
        let arranged = RosterArrangement.sections(
            rows, view: .recent, showsInvitations: false, now: now
        ).flatMap(\.rows).filter { seen.insert($0.row.room.id).inserted }
        let needsYou = arranged.filter { $0.state == .needsYou }.count
        let agents = arranged.filter(\.describesAgent).prefix(agentLimit).map { row in
            WidgetSnapshot.Agent(
                id: row.row.room.id, name: row.row.identity.name, state: row.state.word,
                needsYou: row.state == .needsYou, active: row.state == .active,
                lastActivity: row.row.room.lastActivityMs.map {
                    Date(timeIntervalSince1970: TimeInterval($0) / 1000)
                })
        }
        return WidgetSnapshot(
            signedIn: true, needsYou: needsYou, agents: Array(agents), updatedAt: now)
    }
}
