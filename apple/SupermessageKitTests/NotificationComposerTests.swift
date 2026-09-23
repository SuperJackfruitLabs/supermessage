import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// Which changes post a local notification, in which category, and when
/// the room being open holds one back.
struct NotificationComposerTests {
    // MARK: - Fixtures

    static let now: UInt64 = 1_700_000_000_000

    func roomRow(
        _ id: String, unread: UInt64, own: Bool = false, pending: Bool = false,
        text: String = "hello", membership: Membership = .joined, at: UInt64 = now
    ) -> RoomRow {
        RoomRow(
            room: RoomSummary(
                id: id, name: id, avatarUrl: nil, unread: unread, lastMessage: text,
                lastMessageIsOwn: own, lastMessageNamesSender: false, lastEventType: nil,
                lastActivityMs: at, runtime: nil, membership: membership),
            identity: RoomIdentity(glyph: nil, name: "Room \(id)", role: nil, initial: "R"),
            preview: RoomPreview(text: text, pending: pending),
            affordance: .compose)
    }

    func timelineRow(
        _ eventId: String?, view: ItemView = .bubble(muted: false, blocks: []),
        own: Bool = false, at: UInt64? = now, preview: String? = "hi there",
        sender: String = "Atlas"
    ) -> TimelineRow {
        TimelineRow(
            item: TimelineItemDto(
                id: "u-\(eventId ?? "local")", eventId: eventId, kind: "message",
                msgtype: "m.text", detail: nil, sender: "@atlas:x.org",
                senderDisplayName: sender, senderAvatar: nil, body: preview,
                formattedBody: nil, media: nil, customPayload: nil, timestampMs: at,
                isOwn: own, sendState: nil, replyTo: nil, edited: false, reactions: [],
                readBy: [], editable: false, membershipSubject: nil),
            view: view, senderName: sender, senderShort: sender, senderInitial: "A",
            membershipVerb: nil, replyQuote: nil, canReplyOrReact: eventId != nil,
            replyPreview: preview)
    }

    func decisionView(subject: String?, options: [String]) -> ItemView {
        .customEvent(
            view: .rendered(
                fields: [], reasoning: nil, newerVersion: false,
                decision: CustomEventDecision(
                    prompt: "Allow Write src/main.ts?",
                    options: options.map { CustomEventDecisionOption(label: $0, id: $0) },
                    subject: subject),
                link: nil),
            label: subject == nil ? "Permission" : "Gate",
            eventType: subject == nil ? "dev.agentpod.permission.v1" : "dev.superpipeline.gate.v1")
    }

    let background = NotificationContext(openRoomId: nil, appActive: false, timelineRoomId: nil)

    // MARK: - Roster

    @Test("the first roster is a baseline, not a burst")
    func firstRosterIsBaseline() {
        let notes = NotificationComposer.forRoster(
            previous: [], next: [roomRow("!a", unread: 5)], context: background)
        #expect(notes.isEmpty)
    }

