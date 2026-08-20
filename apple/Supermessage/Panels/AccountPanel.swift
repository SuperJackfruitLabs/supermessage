import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Who you are signed in as, and the way out.
///
/// **The way out is the point.** `Session.signOut` was implemented and tested
/// and called from nowhere, so a signed-in app could not be signed out except
/// by deleting it. That is a missing exit rather than a missing feature.
struct AccountPanel: View {
    let session: Session
    let onClose: () -> Void

    @State private var account: AccountDto?
    @State private var confirmingSignOut = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 12) {
                        ZStack {
                            Circle().fill(.quaternary)
                            Text(initial).font(.headline)
                        }
                        .frame(width: 44, height: 44)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(name).font(.headline)
                            if let account {
                                Text(account.userId)
                                    .metaFace()
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                        }
                    }
                    if let account {
                        LabeledContent("Homeserver", value: account.homeserver)
                            .metaFace()
                    }
                } header: {
                    Text("Signed in as")
                }

                Section {
                    Button("Sign out", role: .destructive) { confirmingSignOut = true }
                } footer: {
                    // Said plainly, because it is true and because signing out
                    // of this app is not the small thing it is elsewhere: the
                    // encrypted store goes with it.
                    Text("Signing out removes this account and its messages from this device.")
                }
            }
            .navigationTitle("Account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) }
            }
            .task { account = await session.account() }
            .confirmationDialog(
                "Sign out of \(name)?", isPresented: $confirmingSignOut, titleVisibility: .visible
            ) {
                Button("Sign out", role: .destructive) {
                    Task {
                        await session.signOut()
                        onClose()
                    }
                }
                Button("Cancel", role: .cancel) {}
            }
        }
    }

    /// The local part of the Matrix id — `@rakesh:id.agentpod.dev` is a name
    /// and an address, and only the first half is worth a headline.
    private var name: String {
        guard let id = account?.userId, id.hasPrefix("@"), let colon = id.firstIndex(of: ":") else {
            return account?.userId ?? "Signed in"
        }
        return String(id[id.index(after: id.startIndex)..<colon])
    }

    private var initial: String {
        name.first.map { String($0).uppercased() } ?? "?"
    }
}
