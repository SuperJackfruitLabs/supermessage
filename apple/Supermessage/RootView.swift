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
    }
}

/// Everything behind a session. The room list and timeline land here next.
struct SignedInView: View {
    let session: Session

    var body: some View {
        VStack(spacing: 16) {
            Text("Signed in")
                .font(.system(.title2, design: .serif))
            ConnectionBar(connection: session.connection)
            Button("Sign out") {
                Task { await session.signOut() }
            }
        }
        .padding()
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
            .padding(.vertical, 4)
            .padding(.horizontal, 10)
            .background(.thinMaterial, in: Capsule())
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
