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
    @AppStorage("roster.showsInvitations") private var showsInvitations = false
    @AppStorage("roster.showsState") private var showsState = true
    @AppStorage("roster.filter") private var storedFilter = RosterFilter.all.rawValue

    @State private var showsSettings = false
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

    private var view: RosterChoice { RosterChoice(rawValue: storedView) ?? .waiting }
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
    private var arranged: [RosterSection] {
        RosterArrangement.sections(
            session.rooms.rooms, view: view, showsInvitations: showsInvitations, now: now)
    }

    /// The arrangement, narrowed by the chip. Never re-ordered.
    private var sections: [RosterSection] { filter.apply(arranged) }

    private var counts: [RosterFilter: Int] {
        let rows = arranged.flatMap(\.rows)
        return Dictionary(
            uniqueKeysWithValues: RosterFilter.allCases.map { chip in
                (chip, rows.filter(chip.admits).count)
            })
    }

    private var hiddenInvitations: Int {
        RosterArrangement.hiddenInvitations(session.rooms.rooms, showsInvitations: showsInvitations)
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
                }
                .textCase(nil)
                .listRowInsets(EdgeInsets())
            }

            ForEach(sections, id: \.id) { section in
                Section {
                    ForEach(section.rows, id: \.row.room.id) { entry in
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
        .paletteListGround()
        .sensoryFeedback(.success, trigger: swipeLanded)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { arrangementMenu }
        }
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
            } else if sections.isEmpty {
                // Every room was filtered away. Say so, and say what by.
                ContentUnavailableView(
                    "Nothing but invitations", systemImage: "envelope",
                    description: Text("\(hiddenInvitations) waiting. Turn them on to see them."))
            }
        }
        .sheet(isPresented: $showsSettings) {
            RosterSettings(
                view: $storedView, showsInvitations: $showsInvitations, showsState: $showsState,
                invitationCount: session.rooms.rooms.filter {
                    $0.affordance == .respondToInvitation
                }.count
            ) { showsSettings = false }
            .presentationDetents([.medium])
            .paletteSheet()
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

/// The arrangement switcher, in the toolbar with compose.
    ///
    /// It used to be a segmented control pinned inside the list, which meant
    /// the roster carried a second permanent bar of chrome above it, and the
    /// one control that is *not* about the list's contents was the one
    /// sitting in them. A menu also has room to name each arrangement
    /// properly, which three segments never did.
    private var arrangementMenu: some View {
        Menu {
            Picker("Arrangement", selection: $storedView) {
                ForEach(RosterChoice.allCases, id: \.rawValue) { option in
                    Text(option.title).tag(option.rawValue)
                }
            }
            .pickerStyle(.inline)

            Divider()

            Button("Roster options") { showsSettings = true }
        } label: {
            // Admits to what is being withheld. Hidden must never mean gone:
            // a roster that silently drops a room you were invited to is a
            // roster that lost it.
            if hiddenInvitations > 0 {
                Label("\(hiddenInvitations)", systemImage: "envelope")
                    .labelStyle(.titleAndIcon)
            } else {
                Image(systemName: "line.3.horizontal.decrease.circle")
            }
        }
        .accessibilityLabel(
            hiddenInvitations > 0
                ? "Roster options, \(hiddenInvitations) invitations hidden" : "Roster options")
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

/// What the roster opens on, and what it leaves out.
private struct RosterSettings: View {
    @Binding var view: String
    @Binding var showsInvitations: Bool
    @Binding var showsState: Bool
    let invitationCount: Int
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section("Open the roster on") {
                    ForEach(RosterChoice.allCases, id: \.rawValue) { option in
                        Button { view = option.rawValue } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(option.title).foregroundStyle(Theme.content)
                                    Text(blurb(for: option))
                                        .metaFace()
                                        .foregroundStyle(Theme.contentMuted)
                                }
                                Spacer()
                                if view == option.rawValue {
                                    Image(systemName: "checkmark").foregroundStyle(Theme.accent)
                                }
                            }
                        }
                    }
                }

                Section("Show") {
                    Toggle(isOn: $showsInvitations) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Invitations")
                            Text(
                                invitationCount == 1
                                    ? "1 pending" : "\(invitationCount) pending"
                            )
                            .metaFace()
                            .foregroundStyle(Theme.contentMuted)
                        }
                    }
                    Toggle(isOn: $showsState) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Agent state")
                            Text("the dot and its word")
                                .metaFace()
                                .foregroundStyle(Theme.contentMuted)
                        }
                    }
                }
            }
            .paletteGroupedGround()
            .navigationTitle("Roster")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) }
            }
        }
    }

    private func blurb(for option: RosterChoice) -> String {
        switch option {
        case .recent: return "newest first"
        case .waiting: return "what needs an answer, then the rest"
        case .machine: return "grouped by the machine it runs on"
        }
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
