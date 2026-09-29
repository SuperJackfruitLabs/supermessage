import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// The widgets' snapshot through the real core, as the app and the
/// Notification Service Extension write it and the widget reads it back.
struct WidgetFeedTests {
    static let now: UInt64 = 1_800_000_000_000
    static let minute: UInt64 = 60_000

    static func feed(at now: UInt64 = WidgetFeedTests.now) -> (WidgetFeed, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("widget-feed-\(UUID().uuidString)", isDirectory: true)
        return (WidgetFeed(writer: WidgetSnapshotWriter(directory: dir), now: { now }), dir)
    }

    static func activity(
        _ kind: ActivityKind, at: UInt64, sender: String = "@agent_hermes:hs", line: String? = nil,
        gateId: String? = nil, references: String? = nil
    ) -> NotificationActivity {
        NotificationActivity(
            kind: kind, roomName: "Hermes", roomIsAgent: true, sender: sender, atMs: at, line: line,
            gateId: gateId, references: references, optionId: nil)
    }

    static func permission(event: String = "$p", at: UInt64 = now - minute) -> NotificationDto {
        NotificationDto(
            roomId: "!r:hs", eventId: event, title: "Hermes", subtitle: "Permission",
            body: "Run git push?", category: .permission,
            permission: PermissionAnswers(allowOptionId: "Allow once", rejectOptionId: "Reject"),
            gate: nil, threadId: "!r:hs", suppress: nil, fallbackTitle: nil, fallbackBody: nil,
            activity: activity(.decision, at: at))
    }

    static func gate(at: UInt64 = now - minute) -> NotificationDto {
        NotificationDto(
            roomId: "!b:hs", eventId: "$g", title: "Launch board", subtitle: "Gate",
            body: "Approve \"Ship v2\"?", category: .gate, permission: nil,
            gate: GateAnswers(
                gateId: "gate-9", prompt: "Ship v2", optionIds: ["approve", "request_changes", "reject"]),
            threadId: "!b:hs", suppress: nil, fallbackTitle: nil, fallbackBody: nil,
            activity: activity(.decision, at: at))
    }

    static func outcome() -> NotificationDto {
        NotificationDto(
            roomId: "!b:hs", eventId: "$o", title: "", subtitle: nil, body: "New message",
            category: .message, permission: nil, gate: nil, threadId: "!b:hs",
            suppress: .notNews, fallbackTitle: "Launch board", fallbackBody: "posted an update",
            activity: activity(.gateOutcome, at: now, gateId: "gate-9"))
    }

    static func row(_ id: String, name: String, at: UInt64, says: String) -> RoomRow {
        RoomRow(
            room: RoomSummary(
                id: id, name: name, avatarUrl: nil, unread: 0, lastMessage: says,
                lastMessageIsOwn: false, lastMessageNamesSender: false, lastEventType: nil,
                lastActivityMs: at, runtime: RuntimeDto(harness: "OpenClaw", host: "foundry"),
                membership: .joined),
            identity: RoomIdentity(glyph: nil, name: name, role: nil, initial: String(name.prefix(1))),
            preview: RoomPreview(text: says, pending: false), affordance: .compose)
    }

    // MARK: - What the Notification Service Extension writes

    @Test("a pushed permission request reaches the widget as a decision it can answer")
    func pushedDecision() throws {
        let (feed, dir) = Self.feed()
        #expect(feed.apply(Self.permission()), "a new decision is always worth a reload")
        let snapshot = try #require(WidgetSnapshotStore.read(in: dir))
        #expect(snapshot.signedIn)
        let decision = try #require(snapshot.decisions.first)
        #expect(decision.kind == .permission)
        #expect(decision.agent == "Hermes")
        #expect(decision.question == "Run git push?")
        #expect(decision.buttons.map(\.label) == ["Allow once", "Reject"])
        #expect(decision.askedAt == Date(timeIntervalSince1970: TimeInterval(Self.now - Self.minute) / 1000))
        let frame = try #require(snapshot.frame(at: Date(timeIntervalSince1970: TimeInterval(Self.now) / 1000)))
        #expect(frame.needsYou == 1)
        #expect(frame.needsYouLine == "1 needs you")
    }

    @Test("the same push twice writes once")
    func samePushTwice() throws {
        let (feed, dir) = Self.feed()
        feed.apply(Self.permission())
        let first = try #require(WidgetSnapshotStore.read(in: dir))
        #expect(!feed.apply(Self.permission()))
        #expect(WidgetSnapshotStore.read(in: dir)?.revision == first.revision)
    }

    @Test("a gate's receipt, pushed, takes the gate off the widget")
    func pushedOutcome() throws {
        let (feed, dir) = Self.feed()
        feed.apply(Self.gate())
        #expect(WidgetSnapshotStore.read(in: dir)?.decisions.count == 1)
        #expect(feed.apply(Self.outcome()))
        #expect(WidgetSnapshotStore.read(in: dir)?.decisions.isEmpty == true)
    }

    // MARK: - Two writers

    @Test("the app's roster keeps what a push added, and an older roster loses to a newer")
    func olderRosterLoses() throws {
        let (feed, dir) = Self.feed()
        feed.apply(Self.permission())
        feed.apply(
            rows: [Self.row("!a:hs", name: "Atlas", at: Self.now - Self.minute, says: "Newer")],
            live: [], asOf: Self.now)
        var snapshot = try #require(WidgetSnapshotStore.read(in: dir))
        #expect(snapshot.decisions.count == 1, "the roster cannot see cards; it keeps them")
        #expect(snapshot.agents.map(\.line) == ["Newer"])

        // A roster read earlier, written later — the slow writer.
        let reload = feed.apply(
            rows: [
                Self.row("!a:hs", name: "Atlas", at: Self.now - 9 * Self.minute, says: "Older"),
                Self.row("!h:hs", name: "Hermes", at: Self.now - 9 * Self.minute, says: "Gone"),
            ],
            live: [], asOf: Self.now - Self.minute)
        #expect(!reload)
        snapshot = try #require(WidgetSnapshotStore.read(in: dir))
        #expect(snapshot.agents.map(\.name) == ["Atlas"])
        #expect(snapshot.agents.map(\.line) == ["Newer"])
    }

    @Test("the widget's mirror reads every field the core writes")
    func mirrorDecodesTheCore() throws {
        let write = widgetApplyNotification(stored: nil, note: Self.gate(), nowMs: Self.now)
        let snapshot = try #require(WidgetSnapshot.decode(Data(write.json.utf8)))
        let decision = try #require(snapshot.decisions.first)
        #expect(decision.gateId == "gate-9")
        #expect(decision.prompt == "Ship v2")
        #expect(decision.options.map(\.id) == ["approve", "request_changes", "reject"])
        #expect(decision.buttons.map(\.id) == ["approve", "reject"])
        #expect(snapshot.schema == WidgetSnapshot.schemaVersion)
        #expect(!snapshot.frames.isEmpty)
    }
}
