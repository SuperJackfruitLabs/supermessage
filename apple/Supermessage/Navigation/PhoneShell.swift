import SupermessageKit
import SwiftUI

/// The phone: a tab bar over four stacks.
///
/// **Each tab owns the room it opened.** The inbox pushing a room onto the
/// Chats stack would mean Back from a decision lands on the roster rather than
/// on the inbox the reader was working through. So every tab has its own
/// `open` state, and `RoomScreen` makes whichever room is on screen the one
/// the stores follow.
struct PhoneShell: View {
    let session: Session
    @Binding var showsAccount: Bool

    @State private var tab = AppDestination.chats
    @State private var chatsOpen: String?
    @State private var inboxOpen: String?
    @State private var agentsOpen: String?
    @State private var searchOpen: String?
    @State private var showsNewRoom = false
    @State private var now = Date()

    private var inboxCount: Int { NeedsYouInbox.from(session.rooms.rooms, now: now).badgeCount }

    var body: some View {
        TabView(selection: $tab) {
            Tab(AppDestination.chats.title, systemImage: AppDestination.chats.systemImage, value: .chats) {
                NavigationStack {
                    RoomListView(session: session, selection: $chatsOpen)
                        .safeAreaInset(edge: .top, spacing: 0) {
                            ConnectionBar(connection: session.connection)
                        }
                        .toolbar {
                            ChatsToolbar(
                                session: session,
                                onAccount: { showsAccount = true },
                                onCompose: { showsNewRoom = true })
                        }
                        .roomDestination(session: session, item: $chatsOpen)
                }
            }

            Tab(
                AppDestination.needsYou.title, systemImage: AppDestination.needsYou.systemImage,
                value: .needsYou
            ) {
                NavigationStack {
                    NeedsYouView(session: session, selection: $inboxOpen)
                        .roomDestination(session: session, item: $inboxOpen)
                }
            }
            // The count that makes the inbox finishable: it goes to nothing.
            .badge(inboxCount)

            Tab(
                AppDestination.agents.title, systemImage: AppDestination.agents.systemImage,
                value: .agents
            ) {
                NavigationStack {
                    AgentsView(session: session, selection: $agentsOpen)
                        .roomDestination(session: session, item: $agentsOpen)
                }
            }

            // The search role: on iOS 18 the system gives it its place at the
            // trailing end of the bar and its own label.
            Tab(value: AppDestination.search, role: .search) {
                NavigationStack {
                    SearchPanel(session: session, onOpen: { searchOpen = $0 })
                        .roomDestination(session: session, item: $searchOpen)
                }
            }
        }
        .tint(Theme.accent)
        // A room asked for from outside — a notification, a widget, a Live
        // Activity or a `supermessage://` link. Always opened in Chats, so
        // Back lands somewhere that lists it. `initial: true` so a request
        // made before sign-in is still honoured once the shell exists.
        .onChange(of: NotificationRouter.shared.pendingRoomId, initial: true) { _, id in
            guard id != nil, let room = NotificationRouter.shared.consume() else { return }
            tab = .chats
            chatsOpen = room
        }
        .onOpenURL { AppLinks.handle($0) }
        .task(id: session.rooms.rooms.count) { now = Date() }
        .sensoryFeedback(.selection, trigger: tab)
        .sheet(isPresented: $showsNewRoom) {
            NewRoomPanel(
                session: session,
                onOpen: { roomId in
                    tab = .chats
                    chatsOpen = roomId
                }
            ) { showsNewRoom = false }
            .paletteSheet()
        }
    }
}
