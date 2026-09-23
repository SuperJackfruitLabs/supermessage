#if DEBUG
import Foundation
import SupermessageFFI
import SupermessageKit

/// Fixtures for the navigation revamp: the tab bar, the Needs you inbox, the
/// Agents directory and the two-line roster with generated agent tiles.
///
/// Built on `PreviewFixtures.roomRow`, and with the same rule it follows: the
/// identities are what the core's parser produces for these names, stated
/// rather than derived, so this file is not a second parser.
enum NavigationRevampFixtures {
    /// Ids chosen to land on different tiles, so a preview of the tiles shows
    /// the palette rather than one colour six times.
    static var agentIds: [String] {
        var picked: [String] = []
        var seen: Set<Int> = []
        var n = 0
        while picked.count < AgentAvatarStyle.variants.count, n < 500 {
            let id = "!agent\(n):example.org"
            let index = AgentAvatarStyle.variants.firstIndex(of: AgentAvatarStyle.forRoom(id)) ?? 0
            if seen.insert(index).inserted { picked.append(id) }
            n += 1
        }
        return picked
    }

    /// Minutes before the fixtures' clock — the real one, since a roster
    /// preview measures recency against `Date()`.
    static func ago(_ minutes: Double) -> UInt64 {
        UInt64((Date().timeIntervalSince1970 - minutes * 60) * 1000)
    }

    /// A fleet: agents in every state, a room of people, and an invitation.
    static var fleet: [RoomRow] {
        [
            PreviewFixtures.roomRow(
                id: "!superpipeline:example.org", rawName: "⌘ Superpipeline — Delivery", glyph: "⌘",
                name: "Superpipeline", role: "Delivery", initial: "⌘",
                preview: RoomPreview(text: "Merge this branch into main?", pending: true),
                unread: 2, lastActivityMs: ago(3),
                runtime: RuntimeDto(harness: "superpipeline", host: "foundry")),
            PreviewFixtures.roomRow(
                id: "!atlas:example.org", rawName: "✳ Atlas — Platform", glyph: "✳",
                name: "Atlas", role: "Platform", initial: "✳",
                preview: RoomPreview(text: "Atlas: Rebased onto main and the diff is empty.", pending: false),
                unread: 1, lastActivityMs: ago(6),
                runtime: RuntimeDto(harness: "claude-code", host: "foundry")),
            PreviewFixtures.roomRow(
                id: "!sam:example.org", rawName: "◆ Strategy Sam — Strategy", glyph: "◆",
                name: "Strategy Sam", role: "Strategy", initial: "◆",
                preview: RoomPreview(text: "Can I open a draft PR for the pricing page?", pending: true),
                lastActivityMs: ago(40),
                runtime: RuntimeDto(harness: "codex", host: "studio")),
            PreviewFixtures.roomRow(
                id: "!design:example.org", rawName: "Design review", glyph: nil,
                name: "Design Review", role: nil, initial: "D",
                preview: RoomPreview(text: "Priya: the new tab bar reads well", pending: false),
                unread: 4, lastActivityMs: ago(90)),
            PreviewFixtures.roomRow(
                id: "!quill:example.org", rawName: "✒ Quill — Writing", glyph: "✒",
                name: "Quill", role: "Writing", initial: "✒",
                preview: RoomPreview(text: "Draft is in the branch.", pending: false),
                lastActivityMs: ago(60 * 24 * 3)),
            PreviewFixtures.roomRow(
                id: "!estate:example.org", rawName: "Estate Planning", glyph: nil,
                name: "Estate Planning", role: nil, initial: "E", preview: nil,
                lastActivityMs: nil, membership: .invited, affordance: .respondToInvitation),
        ]
    }

    /// The same fleet with every decision answered and no invitation left:
    /// what "finished" looks like.
    static var caughtUp: [RoomRow] {
        fleet
            .filter { $0.affordance != .respondToInvitation }
            .map { row in
                var answered = row
                if let preview = row.preview {
                    answered.preview = RoomPreview(text: preview.text, pending: false)
                }
                return answered
            }
    }

    @MainActor
    static func session(rows: [RoomRow]) -> Session {
        let session = Session(previewClient: PreviewClient(), phase: .signedIn)
        session.connection.apply(ConnectionState(state: "live", message: nil))
        session.rooms.handle(
            RoomDiffEnvelope(channel: "rooms", subject: "", seq: 1, ops: [.reset(values: rows)]))
        return session
    }

    @MainActor static func fleetSession() -> Session { session(rows: fleet) }
    @MainActor static func caughtUpSession() -> Session { session(rows: caughtUp) }
}
#endif
