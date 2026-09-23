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
    /// Close the panel. `nil` when search is a destination of its own — the
    /// Search tab, or the iPad sidebar's — rather than a sheet: there is
    /// nothing to cancel back to, and the caller owns the navigation stack.
    let onClose: (() -> Void)?

    init(
        session: Session, scope: Scope? = nil, onOpen: @escaping (String) -> Void,
        onClose: (() -> Void)? = nil
    ) {
        self.session = session
        self.scope = scope
        self.onOpen = onOpen
        self.onClose = onClose
    }

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
        if onClose == nil {
            // A destination: the caller's stack is the one this sits in.
            content
        } else {
            NavigationStack { content }
        }
    }

    private var content: some View {
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
            .background(Theme.surface)
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            // Cancel, not Done: nothing here is being composed, and the only
            // thing this button does is abandon the search.
            .toolbar {
                if let onClose {
                    ToolbarItem(placement: .topBarTrailing) { Button("Cancel", action: onClose) }
                }
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
                Text("Searching…").metaFace().foregroundStyle(Theme.contentMuted)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

        case let .empty(query):
            ContentUnavailableView.search(text: query)

        case let .failed(_, message):
            // Distinct from `.empty` on purpose: this is the state that did
            // not exist before this task, and its absence was the whole
            // defect — a refused search read as "no results" instead of as a
            // refusal.
            ContentUnavailableView(
                "Couldn't search", systemImage: "exclamationmark.triangle",
                description: Text(message))

        case let .found(results):
            List(results, id: \.eventId) { result in
                Button {
                    onOpen(result.roomId)
                    onClose?()
                } label: {
                    ResultRow(
                        result: result,
                        identity: session.rooms.row(for: result.roomId)?.identity,
                        avatarURI: session.avatars.uri(for: result.roomId))
                }
                .buttonStyle(.plain)
                .task { await session.avatars.load(result.roomId) }
                .listRowBackground(Theme.surface)
            }
            .paletteListGround()
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
        do {
            let results = try await session.search(query, in: narrowed ? scope?.roomId : nil)
            state = results.isEmpty ? .empty(query) : .found(results)
        } catch let error as FfiError {
            state = .failed(query: query, message: ErrorPresenter.message(for: error))
        } catch {
            state = .failed(query: query, message: "Couldn't search.")
        }
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
                Circle().fill(Theme.surfaceRaised)
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
                        .foregroundStyle(Theme.contentMuted)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    // When, so a hit can be placed. A result with no date is a
                    // fragment with no context.
                    Text(RelativeTime.label(for: result.timestampMs, now: .now))
                        .metaFace()
                        .foregroundStyle(Theme.contentFaint)
                }
                Text(result.body).font(.callout).lineLimit(2)
            }
        }
    }
}

#if DEBUG
// Opened from inside a room, so it has a scope to offer and starts narrowed.
//
// Nothing has been typed yet: the panel's idle state, which is what a reader
// sees for as long as it takes them to think of a word.
#Preview("Scoped, idle") {
    SearchPanel(
        session: PreviewFixtures.session(),
        // `identity.name`, which is what RootView passes: no glyph — that
        // lives in the avatar — and no role either.
        scope: .init(roomId: PreviewFixtures.roomId, name: "Atlas"),
        onOpen: { _ in }, onClose: {})
        .previewChrome()
}

// Opened from nowhere in particular.
//
// `scope` is `nil`, so the segmented control is absent entirely — a
// segmented control with one option is a label wearing a control's clothes.
// This preview is the one that shows the layout without it.
#Preview("Unscoped") {
    SearchPanel(session: PreviewFixtures.session(), onOpen: { _ in }, onClose: {})
        .previewChrome()
}
#endif