    @Test("a new message in another room notifies, with the core's preview")
    func newMessageNotifies() {
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!a", unread: 0)],
            next: [roomRow("!a", unread: 1, text: "deploy finished")], context: background)
        #expect(notes.count == 1)
        #expect(notes.first?.category == .message)
        #expect(notes.first?.body == "deploy finished")
        #expect(notes.first?.title == "Room !a")
        #expect(notes.first?.roomId == "!a")
    }

    @Test("a room new to the roster is not news")
    func newRoomIsBaseline() {
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!b", unread: 0)], next: [roomRow("!a", unread: 5)],
            context: background)
        #expect(notes.isEmpty, "a space switch would notify for every room it brought in")
    }

    @Test("an unread count that did not rise is not news")
    func noRiseNoNotification() {
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!a", unread: 2)], next: [roomRow("!a", unread: 2, text: "edit")],
            context: background)
        #expect(notes.isEmpty)
    }

    @Test("the reader's own message does not notify them")
    func ownMessageIsQuiet() {
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!a", unread: 0)], next: [roomRow("!a", unread: 1, own: true)],
            context: background)
        #expect(notes.isEmpty)
    }

    @Test("the open room, with the app in front, does not notify")
    func openRoomSuppressed() {
        let open = NotificationContext(openRoomId: "!a", appActive: true, timelineRoomId: nil)
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!a", unread: 0), roomRow("!b", unread: 0)],
            next: [roomRow("!a", unread: 1), roomRow("!b", unread: 1)], context: open)
        #expect(notes.map(\.roomId) == ["!b"], "only the room not on screen notifies")
    }

    @Test("the open room notifies once the app is in the background")
    func openRoomInBackgroundNotifies() {
        let away = NotificationContext(openRoomId: "!a", appActive: false, timelineRoomId: nil)
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!a", unread: 0)], next: [roomRow("!a", unread: 1)], context: away)
        #expect(notes.count == 1)
    }

    @Test("the subscribed room is left to the timeline path")
    func timelineRoomSkipped() {
        let context = NotificationContext(openRoomId: nil, appActive: false, timelineRoomId: "!a")
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!a", unread: 0)], next: [roomRow("!a", unread: 1)],
            context: context)
        #expect(notes.isEmpty, "the same message would be posted twice")
    }

    @Test("a pending decision in another room opens it rather than offering answers")
    func pendingRosterRowIsGate() {
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!a", unread: 0)],
            next: [roomRow("!a", unread: 1, pending: true, text: "Waiting on you")],
            context: background)
        #expect(notes.first?.category == .gate)
        #expect(notes.first?.allowOptionId == nil)
    }

    @Test("an invitation's roster row is not a message")
    func invitationRowIsQuiet() {
        let notes = NotificationComposer.forRoster(
            previous: [roomRow("!a", unread: 0, membership: .invited)],
            next: [roomRow("!a", unread: 1, membership: .invited)], context: background)
        #expect(notes.isEmpty)
    }

    // MARK: - Timeline

    func timeline(
        _ rows: [TimelineRow], since: UInt64 = now, notified: Set<String> = [],
        context: NotificationContext? = nil
    ) -> [LocalNotification] {
        NotificationComposer.forTimeline(
            roomId: "!a", roomName: "Room !a", rows: rows, since: since,
            alreadyNotified: notified,
            context: context
                ?? NotificationContext(openRoomId: "!a", appActive: false, timelineRoomId: "!a"))
    }

    @Test("a new message in the subscribed room notifies while the app is away")
    func timelineMessage() {
        let notes = timeline([timelineRow("$1")])
        #expect(notes.count == 1)
        #expect(notes.first?.category == .message)
        #expect(notes.first?.title == "Atlas")
        #expect(notes.first?.subtitle == "Room !a")
        #expect(notes.first?.body == "hi there")
    }

    @Test("the subscribed room on screen, with the app in front, is silent")
    func timelineSuppressed() {
        let onScreen = NotificationContext(openRoomId: "!a", appActive: true, timelineRoomId: "!a")
        #expect(timeline([timelineRow("$1")], context: onScreen).isEmpty)
    }

    @Test("the subscribed room notifies once the reader has gone back to the roster")
    func timelineNotOpen() {
        let roster = NotificationContext(openRoomId: nil, appActive: true, timelineRoomId: "!a")
        #expect(timeline([timelineRow("$1")], context: roster).count == 1)
    }

    @Test("history does not notify")
    func historyIsQuiet() {
        #expect(timeline([timelineRow("$old", at: Self.now - 1)]).isEmpty)
    }

    @Test("a row already posted is not posted again when it changes")
    func alreadyNotified() {
        #expect(timeline([timelineRow("$1")], notified: ["$1"]).isEmpty)
    }

    @Test("own rows and local echoes do not notify")
    func ownAndLocal() {
        #expect(timeline([timelineRow("$1", own: true), timelineRow(nil)]).isEmpty)
    }

    @Test("a permission request with Allow once and Reject gets both actions")
    func permissionCategory() {
        let row = timelineRow(
            "$p", view: decisionView(subject: nil, options: ["Allow once", "Allow always", "Reject"]))
        let note = timeline([row]).first
        #expect(note?.category == .permission)
        #expect(note?.allowOptionId == "Allow once")
        #expect(note?.rejectOptionId == "Reject")
        #expect(note?.body == "Allow Write src/main.ts?")
        #expect(note?.subtitle == "Permission")
    }

    @Test("'Allow always' is never taken for 'Allow once'")
    func alwaysIsNotOnce() {
        let row = timelineRow("$p", view: decisionView(subject: nil, options: ["Allow always", "Reject"]))
        let note = timeline([row]).first
        #expect(note?.category == .gate, "a lock-screen tap must not grant more than it said")
        #expect(note?.allowOptionId == nil)
    }

    @Test("a permission request with no reject answer only opens")
    func permissionWithoutReject() {
        let row = timelineRow("$p", view: decisionView(subject: nil, options: ["Allow once"]))
        #expect(timeline([row]).first?.category == .gate)
    }

    @Test("a gate only opens: its details must be read before it is answered")
    func gateCategory() {
        // Options that would pass for a permission request's, so it is the
        // subject — what makes this a gate — that decides, not the labels.
        for options in [["approve", "reject"], ["Allow once", "Reject"]] {
            let row = timelineRow("$g", view: decisionView(subject: "gate-1", options: options))
            let note = timeline([row]).first
            #expect(note?.category == .gate, "options \(options)")
            #expect(note?.allowOptionId == nil)
            #expect(note?.rejectOptionId == nil)
        }
    }

    @Test("a card with nothing to decide is not an interruption")
    func cardWithoutDecision() {
        let view = ItemView.customEvent(
            view: .rendered(fields: [], reasoning: nil, newerVersion: false, decision: nil, link: nil),
            label: "Turn", eventType: "dev.agentpod.turn.v1")
        #expect(timeline([timelineRow("$t", view: view)]).isEmpty)
    }

    @Test("system rows do not notify")
    func systemRows() {
        let row = timelineRow("$s", view: .system(kind: .membershipChanged(who: "Atlas", detail: nil), text: "Atlas joined"))
        #expect(timeline([row]).isEmpty)
    }

    // MARK: - Room settings

    @Test("a muted room holds messages back but never a decision")
    func mutedRoom() {
        let message = LocalNotification(
            id: "1", roomId: "!a", eventId: nil, title: "", subtitle: nil, body: "",
            category: .message)
        let decision = LocalNotification(
            id: "2", roomId: "!a", eventId: nil, title: "", subtitle: nil, body: "",
            category: .permission, allowOptionId: "Allow once", rejectOptionId: "Reject")
        #expect(!NotificationComposer.delivers(message, mode: .muted))
        #expect(!NotificationComposer.delivers(message, mode: .mentionsOnly))
        #expect(NotificationComposer.delivers(message, mode: .allMessages))
        #expect(NotificationComposer.delivers(message, mode: nil))
        #expect(NotificationComposer.delivers(decision, mode: .muted))
    }

    // MARK: - Actions

    @Test("Allow once and Reject send the option the notification carried")
    func actionsDecode() {
        let note = LocalNotification(
            id: "p", roomId: "!a", eventId: "$p", title: "", subtitle: nil, body: "",
            category: .permission, allowOptionId: "Allow once", rejectOptionId: "Reject")
        let info = NotificationKeys.userInfo(for: note)
        #expect(
            NotificationKeys.response(actionIdentifier: NotificationKeys.allowAction, userInfo: info)
                == .answer(roomId: "!a", optionId: "Allow once"))
        #expect(
            NotificationKeys.response(actionIdentifier: NotificationKeys.rejectAction, userInfo: info)
                == .answer(roomId: "!a", optionId: "Reject"))
        #expect(
            NotificationKeys.response(
                actionIdentifier: "com.apple.UNNotificationDefaultActionIdentifier", userInfo: info)
                == .open(roomId: "!a"))
    }

    @Test("an action with no option to send opens the room instead")
    func actionWithoutOption() {
        let note = LocalNotification(
            id: "g", roomId: "!a", eventId: "$g", title: "", subtitle: nil, body: "",
            category: .gate)
        let info = NotificationKeys.userInfo(for: note)
        #expect(
            NotificationKeys.response(actionIdentifier: NotificationKeys.allowAction, userInfo: info)
                == .open(roomId: "!a"))
    }

    @MainActor
    @Test("the router hands a room over once")
    func routerConsumes() {
        let router = NotificationRouter()
        router.request(roomId: "!a")
        #expect(router.pendingRoomId == "!a")
        #expect(router.consume() == "!a")
        #expect(router.pendingRoomId == nil)
        #expect(router.consume() == nil)
    }
}

