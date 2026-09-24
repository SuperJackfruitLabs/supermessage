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
    @State private var session: Session
    @Environment(\.scenePhase) private var scenePhase

    /// The session this app runs on, injectable only so that this view can be
    /// previewed at all.
    ///
    /// It was `@State private var session = Session()`, which builds a
    /// `CoreClient` and therefore a `Core` — so the one screen that decides
    /// which of the three phases a reader lands in could not be looked at
    /// outside a signed-in build. The default keeps `SupermessageApp`'s call
    /// site unchanged and keeps the real app's behaviour identical.
    init(session: Session = Session()) {
        _session = State(initialValue: session)
    }

    var body: some View {
        Group {
            switch session.phase {
            case .starting:
                ProgressView()
            case .signedOut:
                SignedOutView(session: session)
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
        // Every control in the app, in the palette's accent.
        //
        // Without this a `Button` takes SwiftUI's default tint, which is the
        // system blue — so `RoomInfoPanel`'s `Done` rendered #007AFF while
        // `design/tokens.toml` says the accent is #5b43d4. Nothing set a tint
        // anywhere, so all 30 Button and ToolbarItem sites were Apple's blue.
        //
        // **`PreviewGround` sets the same tint, and the two must agree.** A
        // preview renders a panel directly rather than through this view, so
        // without it there the catalogue would show a colour the app does
        // not have — which is the exact failure the fixture work spent a day
        // on.
        .tint(Theme.accent)
        // And every piece of text that does not say otherwise.
        //
        // The 51 explicit `foregroundStyle` sites are only half of it: text
        // with no style at all takes SwiftUI's label colour, which is Apple's
        // near-black and not `content` #221c38. Setting it here means the
        // palette owns the page rather than its exceptions — which is what
        // `docs/p6-palette-audit.md` found was backwards.
        .foregroundStyle(Theme.content)
    }
}

/// Everything behind a session.
///
/// Two shells over the same screens, chosen by the width class:
///
/// - **A phone** gets a tab bar — Chats, Needs you, Agents, Search — each tab
///   its own navigation stack, so a room opened from the inbox returns to the
///   inbox. See `PhoneShell`.
/// - **An iPad** keeps the split view: the sidebar gains the same four
///   destinations above its list, the detail column is the open room, and
///   room info slides in as an inspector where it fits. See `SplitShell`.
///
/// What both share lives here: the account sheet, the recovery key shown at
/// first sign-in, and the first-run demo.
struct SignedInView: View {
    let session: Session

    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var showsAccount = false
    /// Set by `LoginView` on an interactive sign-in, never by a restore — so
    /// the demo meets someone who has just signed in, not someone who opened
    /// the app they use every day.
    @AppStorage(FirstRun.demoPendingKey) private var demoPending = false
    @AppStorage(FirstRun.demoSeenKey) private var demoSeen = false

    var body: some View {
        Group {
            if sizeClass == .compact {
                PhoneShell(session: session, showsAccount: $showsAccount)
            } else {
                SplitShell(session: session, showsAccount: $showsAccount)
            }
        }
        .sheet(isPresented: $showsAccount) {
            AccountPanel(session: session) { showsAccount = false }
                .paletteSheet()
        }
        // The key generated at sign-in, shown where the reader already is.
        //
        // Presented from here rather than from the account panel because the
        // reader has not gone looking for it — it arrives a moment after the
        // first sign-in, and the alternative was an account whose backup could
        // never be opened by anyone, silently.
        .recoveryKeySheet(session: session)
        // The demo waits for the key: two things arriving at once on the
        // first screen is one too many, and the key is the one that matters.
        .fullScreenCover(
            isPresented: Binding(
                get: { demoPending && !demoSeen && session.newRecoveryKey == nil },
                set: { if !$0 { finishDemo() } })
        ) {
            FirstRunDemoView(onFinish: finishDemo)
                .recoveryKeySheet(session: session)
        }
    }

    private func finishDemo() {
        demoSeen = true
        demoPending = false
    }
}

extension View {
    /// The first-sign-in recovery key. Applied wherever the reader might be
    /// when it arrives — the shell, or the demo covering it — because only
    /// the topmost presentation can show a sheet.
    func recoveryKeySheet(session: Session) -> some View {
        sheet(
            isPresented: Binding(
                get: { session.newRecoveryKey != nil },
                set: { if !$0 { session.clearNewRecoveryKey() } })
        ) {
            if let key = session.newRecoveryKey {
                NewRecoveryKeyView(key: key) { session.clearNewRecoveryKey() }
                    .paletteSheet()
            }
        }
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
                    Text(message).metaFace().foregroundStyle(Theme.contentMuted)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
            .background(Theme.surfaceSunken)
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

#if DEBUG
// The whole app, signed in, at a phone's width.
//
// This is the frame that shows whether the pieces agree: the roster's state
// words beside the timeline's attribution, the connection bar's intrusion,
// the header's own state word against the row's. Each of those has a preview
// of its own; none of them shows the composition.
#Preview("Signed in") {
    RootView(session: PreviewFixtures.session())
}

// A signed-in session whose sync has failed.
//
// `ConnectionBar` is shown only when the state is `isWorthShowing`, so in
// every other preview here it is absent. The failure state carries a message
// from the core, and the question is whether a long one pushes the roster
// down or truncates.
#Preview("Sync failed") {
    RootView(session: PreviewFixtures.session(connection: "error"))
}

// Signed out: the welcome on a first run, `LoginView` after it.
#Preview("Signed out") {
    RootView(session: PreviewFixtures.session(phase: .signedOut))
}

// A furnished account with an empty roster — a new sign-in that has synced
// and genuinely has nothing yet.
//
// Not a rare state: it is the first thing every new reader sees, and the one
// most likely to have been drawn once and never looked at again.
#Preview("Nothing yet") {
    RootView(session: PreviewFixtures.session(.empty))
}

// `.starting`, which is a `ProgressView` — and a frame that is genuinely hard
// to catch.
//
// Even here it is transient: `RootView`'s own `.task` sees `.starting` and
// calls `start()`, the stub answers `false` immediately, and the phase moves
// to `.signedOut`. So this preview shows the cold-launch frame for about as
// long as the real app does, which is honest but not much use for looking at
// it. Worth having anyway, because it is the one preview that exercises the
// phase transition rather than a phase.
#Preview("Starting") {
    RootView(session: PreviewFixtures.session(phase: .starting))
}

// The iPad arrangement, where the roster sits beside the timeline and room
// info slides in as an inspector rather than covering it.
//
// `SignedInView`'s sidebar visibility is `.all` rather than `.automatic`
// precisely because the automatic default hid the roster on an iPad in
// portrait — the app opened on an empty detail pane with the roster behind a
// toggle nobody had reason to look for. This is where that would be caught.
#Preview("iPad", traits: .fixedLayout(width: 1024, height: 768)) {
    SignedInView(session: PreviewFixtures.session())
}

// The phone's tab bar over a fleet, in dark — the frame D9 was about: every
// ground the palette's, none of them #000.
#Preview("Tabs, dark") {
    SignedInView(session: NavigationRevampFixtures.fleetSession())
        .preferredColorScheme(.dark)
}

// The iPad sidebar with the four destinations above the roster.
#Preview("iPad fleet", traits: .fixedLayout(width: 1180, height: 820)) {
    SignedInView(session: NavigationRevampFixtures.fleetSession())
}
#endif
