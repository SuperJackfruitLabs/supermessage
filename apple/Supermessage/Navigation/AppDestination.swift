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

/// The toolbar the Chats list carries: you at the leading edge, where every
/// messaging app puts the account, and compose — the one primary action — at
/// the trailing one.
///
/// Two buttons, each with one job. A third, the roster options, sat beside
/// compose and turned into an envelope when invitations were hidden: an
/// options menu and a status light in one button, reading as a second
/// messaging action (2026-09-24). Its arrangement moved to Account → Roster
/// and its invitations into the list.
struct ChatsToolbar: ToolbarContent {
    let session: Session
    let onAccount: () -> Void
    let onCompose: () -> Void

    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            AccountButton(session: session, action: onAccount)
        }
        ToolbarItem(placement: .topBarTrailing) {
            // Labelled: an icon-only control is announced as "button" and
            // nothing else.
            Button(action: onCompose) {
                Image(systemName: "square.and.pencil")
                    .font(.body.weight(.medium))
                    // The glyph's pencil overhangs its square up and to the
                    // right, so centred by its bounds it reads low-left in a
                    // round button. Half a point each way puts the square
                    // at the centre, which is what the eye measures.
                    .offset(x: -0.5, y: -0.5)
                    .frame(width: ToolbarGlyph.side, height: ToolbarGlyph.side)
            }
            .accessibilityLabel("New conversation")
        }
    }
}

/// One square for both toolbar items, so the glass around each is the same
/// circle rather than a capsule sized to whatever the glyph measured.
private enum ToolbarGlyph {
    static let side: CGFloat = 30
}

/// Your initial, in a circle: which account this is, and the way to it.
///
/// A generic person glyph said "an account" rather than "you"; Apple's apps,
/// WhatsApp and Slack all show the person signed in.
private struct AccountButton: View {
    let session: Session
    let action: () -> Void
    @State private var userId: String?

    var body: some View {
        Button(action: action) {
            ZStack {
                // On iOS 26 the bar puts every item in its own glass circle,
                // and a second, smaller circle inside it read as a ring off
                // centre (2026-09-24). There the glass is the disc; before
                // it, there is no glass and the disc is ours.
                if #available(iOS 26, *) {
                } else {
                    Circle().fill(Theme.accent.opacity(0.18))
                }
                Text(AccountLabel.initial(of: userId))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.accent)
            }
            .frame(width: ToolbarGlyph.side, height: ToolbarGlyph.side)
        }
        .accessibilityLabel("Account")
        .task { userId = await session.account()?.userId }
    }
}

