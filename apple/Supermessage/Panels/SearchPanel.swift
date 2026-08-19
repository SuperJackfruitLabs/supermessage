import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Search across rooms.
struct SearchPanel: View {
    let session: Session
    let onOpen: (String) -> Void
    let onClose: () -> Void

    @State private var term = ""
    @State private var results: [SearchResultDto] = []
    @State private var searched = false

    var body: some View {
        NavigationStack {
            Group {
                if !searched {
                    ContentUnavailableView(
                        "Search", systemImage: "magnifyingglass",
                        description: Text("Find a message across your rooms."))
                } else if results.isEmpty {
                    ContentUnavailableView.search(text: term)
                } else {
                    List(results, id: \.eventId) { result in
                        Button {
                            onOpen(result.roomId)
                            onClose()
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(roomLabel(result.roomId))
                                    .metaFace()
                                    .textCase(.uppercase)
                                    .foregroundStyle(.secondary)
                                Text(result.body).font(.callout).lineLimit(2)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) } }
        }
        .searchable(text: $term)
        .onSubmit(of: .search) { Task { await run() } }
    }

    /// The room a hit belongs to, named the way the roster names it.
    private func roomLabel(_ roomId: String) -> String {
        session.rooms.row(for: roomId)?.identity.name ?? roomId
    }

    private func run() async {
        results = await session.search(term)
        searched = true
    }
}
