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
    /// Whether a `nil` from the list means "popped back to the roster".
    ///
    /// Decided by the view that owns the `NavigationSplitView`, not read from
    /// the environment here: **a column reports its own width**, and a
    /// sidebar on an iPad is compact. Asking inside this view gave the answer
    /// for the sidebar rather than for the window, so an iPad would have
    /// obeyed a `nil` and closed the room the reader was in.
    let clearsSelectionOnPop: Bool



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

    /// Selection, in both directions.
    ///
    /// **The `set` used to drop `nil` on the floor**, and that one `if let` is
    /// the whole of the reported bug. A collapsed `NavigationSplitView`
    /// navigates by selection and writes `nil` back when it pops — swallowing
    /// that left the row highlighted after coming back, and left `List`'s idea
    /// of its selection disagreeing with ours, so tapping the same room again
    /// produced no change and the room would not reopen.
    ///
    /// `nil` is honoured only on a phone, where it means "popped back to the
    /// roster". On an iPad the roster sits beside the conversation and nothing
    /// legitimately deselects — a `nil` there would be the list resetting
    /// under a roster reload, and obeying it would close the room the reader
    /// is in. See `clearsSelectionOnPop` for why that is not decided here.
    private var selectionBinding: Binding<String?> {
        Binding(
            get: { session.rooms.selectedId },
            set: { next in
                if let id = next {
                    session.rooms.select(id)
                } else if clearsSelectionOnPop {
                    session.rooms.deselect()
                }
            })
    }
}
