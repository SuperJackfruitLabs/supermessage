import SupermessageFFI
import SupermessageKit
import SwiftUI

/// One open room: its header, its timeline and composer, and its info panel.
///
/// Shared by both shells. On a phone it is pushed onto a tab's stack; on an
/// iPad it is the split view's detail column. The only difference between the
/// two is where room info goes — a sheet, or an inspector beside the
/// conversation — and the shell says which by `isWide`.
struct RoomScreen: View {
    let session: Session
    let roomId: String
    /// Whether the info panel fits *beside* the conversation. See
    /// `SplitShell.isWide` for why this is measured rather than inferred.
    var isWide: Bool = false
    /// Called in the same update that opens the panel, so a shell can free a
    /// column for it before the inspector lays out. See `SplitShell`.
    var onInfoOpening: () -> Void = {}
    /// Called when the panel closes, so a shell can restore its columns.
    var onInfoClosed: () -> Void = {}

    @State private var showsInfo = false
    /// The clock the header's state is measured against. Refreshed when the
    /// room changes, which is when the reader is looking at it.
    @State private var now = Date()
    /// How many people are here, for a room of people — the one thing its
    /// header can usefully say under the name.
    @State private var memberCount: UInt64?

    var body: some View {
        content
            // The panel describes one room, and a room switch must not leave
            // it asking about the one the reader just left.
            .onChange(of: roomId) { _, _ in
                now = Date()
                showsInfo = false
                memberCount = nil
            }
            // "Active" is a claim about the last 15 minutes, so the header's
            // clock has to move while the reader stays: without this, an
            // agent that went quiet stayed "Active" until the room changed.
            .task(id: roomId) {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(60))
                    now = Date()
                }
            }
    }

    @ViewBuilder private var content: some View {
        if let row = session.rooms.row(for: roomId) {
            room(row: row)
        } else if session.rooms.selectedId == roomId, let name = session.rooms.selectedName {
            // The room left the roster — a space switch — but the reader is
            // still in it. Keep showing it rather than blanking the pane.
            timeline(name: name)
                .navigationTitle(name)
                .navigationBarTitleDisplayMode(.inline)
        } else {
            ContentUnavailableView(
                "Room unavailable", systemImage: "bubble.left.and.bubble.right",
                description: Text("It may have been left or removed."))
        }
    }

    /// The room's one name.
    ///
    /// `RoomIdentity.name`, everywhere this screen says who the room is — the
    /// back button's title, the header, the accessibility label. D11 was one
    /// person going by three names; this screen at least says one.
    private func name(of row: RoomRow) -> String { row.identity.name }

    private func rosterRow(_ row: RoomRow) -> RosterRow? {
        RosterArrangement.rosterRow(for: roomId, in: [row], now: now)
    }

    @ViewBuilder private func room(row: RoomRow) -> some View {
        let arranged = rosterRow(row)
        let describesAgent = arranged?.describesAgent ?? false
        let status = RoomStatus.of(
            state: arranged?.state ?? RosterArrangement.state(for: row, now: now),
            describesAgent: describesAgent,
            turnInProgress: session.live.isLive && !session.live.finished)

        Group {
            if row.affordance == .respondToInvitation {
                // An invited room has no readable history, so there is nothing
                // to page through and no composer to offer.
                InvitationEmptyTimeline()
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        InvitationView(session: session, roomId: roomId, roomName: name(of: row))
                    }
            } else {
                timeline(name: name(of: row))
            }
        }
        // The name is still the back button's title, but what is *drawn* at
        // the top is the two-line header below.
        .navigationTitle(name(of: row))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                // The header is the way into room info; a separate ⓘ button
                // beside it would be a second door to the same room.
                Button(action: openInfo) {
                    RoomHeader(
                        name: name(of: row),
                        status: status,
                        memberCount: describesAgent ? nil : memberCount)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(name(of: row)), about this room")
            }
        }
        .task(id: roomId) {
            // Only a room of people says how many are in it; an agent's room
            // says what the agent is doing instead.
            guard !describesAgent, memberCount == nil,
                row.affordance != .respondToInvitation
            else { return }
            memberCount = try? await session.roomInfo(roomId).activeMemberCount
        }
        // On an iPad the panel comes in from the trailing edge, beside the
        // conversation rather than over it.
        //
        // **Only where it fits.** On a phone this modifier is not applied at
        // all rather than applied with a binding that is always false — see
        // `WideInspector` below.
        .modifier(
            WideInspector(
                isWide: isWide, showsInfo: $showsInfo,
                panel: { RoomInfoPanel(session: session, roomId: roomId) { showsInfo = false } }))
        .sheet(isPresented: Binding(get: { !isWide && showsInfo }, set: { showsInfo = $0 })) {
            RoomInfoPanel(session: session, roomId: roomId) { showsInfo = false }
                // Large by default. At the medium detent "Leave room" sat
                // below the fold, so the one destructive action in the app was
                // the one a reader had to go looking for.
                .presentationDetents([.large, .medium])
                .paletteSheet()
        }
        .onChange(of: showsInfo) { _, open in
            if !open { onInfoClosed() }
        }
    }

    /// Open the room-info panel. Both state changes in one update — see
    /// `SplitShell.detail` for the race this avoids.
    private func openInfo() {
        onInfoOpening()
        showsInfo = true
    }

    private func timeline(name: String) -> some View {
        TimelineView(session: session, timeline: session.timeline)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                ComposerView(session: session, roomId: roomId)
            }
            .task(id: roomId) {
                // Opening is what makes this the room every store is about.
                // Selecting here rather than only in the list is what lets a
                // phone's tabs each open a room: whichever screen is on top
                // is the one the stores follow.
                if session.rooms.selectedId != roomId { session.rooms.select(roomId) }
                await session.open(roomId: roomId)
            }
    }
}

