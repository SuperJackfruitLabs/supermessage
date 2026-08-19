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

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            avatar
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(row.identity.name)
                        .font(Theme.name)
                        .lineLimit(1)
                    if let role = row.identity.role {
                        Text(role)
                            .font(Theme.meta)
                            .textCase(.uppercase)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    if row.affordance == .respondToInvitation {
                        Text("Invitation")
                            .font(Theme.meta)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .overlay(Capsule().stroke(Theme.accent, lineWidth: 1))
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
