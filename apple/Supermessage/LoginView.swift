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

    var body: some View {
        VStack(spacing: 20) {
            Text("supermessage")
                .font(.system(.largeTitle, design: .serif))

            VStack(spacing: 12) {
                TextField("Homeserver", text: $homeserver)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Username", text: $username)
                    .textContentType(.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .onSubmit { Task { await signIn() } }
            }
            .textFieldStyle(.roundedBorder)

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
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.accent)
            .disabled(busy || username.isEmpty || password.isEmpty)
        }
        .padding(28)
        .frame(maxWidth: 420)
    }

    private func signIn() async {
        busy = true
        defer { busy = false }
        await session.signIn(homeserver: homeserver, username: username, password: password)
    }
}
