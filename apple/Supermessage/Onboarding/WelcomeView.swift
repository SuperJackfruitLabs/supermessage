import SupermessageKit
import SwiftUI

/// The keys the first run is remembered by.
///
/// `@AppStorage` rather than anything in the core: these are about this
/// install's first minutes, not about the account, and a second device should
/// get its own welcome.
enum FirstRun {
    /// The welcome screen has been passed, so a signed-out app opens on the
    /// sign-in form.
    static let welcomeSeenKey = "onboarding.welcomeSeen"
    /// An interactive sign-in has just succeeded and the demo has not run.
    static let demoPendingKey = "onboarding.demoPending"
    /// The demo has been shown — finished or skipped. Shown once, ever.
    static let demoSeenKey = "onboarding.demoSeen"
}

/// Signed out: the welcome on a first run, the sign-in form after it.
struct SignedOutView: View {
    let session: Session

    @AppStorage(FirstRun.welcomeSeenKey) private var welcomeSeen = false

    var body: some View {
        if welcomeSeen {
            LoginView(session: session)
        } else {
            WelcomeView { welcomeSeen = true }
        }
    }
}

/// The first screen anyone sees: the mark, what this is for, and the way in.
struct WelcomeView: View {
    let onSignIn: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var arrived = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)
            Image("Mark")
                .resizable()
                .scaledToFit()
                .frame(width: 132)
                .accessibilityLabel("supermessage")
                .scaleEffect(arrived || reduceMotion ? 1 : 0.9)
                .opacity(arrived || reduceMotion ? 1 : 0)
            Text("supermessage")
                .font(.largeTitle.weight(.bold))
                .padding(.top, 20)
            Text("Chat with your team and your agents — and approve their work without leaving the conversation.")
                .font(.body)
                .foregroundStyle(Theme.contentMuted)
                .multilineTextAlignment(.center)
                .padding(.top, 10)
                .padding(.horizontal, 8)
            Spacer(minLength: 24)
            Button(action: onSignIn) {
                Text("Sign in")
                    .font(.headline)
                    // Set, not inherited: the ancestor's `content` colour was
                    // winning, and dark on accent read as disabled.
                    .foregroundStyle(Theme.accentContent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.accent)
            .controlSize(.large)
        }
        .padding(28)
        .frame(maxWidth: 460)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.surface.ignoresSafeArea())
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.spring(duration: 0.6, bounce: 0.3)) { arrived = true }
        }
    }
}

#if DEBUG
#Preview("Welcome") {
    WelcomeView {}
        .previewChrome()
}

#Preview("Welcome, dark") {
    WelcomeView {}
        .preferredColorScheme(.dark)
        .previewChrome()
}
#endif
