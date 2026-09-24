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
    /// The roster's arrangement and whether it shows agent state. Here
    /// rather than behind a button beside compose: both are chosen once and
    /// rarely changed, which is what an account screen is for.
    @AppStorage("roster.view") private var storedView = RosterChoice.waiting.rawValue
    @AppStorage("roster.showsState") private var showsState = true
    @AppStorage(AppearanceSettings.modeKey) private var mode = AppearanceMode.system.rawValue
    @AppStorage(AppearanceSettings.darkStyleKey) private var darkStyle = DarkStyle.tinted.rawValue
    @AppStorage(AppearanceSettings.accentKey) private var accent = ""

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
    @Environment(\.themeChoice) private var choice

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
    AccountPanel(session: PreviewFixtures.session(), onClose: {})
        .previewChrome()
}

// Dark, Black, teal: the environment alone has to carry the choice to every
// colour, since a preview has no window for `appliesAppearance` to set.
#Preview("Account, black and teal") {
    AccountPanel(session: PreviewFixtures.session(), onClose: {})
        .previewChrome()
        .environment(\.themeChoice, ThemeChoice(darkStyle: .black, accent: "teal"))
        .preferredColorScheme(.dark)
}
#endif
