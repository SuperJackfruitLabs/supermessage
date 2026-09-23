import SupermessageKit
import SwiftUI

/// Sign-in.
///
/// Password only, because `m.login.password` is the only flow
/// `id.agentpod.dev` advertises — both `/_matrix/client/v1/auth_metadata` and
/// the MSC2965 unstable path return 404. OIDC is the intended target and needs
/// matrix-authentication-service deployed first.
struct LoginView: View {
    let session: Session

    /// Remembered between attempts.
    ///
    /// It was `@State`, so a failed sign-in — a typo in the password, a
    /// homeserver that was briefly down — threw the address away and made the
    /// reader type it again to try the thing that was nearly right.
    @AppStorage("login.homeserver") private var homeserver = "https://id.agentpod.dev"
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    /// The homeserver is tucked away: nearly everyone signs in to the
    /// default, and a URL field first on the form reads as a setup step.
    @State private var showsAdvanced = false

    private enum Field { case username, password }
    @FocusState private var focus: Field?

    var body: some View {
        VStack(spacing: 20) {
            Image("Mark")
                .resizable()
                .scaledToFit()
                .frame(width: 72)
                .accessibilityHidden(true)
            Text("Sign in")
                .font(.title.weight(.bold))

            VStack(spacing: 12) {
                TextField("Username", text: $username)
                    .textContentType(.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .username)
                    .submitLabel(.next)
                    .onSubmit { focus = .password }
                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .focused($focus, equals: .password)
                    .submitLabel(.go)
                    .onSubmit { Task { await signIn() } }
            }
            .textFieldStyle(.roundedBorder)

            DisclosureGroup(isExpanded: $showsAdvanced) {
                VStack(alignment: .leading, spacing: 6) {
                    TextField("Homeserver", text: $homeserver)
                        .textContentType(.URL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textFieldStyle(.roundedBorder)
                    Text("The Matrix server your account lives on.")
                        .metaFace()
                        .foregroundStyle(Theme.contentMuted)
                }
                .padding(.top, 8)
            } label: {
                HStack {
                    Text("Advanced").font(.subheadline)
                    Spacer()
                    if !showsAdvanced {
                        Text(host)
                            .metaFace()
                            .foregroundStyle(Theme.contentFaint)
                            .lineLimit(1)
                    }
                }
            }
            .tint(Theme.contentMuted)

            if let failure = session.failure {
                Text(failure)
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
                    .multilineTextAlignment(.center)
            }

            Button {
                Task { await signIn() }
            } label: {
                if busy {
                    ProgressView()
                } else {
                    Text("Sign in").frame(maxWidth: .infinity)
                        .foregroundStyle(Theme.accentContent)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .tint(Theme.accent)
            .disabled(busy || username.isEmpty || password.isEmpty)
        }
        .padding(28)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.surface.ignoresSafeArea())
    }

    /// The homeserver as a reader would say it — the host, without the scheme.
    private var host: String {
        URL(string: homeserver)?.host() ?? homeserver
    }

    private func signIn() async {
        busy = true
        defer { busy = false }
        await session.signIn(homeserver: homeserver, username: username, password: password)
        // Only an interactive sign-in queues the demo: a restored session is
        // someone opening the app they already use. Written to the defaults
        // directly: by now this view has usually been replaced by the signed-in
        // one, which is observing the same key.
        if session.phase == .signedIn {
            UserDefaults.standard.set(true, forKey: FirstRun.demoPendingKey)
        }
    }
}

#if DEBUG
#Preview {
    LoginView(session: PreviewFixtures.session(phase: .signedOut))
        .previewChrome()
}

// Dark, because this is the first screen anyone sees and the only one they
// see before the palette has any content to be judged against.
#Preview("Dark") {
    LoginView(session: PreviewFixtures.session(phase: .signedOut))
        .preferredColorScheme(.dark)
        .previewChrome()
}
#endif