/// The push gate: nothing is registered unless a real gateway is configured.
struct PushConfigurationTests {
    @Test("an empty or unexpanded gateway key means no pusher")
    func emptyGateway() {
        #expect(PushConfiguration.gatewayURL(from: nil) == nil)
        #expect(PushConfiguration.gatewayURL(from: [:]) == nil)
        #expect(PushConfiguration.gatewayURL(from: ["SMPushGatewayURL": ""]) == nil)
        #expect(PushConfiguration.gatewayURL(from: ["SMPushGatewayURL": "  "]) == nil)
        #expect(
            PushConfiguration.gatewayURL(from: ["SMPushGatewayURL": "$(SM_PUSH_GATEWAY_URL)"]) == nil)
        #expect(
            PushConfiguration.gatewayURL(from: ["SMPushGatewayURL": "http://push.example.org/x"])
                == nil, "a pusher over plain http would expose the push path")
    }

    @Test("a configured https gateway is used as given")
    func configuredGateway() {
        let url = "https://push.example.org/_matrix/push/v1/notify"
        #expect(PushConfiguration.gatewayURL(from: ["SMPushGatewayURL": url]) == url)
    }

    @Test("the token is lowercase hex, and the sandbox has its own app id")
    func registration() {
        let reg = PushConfiguration.registration(
            token: Data([0x0a, 0xff, 0x10]), gateway: "https://g.example/n",
            bundleId: "dev.supermessage.ios", sandbox: true, deviceName: "iPhone", language: "en")
        #expect(reg.pushkey == "0aff10")
        #expect(reg.appId == "dev.supermessage.ios.dev")
        #expect(reg.gatewayUrl == "https://g.example/n")
        #expect(PushConfiguration.appId(bundleId: "dev.supermessage.ios", sandbox: false)
            == "dev.supermessage.ios")
    }
}

