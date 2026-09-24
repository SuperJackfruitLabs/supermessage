import SupermessageFFI
import SupermessageKit
import SwiftUI

/// One roster row.
///
/// Everything on it was decided by the core — the sigil and name come from
/// `row.identity`, the preview line from `row.preview`. This view parses
/// nothing and composes nothing; it lays out what it was handed.
struct RoomRowView: View {
    let row: RoomRow
    let avatarURI: String?
    /// What the roster may say this agent is doing.
    let state: AgentState
    /// Coarsened by `RelativeTime`, empty when the room has never spoken.
    let when: String
    /// Whether to draw the state dot at all — a reader can turn it off.
    var showsState: Bool = true
    /// Whether the room reads as an agent's — `RosterRow.describesAgent`.
    /// "idle" under a room of people says nothing anyone wants to know.
    var describesAgent: Bool = true
    /// Open this room's info. When `nil` the avatar is not a control — a
    /// picture that does nothing when tapped should not look tappable.
    var onOpenInfo: (() -> Void)?

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            // The avatar is the shortcut into room info: it is the one part
            // of the row that is *about* the room rather than about the
            // conversation, so tapping it asks about the room and tapping
            // anywhere else opens the conversation.
            if let onOpenInfo {
                Button(action: onOpenInfo) { avatar }
                    .buttonStyle(.plain)
                    .accessibilityLabel("About \(row.identity.name)")
            } else {
                avatar
            }

            // Two lines, and only two: who, and what they last said. The
            // state is the dot on the avatar and the runtime lives in room
            // info — the "active · claude-code · foundry" line that used to
            // sit between them was metadata competing with the conversation.
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(row.identity.name)
                        .nameFace()
                        .lineLimit(1)
                    if row.affordance == .respondToInvitation {
                        Text("Invitation")
                            .metaFace()
                            .foregroundStyle(Theme.accent)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 1)
                            .overlay(Capsule().stroke(Theme.accent, lineWidth: 1))
                    }
                    Spacer(minLength: 4)
                    if !when.isEmpty {
                        Text(when)
                            .metaFace()
                            .foregroundStyle(row.room.unread > 0 ? Theme.accent : Theme.contentFaint)
                    }
                }

                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(previewText)
                        .font(.subheadline)
                        // The row's amber switch. If it is on screen, the
                        // operator owes someone an answer.
                        .foregroundStyle(previewColour)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    if row.room.unread > 0 {
                        UnreadBadge(count: row.room.unread)
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }

    /// The preview, or the room's role when it has said nothing — never an
    /// invented placeholder. A room with neither shows an empty line, which
    /// keeps every row the same height.
    private var previewText: String {
        if let preview = row.preview { return preview.text }
        return row.identity.role ?? " "
    }

    private var previewColour: Color {
        guard let preview = row.preview else { return Theme.contentFaint }
        return preview.pending ? Theme.signal : Theme.contentMuted
    }

    /// The state is drawn when the reader wants it and it means something:
    /// always for a pending decision, otherwise only for an agent's room.
    private var drawsState: Bool {
        showsState && (describesAgent || state == .needsYou)
    }

    private var avatar: some View {
        RoomAvatar(
            roomId: row.room.id, initial: row.identity.initial, avatarURI: avatarURI,
            describesAgent: describesAgent, state: drawsState ? state : nil)
    }

    /// Decode the `data:` URI the core produced. No network, no URL loading —
    /// the bytes already crossed the boundary.
    static func image(from uri: String) -> Image? {
        guard let comma = uri.firstIndex(of: ","),
            let data = Data(base64Encoded: String(uri[uri.index(after: comma)...])),
            let ui = UIImage(data: data)
        else { return nil }
        return Image(uiImage: ui)
    }
}

/// How many messages a room has that the reader has not seen.
///
/// Never amber. An unread count is not something owed — it is something
/// waiting, and the console spec reserves amber for the former.
private struct UnreadBadge: View {
    let count: UInt64

    var body: some View {
        Text(count > 99 ? "99+" : "\(count)")
            .metaFace()
            .foregroundStyle(.white)
            .padding(.horizontal, 5)
            .frame(minWidth: 19, minHeight: 19)
            .background(Theme.accent, in: Capsule())
            .accessibilityLabel("\(count) unread")
    }
}

#if DEBUG
// The four states `RoomRow` can arrive in, in one frame.
//
// Together rather than separately on purpose: the whole point of the state
// word and the pending mark is that they are *distinguishable at a glance in
// a list*, and a row previewed alone cannot show that. Reading down this
// preview, exactly one row is amber — that is `docs/design-language.md` §2,
// and a second amber row anywhere in it is a defect rather than a taste
// disagreement.
#Preview("Every state") {
    List {
        RoomRowView(
            row: PreviewFixtures.roomNeedsYou, avatarURI: nil, state: .needsYou, when: "2m")
        RoomRowView(row: PreviewFixtures.roomActive, avatarURI: nil, state: .active, when: "14m")
        RoomRowView(
            row: PreviewFixtures.roomInvitation, avatarURI: nil, state: .idle, when: "",
            describesAgent: false)
        RoomRowView(row: PreviewFixtures.roomQuiet, avatarURI: nil, state: .quiet, when: "3d")
        RoomRowView(
            row: PreviewFixtures.roomBare, avatarURI: nil, state: .idle, when: "1h",
            describesAgent: false)
    }
    .listStyle(.plain)
}

// The same rows in dark, where the palette ramp runs the other way.
//
// `content-faint`'s contrast contract lists all three grounds rather than
// only the reading surface, because in dark the ground it fails on flips to
// `surface-raised`. A roster row sits on one of those, so this is where that
// would show.
#Preview("Every state, dark") {
    List {
        RoomRowView(
            row: PreviewFixtures.roomNeedsYou, avatarURI: nil, state: .needsYou, when: "2m")
        RoomRowView(row: PreviewFixtures.roomActive, avatarURI: nil, state: .active, when: "14m")
        RoomRowView(row: PreviewFixtures.roomQuiet, avatarURI: nil, state: .quiet, when: "3d")
    }
    .listStyle(.plain)
    .preferredColorScheme(.dark)
}

// The state word turned off, which is a stored roster preference.
//
// `showsState` defaults to `true`, so every other preview here is showing the
// default. This is the arrangement a reader who turned it off actually has,
// and the question it answers is whether the row still balances without it.
#Preview("State word hidden") {
    List {
        RoomRowView(
            row: PreviewFixtures.roomNeedsYou, avatarURI: nil, state: .needsYou, when: "2m",
            showsState: false)
        RoomRowView(
            row: PreviewFixtures.roomActive, avatarURI: nil, state: .active, when: "14m",
            showsState: false)
    }
    .listStyle(.plain)
}
#endif
