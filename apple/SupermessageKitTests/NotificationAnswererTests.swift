import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// A core that records what it was asked to send, and can be told to have
/// nothing stored, to fail, or to hang.
private actor StubAnswering: NotificationAnswering {
    enum Call: Equatable {
        case restore
        case permission(roomId: String, optionId: String)
        case gate(
            roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String,
            prompt: String)
    }

    private(set) var calls: [Call] = []
    let stored: Bool
    let fails: Bool
    let hangs: Bool

    init(stored: Bool = true, fails: Bool = false, hangs: Bool = false) {
        self.stored = stored
        self.fails = fails
        self.hangs = hangs
    }

    func restoreSessionQuietly() async throws -> Bool {
        calls.append(.restore)
        return stored
    }

    func sendPermissionAnswer(roomId: String, optionId: String) async throws {
        calls.append(.permission(roomId: roomId, optionId: optionId))
        try await outcome()
    }

    func sendGateDecisionTo(
        roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String,
        prompt: String
    ) async throws {
        calls.append(
            .gate(
                roomId: roomId, gateId: gateId, optionId: optionId, comment: comment,
                inReplyTo: inReplyTo, prompt: prompt))
        try await outcome()
    }

    private func outcome() async throws {
        // Not cancellable, like the blocking core call it stands in for.
        if hangs { try? await Task.sleep(for: .seconds(30)) }
        if fails { throw FfiError.Network(detail: "offline") }
    }
}

struct NotificationAnswererTests {
    @Test("a permission answer restores quietly, then sends the option to its room")
    func permission() async {
        let core = StubAnswering()
        let landed = await NotificationAnswerer.send(
            .permission(roomId: "!a", optionId: "Allow once"), via: core)
        #expect(landed)
        #expect(await core.calls == [.restore, .permission(roomId: "!a", optionId: "Allow once")])
    }

    @Test("a gate answer sends the card's decision, referencing the gate's event")
    func gate() async {
        let core = StubAnswering()
        let landed = await NotificationAnswerer.send(
            .gate(
                roomId: "!a", gateEventId: "$g", gateId: "gate-1", optionId: "request_changes",
                comment: "Add a test first.", prompt: "Ship it?"),
            via: core)
        #expect(landed)
        #expect(
            await core.calls == [
                .restore,
                .gate(
                    roomId: "!a", gateId: "gate-1", optionId: "request_changes",
                    comment: "Add a test first.", inReplyTo: "$g", prompt: "Ship it?"),
            ])
    }

    @Test("signed out, nothing is sent and the answer is reported as not sent")
    func signedOut() async {
        let core = StubAnswering(stored: false)
        let landed = await NotificationAnswerer.send(
            .permission(roomId: "!a", optionId: "Reject"), via: core)
        #expect(!landed)
        #expect(await core.calls == [.restore])
    }

    @Test("a send the homeserver refused is reported as not sent")
    func refused() async {
        let core = StubAnswering(fails: true)
        #expect(
            !(await NotificationAnswerer.send(.permission(roomId: "!a", optionId: "Reject"), via: core)))
    }

    @Test("a send that hangs is given up on within the budget, not the system's")
    func hangs() async {
        // iOS kills a background action at ~30 s. The completion handler has
        // to be called before then, whatever the network is doing.
        let core = StubAnswering(hangs: true)
        let clock = ContinuousClock()
        let started = clock.now
        let landed = await NotificationAnswerer.send(
            .permission(roomId: "!a", optionId: "Reject"), via: core, within: .milliseconds(200))
        #expect(!landed)
        #expect(clock.now - started < .seconds(5))
    }

    @Test("the budget leaves room inside iOS's thirty seconds")
    func budget() {
        #expect(NotificationAnswerer.budget <= .seconds(20))
    }

    @Test("a quick answer is not held until the deadline")
    func quickAnswerIsNotHeld() async {
        let clock = ContinuousClock()
        let started = clock.now
        let landed = await NotificationAnswerer.withDeadline(.seconds(10)) { true }
        #expect(landed)
        #expect(clock.now - started < .seconds(5))
    }
}
