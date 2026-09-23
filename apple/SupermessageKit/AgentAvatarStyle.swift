import Foundation

/// The generated tile an agent's room wears when it has no picture.
///
/// **Deterministic from the room id, and only from it.** A fleet of agents
/// that all draw the same grey circle is a list a reader has to read; one
/// where Atlas is always the same tile is a list they can recognise. The id
/// is the one thing about a room that never changes — a rename must not
/// repaint it — and the hash is FNV-1a rather than `Hasher`, because Swift
/// seeds `Hasher` per process, so the same room would change colour on every
/// launch.
///
/// **Palette roles only.** The Kit imports no SwiftUI, so a tile is named in
/// roles the view resolves through `Theme`. `signal` is not among them and
/// cannot be: amber means a pending decision and only `DecisionCard` draws it.
/// `danger` is left out for the same kind of reason — a red face reads as an
/// error before it reads as a name.
public struct AgentAvatarStyle: Equatable, Sendable {
    public enum Role: String, CaseIterable, Sendable {
        case accent
        case accentSoft
        case accentContent
        case ok
        case contentMuted
        case surface
        case surfaceRaised
    }

    /// What the tile is filled with.
    public let ground: Role
    /// The glyph or initial drawn on it.
    public let ink: Role

    /// Every pairing, each chosen so the ink clears the ground in both
    /// appearances (`accentContent` is defined as what is legible on
    /// `accent`; the others pair a light role with a dark one in paper and
    /// the reverse in dark).
    public static let variants: [AgentAvatarStyle] = [
        AgentAvatarStyle(ground: .accent, ink: .accentContent),
        AgentAvatarStyle(ground: .accentSoft, ink: .accent),
        AgentAvatarStyle(ground: .ok, ink: .surface),
        AgentAvatarStyle(ground: .surfaceRaised, ink: .accent),
        AgentAvatarStyle(ground: .contentMuted, ink: .surface),
        AgentAvatarStyle(ground: .surfaceRaised, ink: .ok),
    ]

    /// The tile for `roomId`.
    public static func forRoom(_ roomId: String) -> AgentAvatarStyle {
        variants[Int(fnv1a(roomId) % UInt64(variants.count))]
    }

    /// 64-bit FNV-1a over the UTF-8 bytes. Stable across launches, devices
    /// and platforms, which is the entire requirement.
    static func fnv1a(_ text: String) -> UInt64 {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        return hash
    }
}
