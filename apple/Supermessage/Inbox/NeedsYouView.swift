import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Needs you: every decision owed and every invitation waiting, and nothing
/// else.
///
/// **Finishable.** A row leaves when the core stops saying it needs you — the
/// decision answered in its room, the invitation accepted or declined — so an
/// empty inbox is a fact rather than a filter. See `NeedsYouInbox`.
struct NeedsYouView: View {
    let session: Session
    @Binding var selection: String?

    @AppStorage("roster.showsState") private var showsState = true
    @State private var now = Date()

    private var inbox: NeedsYouInbox { NeedsYouInbox.from(session.rooms.rooms, now: now) }

    var body: some View {
        List(selection: $selection) {
            if !inbox.decisions.isEmpty {
                Section {
                    rows(inbox.decisions)
                } header: {
                    header("Waiting on you", count: inbox.decisions.count)
                }
            }
            if !inbox.invitations.isEmpty {
                Section {
                    rows(inbox.invitations)
                } header: {
                    header("Invitations", count: inbox.invitations.count)
                }
            }
        }
        // `.inset`, not `.plain`: on iOS 26 devices a plain SwiftUI list
        // leaves the bars' glass one appearance behind after every light/dark
        // switch — dark glass over a light page — until the list is scrolled
        // (FB20370553, forum thread 802028; reproduced on a stock list on
        // iOS 26.6.1, 2026-09-24). `.inset` draws the same full-width rows
        // without the bug.
        .listStyle(.inset)
        .paletteListGround()
        .overlay {
            if inbox.isEmpty { CaughtUpView() }
        }
        // A row leaving is the inbox being finished; let it be seen to go.
        .animation(.default, value: inbox)
        .navigationTitle("Needs you")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: session.rooms.rooms.count) { now = Date() }
        .refreshable {
            now = Date()
            await session.rooms.seed()
        }
    }

    private func rows(_ rows: [RosterRow]) -> some View {
        ForEach(rows, id: \.row.room.id) { entry in
            RoomRowView(
                row: entry.row,
                avatarURI: session.avatars.uri(for: entry.row.room.id),
                state: entry.state,
                when: RelativeTime.label(for: entry.row.room.lastActivityMs, now: now),
                showsState: showsState,
                describesAgent: entry.describesAgent)
            .tag(entry.row.room.id)
            .listRowBackground(Color.clear)  // the list's own ground shows through — see `paletteListGround`
            .task { await session.avatars.load(entry.row.room.id) }
        }
    }

    private func header(_ title: String, count: Int) -> some View {
        HStack(spacing: 6) {
            Text(title).metaFace().foregroundStyle(Theme.contentMuted)
            Text("\(count)").metaFace().foregroundStyle(Theme.contentFaint)
        }
        .textCase(nil)
    }
}

/// The empty inbox, which is the good outcome and should feel like one.
///
/// One symbol, one bounce when it arrives, and no bounce at all under Reduce
/// Motion — the words say it either way.
struct CaughtUpView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var arrived = false

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 56, weight: .regular))
                .foregroundStyle(Theme.ok)
                .symbolEffect(.bounce.up.byLayer, options: .nonRepeating, value: arrived)
                .accessibilityHidden(true)
            Text("You're all caught up")
                .font(.title3.weight(.semibold))
                .foregroundStyle(Theme.content)
            Text("Decisions and invitations that need you land here.")
                .font(.subheadline)
                .foregroundStyle(Theme.contentMuted)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
        .onAppear {
            guard !reduceMotion else { return }
            // A beat after appearing, so the bounce is seen rather than
            // happening during the tab transition.
            Task {
                try? await Task.sleep(for: .milliseconds(250))
                arrived.toggle()
            }
        }
    }
}

#if DEBUG
#Preview("Needs you") {
    @Previewable @State var open: String?
    NavigationStack {
        NeedsYouView(session: NavigationRevampFixtures.fleetSession(), selection: $open)
    }
}

#Preview("Needs you, dark") {
    @Previewable @State var open: String?
    NavigationStack {
        NeedsYouView(session: NavigationRevampFixtures.fleetSession(), selection: $open)
    }
    .preferredColorScheme(.dark)
}

#Preview("All caught up") {
    @Previewable @State var open: String?
    NavigationStack {
        NeedsYouView(session: NavigationRevampFixtures.caughtUpSession(), selection: $open)
    }
}
#endif