/// The inspector, applied only where it can be shown.
///
/// On a phone the room's one panel is the sheet; attaching an inspector as
/// well — with a binding that is always false there — left a second
/// presentation of `RoomInfoPanel`, `Done` button and all, hanging off the
/// same navigation bar. It was one of the candidates for D10's "Done beside
/// Back"; a probe of the unopened state did not show it, so this is removal
/// of a suspect, not a confirmed fix.
private struct WideInspector<Panel: View>: ViewModifier {
    let isWide: Bool
    @Binding var showsInfo: Bool
    @ViewBuilder let panel: () -> Panel

    func body(content: Content) -> some View {
        if isWide {
            content.inspector(isPresented: $showsInfo) {
                panel().inspectorColumnWidth(min: 280, ideal: 340, max: 420)
            }
        } else {
            content
        }
    }
}

/// What a room says about itself at the top of the screen.
///
/// Two lines: what it is called, and — for an agent's room — what it is
/// doing, from a fixed vocabulary. A room of people says how many are in it,
/// or nothing.
struct RoomHeader: View {
    let name: String
    let status: RoomStatus?
    let memberCount: UInt64?

    var body: some View {
        VStack(spacing: 1) {
            Text(name)
                .font(.headline)
                .lineLimit(1)
            if let status {
                StatusPill(status: status, compact: true)
            } else if let memberCount, memberCount > 0 {
                Text(memberCount == 1 ? "1 member" : "\(memberCount) members")
                    .metaFace()
                    .foregroundStyle(Theme.contentMuted)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: 260)
    }
}

/// Working · Needs you · Active · Idle · Quiet, as a small pill.
///
/// Working pulses, unless Reduce Motion is on; a pending decision is the one
/// state drawn in amber, because it is the one that is a decision.
struct StatusPill: View {
    let status: RoomStatus
    var compact = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "circle.fill")
                .font(.system(size: compact ? 6 : 8))
                .foregroundStyle(dotColour)
                .symbolEffect(
                    .pulse, options: .repeating,
                    isActive: status == .working && !reduceMotion)
            Text(status.word)
                .metaFace()
                .foregroundStyle(status == .needsYou ? Theme.signal : Theme.contentMuted)
                .lineLimit(1)
        }
        .padding(.horizontal, compact ? 7 : 9)
        .padding(.vertical, compact ? 1 : 3)
        .background(background, in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(status.word)
    }

    private var dotColour: Color {
        switch status {
        // Working and Active share the roster dot's green; only Working
        // pulses, which is the difference between "is" and "was just".
        case .working, .active: return Theme.ok
        case .needsYou: return Theme.signal
        case .idle: return Theme.contentFaint
        case .quiet: return Theme.border
        }
    }

    private var background: Color {
        status == .needsYou ? Theme.signalSoft : Theme.surfaceRaised
    }
}

#if DEBUG
#Preview("Header statuses") {
    PreviewGround {
        VStack(spacing: 16) {
            ForEach(RoomStatus.allCases, id: \.self) { status in
                RoomHeader(name: "Atlas", status: status, memberCount: nil)
            }
            RoomHeader(name: "Design review", status: nil, memberCount: 4)
            RoomHeader(name: "Quiet room", status: nil, memberCount: nil)
        }
    }
}

#Preview("Header statuses, dark") {
    PreviewGround {
        VStack(spacing: 16) {
            ForEach(RoomStatus.allCases, id: \.self) { status in
                RoomHeader(name: "Atlas", status: status, memberCount: nil)
            }
        }
    }
    .preferredColorScheme(.dark)
}
#endif
