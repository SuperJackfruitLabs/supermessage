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
    /// Whether a `nil` from the list means "popped back to the roster".
    ///
    /// Decided by the view that owns the `NavigationSplitView`, not read from
    /// the environment here: **a column reports its own width**, and a sidebar
    /// on an iPad is compact. Asking inside this view gave the answer for the
    /// sidebar rather than for the window, so an iPad would have obeyed a `nil`
    /// and closed the room the reader was in.
    let clearsSelectionOnPop: Bool

    /// The arrangement the app opens on, and the filters, all remembered.
    @AppStorage("roster.view") private var storedView = RosterChoice.waiting.rawValue
    @AppStorage("roster.showsInvitations") private var showsInvitations = false
    @AppStorage("roster.showsState") private var showsState = true

    @State private var showsSettings = false
    /// Re-read on every roster change so "2m" does not sit at "2m" all day.
    @State private var now = Date()
    /// The room whose info panel is open from the roster, if any.
    @State private var infoRequest: RoomInfoRequest?

    private var view: RosterChoice { RosterChoice(rawValue: storedView) ?? .waiting }

    private var sections: [RosterSection] {
        RosterArrangement.sections(
            session.rooms.rooms, view: view, showsInvitations: showsInvitations, now: now)
    }

    private var hiddenInvitations: Int {
        RosterArrangement.hiddenInvitations(session.rooms.rooms, showsInvitations: showsInvitations)
    }

    var body: some View {
        List(selection: selectionBinding) {
            Section {
                EmptyView()
            } header: {
                // Inside the scroll content on purpose — see SpacePillStrip.
                if !session.spaces.spaces.isEmpty {
                    SpacePillStrip(spaces: session.spaces, allCount: session.rooms.rooms.count)
                        .textCase(nil)
                        .listRowInsets(EdgeInsets())
                }
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
                            hidesHost: view == .machine,
                            onOpenInfo: { infoRequest = RoomInfoRequest(id: entry.row.room.id) }
                        )
                        .tag(entry.row.room.id)
                        .task { await session.avatars.load(entry.row.room.id) }
                    }
                } header: {
                    if let title = section.title {
                        SectionHeader(title: title, detail: section.detail, attention: section.attention)
                    }
                }
            }
        }
        .listStyle(.plain)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { arrangementMenu }
        }
        .navigationTitle(session.spaces.selectedName ?? "All rooms")
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
        }
        // Reached by tapping a row's avatar. Presented from the roster rather
        // than by opening the room first: asking what a room *is* should not
        // require entering the conversation and marking it read.
        .sheet(item: $infoRequest) { request in
            RoomInfoPanel(session: session, roomId: request.id) { infoRequest = nil }
                .presentationDetents([.large, .medium])
        }
    }

/// The arrangement switcher, in the toolbar with search and compose.
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

    /// Selection, in both directions.
    ///
    /// **The `set` used to drop `nil` on the floor.** A collapsed
    /// `NavigationSplitView` navigates by selection and writes `nil` back when
    /// it pops — swallowing that left the row highlighted after coming back,
    /// and left `List`'s idea of its selection disagreeing with ours, so
    /// tapping the same room again produced no change and it would not reopen.
    private var selectionBinding: Binding<String?> {
        Binding(
            get: { session.rooms.selectedId },
            set: { next in
                if let id = next {
                    session.rooms.select(id)
                } else if clearsSelectionOnPop {
                    session.rooms.deselect()
                }
            })
    }
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
                .foregroundStyle(attention ? Theme.signal : .secondary)
            if let detail {
                Text(detail).metaFace().foregroundStyle(.tertiary).textCase(nil)
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
                                    Text(option.title).foregroundStyle(.primary)
                                    Text(blurb(for: option))
                                        .metaFace()
                                        .foregroundStyle(.secondary)
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
                            .foregroundStyle(.secondary)
                        }
                    }
                    Toggle(isOn: $showsState) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Agent state")
                            Text("the dot and its word")
                                .metaFace()
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
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
