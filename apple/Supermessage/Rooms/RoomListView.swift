import SupermessageFFI
import SupermessageKit
import SwiftUI

/// The roster.
///
/// Sorted by recency, which is the only honest per-row liveness signal
/// available: per-room typing is not streamed, and the typing channel is
/// scoped to the focused room.
struct RoomListView: View {
    let session: Session



    private var sorted: [RoomRow] {
        session.rooms.rooms.sorted {
            ($0.room.lastActivityMs ?? 0) > ($1.room.lastActivityMs ?? 0)
        }
    }

    var body: some View {
        List(selection: selectionBinding) {
            Section {
                ForEach(sorted, id: \.room.id) { row in
                    RoomRowView(
                        row: row,
                        avatarURI: session.avatars.uri(for: row.room.id),
                    )
                    .tag(row.room.id)
                    .task { await session.avatars.load(row.room.id) }
                }
            } header: {
                // Inside the scroll content on purpose — see SpacePillStrip.
                if !session.spaces.spaces.isEmpty {
                    SpacePillStrip(spaces: session.spaces)
                        .textCase(nil)
                        .listRowInsets(EdgeInsets())
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(session.spaces.selectedName ?? "All rooms")
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if session.rooms.rooms.isEmpty {
                ContentUnavailableView(
                    "No rooms yet", systemImage: "tray",
                    description: Text("Rooms appear here as they sync."))
            }
        }
    }

    private var selectionBinding: Binding<String?> {
        Binding(
            get: { session.rooms.selectedId },
            set: { if let id = $0 { session.rooms.select(id) } })
    }
}
