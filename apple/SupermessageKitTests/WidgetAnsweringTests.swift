import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// A core that records what a widget's button made it send.
private actor RecordingCore: NotificationAnswering {
    enum Call: Equatable {
        case restore
        case permission(roomId: String, optionId: String)
        case gate(roomId: String, gateId: String, optionId: String, inReplyTo: String, prompt: String)
    }

    private(set) var calls: [Call] = []
    let fails: Bool

    init(fails: Bool = false) { self.fails = fails }

    func restoreSessionQuietly() async throws -> Bool {
        calls.append(.restore)
        return true
    }

    func sendPermissionAnswer(roomId: String, optionId: String) async throws {
        calls.append(.permission(roomId: roomId, optionId: optionId))
        if fails { throw FfiError.Network(detail: "offline") }
    }

    func sendGateDecisionTo(
        roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String,
        prompt: String
    ) async throws {
        calls.append(
            .gate(
                roomId: roomId, gateId: gateId, optionId: optionId, inReplyTo: inReplyTo,
                prompt: prompt))
        if fails { throw FfiError.Network(detail: "offline") }
    }
}

private final class Reloads: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func note() { lock.withLock { count += 1 } }
    var value: Int { lock.withLock { count } }
}

/// A widget's Allow / Reject / Approve, end to end through the real core's
/// reading of the snapshot and a recording client.
struct WidgetAnsweringTests {
    private func answer(
        _ feed: WidgetFeed, room: String, event: String, option: String, core: RecordingCore,
        reloads: Reloads = Reloads()
    ) async -> WidgetAnswering.Outcome {
        await WidgetAnswering.answer(
            roomId: room, eventId: event, optionId: option, feed: feed, via: core,
            reload: { reloads.note() })
    }

    @Test("Allow sends the permission's own option to its room, and the widget says it was sent")
    func allow() async throws {
        let (feed, dir) = WidgetFeedTests.feed()
        feed.apply(WidgetFeedTests.permission())
        let core = RecordingCore()
        let reloads = Reloads()
        let outcome = await answer(
            feed, room: "!r:hs", event: "$p", option: "Allow once", core: core, reloads: reloads)
        #expect(outcome == .sent)
        #expect(await core.calls == [.restore, .permission(roomId: "!r:hs", optionId: "Allow once")])
        let decision = try #require(WidgetSnapshotStore.read(in: dir)?.decisions.first)
        #expect(decision.answered?.line == "Sent: Allow once")
        #expect(decision.buttons.isEmpty)
        #expect(reloads.value >= 1)
    }

    @Test("Approve sends the gate's decision against the gate event, and is not called approved")
    func approve() async throws {
        let (feed, dir) = WidgetFeedTests.feed()
        feed.apply(WidgetFeedTests.gate())
        let core = RecordingCore()
        #expect(await answer(feed, room: "!b:hs", event: "$g", option: "approve", core: core) == .sent)
        #expect(
            await core.calls == [
                .restore,
                .gate(
                    roomId: "!b:hs", gateId: "gate-9", optionId: "approve", inReplyTo: "$g",
                    prompt: "Ship v2"),
            ])
        let line = try #require(WidgetSnapshotStore.read(in: dir)?.decisions.first?.answered?.line)
        #expect(line == "Sent: Approve · waiting for the board")
        #expect(!line.contains("Approved"))
    }

    @Test("a gate the board already resolved sends nothing at all")
    func resolvedGateSendsNothing() async {
        let (feed, _) = WidgetFeedTests.feed()
        feed.apply(WidgetFeedTests.gate())
        feed.apply(WidgetFeedTests.outcome())
        let core = RecordingCore()
        let reloads = Reloads()
        let outcome = await answer(
            feed, room: "!b:hs", event: "$g", option: "approve", core: core, reloads: reloads)
        #expect(outcome == .notOwed)
        #expect(await core.calls.isEmpty, "not even a restore")
        #expect(reloads.value == 1, "the stale widget is redrawn")
    }

    @Test("a second tap on an answered decision sends nothing")
    func secondTap() async {
        let (feed, _) = WidgetFeedTests.feed()
        feed.apply(WidgetFeedTests.gate())
        let first = RecordingCore()
        _ = await answer(feed, room: "!b:hs", event: "$g", option: "approve", core: first)
        let second = RecordingCore()
        #expect(await answer(feed, room: "!b:hs", event: "$g", option: "reject", core: second) == .notOwed)
        #expect(await second.calls.isEmpty)
    }

    @Test("Request changes is never sent from a button")
    func requestChangesIsNotABadge() async {
        let (feed, _) = WidgetFeedTests.feed()
        feed.apply(WidgetFeedTests.gate())
        let core = RecordingCore()
        #expect(
            await answer(feed, room: "!b:hs", event: "$g", option: "request_changes", core: core)
                == .notOwed)
        #expect(await core.calls.isEmpty)
    }

    @Test("a send that fails leaves the decision owed again")
    func failureIsOwedAgain() async throws {
        let (feed, dir) = WidgetFeedTests.feed()
        feed.apply(WidgetFeedTests.permission())
        let core = RecordingCore(fails: true)
        #expect(await answer(feed, room: "!r:hs", event: "$p", option: "Reject", core: core) == .failed)
        let decision = try #require(WidgetSnapshotStore.read(in: dir)?.decisions.first)
        #expect(decision.answered == nil)
        #expect(decision.buttons.map(\.id) == ["Allow once", "Reject"])
    }
}
