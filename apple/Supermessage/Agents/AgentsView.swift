import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Agents: every room the core says reads as an agent's, with what each is
/// doing and when it last did anything.
///
/// A directory, not a second roster: the order is the core's recency order,
/// and the rows are the same rows Chats shows, drawn for a different question
/// — "who is on my fleet and are they alive" rather than "what was said".
struct AgentsView: View {
    let session: Session
    @Binding var selection: String?

    @State private var now = Date()

    private var agents: [RosterRow] { AgentDirectory.rows(session.rooms.rooms, now: now) }

    var body: some View {
        List(selection: $selection) {
            ForEach(agents, id: \.row.room.id) { entry in
                AgentRow(
                    entry: entry,
                    avatarURI: session.avatars.uri(for: entry.row.room.id),
                    status: RoomStatus.of(
                        state: entry.state, describesAgent: true,
                        turnInProgress: turnInProgress(entry.row.room.id)),
                    when: RelativeTime.label(for: entry.row.room.lastActivityMs, now: now))
                .tag(entry.row.room.id)
                .listRowBackground(Theme.surface)
                .task { await session.avatars.load(entry.row.room.id) }
            }
        }
        .listStyle(.plain)
        .paletteListGround()
        .overlay {
            if agents.isEmpty {
                ContentUnavailableView(
                    "No agents yet", systemImage: "sparkles",
                    description: Text("Rooms with an agent in them appear here."))
            }
        }
        .navigationTitle("Agents")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: session.rooms.rooms.count) { now = Date() }
        .refreshable {
            now = Date()
            await session.rooms.seed()
        }
    }

    /// Only the open room's turn is visible to this app — `LiveStore` keeps
    /// the focused room's and nothing else — so only that room can say
    /// Working. Every other agent's status is the core's.
    private func turnInProgress(_ roomId: String) -> Bool {
        session.rooms.selectedId == roomId && session.live.isLive && !session.live.finished
    }
}

/// One agent: face, name, role, what it is doing, when it last spoke.
private struct AgentRow: View {
    let entry: RosterRow
    let avatarURI: String?
    let status: RoomStatus?
    let when: String

    var body: some View {
        HStack(spacing: 12) {
            RoomAvatar(
                roomId: entry.row.room.id, initial: entry.row.identity.initial,
                avatarURI: avatarURI, describesAgent: true, state: entry.state, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(entry.row.identity.name).nameFace().lineLimit(1)
                    Spacer(minLength: 4)
                    if !when.isEmpty {
                        Text(when).metaFace().foregroundStyle(Theme.contentFaint)
                    }
                }
                HStack(spacing: 6) {
                    if let role = entry.row.identity.role, !role.isEmpty {
                        Text(role)
                            .font(.subheadline)
                            .foregroundStyle(Theme.contentMuted)
                            .lineLimit(1)
                    }
                    if let status {
                        StatusPill(status: status, compact: true)
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }
}

#if DEBUG
#Preview("Agents") {
    @Previewable @State var open: String?
    NavigationStack {
        AgentsView(session: NavigationRevampFixtures.fleetSession(), selection: $open)
    }
}

#Preview("Agents, dark") {
    @Previewable @State var open: String?
    NavigationStack {
        AgentsView(session: NavigationRevampFixtures.fleetSession(), selection: $open)
    }
    .preferredColorScheme(.dark)
}
#endif
