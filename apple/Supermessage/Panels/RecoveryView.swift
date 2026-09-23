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
/// ## Two situations, not four states
///
/// The SDK reports four (`unknown`, `enabled`, `disabled`, `incomplete`) and
/// an earlier version of this screen showed all four as four different pages.
/// That was the wrong shape: the words are the SDK's, not a person's, and a
/// reader who has just been told their device "is incomplete" has learned
/// nothing they can act on.
///
/// A person is in one of two situations, and each has exactly one action:
///
///   covered  → the key exists, nothing to do
///   stranded → this device cannot read the history, so either produce the
///              key or start again
///
/// `unknown` is the absence of an answer and shows a spinner rather than a
/// guess; `disabled` is now vanishingly rare, because ``Session/ensureRecovery``
/// sets recovery up at sign-in. Element reorganised its own version of this
/// screen for the same reason and reached the same two situations.
struct RecoveryView: View {
    let session: Session
    let onClose: () -> Void

    @State private var state: String
    @State private var freshKey: String?
    @State private var entered = ""
    @State private var busy = false
    @State private var failure: String?
    @State private var copied = false
    @State private var resetting = false
    @State private var password = ""

    /// `initialState` is the frame this screen opens on, and it exists because
    /// a snapshot has no time to wait.
    ///
    /// `.task` corrects it a moment later from the session, which is what the
    /// app relies on and why the default is the same `unknown` the app starts
    /// from. But the preview renderer photographs the *first* frame: without
    /// this, the previews below would capture "Checking…" and come out
    /// identical — the exact failure `scripts/snapshot-index.py` flags, and one
    /// already documented there for two other screens.
    init(session: Session, onClose: @escaping () -> Void, initialState: String = "unknown") {
        self.session = session
        self.onClose = onClose
        _state = State(initialValue: initialState)
    }

    /// Whether this device can read what was backed up.
    ///
    /// `incomplete` and `disabled` are the same situation to a reader — the
    /// history is not reachable from here — and differ only in which action
    /// fixes it, which is a difference the buttons already express.
    private var stranded: Bool { state == "incomplete" || state == "disabled" }

    var body: some View {
        NavigationStack {
            List {
                Group {
                    if let freshKey {
                        freshKeySection(freshKey)
                    } else if state == "unknown" {
                        // Never "not set up": offering a second key to somebody who
                        // has one is how the first is orphaned.
                        Section { Text("Checking this account…") }
                    } else if stranded {
                        strandedSections
                    } else {
                        coveredSection
                    }

                    if let failure {
                        Section { Text(failure).foregroundStyle(Theme.danger).metaFace() }
                    }
                }
                .listRowBackground(Theme.surface)
            }
            .paletteGroupedGround()
            .navigationTitle("Encryption recovery")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) }
            }
            .task { await refresh() }
        }
    }

    // MARK: The key, shown once

    private func freshKeySection(_ key: String) -> some View {
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
            // The one moment this key exists outside the SDK. Said plainly:
            // losing it costs nothing today and everything on the day the
            // phone goes in a river.
            Text(
                "Save this somewhere safe. It is shown once, and it is the only way to "
                    + "read your encrypted messages on a new device."
            )
        }
    }

    // MARK: Covered

    private var coveredSection: some View {
        Section {
            Text("Your messages can be recovered.")
        } footer: {
            Text(
                "They can be restored on a new device with your recovery key. There is no way "
                    + "to show it again — start over below if it is lost."
            )
        }
    }

    // MARK: Stranded — the key, or start again

    @ViewBuilder
    private var strandedSections: some View {
        Section {
            TextField("Recovery key", text: $entered, axis: .vertical)
                .font(.system(.body, design: .monospaced))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            Button("Restore") { Task { await restore() } }
                .disabled(busy || entered.trimmingCharacters(in: .whitespaces).isEmpty)
        } header: {
            Text("This device is missing your encryption keys")
        } footer: {
            Text("Enter your recovery key to read your earlier messages here.")
        }

        // The escape hatch, and the reason this screen stopped being a dead
        // end. Without it, somebody who never had a key is shown a field they
        // cannot fill and given nothing else — which was the state this app
        // shipped in. Element offers exactly one way out of the same corner
        // and this is the same one.
        Section {
            if resetting {
                SecureField("Your password", text: $password)
                Button("Start over", role: .destructive) { Task { await reset() } }
                    .disabled(busy || password.isEmpty)
                Button("Cancel") {
                    resetting = false
                    password = ""
                    failure = nil
                }
            } else {
                Button("Start over with a new key") { resetting = true }
            }
        } header: {
            Text("No recovery key?")
        } footer: {
            // The consequence stays beside the button that causes it. An
            // earlier version swapped it for the sentence about the password,
            // so the one moment a reader was deciding to destroy a backup was
            // the one moment nothing on screen said so.
            Text(
                resetting
                    ? "Anything backed up under the old key is lost, and your other devices will "
                        + "need verifying again. Messages already on this device stay readable. "
                        + "Your password confirms this with your homeserver; it is used once and "
                        + "not stored."
                    : "Start again with a new recovery key. Messages already on this device stay "
                        + "readable, but anything backed up under the old key is lost, and your "
                        + "other devices will need verifying again."
            )
        }
    }

    // MARK: Actions

    private func refresh() async {
        do { state = try await session.recoveryState() } catch {
            // "unknown" already reads as "checking", which is the honest thing
            // to show when the question could not be asked.
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

    private func reset() async {
        busy = true
        failure = nil
        defer { busy = false }
        do {
            freshKey = try await session.resetRecovery(password: password)
            state = "enabled"
            resetting = false
            password = ""
        } catch {
            failure = error.localizedDescription
        }
    }
}

#if DEBUG
// The three frames a person can actually be shown.
//
// The one worth having is "stranded": it is reached on the worst day the
// account has, which is a poor time to find out the screen was drawn once and
// never looked at. It carries both ways out at once, which is the whole point
// of the rewrite — the earlier version showed the key field alone and left
// somebody who had no key with nothing to do.
#Preview("Covered") {
    RecoveryView(
        session: PreviewFixtures.session(recovery: "enabled"), onClose: {},
        initialState: "enabled"
    )
    .previewChrome()
}

#Preview("Stranded") {
    RecoveryView(
        session: PreviewFixtures.session(recovery: "incomplete"), onClose: {},
        initialState: "incomplete"
    )
    .previewChrome()
}

#Preview("Checking") {
    RecoveryView(session: PreviewFixtures.session(), onClose: {})
        .previewChrome()
}
#endif
