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
    @State private var showingRecovery = false
    /// Everyone blocked, named by the core. `nil` until asked.
    @State private var blocked: [RoomMemberDto]?
    @State private var unblockFailure: String?
    @State private var unblocked = 0
    /// The roster's arrangement and whether it shows agent state. Here
    /// rather than behind a button beside compose: both are chosen once and
    /// rarely changed, which is what an account screen is for.
    @AppStorage("roster.view") private var storedView = RosterChoice.waiting.rawValue
    @AppStorage("roster.showsState") private var showsState = true
    @AppStorage(AppearanceSettings.modeKey) private var mode = AppearanceMode.system.rawValue
    @AppStorage(AppearanceSettings.darkStyleKey) private var darkStyle = DarkStyle.tinted.rawValue
    @AppStorage(AppearanceSettings.accentKey) private var accent = ""

    init(session: Session, onClose: @escaping () -> Void) {
        self.session = session
        self.onClose = onClose
    }

    #if DEBUG
    /// The panel with its account and blocked list already loaded — each
    /// arrives from its own `await`, and a frame must not depend on which won
    /// the race with the snapshot (CI drew "rakesh" on one run and "?" on the
    /// next).
    init(
        session: Session, account: AccountDto, blocked: [RoomMemberDto],
        onClose: @escaping () -> Void
    ) {
        self.session = session
        self.onClose = onClose
        _account = State(initialValue: account)
        _blocked = State(initialValue: blocked)
    }
    #endif

    var body: some View {
        NavigationStack {
            List {
                Group {
                    Section {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle().fill(Theme.surfaceRaised)
                                Text(initial).font(.headline)
                            }
                            .frame(width: 44, height: 44)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(name).font(.headline)
                                if let account {
                                    Text(account.userId)
                                        .metaFace()
                                        .foregroundStyle(Theme.contentMuted)
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
                        Picker("Appearance", selection: $mode) {
                            ForEach(AppearanceMode.allCases) { Text($0.label).tag($0.rawValue) }
                        }
                        .pickerStyle(.segmented)
                        .accessibilityLabel("Appearance")
                        Picker("Dark style", selection: $darkStyle) {
                            ForEach(DarkStyle.allCases) { Text($0.label).tag($0.rawValue) }
                        }
                        AccentSwatches(selection: $accent)
                    } header: {
                        Text("Appearance")
                    } footer: {
                        Text("Black is true black in dark mode, for OLED screens. The accent colours buttons, links and your own highlights.")
                    }

                    Section {
                        Picker("Order rooms by", selection: $storedView) {
                            ForEach(RosterChoice.offered, id: \.rawValue) { option in
                                Text(option == .waiting ? "Waiting first" : "Most recent")
                                    .tag(option.rawValue)
                            }
                        }
                        Toggle("Agent state", isOn: $showsState)
                    } header: {
                        Text("Chats")
                    } footer: {
                        Text("Waiting first puts what needs an answer at the top. Agent state is the dot and word beside an agent's name.")
                    }

                    // Only when there is someone in it: an empty "Blocked"
                    // heading on every account is a list of nobody.
                    if let blocked, !blocked.isEmpty {
                        Section {
                            ForEach(blocked, id: \.userId) { member in
                                BlockedUserRow(member: member) {
                                    Task { await unblock(member) }
                                }
                            }
                        } header: {
                            Text("Blocked")
                        } footer: {
                            if let unblockFailure {
                                Text(unblockFailure).foregroundStyle(Theme.danger)
                            } else {
                                Text("Their messages are hidden in every room, on all your devices. A blocked agent keeps running. Block someone from a room's member list.")
                            }
                        }
                    }

                    Section {
                        Link(destination: Self.supportURL) {
                            Label("Help & support", systemImage: "questionmark.circle")
                        }
                        Link(destination: Self.termsURL) {
                            Label("Terms of use", systemImage: "doc.text")
                        }
                        Link(destination: Self.privacyURL) {
                            Label("Privacy", systemImage: "hand.raised")
                        }
                    } footer: {
                        Text("To report abuse, long-press a message or open a room's info. Reports go to your homeserver's administrators.")
                    }

                    Section {
                        // Beside `Sign out` because it is the same rarely-visited
                        // class of account action — and because the day it is
                        // needed is the day someone is setting up a new device and
                        // looking for exactly this.
                        Button("Encryption recovery") { showingRecovery = true }
                    }

                    Section {
                        // Red by hand, like Leave room: the root's
                        // `.foregroundStyle(Theme.content)` outranks the role.
                        Button("Sign out", role: .destructive) { confirmingSignOut = true }
                            .foregroundStyle(Theme.danger)
                    } footer: {
                        // Said plainly, because it is true and because signing out
                        // of this app is not the small thing it is elsewhere: the
                        // encrypted store goes with it.
                        Text("Signing out removes this account and its messages from this device.")
                    }
                }
                .listRowBackground(Theme.surface)
            }
            .sheet(isPresented: $showingRecovery) {
                RecoveryView(session: session) { showingRecovery = false }
            }
            .paletteGroupedGround()
            .navigationTitle("Account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) }
            }
            .task {
                account = await session.account()
                if blocked == nil { blocked = await session.blockedUsers() }
            }
            .sensoryFeedback(.success, trigger: unblocked)
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

    /// The landing site's pages. `supermessage.dev` is the site's own domain
    /// (`landing/astro.config.mjs`), and these three are the pages the stores
    /// ask an app with user content to link: support with a contact for abuse,
    /// the terms, and the privacy policy.
    static let supportURL = URL(string: "https://supermessage.dev/support/")!
    static let termsURL = URL(string: "https://supermessage.dev/terms/")!
    static let privacyURL = URL(string: "https://supermessage.dev/privacy/")!

    private func unblock(_ member: RoomMemberDto) async {
        unblockFailure = nil
        if let refused = await session.unblock(member.userId) {
            unblockFailure = refused
            return
        }
        blocked?.removeAll { $0.userId == member.userId }
        unblocked += 1
    }

    /// The local part of the Matrix id — `@rakesh:id.agentpod.dev` is a name
    /// and an address, and only the first half is worth a headline.
    private var name: String { AccountLabel.name(of: account?.userId) }

    private var initial: String { AccountLabel.initial(of: account?.userId) }
}

/// The accents, as a row of swatches — the house violet first.
///
/// Each swatch is drawn in its own colour for the current appearance, so
/// what is offered is what will be seen; the selection ring is `content`,
/// not the accent, because the accent is what is being chosen.
private struct AccentSwatches: View {
    @Binding var selection: String
    @Environment(\.colorScheme) private var scheme
    private var choice: ThemeChoice { ThemeState.shared.choice }

    private var options: [String] { [""] + ThemeAccents.names }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.self) { name in
                Button {
                    selection = name
                } label: {
                    Circle()
                        .fill(color(for: name))
                        .frame(width: 30, height: 30)
                        .padding(4)
                        .overlay {
                            if selection == name {
                                Circle().strokeBorder(Theme.content, lineWidth: 2)
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(name.isEmpty ? "Violet" : name.capitalized)
                .accessibilityAddTraits(selection == name ? .isSelected : [])
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Accent")
    }

    private func color(for name: String) -> Color {
        ThemeChoice(darkStyle: choice.darkStyle, accent: name.isEmpty ? nil : name)
            .palette(dark: scheme == .dark).accent
    }
}

#if DEBUG
// The account, which is two facts and a way out.
#Preview("Account") {
    AccountPanel(
        session: PreviewFixtures.session(), account: PreviewFixtures.account, blocked: [],
        onClose: {})
        .previewChrome()
}

// Two blocked accounts, an agent and a person, each with a way back.
#Preview("Blocked users") {
    AccountPanel(
        session: PreviewFixtures.session(), account: PreviewFixtures.account,
        blocked: PreviewFixtures.blockedUsers, onClose: {}
    )
    // Tall enough for the whole list: the blocked section sits below the
    // fold on a phone, and a frame that cropped it would be a frame of the
    // settings above it.
    .frame(height: 1400)
    .previewChrome()
}

#endif
