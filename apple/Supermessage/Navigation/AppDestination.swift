import SupermessageKit
import SwiftUI

/// The app's four places: the same four on a phone's tab bar and an iPad's
/// sidebar.
enum AppDestination: String, CaseIterable, Hashable {
    case chats
    case needsYou
    case agents
    case search

    var title: String {
        switch self {
        case .chats: return "Chats"
        case .needsYou: return "Needs you"
        case .agents: return "Agents"
        case .search: return "Search"
        }
    }

    var systemImage: String {
        switch self {
        case .chats: return "bubble.left.and.bubble.right"
        case .needsYou: return "tray"
        case .agents: return "sparkles"
        case .search: return "magnifyingglass"
        }
    }
}

extension View {
    /// Push a room when `item` names one, and pop back when it is cleared.
    ///
    /// Each phone tab owns its own `item`, so a room opened from the inbox is
    /// the inbox's and going back returns there.
    func roomDestination(session: Session, item: Binding<String?>) -> some View {
        navigationDestination(item: item) { roomId in
            RoomScreen(session: session, roomId: roomId)
                // A conversation is a place, not a tab: the bar goes, as it
                // does in every messenger. On a 13 mini it cost the room
                // about 80pt, stacked right under the composer. Back returns
                // to the list, where the tabs are.
                .toolbar(.hidden, for: .tabBar)
        }
    }
}

/// The toolbar the Chats list carries: the account at the leading edge, where
/// every messaging app puts it, and compose at the trailing one.
struct ChatsToolbar: ToolbarContent {
    let onAccount: () -> Void
    let onCompose: () -> Void

    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button(action: onAccount) { Image(systemName: "person.crop.circle") }
                .accessibilityLabel("Account")
        }
        ToolbarItem(placement: .topBarTrailing) {
            // Labelled: an icon-only control is announced as "button" and
            // nothing else.
            Button(action: onCompose) { Image(systemName: "square.and.pencil") }
                .accessibilityLabel("New conversation")
        }
    }
}
