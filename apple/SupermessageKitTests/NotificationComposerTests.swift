import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// Which changes post a local notification, in which category, and when
/// the room being open holds one back.
struct NotificationComposerTests {
    // MARK: - Fixtures

    static let now: UInt64 = 1_700_000_000_000

    func timelineRow(
        _ eventId: String?, view: ItemView = .bubble(muted: false, blocks: [], voice: nil),
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
                link: nil, outcome: nil),
            label: subject == nil ? "Permission" : "Gate",
            eventType: subject == nil ? "dev.agentpod.permission.v1" : "dev.superpipeline.gate.v1")
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
        #expect(note?.category == .decision, "a lock-screen tap must not grant more than it said")
        #expect(note?.allowOptionId == nil)
    }

    @Test("a permission request with no reject answer only opens")
    func permissionWithoutReject() {
        let row = timelineRow("$p", view: decisionView(subject: nil, options: ["Allow once"]))
        #expect(timeline([row]).first?.category == .decision)
    }

    @Test("a gate carries what its actions need to answer it with no room open")
    func gateCategory() {
        // Operator decision 2026-09-27: gates are answered from the
        // notification. The actions need the gate's id, its question and
        // which of superpipeline's three decisions it offers.
        let row = timelineRow(
            "$g", view: decisionView(subject: "gate-1", options: ["approve", "request_changes", "reject"]))
        let note = timeline([row]).first
        #expect(note?.category == .gate)
        #expect(note?.eventId == "$g", "the decision references the gate's own event")
        #expect(
            note?.gate
                == LocalNotification.Gate(
                    gateId: "gate-1", prompt: "Allow Write src/main.ts?",
                    optionIds: ["approve", "request_changes", "reject"]))
        #expect(note?.allowOptionId == nil)
        #expect(note?.rejectOptionId == nil)
    }

    @Test("a gate offers only the decisions it has")
    func gateOffersItsOwnOptions() {
        let row = timelineRow("$g", view: decisionView(subject: "gate-1", options: ["reject", "approve"]))
        #expect(timeline([row]).first?.gate?.optionIds == ["approve", "reject"])
    }

    @Test("a gate is matched on superpipeline's ids, never on labels, and otherwise only opens")
    func gateWithoutKnownOptions() {
        // Options that would pass for a permission request's: it is the
        // subject that makes this a gate, and the ids that make it answerable.
        for options in [["Allow once", "Reject"], ["request_changes"], []] {
            let row = timelineRow("$g", view: decisionView(subject: "gate-1", options: options))
            let note = timeline([row]).first
            #expect(note?.category == .decision, "options \(options)")
            #expect(note?.gate == nil, "options \(options)")
        }
    }

    @Test("a card with nothing to decide is not an interruption")
    func cardWithoutDecision() {
        let view = ItemView.customEvent(
            view: .rendered(fields: [], reasoning: nil, newerVersion: false, decision: nil, link: nil, outcome: nil),
            label: "Turn", eventType: "dev.agentpod.turn.v1")
        #expect(timeline([timelineRow("$t", view: view)]).isEmpty)
    }

    @Test("a failed turn notifies like the message it is, in the hub's own words")
    func turnError() {
        // The reader asked for something and it did not happen: that is worth
        // an interruption, and the body the hub wrote already says what went
        // wrong in a sentence.
        let row = timelineRow(
            "$e", view: .turnError(card: TurnErrorPresentationTests.card),
            preview: "This agent reported an error: You've reached your weekly usage limit.")
        let note = timeline([row]).first
        #expect(note?.category == .message)
        #expect(note?.body == "This agent reported an error: You've reached your weekly usage limit.")
    }

    @Test("a failed turn with no body to preview still says what failed")
    func turnErrorWithoutPreview() {
        let row = timelineRow(
            "$e", view: .turnError(card: TurnErrorPresentationTests.card), preview: nil)
        #expect(timeline([row]).first?.body == "Usage limit reached · kimi-coding / k2p6")
    }

    @Test("a voice note's transcript does not notify a second time")
    func voiceTranscript() {
        // The hub posts it after the note; the note was the interruption.
        let view = ItemView.voiceTranscript(
            transcript: VoiceTranscriptPresentationTests.transcript, onOwnNote: false)
        let row = timelineRow("$v", view: view, preview: "Transcript: Can you move the review to Thursday?")
        #expect(timeline([row]).isEmpty)
    }

    @Test("an agent's spoken answer does not notify a second time, but its text does")
    func voiceReply() {
        // The hub posts the text, then its voice. The core draws the text with
        // the player and hides the voice row; standalone (its text not loaded)
        // the voice is still not news.
        let audio = AudioView(
            isVoice: true, durationMs: 4_210, lengthLabel: "0:04", waveform: nil,
            title: "Voice reply", filename: "Voice message.ogg", size: nil,
            mimetype: "audio/ogg", caption: nil, accessibilityLabel: "Voice reply, 4 seconds")
        var voice = timelineRow("$voice", view: .audio(audio: audio, transcript: nil), preview: nil)
        voice.voiceReply = VoiceReplyLink(textEventId: "$text", voice: "bf_emma", seconds: 4)
        #expect(timeline([voice]).isEmpty)

        let text = timelineRow(
            "$text",
            view: .bubble(
                muted: false, blocks: [],
                voice: VoiceReplyPlayer(eventId: "$voice", audio: audio)),
            preview: "Yes — Thursday at ten.")
        #expect(timeline([text]).map(\.body) == ["Yes — Thursday at ten."])
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
                == .answer(answer: .permission(roomId: "!a", optionId: "Allow once")))
        #expect(
            NotificationKeys.response(actionIdentifier: NotificationKeys.rejectAction, userInfo: info)
                == .answer(answer: .permission(roomId: "!a", optionId: "Reject")))
        #expect(
            NotificationKeys.response(
                actionIdentifier: "com.apple.UNNotificationDefaultActionIdentifier", userInfo: info)
                == .open(roomId: "!a"))
    }

    func gateNote(options: [String] = ["approve", "request_changes", "reject"]) -> [String: String] {
        NotificationKeys.userInfo(
            for: LocalNotification(
                id: "$g", roomId: "!a", eventId: "$g", title: "", subtitle: nil, body: "Ship it?",
                category: .gate,
                gate: LocalNotification.Gate(gateId: "gate-1", prompt: "Ship it?", optionIds: options)))
    }

    @Test("Approve and Reject send the gate's decision, as the card would")
    func gateActionsDecode() {
        #expect(
            NotificationKeys.response(actionIdentifier: NotificationKeys.approveGateAction, userInfo: gateNote())
                == .answer(
                    answer: .gate(
                        roomId: "!a", gateEventId: "$g", gateId: "gate-1", optionId: "approve",
                        comment: nil, prompt: "Ship it?")))
        #expect(
            NotificationKeys.response(actionIdentifier: NotificationKeys.rejectGateAction, userInfo: gateNote())
                == .answer(
                    answer: .gate(
                        roomId: "!a", gateEventId: "$g", gateId: "gate-1", optionId: "reject",
                        comment: nil, prompt: "Ship it?")))
        // The permission request's Reject is a different action: it must not
        // be read as a gate's.
        #expect(
            NotificationKeys.response(actionIdentifier: NotificationKeys.rejectAction, userInfo: gateNote())
                == .open(roomId: "!a"))
    }

    @Test("Request changes sends what the reader typed as the comment")
    func requestChangesDecodes() {
        #expect(
            NotificationKeys.response(
                actionIdentifier: NotificationKeys.requestChangesAction, userInfo: gateNote(),
                userText: "  Add a test first.\n")
                == .answer(
                    answer: .gate(
                        roomId: "!a", gateEventId: "$g", gateId: "gate-1",
                        optionId: "request_changes", comment: "Add a test first.",
                        prompt: "Ship it?")))
    }

    @Test("the categories' actions are the core's, with iOS's ids and copy")
    func actionsAreTheCores() {
        #expect(
            notificationActions(category: .gate).map(\.id) == [
                NotificationKeys.approveGateAction, NotificationKeys.requestChangesAction,
                NotificationKeys.rejectGateAction, NotificationKeys.openAction,
            ])
        #expect(notificationActions(category: .permission).map(\.title) == ["Allow once", "Reject"])
        #expect(
            notificationUserInfo(note: answerFailedNotification(roomId: "!a"))
                == [NotificationKeys.roomId: "!a"])
    }

    @Test("a planned notification survives the Swift value both ways")
    func plannedRoundTrips() {
        let failed = answerFailedNotification(roomId: "!a")
        let gate = plannedFromDecided(
            note: NotificationDto(
                roomId: "!a", eventId: "$gate", title: "Room !a", subtitle: "Approval",
                body: "Ship it?", category: .gate, permission: nil,
                gate: GateAnswers(gateId: "g1", prompt: "Ship it?", optionIds: ["approve", "reject"]),
                threadId: "!a", suppress: nil, fallbackTitle: nil, fallbackBody: nil, activity: nil))
        for planned in [failed, gate] {
            #expect(LocalNotification(planned: planned).planned == planned)
        }
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
