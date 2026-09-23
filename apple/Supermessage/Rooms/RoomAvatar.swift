import SupermessageFFI
import SupermessageKit
import SwiftUI

/// A room's face: its picture, or a generated tile, with the agent's state as
/// a dot on its corner.
///
/// Agents and people are told apart by shape before anything is read — an
/// agent is a rounded square in a colour of its own, a person or a room of
/// people is a circle — and an agent also wears a small badge, so the
/// distinction does not rest on shape alone.
struct RoomAvatar: View {
    let roomId: String
    /// The core's parsed initial — for a structured room, its glyph.
    let initial: String
    let avatarURI: String?
    let describesAgent: Bool
    /// The state dot, or `nil` for none.
    let state: AgentState?
    var size: CGFloat = 40

    var body: some View {
        face
            .frame(width: size, height: size)
            .overlay(alignment: .topTrailing) {
                if describesAgent { AgentBadge(size: size * 0.36).offset(x: 3, y: -3) }
            }
            .overlay(alignment: .bottomTrailing) {
                if let state, let colour = Self.dotColour(state) {
                    Circle()
                        .fill(colour)
                        .frame(width: size * 0.3, height: size * 0.3)
                        .overlay(Circle().stroke(Theme.surface, lineWidth: 2))
                        .offset(x: 2, y: 2)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText)
    }

    @ViewBuilder private var face: some View {
        if let avatarURI, let image = RoomRowView.image(from: avatarURI) {
            image.resizable().scaledToFill().clipShape(shape)
        } else if describesAgent {
            let style = AgentAvatarStyle.forRoom(roomId)
            ZStack {
                shape.fill(Self.colour(style.ground))
                shape.stroke(Theme.border, lineWidth: style.ground == .surfaceRaised ? 1 : 0)
                Text(initial)
                    .font(.system(size: size * 0.44, weight: .semibold))
                    .foregroundStyle(Self.colour(style.ink))
                    .minimumScaleFactor(0.5)
            }
        } else {
            ZStack {
                shape.fill(Theme.surfaceRaised)
                shape.stroke(Theme.border, lineWidth: 1)
                Text(initial)
                    .font(.system(size: size * 0.42, weight: .medium))
                    .foregroundStyle(Theme.contentMuted)
                    .minimumScaleFactor(0.5)
            }
        }
    }

    private var shape: AnyShape {
        describesAgent
            ? AnyShape(RoundedRectangle(cornerRadius: size * 0.28, style: .continuous))
            : AnyShape(Circle())
    }

    private var accessibilityText: String {
        var parts: [String] = []
        if describesAgent { parts.append("Agent") }
        if let state, Self.dotColour(state) != nil { parts.append(state.word) }
        return parts.joined(separator: ", ")
    }

    /// The same vocabulary everywhere a room's state is drawn. Quiet draws
    /// nothing: absence is not a state worth a mark of its own.
    static func dotColour(_ state: AgentState) -> Color? {
        switch state {
        // A pending decision — the one meaning amber has.
        case .needsYou: return Theme.signal
        case .active: return Theme.ok
        case .idle: return Theme.contentFaint
        case .quiet: return nil
        }
    }

    static func colour(_ role: AgentAvatarStyle.Role) -> Color {
        switch role {
        case .accent: return Theme.accent
        case .accentSoft: return Theme.accentSoft
        case .accentContent: return Theme.accentContent
        case .ok: return Theme.ok
        case .contentMuted: return Theme.contentMuted
        case .surface: return Theme.surface
        case .surfaceRaised: return Theme.surfaceRaised
        }
    }
}

/// The small mark that says "this is an agent" on an avatar's corner.
struct AgentBadge: View {
    var size: CGFloat = 14

    var body: some View {
        Image(systemName: "sparkle")
            .font(.system(size: size * 0.6, weight: .bold))
            .foregroundStyle(Theme.accentContent)
            .frame(width: size, height: size)
            .background(Theme.accent, in: Circle())
            .overlay(Circle().stroke(Theme.surface, lineWidth: 1.5))
            .accessibilityHidden(true)
    }
}

#if DEBUG
#Preview("Avatars") {
    PreviewGround {
      VStack(alignment: .leading, spacing: 16) {
        HStack(spacing: 16) {
            ForEach(NavigationRevampFixtures.agentIds, id: \.self) { id in
                RoomAvatar(
                    roomId: id, initial: String(id.dropFirst().prefix(1)).uppercased(),
                    avatarURI: nil, describesAgent: true, state: .idle)
            }
        }
        HStack(spacing: 16) {
            RoomAvatar(
                roomId: "!a", initial: "✳", avatarURI: nil, describesAgent: true, state: .active)
            RoomAvatar(
                roomId: "!b", initial: "⌘", avatarURI: nil, describesAgent: true, state: .needsYou)
            RoomAvatar(
                roomId: "!c", initial: "M", avatarURI: nil, describesAgent: false, state: nil)
        }
      }
    }
}

#Preview("Avatars, dark") {
    PreviewGround {
        HStack(spacing: 16) {
            ForEach(NavigationRevampFixtures.agentIds, id: \.self) { id in
                RoomAvatar(
                    roomId: id, initial: String(id.dropFirst().prefix(1)).uppercased(),
                    avatarURI: nil, describesAgent: true, state: .active)
            }
        }
    }
    .preferredColorScheme(.dark)
}
#endif
