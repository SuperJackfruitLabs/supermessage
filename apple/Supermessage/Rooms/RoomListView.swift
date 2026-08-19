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
    @AppStorage("roster.view") private var storedView = RosterView.waiting.rawValue
    @AppStorage("roster.showsInvitations") private var showsInvitations = false
    @AppStorage("roster.showsState") private var showsState = true

    @State private var showsSettings = false
    /// Re-read on every roster change so "2m" does not sit at "2m" all day.
    @State private var now = Date()

    private var view: RosterView { RosterView(rawValue: storedView) ?? .waiting }

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
                picker
                    .listRowInsets(EdgeInsets(top: 4, leading: 12, bottom: 4, trailing: 12))
                    .listRowSeparator(.hidden)
            } header: {
                // Inside the scroll content on purpose — see SpacePillStrip.
                if !session.spaces.spaces.isEmpty {
                    SpacePillStrip(spaces: session.spaces)
                        .textCase(nil)
                        .listRowInsets(EdgeInsets())
                }
            }

            ForEach(sections) { section in
                Section {
                    ForEach(section.rows, id: \.room.id) { row in
                        RoomRowView(
                            row: row,
                            avatarURI: session.avatars.uri(for: row.room.id),
                            state: RosterArrangement.state(for: row, now: now),
                            when: RelativeTime.label(for: row.room.lastActivityMs, now: now),
                            showsState: showsState,
                            hidesHost: view == .machine
                        )
                        .tag(row.room.id)
                        .task { await session.avatars.load(row.room.id) }
                    }
                } header: {
                    if let title = section.title {
                        SectionHeader(title: title, detail: section.detail, attention: section.attention)
                    }
                }
            }
        }
        .listStyle(.plain)
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
    }

    /// The arrangement switcher, plus a way into the rest.
    private var picker: some View {
        HStack(spacing: 8) {
            Picker("Arrangement", selection: $storedView) {
                ForEach(RosterView.allCases, id: \.rawValue) { option in
                    Text(option.title).tag(option.rawValue)
                }
            }
            .pickerStyle(.segmented)

            Button { showsSettings = true } label: {
                // Admits to what is being withheld. Hidden must never mean
                // gone: a roster that silently drops a room you were invited
                // to is a roster that lost it.
                if hiddenInvitations > 0 {
                    Label("\(hiddenInvitations)", systemImage: "envelope")
                        .labelStyle(.titleAndIcon)
                        .metaFace()
                } else {
                    Image(systemName: "line.3.horizontal.decrease")
                }
            }
            .buttonStyle(.plain)
            .foregroundStyle(Theme.accent)
            .accessibilityLabel(
                hiddenInvitations > 0
                    ? "Roster options, \(hiddenInvitations) invitations hidden" : "Roster options")
        }
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
                    ForEach(RosterView.allCases, id: \.rawValue) { option in
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

    private func blurb(for option: RosterView) -> String {
        switch option {
        case .recent: return "newest first"
        case .waiting: return "what needs an answer, then the rest"
        case .machine: return "grouped by the machine it runs on"
        }
    }
}
