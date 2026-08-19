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
    /// The sidebar starts visible.
    ///
    /// `NavigationSplitView` defaults to `.automatic`, which on an iPad in
    /// portrait hides the sidebar — so the app opened on an empty detail pane
    /// with the roster behind a toggle nobody had reason to look for. On
    /// iPhone this is ignored, because a collapsed split view is a stack and
    /// the roster is the stack's root.
    @State private var columns: NavigationSplitViewVisibility = .all
    @State private var showsInfo = false
    @State private var showsSearch = false
    @State private var showsNewRoom = false

    private var isWide: Bool { sizeClass == .regular }

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
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
        // The info panel describes one room, and it is presented from state
        // that outlives a room switch: leave it up while the detail pane moves
        // on and it asks about the room the reader just left, under the new
        // room's header. The core no longer refuses that question — it is a
        // read about a named room — so nothing would report the mismatch any
        // more. Closing it is the fix; a panel about a room you are no longer
        // in has nothing to say.
        .onChange(of: session.rooms.selectedId) { _, _ in showsInfo = false }

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
        // A sheet on every size, deliberately — **not** an `.inspector`.
        //
        // The inspector was the design (read a room's members beside the
        // conversation rather than on top of it) and it does not currently
        // work here. On an iPad in portrait with the sidebar pinned open, the
        // panel is laid out past the right edge of the window: measured at
        // x=850.5 on an 834-point screen, present in the accessibility tree,
        // invisible to the reader. Sidebar plus a readable timeline already
        // spend the width, and there is no third column left to take.
        //
        // Gating it on measured width was tried and did not hold, so rather
        // than ship a panel that is sometimes invisible, this is a sheet
        // everywhere. `roomId` is the detail pane's own parameter, so it
        // always describes the room on screen. Restoring the inspector for
        // genuinely wide windows is worth doing — as a change with a test
        // that asserts the panel has area on screen, which is the assertion
        // that caught this in the first place.
        .sheet(isPresented: $showsInfo) {
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
        case .error: return "reconnecting…"
        case let .unknown(raw): return raw
        }
    }
}
