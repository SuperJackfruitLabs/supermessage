import SupermessageFFI
import SupermessageKit
import SwiftUI

/// The roster.
///
/// Three arrangements, chosen by the reader and remembered. The rules for what
/// goes where live in `RosterArrangement` — this view draws the answer and
/// makes none of the decisions itself.
struct RoomListView: View {
    let session: Session
    /// The open room, in whichever sense the shell means it.
    ///
    /// On an iPad this is `RoomsStore.selectedId`, because the detail column
    /// shows the selected room. On a phone each tab owns its own stack, so
    /// the Chats tab passes its own state and a room opened from the Needs
    /// you tab cannot also push itself onto Chats.
    ///
    /// Decided by the view that owns the navigation, not read from the
    /// environment here: **a column reports its own width**, and a sidebar on
    /// an iPad is compact. Asking inside this view gave the answer for the
    /// sidebar rather than for the window.
    @Binding var selection: String?

    /// The arrangement the app opens on, and the filters, all remembered.
    @AppStorage("roster.view") private var storedView = RosterChoice.waiting.rawValue
    /// Whether the invitations row is open. Not stored: invitations are
    /// something to act on, and a list that opened with them spread across
    /// its top every launch would bury the conversations.
    @State private var showsInvitations = false
    @AppStorage("roster.showsState") private var showsState = true
    @AppStorage("roster.filter") private var storedFilter = RosterFilter.all.rawValue

    /// Re-read on every roster change so "2m" does not sit at "2m" all day.
    @State private var now = Date()
    /// The room whose info panel is open from the roster, if any.
    @State private var infoRequest: RoomInfoRequest?
    /// What a swipe learned about a room's settings, so its next swipe can
    /// say "Unmute" rather than "Mute". Only ever filled by a swipe that
    /// asked the core — never guessed.
    @State private var settings: [String: KnownSettings] = [:]
    /// Bumped when a swipe lands, for the haptic.
    @State private var swipeLanded = 0
    /// Open for a moment after the space changes, so the rows the new space
    /// brings slide into place rather than the list being swapped under the
    /// reader. Only then: the roster also reorders on every message, and a
    /// list that moved each time an agent spoke would be one to chase.
    ///
    /// A window rather than `withAnimation` around `select`, because the
    /// rows do not change there — the core's diff arrives after it.
    @State private var spaceChanging = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The arrangement, chosen in Account → Roster. "Machine" is no longer
    /// offered — a space is a machine's rooms, and the title chooses spaces —
    /// so a stored choice of it reads as the default.
    private var view: RosterChoice {
        let stored = RosterChoice(rawValue: storedView) ?? .waiting
        return RosterChoice.offered.contains(stored) ? stored : .waiting
    }
    /// The chip in force. A filter that is no longer offered as a chip —
    /// Agents and Needs you, which are tabs — reads as All, so a stored
    /// choice from before cannot leave the list narrowed with no chip lit.
    private var filter: RosterFilter {
        let stored = RosterFilter(rawValue: storedFilter) ?? .all
        return RosterFilterChips.offered.contains(stored) ? stored : .all
    }

    private var filterBinding: Binding<RosterFilter> {
        Binding(get: { filter }, set: { storedFilter = $0.rawValue })
    }

    /// The core's arrangement — `core::roster` orders and groups.
    ///
    /// Invitations are never left in it: when open they are drawn directly
    /// under their own row (`invitationRows`). Left to the core they came
    /// after its first section — below Strategy Sam, away from the row that
    /// had just been tapped to show them (2026-09-24).
    private var arranged: [RosterSection] {
        RosterArrangement.sections(
            session.rooms.rooms, view: view, showsInvitations: false, now: now)
    }

    /// The invitations, in the core's order, for under their row.
    private var invitationRows: [RosterRow] {
        RosterArrangement.sections(
            session.rooms.rooms, view: view, showsInvitations: true, now: now
        )
        .flatMap(\.rows)
        .filter { $0.row.affordance == .respondToInvitation }
    }

    /// The arrangement, narrowed by the chip. Never re-ordered.
    private var sections: [RosterSection] { filter.apply(arranged) }

    /// What the space animation watches: which rooms, in which sections.
    private var rowIds: [String] {
        sections.flatMap { section in [section.id] + section.rows.map(\.row.room.id) }
    }

    private var counts: [RosterFilter: Int] {
        let rows = arranged.flatMap(\.rows)
        return Dictionary(
            uniqueKeysWithValues: RosterFilter.allCases.map { chip in
                (chip, rows.filter(chip.admits).count)
            })
    }

    private var hiddenInvitations: Int {
        RosterArrangement.hiddenInvitations(session.rooms.rooms, showsInvitations: false)
    }

    private var invitationCount: Int {
        session.rooms.rooms.filter { $0.affordance == .respondToInvitation }.count
    }

