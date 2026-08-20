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
    @State private var showsAccount = false
    @State private var showsSearch = false
    /// The clock the header's state word is measured against. Refreshed when
    /// the room changes, which is when the reader is looking at it.
    @State private var now = Date()
    @State private var showsNewRoom = false

    /// The window's width, measured rather than inferred.
    @State private var width: CGFloat = 0

    /// Whether the info panel fits *beside* the conversation.
    ///
    /// `sizeClass == .regular` was the first answer and it is wrong on the
    /// device that exposed it. An iPad is a regular width class in both
    /// orientations, but three columns — roster, a readable timeline, and the
    /// panel — only fit in landscape. In portrait at 834 points the inspector
    /// was laid out at x=850.5: present in the accessibility tree, off the
    /// side of the screen, invisible.
    ///
    /// This took a while to see because it is orientation-dependent, so the
    /// same build passed and failed depending on how the simulator happened to
    /// be turned. Measuring is the only honest answer to "is there room".
    private var isWide: Bool { sizeClass == .regular && width >= Self.threeColumnWidth }

    /// Roster, a readable timeline, and a panel, none of them squeezed to
    /// uselessness. An iPad clears this in landscape and not in portrait,
    /// which matches where the panel actually fits.
    static let threeColumnWidth: CGFloat = 1_000

    var body: some View {
        // Measured here, at the split view, because this is the only place that
        // knows the window's width — a column reports its own.
        GeometryReader { geometry in
            splitView
                .onAppear { width = geometry.size.width }
                .onChange(of: geometry.size.width) { _, next in
                    width = next
                    // Rotating to portrait with the panel open would leave it
                    // laid out where it no longer fits.
                    if showsInfo, next < Self.threeColumnWidth { columns = .all }
                }
        }
    }

    private var splitView: some View {
        NavigationSplitView(columnVisibility: $columns) {
            RoomListView(session: session, clearsSelectionOnPop: !isWide)
                .safeAreaInset(edge: .top, spacing: 0) {
                    ConnectionBar(connection: session.connection)
                }
                .toolbar {
                    // The leading edge, where every messaging app puts the
                    // account — and where the way out has to live, since
                    // signing out had nowhere to be reached from at all.
                    ToolbarItem(placement: .topBarLeading) {
                        Button { showsAccount = true } label: {
                            Image(systemName: "person.crop.circle")
                        }
                        .accessibilityLabel("Account")
                    }
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
        .onChange(of: session.rooms.selectedId) { _, id in
            now = Date()
            showsInfo = false
            // Opening a room gets out of its way.
            //
            // Pinning the sidebar to `.all` is what stops the app launching on
            // an empty pane, but on a narrow iPad `.all` is an *overlay*: the
            // roster sits on top of the conversation, dimming it and taking
            // its taps — the room's own toolbar buttons stopped responding.
            // Once a room is chosen there is nothing left to choose, so the
            // roster steps aside. Where three columns fit it stays, because
            // there it sits beside the room rather than over it.
            if id != nil, !isWide { columns = .detailOnly }
        }

        .sheet(isPresented: $showsAccount) {
            AccountPanel(session: session) { showsAccount = false }
        }
        .sheet(isPresented: $showsSearch) {
            // Scoped to the open room when there is one. A reader who opens
            // search from inside a conversation is usually asking about that
            // conversation, and the segmented control is there for when they
            // are not.
            SearchPanel(
                session: session,
                scope: session.rooms.selectedRow.map {
                    SearchPanel.Scope(roomId: $0.room.id, name: $0.identity.name)
                },
                onOpen: { session.rooms.select($0) }
            ) {
                showsSearch = false
            }
        }
        .sheet(isPresented: $showsNewRoom) {
            NewRoomPanel(session: session, onOpen: { session.rooms.select($0) }) {
                showsNewRoom = false
            }
        }
    }

    /// Open the room-info panel.
    ///
    /// Both state changes in one update, deliberately. Collapsing the sidebar
    /// in a *reaction* to `showsInfo` is a race: the inspector can begin
    /// laying out before the column is free, and then it goes where there is
    /// no room — off the side of the screen at x=850.5, which is exactly the
    /// fault this was meant to fix. One state change, one layout.
    private func openInfo() {
        if isWide { columns = .detailOnly }
        showsInfo = true
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
        // The name is still the back button's title, but what is *drawn* at
        // the top is the two-line header below.
        .navigationTitle(row.identity.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                // On a console, whether the agent is alive belongs at the top
                // of the screen — a header that says only the name answers
                // the one question the reader already knew the answer to. And
                // once the header is a control, the ⓘ button beside it was a
                // second door to the same room.
                Button(action: openInfo) {
                    RoomHeader(
                        name: row.identity.name,
                        role: row.identity.role,
                        state: RosterArrangement.state(for: row, now: now))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(row.identity.name), about this room")
            }
        }
        // On an iPad the panel comes in from the trailing edge, beside the
        // conversation rather than over it. On a phone there is no room beside
        // anything, so it is a sheet.
        //
        // **The sidebar has to give up its column for this to work.** An
        // inspector takes a third column, and an iPad in portrait at 834
        // points has already spent its width on a pinned sidebar and a
        // readable timeline. Opening one anyway laid the panel out at x=850.5
        // — in the accessibility tree, off the side of the screen, invisible.
        // Collapsing the sidebar while the panel is open is what makes the
        // room, and it restores when the panel closes.
        .inspector(isPresented: Binding(get: { isWide && showsInfo }, set: { showsInfo = $0 })) {
            RoomInfoPanel(session: session, roomId: roomId) { showsInfo = false }
                .inspectorColumnWidth(min: 280, ideal: 340, max: 420)
        }
        .sheet(isPresented: Binding(get: { !isWide && showsInfo }, set: { showsInfo = $0 })) {
            RoomInfoPanel(session: session, roomId: roomId) { showsInfo = false }
                // Large by default. At the medium detent "Leave room" sat
                // below the fold, so the one destructive action in the app was
                // the one a reader had to go looking for — and a reader who
                // does not know it is there cannot know to drag.
                .presentationDetents([.large, .medium])
        }
        // Only the restore. Opening is done by the button above, in the same
        // update that presents the panel.
        .onChange(of: showsInfo) { _, open in
            guard isWide, !open else { return }
            columns = .all
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
                Text(label).metaFace()
                if let message = connection.message {
                    Text(message).metaFace().foregroundStyle(.secondary)
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

/// What a room says about itself at the top of the screen.
///
/// Two lines: what it is called, and what it is doing. The second line is the
/// reason this exists — on a console, "is it alive" is the question a reader
/// has on opening a room, and answering it meant opening a panel.
private struct RoomHeader: View {
    let name: String
    let role: String?
    let state: AgentState

    var body: some View {
        VStack(spacing: 0) {
            Text(name)
                .font(.headline)
                .lineLimit(1)
            HStack(spacing: 4) {
                Circle()
                    .fill(dotColour)
                    .frame(width: 5, height: 5)
                Text(subtitle)
                    .metaFace()
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: 260)
    }

    /// The role, when the room has one, and the state word either way — the
    /// role says what this agent is for, the state says whether it is there.
    private var subtitle: String {
        guard let role, !role.isEmpty else { return state.word }
        return "\(role) · \(state.word)"
    }

    /// The same vocabulary the roster's dot uses, so one room does not have
    /// two colours for one condition. Quiet draws nothing rather than a grey
    /// dot: absence is not a state worth a mark of its own.
    private var dotColour: Color {
        switch state {
        case .needsYou: return Theme.signal
        case .active: return Theme.accent
        case .idle: return Color.secondary.opacity(0.55)
        case .quiet: return .clear
        }
    }
}
