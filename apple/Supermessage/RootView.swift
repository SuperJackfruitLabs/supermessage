import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Splash → login → the app.
///
/// The splash is not decoration: `Session.start()` asks the core whether a
/// stored session restores, which is a keychain read and a client build, and
/// showing the login form during it would flash a sign-in screen at someone
/// who is already signed in.
struct RootView: View {
    @State private var session = Session()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch session.phase {
            case .starting:
                ProgressView()
            case .signedOut:
                LoginView(session: session)
            case .signedIn:
                SignedInView(session: session)
            }
        }
        .task {
            guard session.phase == .starting else { return }
            await session.start()
        }
        .onChange(of: scenePhase) { _, phase in
            Task { await session.scenePhaseChanged(to: phase == .active) }
        }
    }
}

/// Everything behind a session.
///
/// One `NavigationSplitView` serves both size classes — it collapses to a push
/// stack on iPhone by itself, so there is no branch on width here. On iPad the
/// sidebar keeps the room list beside the timeline and room info slides in as
/// an `.inspector` rather than covering it; on iPhone that same panel is a
/// sheet, which is what the environment's size class decides below.
struct SignedInView: View {
    let session: Session

    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var showsInfo = false
    @State private var showsSearch = false
    @State private var showsNewRoom = false

    private var isWide: Bool { sizeClass == .regular }

    var body: some View {
        NavigationSplitView {
            RoomListView(session: session)
                .safeAreaInset(edge: .top, spacing: 0) {
                    ConnectionBar(connection: session.connection)
                }
                .toolbar {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button { showsSearch = true } label: { Image(systemName: "magnifyingglass") }
                        Button { showsNewRoom = true } label: { Image(systemName: "square.and.pencil") }
                    }
                }
        } detail: {
            detail
        }
        .sheet(isPresented: $showsSearch) {
            SearchPanel(session: session, onOpen: { session.rooms.select($0) }) {
                showsSearch = false
            }
        }
        .sheet(isPresented: $showsNewRoom) {
            NewRoomPanel(session: session, onOpen: { session.rooms.select($0) }) {
                showsNewRoom = false
            }
        }
    }

    @ViewBuilder private var detail: some View {
        if let roomId = session.rooms.selectedId, let row = session.rooms.selectedRow {
            room(roomId: roomId, row: row)
        } else if let roomId = session.rooms.selectedId, let name = session.rooms.selectedName {
            // The room left the roster — a space switch — but the reader is
            // still in it. Keep showing it rather than blanking the pane.
            timeline(roomId: roomId, name: name)
        } else {
            ContentUnavailableView(
                "No room open", systemImage: "bubble.left.and.bubble.right",
                description: Text("Choose a room to read it."))
        }
    }

    @ViewBuilder private func room(roomId: String, row: RoomRow) -> some View {
        Group {
            if row.affordance == .respondToInvitation {
                // An invited room has no readable history, so there is nothing
                // to page through and no composer to offer.
                InvitationEmptyTimeline()
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        InvitationView(
                            session: session, roomId: roomId, roomName: row.identity.name)
                    }
            } else {
                timeline(roomId: roomId, name: row.identity.name)
            }
        }
        .navigationTitle(row.identity.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showsInfo = true } label: { Image(systemName: "info.circle") }
            }
        }
        // On iPad the panel slides in beside the timeline rather than over it,
        // because there is room and covering a conversation to read its member
        // list is a phone compromise, not a design.
        .inspector(isPresented: Binding(get: { isWide && showsInfo }, set: { showsInfo = $0 })) {
            RoomInfoPanel(session: session, roomId: roomId) { showsInfo = false }
        }
        .sheet(isPresented: Binding(get: { !isWide && showsInfo }, set: { showsInfo = $0 })) {
            RoomInfoPanel(session: session, roomId: roomId) { showsInfo = false }
                .presentationDetents([.medium, .large])
        }
    }

    private func timeline(roomId: String, name: String) -> some View {
        TimelineView(session: session, timeline: session.timeline)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                ComposerView(session: session, roomId: roomId)
            }
            .task(id: roomId) { await session.open(roomId: roomId) }
    }
}

/// A slim line when the core is not live, and nothing when it is.
///
/// Never amber. Amber means the operator owes someone an answer, and a flaky
/// connection is not that.
struct ConnectionBar: View {
    let connection: ConnectionStore

    var body: some View {
        if connection.isWorthShowing {
            HStack(spacing: 6) {
                Text(label).font(Theme.meta)
                if let message = connection.message {
                    Text(message).font(Theme.meta).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
            .background(.bar)
        }
    }

    private var label: String {
        switch connection.state {
        case .live: return "live"
        case .connecting: return "connecting"
        case .offline: return "offline"
        case let .unknown(raw): return raw
        }
    }
}
