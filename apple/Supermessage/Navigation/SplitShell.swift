import SupermessageKit
import SwiftUI

/// The iPad: a sidebar beside the open room.
///
/// The sidebar carries the phone's four destinations above whichever list is
/// chosen, so the two devices have the same places; the detail column is the
/// room `RoomsStore.selectedId` names, from whichever destination chose it.
/// Room info slides in as an `.inspector` beside the conversation where there
/// is room for three columns, and is a sheet where there is not.
struct SplitShell: View {
    let session: Session
    @Binding var showsAccount: Bool

    /// The sidebar starts visible.
    ///
    /// `NavigationSplitView` defaults to `.automatic`, which on an iPad in
    /// portrait hides the sidebar — so the app opened on an empty detail pane
    /// with the roster behind a toggle nobody had reason to look for.
    @State private var columns: NavigationSplitViewVisibility = .all
    @State private var destination = AppDestination.chats
    @State private var showsNewRoom = false
    @State private var now = Date()

    /// The window's width, measured rather than inferred.
    @State private var width: CGFloat = 0

    @Environment(\.horizontalSizeClass) private var sizeClass

    /// Whether the info panel fits *beside* the conversation.
    ///
    /// `sizeClass == .regular` was the first answer and it is wrong on the
    /// device that exposed it. An iPad is a regular width class in both
    /// orientations, but three columns — roster, a readable timeline, and the
    /// panel — only fit in landscape. In portrait at 834 points the inspector
    /// was laid out at x=850.5: present in the accessibility tree, off the
    /// side of the screen, invisible. Measuring is the only honest answer to
    /// "is there room".
    private var isWide: Bool { sizeClass == .regular && width >= Self.threeColumnWidth }

    /// Roster, a readable timeline, and a panel, none of them squeezed to
    /// uselessness. An iPad clears this in landscape and not in portrait.
    static let threeColumnWidth: CGFloat = 1_000

    private var inboxCount: Int { NeedsYouInbox.from(session.rooms.rooms, now: now).badgeCount }

    /// The detail column's room, in both directions.
    ///
    /// A `nil` from a list is ignored: in a split view that does not collapse
    /// there is no "pop back to the roster", and obeying one closed the room
    /// the reader was in.
    private var selection: Binding<String?> {
        Binding(
            get: { session.rooms.selectedId },
            set: { next in if let id = next { session.rooms.select(id) } })
    }

    var body: some View {
        // Measured here, at the split view, because this is the only place that
        // knows the window's width — a column reports its own.
        GeometryReader { geometry in
            splitView
                .onAppear { width = geometry.size.width }
                .onChange(of: geometry.size.width) { _, next in width = next }
        }
    }

    private var splitView: some View {
        NavigationSplitView(columnVisibility: $columns) {
            sidebar
                .safeAreaInset(edge: .top, spacing: 0) {
                    // The connection now shows under the Chats title
                    // (`SpaceMenu`), on the phone and here alike.
                    VStack(spacing: 0) {
                        SidebarDestinations(selection: $destination, inboxCount: inboxCount)
                    }
                    .background(Theme.surface)
                }
                .toolbar {
                    ChatsToolbar(
                        session: session,
                        onAccount: { showsAccount = true },
                        onCompose: { showsNewRoom = true })
                }
        } detail: {
            detail
        }
        .task(id: session.rooms.rooms.count) { now = Date() }
        // A room asked for from a notification, widget or link.
        .onChange(of: NotificationRouter.shared.pendingRoomId, initial: true) { _, id in
            guard id != nil, let room = NotificationRouter.shared.consume() else { return }
            session.rooms.select(room)
        }
        .onOpenURL { AppLinks.handle($0) }
        .onChange(of: session.rooms.selectedId) { _, id in
            // Opening a room gets out of its way.
            //
            // Pinning the sidebar to `.all` is what stops the app launching on
            // an empty pane, but on a narrow iPad `.all` is an *overlay*: the
            // roster sits on top of the conversation, dimming it and taking
            // its taps. Once a room is chosen there is nothing left to choose,
            // so the roster steps aside. Where three columns fit it stays.
            if id != nil, !isWide { columns = .detailOnly }
        }
        .sheet(isPresented: $showsNewRoom) {
            NewRoomPanel(session: session, onOpen: { session.rooms.select($0) }) {
                showsNewRoom = false
            }
            .paletteSheet()
        }
    }

    @ViewBuilder private var sidebar: some View {
        switch destination {
        case .chats:
            RoomListView(session: session, selection: selection)
        case .needsYou:
            NeedsYouView(session: session, selection: selection)
        case .agents:
            AgentsView(session: session, selection: selection)
        case .search:
            SearchPanel(
                session: session,
                scope: session.rooms.selectedRow.map {
                    SearchPanel.Scope(roomId: $0.room.id, name: $0.identity.name)
                },
                onOpen: { session.rooms.select($0) })
        }
    }

    @ViewBuilder private var detail: some View {
        if let roomId = session.rooms.selectedId {
            RoomScreen(
                session: session, roomId: roomId, isWide: isWide,
                // Both state changes in one update, deliberately. Collapsing
                // the sidebar in a *reaction* to the panel opening is a race:
                // the inspector can begin laying out before the column is
                // free, and then it goes where there is no room.
                onInfoOpening: { if isWide { columns = .detailOnly } },
                onInfoClosed: { if isWide { columns = .all } })
        } else {
            ContentUnavailableView(
                "No room open", systemImage: "bubble.left.and.bubble.right",
                description: Text("Choose a room to read it."))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.surface)
        }
    }
}

/// The four destinations, above the sidebar's list.
///
/// A row of labelled buttons rather than a segmented control: four segments
/// in a 288pt column truncate "Needs you", and the inbox's count needs
/// somewhere to go.
struct SidebarDestinations: View {
    @Binding var selection: AppDestination
    let inboxCount: Int

    var body: some View {
        HStack(spacing: 4) {
            ForEach(AppDestination.allCases, id: \.self) { place in
                let selected = selection == place
                Button {
                    selection = place
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: place.systemImage)
                            .font(.system(size: 17, weight: selected ? .semibold : .regular))
                            .overlay(alignment: .topTrailing) {
                                if place == .needsYou, inboxCount > 0 {
                                    Text("\(inboxCount)")
                                        .font(.caption2.weight(.semibold))
                                        .monospacedDigit()
                                        .foregroundStyle(Theme.accentContent)
                                        .padding(.horizontal, 4)
                                        .background(Theme.accent, in: Capsule())
                                        .offset(x: 10, y: -6)
                                }
                            }
                        Text(place.title)
                            .font(.caption2)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(selected ? Theme.accent : Theme.contentMuted)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .background(selected ? Theme.accentSoft : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    place == .needsYou && inboxCount > 0 ? "\(place.title), \(inboxCount)" : place.title)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .sensoryFeedback(.selection, trigger: selection)
    }
}
