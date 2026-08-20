import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Search across rooms, or within one.
///
/// Every state a search can be in says which one it is. The version this
/// replaced had two booleans and could not tell a reader whether it was
/// thinking, had found nothing, or had ignored them — typing left the
/// untouched "Find a message across your rooms" on screen. See `SearchState`.
struct SearchPanel: View {
    let session: Session
    /// The room the reader came from, when they came from one. `nil` opens the
    /// panel with no scope to offer and searches everything.
    var scope: Scope?
    let onOpen: (String) -> Void
    let onClose: () -> Void

    /// Where to look. Offered only when there is a room to look in — a
    /// segmented control with one option is a label wearing a control's
    /// clothes.
    struct Scope: Equatable {
        let roomId: String
        let name: String
    }

    @State private var term = ""
    @State private var state = SearchState.idle
    /// Whether the search is narrowed to `scope`. Starts narrowed: a reader
    /// who opens search from inside a room is asking about that room.
    @State private var narrowed = true

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let scope {
                    Picker("Search in", selection: $narrowed) {
                        Text(scope.name).tag(true)
                        Text("All rooms").tag(false)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                    // Changing where to look re-asks rather than leaving the
                    // old room's results under the new scope's label.
                    .onChange(of: narrowed) { _, _ in
                        guard !state.query.isEmpty || !term.isEmpty else { return }
                        Task { await run() }
                    }
                }
                results
            }
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            // Cancel, not Done: nothing here is being composed, and the only
            // thing this button does is abandon the search.
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Cancel", action: onClose) } }
        }
        .searchable(text: $term)
        .onChange(of: term) { _, next in state = state.typed(next) }
        .onSubmit(of: .search) { Task { await run() } }
    }

    @ViewBuilder private var results: some View {
        switch state {
        case .idle:
            ContentUnavailableView(
                "Search", systemImage: "magnifyingglass",
                description: Text(searchingWhere))

        case let .ready(query):
            ContentUnavailableView(
                "Search for \(query)", systemImage: "magnifyingglass",
                description: Text("Press return to search."))

        case .searching:
            // Not a `ContentUnavailableView`: nothing is unavailable yet, and
            // saying so would be answering a question that has not been asked.
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
                    ResultRow(
                        result: result,
                        identity: session.rooms.row(for: result.roomId)?.identity,
                        avatarURI: session.avatars.uri(for: result.roomId))
                }
                .buttonStyle(.plain)
                .task { await session.avatars.load(result.roomId) }
            }
        }
    }

    /// What the empty state promises, which has to match what will actually
    /// happen when the reader presses return.
    private var searchingWhere: String {
        guard let scope, narrowed else { return "Find a message across your rooms." }
        return "Find a message in \(scope.name)."
    }

    private func run() async {
        let query = term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        state = .searching(query)
        let results = await session.search(query, in: narrowed ? scope?.roomId : nil)
        state = results.isEmpty ? .empty(query) : .found(results)
    }
}

/// One hit: which room, when, and what it said.
///
/// The avatar is what places a hit at a glance. Without it every result is
/// three lines of grey text and the room name has to be read rather than
/// recognised.
private struct ResultRow: View {
    let result: SearchResultDto
    let identity: RoomIdentity?
    let avatarURI: String?

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                Circle().fill(.quaternary)
                if let avatarURI, let image = RoomRowView.image(from: avatarURI) {
                    image.resizable().scaledToFill().clipShape(Circle())
                } else {
                    Text(identity?.initial ?? "?").font(.caption)
                }
            }
            .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(identity?.name ?? result.roomId)
                        .metaFace()
                        .textCase(.uppercase)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    // When, so a hit can be placed. A result with no date is a
                    // fragment with no context.
                    Text(RelativeTime.label(for: result.timestampMs, now: .now))
                        .metaFace()
                        .foregroundStyle(.tertiary)
                }
                Text(result.body).font(.callout).lineLimit(2)
            }
        }
    }
}