    var body: some View {
        List(selection: $selection) {
            Section {
                EmptyView()
            } header: {
                // Inside the scroll content on purpose: present the moment
                // the reader arrives, and giving its height back as soon as
                // they scroll. Spaces are chosen from the title — see
                // `SpaceMenu`.
                VStack(alignment: .leading, spacing: 0) {
                    if !session.rooms.rooms.isEmpty {
                        RosterFilterChips(selection: filterBinding, counts: counts)
                    }
                    if invitationCount > 0 {
                        InvitationsRow(count: invitationCount, isOpen: $showsInvitations)
                    }
                }
                .textCase(nil)
                .listRowInsets(EdgeInsets())
            }

            if showsInvitations {
                Section {
                    ForEach(invitationRows, id: \.row.room.id) { entry in
                        rosterRow(entry)
                    }
                }
            }

            ForEach(sections, id: \.id) { section in
                Section {
                    ForEach(section.rows, id: \.row.room.id) { entry in
                        rosterRow(entry)
                    }
                } header: {
                    if let title = section.title {
                        SectionHeader(
                            title: SpaceNames.display(title), detail: section.detail,
                            attention: section.attention)
                    }
                }
            }
        }
        .listStyle(.plain)
        .animation(spaceChanging && !reduceMotion ? .snappy(duration: 0.3) : nil, value: rowIds)
        .onChange(of: session.spaces.selectedId) { spaceChanging = true }
        .task(id: spaceChanging) {
            guard spaceChanging else { return }
            try? await Task.sleep(for: .milliseconds(800))
            spaceChanging = false
        }
        .paletteListGround()
        .sensoryFeedback(.success, trigger: swipeLanded)
        // Still set: it is the back button's title in a room.
        .navigationTitle(session.spaces.selectedName.map(SpaceNames.display) ?? "Chats")
        .toolbar {
            ToolbarItem(placement: .principal) {
                SpaceMenu(spaces: session.spaces, allCount: session.rooms.rooms.count)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        // A roster that says "2m" forever is lying by the time you look again.
        .task(id: session.rooms.rooms.count) { now = Date() }
        .refreshable {
            now = Date()
            await session.rooms.seed()
        }
        .overlay {
            if session.rooms.rooms.isEmpty {
                ContentUnavailableView(
                    "No rooms yet", systemImage: "tray",
                    description: Text("Rooms appear here as they sync."))
            } else if sections.isEmpty, filter != .all {
                // The chip left nothing. Say which chip, and offer the way back.
                ContentUnavailableView {
                    Label(emptyTitle, systemImage: "line.3.horizontal.decrease.circle")
                } description: {
                    Text(emptyDescription)
                } actions: {
                    Button("Show all") { storedFilter = RosterFilter.all.rawValue }
                }
            } else if sections.isEmpty, !showsInvitations {
                // Every room was filtered away. Say so, and say what by —
                // unless the invitations are open, when they are the list.
                ContentUnavailableView(
                    "Nothing but invitations", systemImage: "envelope",
                    description: Text("\(hiddenInvitations) waiting — open Invitations above."))
            }
        }
        // Reached by tapping a row's avatar. Presented from the roster rather
        // than by opening the room first: asking what a room *is* should not
        // require entering the conversation and marking it read.
        .sheet(item: $infoRequest) { request in
            RoomInfoPanel(session: session, roomId: request.id) { infoRequest = nil }
                .presentationDetents([.large, .medium])
                .paletteSheet()
        }
    }

    private var emptyTitle: String {
        switch filter {
        case .all: return "Nothing here"
        case .unread: return "Nothing unread"
        case .agents: return "No agents here"
        case .needsYou: return "You're all caught up"
        }
    }

    private var emptyDescription: String {
        switch filter {
        case .all: return ""
        case .unread: return "Every room is read."
        case .agents: return "No room here reads as an agent's."
        case .needsYou: return "Nothing is waiting on you."
        }
    }

    // MARK: - Swipes

    /// One room in the roster, with its swipes. Shared by the core's sections
    /// and the invitations under their row, so an invitation opens, reads
    /// and swipes exactly as any other room.
    @ViewBuilder
    private func rosterRow(_ entry: RosterRow) -> some View {
        // The state arrives on the row. Asking per row would
        // be a boundary crossing per visible room per
        // re-render — see `core::roster::RosterRow`.
        RoomRowView(
            row: entry.row,
            avatarURI: session.avatars.uri(for: entry.row.room.id),
            state: entry.state,
            when: RelativeTime.label(
                for: entry.row.room.lastActivityMs, now: now),
            showsState: showsState,
            describesAgent: entry.describesAgent,
            onOpenInfo: { infoRequest = RoomInfoRequest(id: entry.row.room.id) }
        )
        .tag(entry.row.room.id)
        .listRowBackground(Theme.surface)
        .task { await session.avatars.load(entry.row.room.id) }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if entry.row.affordance == .compose {
                Button {
                    Task { await markRead(entry.row.room.id) }
                } label: {
                    Label("Mark read", systemImage: "checkmark.message")
                }
                .tint(Theme.accent)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if entry.row.affordance == .respondToInvitation {
                // Declining is leaving a room one was only invited to. Stale
                // test invitations are the common case, so it is a swipe away.
                Button(role: .destructive) {
                    Task { _ = await session.leaveRoom(entry.row.room.id) }
                } label: {
                    Label("Decline", systemImage: "xmark")
                }
            }
            if entry.row.affordance == .compose {
                let known = settings[entry.row.room.id]
                Button {
                    Task { await toggleMute(entry.row.room.id) }
                } label: {
                    known?.muted == true
                        ? Label("Unmute", systemImage: "bell")
                        : Label("Mute", systemImage: "bell.slash")
                }
                .tint(Theme.contentMuted)
                Button {
                    Task { await togglePin(entry.row.room.id) }
                } label: {
                    known?.pinned == true
                        ? Label("Unpin", systemImage: "pin.slash")
                        : Label("Pin", systemImage: "pin")
                }
                .tint(Theme.ok)
            }
        }
    }

    private func markRead(_ roomId: String) async {
        if await session.rooms.markRead(roomId) { swipeLanded += 1 }
    }

    /// Mute, or unmute a room that is muted.
    ///
    /// Asks the core what the room is set to first. The roster row does not
    /// carry it, and toggling a setting the view only believes it knows is
    /// how a "Mute" button unmutes something.
    private func toggleMute(_ roomId: String) async {
        guard let info = try? await session.roomInfo(roomId) else { return }
        let next = RoomToggles.nextNotificationMode(from: info.notifications)
        if await session.setNotifications(next, in: roomId) {
            settings[roomId] = KnownSettings(muted: next == .muted, pinned: info.pinned)
            swipeLanded += 1
        }
    }

    private func togglePin(_ roomId: String) async {
        guard let info = try? await session.roomInfo(roomId) else { return }
        let next = !info.pinned
        if await session.setPinned(next, in: roomId) {
            settings[roomId] = KnownSettings(muted: info.notifications == .muted, pinned: next)
            swipeLanded += 1
        }
    }

}

/// A room's notification and pin settings, as a swipe last heard them.
private struct KnownSettings {
    let muted: Bool
    let pinned: Bool
}

/// A section heading: what it is, how much of it, and whether it wants you.
private struct SectionHeader: View {
    let title: String
    let detail: String?
    let attention: Bool

    var body: some View {
        HStack(spacing: 6) {
            Text(title)
                .metaFace()
                .foregroundStyle(attention ? Theme.signal : Theme.contentMuted)
            if let detail {
                Text(detail).metaFace().foregroundStyle(Theme.contentFaint).textCase(nil)
            }
        }
    }
}

/// "Invitations · 2", at the top of the list when there are any.
///
/// Invitations used to be hidden behind a switch in a sheet, with the only
/// sign of them an envelope that replaced the options button's icon — which
/// then read as a second messaging action beside compose (2026-09-24). They
/// are something to act on, so they are a row in the list, as Messages does
/// with unknown senders and WhatsApp with its archive. Tapping it opens the
/// core's invitations section in place.
private struct InvitationsRow: View {
    let count: Int
    @Binding var isOpen: Bool

    var body: some View {
        Button {
            isOpen.toggle()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "envelope")
                    .foregroundStyle(Theme.accent)
                Text(count == 1 ? "1 invitation" : "\(count) invitations")
                    .foregroundStyle(Theme.content)
                Spacer()
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.contentMuted)
                    .rotationEffect(.degrees(isOpen ? 180 : 0))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(count == 1 ? "1 invitation" : "\(count) invitations")
        .accessibilityHint(isOpen ? "Hides them" : "Shows them")
    }
}

/// A room the reader has asked about, wrapping the id so `sheet(item:)` has
/// something `Identifiable` without a retroactive conformance on `String`.
private struct RoomInfoRequest: Identifiable {
    let id: String
}

#if DEBUG
// The roster as a reader meets it: five rooms, one of them owing an answer,
// two lines each, the state as a dot on the avatar.
#Preview("Waiting") {
    @Previewable @State var open: String?
    NavigationStack {
        RoomListView(session: PreviewFixtures.session(openRoom: false), selection: $open)
    }
}

// A synced account with no rooms at all.
//
// The empty state is a screen, and this is the one every new reader lands on.
#Preview("Nothing yet") {
    @Previewable @State var open: String?
    NavigationStack {
        RoomListView(session: PreviewFixtures.session(.empty), selection: $open)
    }
}

// Dark, where every ground must be the palette's rather than #000.
#Preview("Waiting, dark") {
    @Previewable @State var open: String?
    NavigationStack {
        RoomListView(session: PreviewFixtures.session(openRoom: false), selection: $open)
    }
    .preferredColorScheme(.dark)
}

// A fleet: agents with generated tiles, each a colour of its own.
#Preview("Fleet") {
    @Previewable @State var open: String?
    NavigationStack {
        RoomListView(session: NavigationRevampFixtures.fleetSession(), selection: $open)
    }
}
#endif
