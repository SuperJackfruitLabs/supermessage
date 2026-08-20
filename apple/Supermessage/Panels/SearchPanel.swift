import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Search across rooms.
///
/// Every state a search can be in says which one it is. The version this
/// replaced had two booleans and could not tell a reader whether it was
/// thinking, had found nothing, or had ignored them — typing left the
/// untouched "Find a message across your rooms" on screen. See `SearchState`.
struct SearchPanel: View {
    let session: Session
    let onOpen: (String) -> Void
    let onClose: () -> Void

    @State private var term = ""
    @State private var state = SearchState.idle

    var body: some View {
        NavigationStack {
            Group {
                switch state {
                case .idle:
                    ContentUnavailableView(
                        "Search", systemImage: "magnifyingglass",
                        description: Text("Find a message across your rooms."))

                case let .ready(query):
                    ContentUnavailableView(
                        "Search for \(query)", systemImage: "magnifyingglass",
                        description: Text("Press return to search."))

                case .searching:
                    // Not a `ContentUnavailableView`: nothing is unavailable
                    // yet, and saying so would be answering a question that
                    // has not been asked.
                    VStack(spacing: 10) {
                        ProgressView()
                        Text("Searching…").metaFace().foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                case let .empty(query):
                    ContentUnavailableView.search(text: query)

                case let .found(results):
                    List(results, id: \.eventId) { result in
                        Button {
                            onOpen(result.roomId)
                            onClose()
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(roomLabel(result.roomId))
                                        .metaFace()
                                        .textCase(.uppercase)
                                        .foregroundStyle(.secondary)
                                    Spacer(minLength: 4)
                                    // When, so a hit can be placed. A result
                                    // with no date is a fragment with no
                                    // context.
                                    Text(RelativeTime.label(for: result.timestampMs, now: .now))
                                        .metaFace()
                                        .foregroundStyle(.tertiary)
                                }
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
        .onChange(of: term) { _, next in state = state.typed(next) }
        .onSubmit(of: .search) { Task { await run() } }
    }

    /// The room a hit belongs to, named the way the roster names it.
    private func roomLabel(_ roomId: String) -> String {
        session.rooms.row(for: roomId)?.identity.name ?? roomId
    }

    private func run() async {
        let query = term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        state = .searching(query)
        let results = await session.search(query)
        state = results.isEmpty ? .empty(query) : .found(results)
    }
}
