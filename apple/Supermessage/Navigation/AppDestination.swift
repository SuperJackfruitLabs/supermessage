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
        account
        ToolbarItem(placement: .topBarTrailing) {
            // Labelled: an icon-only control is announced as "button" and
            // nothing else.
            //
            // The stock symbol, unstyled: the bar sizes, centres and tints
            // it inside its own glass. An earlier frame, weight and
            // half-point nudge were compensating for a circle the bar now
            // draws itself (HIG, Toolbars: "Prefer system-provided symbols
            // without borders").
            Button(action: onCompose) { Image(systemName: "square.and.pencil") }
            .accessibilityLabel("New conversation")
        }
    }

    /// You, at the leading edge.
    ///
    /// **On iOS 26 the bar's own glass is hidden here** and the button draws
    /// its own glass circle. The bar sizes its glass to the label, and it
    /// gives text more room across than down — so an initial came out a pill
    /// beside compose's circle (build 20, 2026-09-24). An icon gets a circle;
    /// a monogram is not an icon. Apple's own iOS 26 apps take the account
    /// out of the shared glass the same way.
    ///
    /// Guarded by the compiler as well as the OS: the modifier is iOS 26
    /// SDK, and this machine's Xcode 16.4 has only 18.5's. CI builds with 26.
    @ToolbarContentBuilder private var account: some ToolbarContent {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            ToolbarItem(placement: .topBarLeading) {
                AccountButton(session: session, action: onAccount)
            }
            .sharedBackgroundVisibility(.hidden)
        } else {
            ToolbarItem(placement: .topBarLeading) {
                AccountButton(session: session, action: onAccount)
            }
        }
        #else
        ToolbarItem(placement: .topBarLeading) {
            AccountButton(session: session, action: onAccount)
        }
        #endif
    }
}

/// The square the compose glyph sits in, and the pre-26 account disc.
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
        Button(action: action) { label }
            .buttonStyle(.plain)
        .accessibilityLabel("Account")
        .task { userId = await session.account()?.userId }
    }

    private var initial: some View {
        Text(AccountLabel.initial(of: userId))
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Theme.accent)
    }

    /// iOS 26: a glass circle of the bar's own size (44pt), since the bar's
    /// glass is hidden for this item — see `ChatsToolbar.account`. Before
    /// it: the tinted disc, as there is no glass to match.
    @ViewBuilder private var label: some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            initial
                .frame(width: 44, height: 44)
                .glassEffect(.regular.interactive(), in: .circle)
                .contentShape(Circle())
        } else {
            disc
        }
        #else
        disc
        #endif
    }

    private var disc: some View {
        ZStack {
            Circle().fill(Theme.accent.opacity(0.18))
            initial
        }
        .frame(width: ToolbarGlyph.side, height: ToolbarGlyph.side)
        .contentShape(Circle())
    }
}

