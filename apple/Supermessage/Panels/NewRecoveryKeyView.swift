import SupermessageKit
import SwiftUI

/// The recovery key, at the moment it is created, on the first sign-in.
///
/// **This screen exists because a backup is not recovery.** The SDK's
/// `auto_enable_backups` creates a key backup; it does not create the secret
/// storage that holds that backup's key. An account with only the former has a
/// backup nothing can ever restore from, and for two builds that was every
/// account this app signed in — the reassurance without the recovery.
///
/// Setting recovery up in a settings screen does not fix it, because the
/// people who most need the key are the ones who will never open that screen.
/// So it is made here, once, where the reader already is.
///
/// Deliberately not dismissible by a swipe: this is the only time the key is
/// ever shown, and a sheet pulled down by accident is indistinguishable from
/// one that was read. The single button says what it costs.
struct NewRecoveryKeyView: View {
    let key: String
    let onDone: () -> Void

    @State private var copied = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(key)
                        .font(.system(.body, design: .monospaced))
                        .textSelection(.enabled)
                    Button(copied ? "Copied" : "Copy") {
                        UIPasteboard.general.string = key
                        copied = true
                    }
                } header: {
                    Text("Your recovery key")
                } footer: {
                    Text(
                        "Save this somewhere safe. It is shown once, and it is the only way to "
                            + "read your encrypted messages on a new device."
                    )
                }

                Section {
                    Button("I've saved it", action: onDone)
                }
            }
            .navigationTitle("Keep this safe")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled()
        }
    }
}

#if DEBUG
// The one frame, and the one that matters most in the whole feature: it is
// shown once per account and never again.
#Preview("Just created") {
    NewRecoveryKeyView(key: PreviewFixtures.recoveryKey, onDone: {})
        .previewChrome()
}
#endif
