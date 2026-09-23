#if DEBUG
import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Fixtures for the 2026-09-23 timeline revamp: agent cards, long reads,
/// receipts as faces, reply quotes next to their parent, membership churn,
/// and a finished turn's record.
///
/// Kept apart from `PreviewFixtures.swift`, which other work edits at the same
/// time; the shapes follow its `item`/`row` helpers exactly.
extension PreviewFixtures {
    static let agentId = "@agent_ashram_openclaw-atlas:example.org"

    static func agentBody(_ text: String) -> ItemView {
        .bubble(muted: false, blocks: [.paragraph(inlines: [.text(text: text)])])
    }

    /// An agent's message, from an id in the `@agent_` namespace: a card, a
    /// face and the **Agent** badge.
    static var agentMessage: TimelineRow {
        let text = "I've rebased the token branch onto main. The diff against the checked-in output is empty."
        return row(
            item(id: "$agent1", sender: agentId, body: text), view: agentBody(text),
            senderName: "Atlas (OpenClaw on Ashram)", senderShort: "Atlas", senderInitial: "✳")
    }

    /// The same agent, moments later: the same run, so no header, 2pt above.
    static var agentFollowUp: TimelineRow {
        let text = "CI is green on all three platforms."
        return row(
            item(id: "$agent2", sender: agentId, body: text, at: 1_757_700_030_000),
            view: agentBody(text),
            senderName: "Atlas (OpenClaw on Ashram)", senderShort: "Atlas", senderInitial: "✳")
    }

    /// A person, not an agent: a card, but no badge.
    static var colleagueMessage: TimelineRow {
        let text = "Looks good — ship it after lunch."
        return row(
            item(id: "$colleague", sender: "@krishna:example.org", body: text), view: agentBody(text),
            senderName: "Krishna", senderShort: "Krishna", senderInitial: "K")
    }

    /// A report past the long-read threshold, with the structure a real one
    /// has: a heading, paragraphs, a list.
    static var agentReport: TimelineRow {
        let paragraph =
            "The token generator now emits every target from one model, and the contrast "
            + "contracts are asserted against all three grounds rather than only the reading "
            + "surface. Two roles failed the stricter check on Slate and were darkened by one "
            + "step; nothing else moved."
        let blocks: [RichBlock] = [
            .heading(level: 2, inlines: [.text(text: "Token audit")]),
            .paragraph(inlines: [.text(text: paragraph)]),
            .paragraph(inlines: [.text(text: paragraph)]),
            .listBlock(
                ordered: false, start: 1,
                items: [
                    RichListItem(blocks: [.paragraph(inlines: [.text(text: "content-faint on surface-sunken")])]),
                    RichListItem(blocks: [.paragraph(inlines: [.text(text: "border-strong on surface-raised")])]),
                ]),
            .paragraph(inlines: [.text(text: paragraph)]),
        ]
        let body = String(repeating: paragraph + " ", count: 3)
        return row(
            item(id: "$report", sender: agentId, body: body), view: .bubble(muted: false, blocks: blocks),
            senderName: "Atlas (OpenClaw on Ashram)", senderShort: "Atlas", senderInitial: "✳")
    }

    static var reportBlocks: [RichBlock] {
        if case let .bubble(_, blocks) = agentReport.view { return blocks }
        return []
    }

    /// Your own message that two people have read.
    static var ownRead: TimelineRow {
        var read = row(
            item(id: "$ownRead", sender: "@rakesh:example.org", body: "Merged.", isOwn: true),
            view: agentBody("Merged."),
            senderName: "Rakesh", senderShort: "Rakesh", senderInitial: "R")
        read.item.readBy = ["@krishna:example.org", agentId, "@surya:example.org", "@ganesha:example.org"]
        return read
    }

    static var readers: [ReaderFace] {
        [
            ReaderFace(userId: "@krishna:example.org", mxcUri: nil, initial: "K"),
            ReaderFace(userId: agentId, mxcUri: nil, initial: "✳"),
            ReaderFace(userId: "@surya:example.org", mxcUri: nil, initial: nil),
            ReaderFace(userId: "@ganesha:example.org", mxcUri: nil, initial: "G"),
        ]
    }

    /// Your own message with reactions, which hang off the bubble's corner.
    static var ownWithReactions: TimelineRow {
        row(
            item(
                id: "$ownReact", sender: "@rakesh:example.org", body: "Shipping at 3.", isOwn: true,
                reactions: [
                    ReactionDto(key: "✅", displayKey: "✅", count: 2, byMe: false, senders: []),
                    ReactionDto(key: "👀", displayKey: "👀", count: 1, byMe: true, senders: []),
                ]),
            view: agentBody("Shipping at 3."),
            senderName: "Rakesh", senderShort: "Rakesh", senderInitial: "R")
    }

    /// The sentences membership churn collapses into.
    static let churnLines = [
        "Strategy Sam made 3 membership changes",
        "Strategy Sam joined the room, then left the room",
        "Ganesha, Krishna and 2 others joined the room",
    ]

    /// A turn that has finished, leaving its steps behind.
    @MainActor static func finishedTurn() -> LiveStore {
        let live = liveStore(thinking: true, answering: true, tools: true)
        live.handleLive(roomId: roomId, seq: 99, text: "", done: true)
        return live
    }
}
#endif