/// When a turn counts as "an agent working", for the Live Activity.
struct LiveTurnSummaryTests {
    @Test("no activity when nothing is live, or the turn has ended")
    func nothingLive() {
        #expect(LiveTurnSummary.of(answering: false, thinking: false, tools: [], finished: false) == nil)
        #expect(
            LiveTurnSummary.of(
                answering: false, thinking: true, tools: [("Read", .done)], finished: true) == nil,
            "a finished turn's record stays in the room, not on the Lock Screen")
    }

    @Test("the running tool is the current step, and finished tools are counted")
    func runningTool() {
        let summary = LiveTurnSummary.of(
            answering: true, thinking: true,
            tools: [("Read file", .done), ("Run tests", .running), ("Lint", .failed)],
            finished: false)
        #expect(summary?.step == "Run tests")
        #expect(summary?.completedSteps == 2)
        #expect(summary?.totalSteps == 3)
    }

    @Test("with no tool running, the phase of the turn is the step")
    func phaseStep() {
        #expect(
            LiveTurnSummary.of(answering: true, thinking: true, tools: [], finished: false)?.step
                == "Writing the answer")
        #expect(
            LiveTurnSummary.of(answering: false, thinking: true, tools: [], finished: false)?.step
                == "Thinking")
    }
}

/// What the widgets are handed.
struct WidgetSummaryTests {
    func row(_ id: String, pending: Bool, agent: Bool, at: UInt64) -> RoomRow {
        RoomRow(
            room: RoomSummary(
                id: id, name: id, avatarUrl: nil, unread: 0, lastMessage: "hi",
                lastMessageIsOwn: false, lastMessageNamesSender: false, lastEventType: nil,
                lastActivityMs: at, runtime: agent ? RuntimeDto(harness: "OpenClaw", host: "h") : nil,
                membership: .joined),
            identity: RoomIdentity(glyph: nil, name: "Name \(id)", role: nil, initial: "N"),
            preview: RoomPreview(text: "hi", pending: pending), affordance: .compose)
    }

    @Test("needs-you counts every pending room once; the agent list holds only agents")
    func summary() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let ms = UInt64(now.timeIntervalSince1970 * 1000)
        let snapshot = WidgetSummary.snapshot(
            rows: [
                row("!a", pending: true, agent: true, at: ms),
                row("!b", pending: true, agent: false, at: ms - 1000),
                row("!c", pending: false, agent: true, at: ms - 2000),
                row("!d", pending: false, agent: false, at: ms - 3000),
            ], now: now)
        #expect(snapshot.needsYou == 2)
        #expect(snapshot.agents.map(\.id) == ["!a", "!c"])
        #expect(snapshot.agents.first?.needsYou == true)
        #expect(snapshot.agents.first?.state == "needs you")
        #expect(snapshot.signedIn)
    }
}
