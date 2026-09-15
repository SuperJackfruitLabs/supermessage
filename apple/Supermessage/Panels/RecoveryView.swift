import SupermessageKit
import SwiftUI

/// The key that gets a conversation back.
///
/// Room keys live in this device's encrypted store. Without recovery a lost or
/// reset phone takes every encrypted conversation on it — there is no
/// server-side copy to fall back on, because that is what end-to-end
/// encryption means. Backup uploads those keys encrypted under a key the server
/// never sees; this screen is where that key is handed over, and where a new
/// device uses it.
///
/// **Shipped later than the encryption it protects, and that gap was real.**
/// Build 6 turned on key backup for iOS with no way to see the recovery key, so
/// an account had a backup it could not unlock from the device that made it.
///
/// Four states, four screens, because they are four different situations and
/// one page that made the reader work out which they were in would be the worst
/// version of this.
struct RecoveryView: View {
    let session: Session
    let onClose: () -> Void

    @State private var state = "unknown"
    @State private var freshKey: String?
    @State private var entered = ""
    @State private var busy = false
    @State private var failure: String?
    @State private var copied = false

    var body: some View {
        NavigationStack {
            List {
                if let freshKey {
                    Section {
                        Text(freshKey)
                            .font(.system(.body, design: .monospaced))
                            .textSelection(.enabled)
                        Button(copied ? "Copied" : "Copy") {
                            UIPasteboard.general.string = freshKey
                            copied = true
                        }
                    } header: {
                        Text("Your recovery key")
                    } footer: {
                        // The one moment this key exists outside the SDK. Said
                        // plainly: losing it costs nothing today and everything
                        // on the day the phone goes in a river.
                        Text(
                            "Save this somewhere safe. It is shown once, and it is the only way to "
                                + "read your encrypted messages on a new device."
                        )
                    }
                } else {
                    switch state {
                    case "unknown":
                        // Never "not set up": offering a second key to somebody
                        // who has one is how the first is orphaned.
                        Section { Text("Checking this account…") }
                    case "enabled":
                        Section {
                            Text("Recovery is on.")
                        } footer: {
                            Text(
                                "Your messages can be restored on a new device with your recovery key. "
                                    + "There is no way to show it again — set up recovery afresh if it is lost."
                            )
                        }
                    case "incomplete":
                        Section {
                            TextField("Recovery key", text: $entered, axis: .vertical)
                                .font(.system(.body, design: .monospaced))
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                            Button("Restore") { Task { await restore() } }
                                .disabled(busy || entered.trimmingCharacters(in: .whitespaces).isEmpty)
                        } header: {
                            Text("This device is missing your keys")
                        } footer: {
                            Text("Enter your recovery key to read your earlier messages here.")
                        }
                    default:
                        Section {
                            Button("Set up recovery") { Task { await enable() } }
                                .disabled(busy)
                        } footer: {
                            Text(
                                "Set up recovery so you can read your encrypted messages on a new "
                                    + "device. Without it, messages stay on this device only."
                            )
                        }
                    }
                }

                if let failure {
                    Section { Text(failure).foregroundStyle(Theme.danger).metaFace() }
                }
            }
            .navigationTitle("Encryption recovery")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) }
            }
            .task { await refresh() }
        }
    }

    private func refresh() async {
        do { state = try await session.recoveryState() } catch {
            // "unknown" already reads as "checking", which is the honest thing
            // to show when the question could not be asked.
            failure = error.localizedDescription
        }
    }

    private func enable() async {
        busy = true
        failure = nil
        defer { busy = false }
        do { freshKey = try await session.enableRecovery() } catch {
            failure = error.localizedDescription
        }
    }

    private func restore() async {
        busy = true
        failure = nil
        defer { busy = false }
        do {
            try await session.recoverWithKey(entered.trimmingCharacters(in: .whitespaces))
            onClose()
        } catch {
            // Led with what the reader can act on: by far the likeliest cause
            // is a mistyped key, and a protocol error alone says nothing about
            // that. The detail stays, because the second likeliest cause is
            // something this sentence would be wrong about.
            failure = "That key was not accepted — check it for typos. (\(error.localizedDescription))"
        }
    }
}
