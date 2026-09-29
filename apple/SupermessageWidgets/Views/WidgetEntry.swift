// Compiled into the widget extension, and into the app so the widgets'
// previews render with its other previews.

import Foundation
import WidgetKit

#if !SM_WIDGET_EXTENSION
import SupermessageKit
#endif

/// What a widget has to draw at one moment.
struct SnapshotEntry: TimelineEntry, Sendable {
    enum Content: Sendable {
        /// The App Group is not provisioned for this build, so nothing can
        /// hand the widget anything. Said honestly, rather than drawn as
        /// "nothing needs you", which would be a claim.
        case unavailable
        case signedOut
        /// Signed in, and nothing written yet — the app has not run since
        /// this build was installed, and no push has arrived.
        case waiting
        case snapshot(WidgetSnapshot)
    }

    let date: Date
    let content: Content

    /// The frame for this entry's moment — the counts, the fleet line and
    /// each agent's state the core worked out for it.
    var frame: WidgetSnapshot.Frame? {
        guard case let .snapshot(snapshot) = content else { return nil }
        return snapshot.frame(at: date)
    }

    /// One entry per frame the core wrote, the first at `now`: WidgetKit
    /// shows each when its time comes, so "active" turns to "idle" on time
    /// with nothing written. Plus one every ten minutes, for the ages.
    static func timeline(for content: Content, now: Date) -> [SnapshotEntry] {
        guard case let .snapshot(snapshot) = content else {
            return [SnapshotEntry(date: now, content: content)]
        }
        // The frames' moments, and every ten minutes for the next hour so the
        // ages ("3 min") do not sit still.
        let ticks = (1...6).map { now.addingTimeInterval(Double($0) * 600) }
        let later = Set(snapshot.frames.map(\.from).filter { $0 > now } + ticks).sorted()
        return [SnapshotEntry(date: now, content: content)]
            + later.map { SnapshotEntry(date: $0, content: content) }
    }
}

/// What the widget gallery and a first placement show before there is data:
/// plainly an example, in the shape the real thing takes.
enum WidgetSample {
    static func snapshot(now: Date = .now, decisions: Int = 2, answered: Bool = false)
        -> WidgetSnapshot
    {
        let ms = UInt64(max(0, now.timeIntervalSince1970 * 1000))
        let minute: UInt64 = 60_000
        let all: [WidgetSnapshot.Decision] = [
            .init(
                kind: .permission, roomId: "!hermes", eventId: "$push", agent: "Hermes",
                question: "Run git push origin main?",
                options: [
                    .init(id: "Allow once", label: "Allow once", inline: true, declines: false),
                    .init(id: "Reject", label: "Reject", inline: true, declines: true),
                ],
                askedAtMs: ms - 3 * minute,
                answered: answered
                    ? .init(optionId: "Allow once", atMs: ms, line: "Sent: Allow once") : nil),
            .init(
                kind: .gate, roomId: "!board", eventId: "$gate", agent: "Launch board",
                question: "Approve \"Ship v2 to production\"?",
                options: [
                    .init(id: "approve", label: "Approve", inline: true, declines: false),
                    .init(id: "request_changes", label: "Request changes", inline: false, declines: true),
                    .init(id: "reject", label: "Reject", inline: true, declines: true),
                ],
                gateId: "gate-9", prompt: "Ship v2 to production", askedAtMs: ms - 42 * minute),
            .init(
                kind: .permission, roomId: "!atlas", eventId: "$rm", agent: "Atlas",
                question: "Delete build/ and rebuild from scratch?",
                options: [
                    .init(id: "Allow once", label: "Allow once", inline: true, declines: false),
                    .init(id: "Reject", label: "Reject", inline: true, declines: true),
                ],
                askedAtMs: ms - 90 * minute),
        ]
        let decisions = Array(all.prefix(decisions))
        let owed = decisions.filter { $0.answered == nil }
        let agents: [WidgetSnapshot.Agent] = [
            .init(
                roomId: "!atlas", name: "Atlas", lastActivityMs: ms - minute,
                line: "Rebuilt the index; 3 tests still failing", step: "Running the tests"),
            .init(
                roomId: "!hermes", name: "Hermes", lastActivityMs: ms - 3 * minute,
                line: "Run git push origin main?"),
            .init(
                roomId: "!ganesha", name: "Ganesha", lastActivityMs: ms - 2 * 60 * minute,
                line: "Finished · 7 steps"),
            .init(
                roomId: "!krishna", name: "Krishna", lastActivityMs: ms - 3 * 24 * 60 * minute,
                line: "Drafted the release notes"),
        ]
        let states: [WidgetSnapshot.AgentState] = [
            .init(word: "working", tone: .working),
            owed.contains(where: { $0.roomId == "!hermes" })
                ? .init(word: "needs you", tone: .needsYou) : .init(word: "active", tone: .active),
            .init(word: "idle", tone: .idle),
            .init(word: "quiet", tone: .quiet),
        ]
        let count = UInt32(owed.count)
        let working = UInt32(states.filter { $0.tone == .working || $0.tone == .active }.count)
        let asks = count == 0 ? nil : (count == 1 ? "1 needs you" : "\(count) need you")
        return WidgetSnapshot(
            signedIn: true, decisions: decisions, agents: agents,
            frames: [
                .init(
                    fromMs: ms, needsYou: count, needsYouCount: "\(count)",
                    needsYouLine: asks ?? "Nothing needs you", working: working,
                    pulse: asks.map { "\(working) working · \($0)" } ?? "\(working) working",
                    states: states)
            ])
    }
}
