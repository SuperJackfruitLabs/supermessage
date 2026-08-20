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

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
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
            VStack(alignment: .leading, spacing: 1) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(row.identity.name)
                        .nameFace()
                        .lineLimit(1)
                    if row.affordance == .respondToInvitation {
                        Text("Invitation")
                            .metaFace()
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .overlay(Capsule().stroke(Theme.accent, lineWidth: 1))
                    }
                    Spacer(minLength: 4)
                    if !when.isEmpty {
                        Text(when).metaFace().foregroundStyle(.tertiary)
                    }
                    if row.room.unread > 0 {
                        UnreadBadge(count: row.room.unread)
                    }
                }

                // State, harness and host on one quiet line. Everything here is
                // metadata *about* the room; the preview below is the room
                // itself speaking, and the two should not compete.
                if let meta = metaLine {
                    HStack(spacing: 5) {
                        if showsState {
                            Circle()
                                .fill(dotColour)
                                .strokeBorder(
                                    state == .quiet ? Color.secondary.opacity(0.5) : .clear,
                                    lineWidth: 1)
                                .frame(width: 7, height: 7)
                        }
                        Text(meta)
                            .metaFace()
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                    }
                }

                if let preview = row.preview {
                    Text(preview.text)
                        .font(.subheadline)
                        // The row's amber switch, and the only place this view
                        // may use it. If it is on screen, the operator owes
                        // someone an answer.
                        .foregroundStyle(preview.pending ? Theme.signal : Color.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 6)
        .contentShape(.rect)
    }

    /// State and runtime, joined only where both exist.
    ///
    /// `nil` collapses the line entirely rather than drawing an empty row —
    /// the same posture as the preview, which says nothing when there is
    /// nothing to say.
    private var metaLine: String? {
        var parts: [String] = []
        if showsState { parts.append(state.word) }
        if let runtime = row.room.runtime {
            parts.append(runtime.harness)
            // The host is the section header in the machine view, so repeating
            // it on every row there would be saying it twice.
            if !hidesHost { parts.append(runtime.host) }
        } else if let role = row.identity.role {
            parts.append(role)
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// Set by the machine view, whose section header already names the host.
    var hidesHost: Bool = false
    /// Open this room's info. When `nil` the avatar is not a control — a
    /// picture that does nothing when tapped should not look tappable.
    var onOpenInfo: (() -> Void)?

    private var dotColour: Color {
        switch state {
        case .needsYou: return Theme.signal
        case .active: return Theme.ok
        case .idle: return Color.secondary.opacity(0.55)
        case .quiet: return .clear
        }
    }

    /// The avatar, or the initial the core derived from the *parsed* name.
    ///
    /// Never the raw name's first character: for a structured room that is the
    /// glyph, and taking it directly is the bug `core::room_identity` exists to
    /// have fixed once.
    @ViewBuilder private var avatar: some View {
        ZStack {
            Circle().fill(.quaternary)
            if let avatarURI, let image = Self.image(from: avatarURI) {
                image.resizable().scaledToFill().clipShape(Circle())
            } else {
                Text(row.identity.initial).font(.subheadline)
            }
        }
        .frame(width: 34, height: 34)
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
