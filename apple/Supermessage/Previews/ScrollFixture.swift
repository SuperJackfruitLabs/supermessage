#if DEBUG
import SupermessageFFI
import SupermessageKit
import SwiftUI

/// A long room to scroll, for reproducing timeline scrolling problems in the
/// simulator without an account: launch with `-fixtureTimeline`.
///
/// Shaped on the room in the 2026-09-23 screen recording — days of agent
/// churn (invited, joined, left, rejoined), an agent whose profile never
/// resolved, replies, a long report and short exchanges — so the list has
/// rows of very different heights, which is what exposes sizing jumps.
enum ScrollFixture {
    static let roomId = PreviewFixtures.roomId

    @MainActor
    static func session() -> Session {
        let session = PreviewFixtures.session(openRoom: false)
        session.rooms.select(roomId)
        return session
    }

    /// Hand the open room its history. Only after the room screen has
    /// subscribed: the store accepts envelopes addressed to the focused room,
    /// numbered from 1, and opening a room resets it — seeding earlier was
    /// wiped, and the first recording scrolled an empty list.
    @MainActor
    static func seed(_ session: Session) async {
        for _ in 0..<50 where session.timeline.roomId != roomId {
            try? await Task.sleep(for: .milliseconds(100))
        }
        session.timeline.handle(
            TimelineDiffEnvelope(
                channel: "timeline", subject: roomId, seq: 1, ops: [.reset(values: history)]))
    }

    static var history: [TimelineRow] {
        var rows: [TimelineRow] = []
        let day: UInt64 = 86_400_000
        let start: UInt64 = 1_757_000_000_000
        let agent = "@agent_strategy-sam:id.agentpod.dev"
        let bridge = "@strategy-sam:guild.example.org"
        for d in 0..<8 {
            let base = start + UInt64(d) * day
            rows.append(
                PreviewFixtures.row(
                    PreviewFixtures.item(id: "$day\(d)", body: nil, at: base, kind: "divider", msgtype: nil),
                    view: .dateDivider))
            let churn: [(String, String, String)] = [
                (agent, "Strategy Sam", "was invited"),
                ("@rakesh:example.org", "Rakesh", "updated their membership"),
                (agent, "Strategy Sam", "joined the room"),
                (bridge, "strategy-sam", "was invited"),
                (agent, "Strategy Sam", "left the room"),
            ]
            for (i, (sender, name, verb)) in churn.enumerated() {
                rows.append(
                    PreviewFixtures.row(
                        PreviewFixtures.item(
                            id: "$m\(d)-\(i)", sender: sender, body: nil, at: base + UInt64(i) * 1000,
                            kind: "membership", msgtype: nil),
                        view: .system(kind: .membershipChanged(who: name, detail: verb), text: "\(name) \(verb)"),
                        senderName: name, senderShort: name, senderInitial: String(name.prefix(1)),
                        membershipVerb: verb, canReplyOrReact: false))
            }
            let texts = [
                (false, "Hey — I'm here. What would you like to dig into?"),
                (true, "Hi. What do you see in this image?"),
                (false, "I don't see an image attached in this chat yet. Please upload it here or send a link or file path, and I'll take a look."),
                (true, "Hey"),
                (false, String(repeating: "I work best when there's a decision to make, a plan to build, or a messy situation to untangle. ", count: d % 3 == 0 ? 6 : 1)),
                (true, "Can you summarise yesterday's run?"),
            ]
            for (i, (own, text)) in texts.enumerated() {
                let at = base + 3_600_000 + UInt64(i) * 60_000
                let sender = own ? "@rakesh:example.org" : (d % 2 == 0 ? agent : bridge)
                let name = own ? "Rakesh" : (d % 2 == 0 ? "Strategy Sam" : "Strategy Sam (Hermes on Guild)")
                rows.append(
                    PreviewFixtures.row(
                        PreviewFixtures.item(id: "$t\(d)-\(i)", sender: sender, body: text, at: at, isOwn: own),
                        view: .bubble(muted: false, blocks: [.paragraph(inlines: [.text(text: text)])]),
                        senderName: name, senderShort: name, senderInitial: String(name.prefix(1))))
            }
        }
        return rows
    }
}

/// The fixture room on screen, the way a phone shows a room.
struct ScrollFixtureRoot: View {
    @State private var session = ScrollFixture.session()

    var body: some View {
        NavigationStack {
            RoomScreen(session: session, roomId: ScrollFixture.roomId)
        }
        .task { await ScrollFixture.seed(session) }
    }
}
#endif
