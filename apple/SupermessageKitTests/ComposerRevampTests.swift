import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

// The revamp's logic that lives in the Kit: D11, D12, C4, A2, A3.
//
// Each suite's comments say which mutation of the implementation it was seen
// to fail against — AGENTS.md's standard for a regression test.

// MARK: - A stub for the two seams a send touches

/// Records every call, in order, and fails the ones it is told to.
actor StubOutbox: AttachmentStaging, MessageSending {
    enum Call: Equatable {
        case stage(String), sendAttachment(String, caption: String? = nil), discard(String)
        case markVoice(String, durationMs: UInt64, points: Int)
        case message(String, mentions: [String]), reply(String)
    }

    private(set) var calls: [Call] = []
    private var tokens = 0
    var failAttachmentSend = false
    /// A refusal thrown before the core takes the token.
    var refuseAttachmentSend: FfiError?
    var failStaging = false
    /// Fail staging only from the Nth call on (1-based), to fail a *re*-stage.
    var failStagingFrom: Int?
    var failText = false

    func set(
        failAttachmentSend: Bool = false, refuseAttachmentSend: FfiError? = nil, failStaging: Bool = false,
        failStagingFrom: Int? = nil, failText: Bool = false
    ) {
        self.failAttachmentSend = failAttachmentSend
        self.refuseAttachmentSend = refuseAttachmentSend
        self.failStaging = failStaging
        self.failStagingFrom = failStagingFrom
        self.failText = failText
    }

    func attachmentStagePath(roomId: String, path: String) async throws -> StagedFile {
        calls.append(.stage(path))
        let stagings = calls.filter { if case .stage = $0 { return true } else { return false } }.count
        if failStaging || (failStagingFrom.map { stagings >= $0 } ?? false) {
            throw FfiError.Store(detail: "cannot read that file: gone")
        }
        tokens += 1
        return StagedFile(
            token: "tok-\(tokens)", filename: (path as NSString).lastPathComponent, sizeBytes: 10,
            mime: "image/png", width: nil, height: nil)
    }

    func attachmentSend(roomId: String, token: String, caption: String?) async throws -> AttachmentSendOutcome {
        calls.append(.sendAttachment(token, caption: caption))
        if let refuseAttachmentSend { throw refuseAttachmentSend }
        guard failAttachmentSend else { return .sent }
        // Like the core: the failed send consumed the token, and the same
        // path is staged again behind the next one, unless that cannot work.
        let error = FfiError.Network(detail: "upload failed")
        let stagings = calls.filter { if case .stage = $0 { return true } else { return false } }.count + 1
        if failStaging || (failStagingFrom.map { stagings >= $0 } ?? false) {
            return .failed(error: error, restaged: nil)
        }
        tokens += 1
        return .failed(
            error: error,
            restaged: StagedFile(
                token: "tok-\(tokens)", filename: "shot.png", sizeBytes: 10, mime: "image/png", width: nil, height: nil))
    }

    func attachmentDiscard(token: String) async {
        calls.append(.discard(token))
    }

    func attachmentMarkVoice(roomId: String, token: String, durationMs: UInt64, waveform: [Float]) async throws {
        calls.append(.markVoice(token, durationMs: durationMs, points: waveform.count))
    }

    func sendMessage(roomId: String, body: String, mentions: [String]) async throws {
        calls.append(.message(body, mentions: mentions))
        if failText { throw FfiError.Network(detail: "offline") }
    }

    func sendReply(roomId: String, body: String, inReplyTo: String) async throws {
        calls.append(.reply(body))
        if failText { throw FfiError.Network(detail: "offline") }
    }

    func editMessage(roomId: String, eventId: String, body: String) async throws {}
    func deleteMessage(roomId: String, eventId: String) async throws {}
    func toggleReaction(roomId: String, eventId: String, key: String) async throws -> Bool { true }
    func sendGateDecision(
        roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String,
        prompt: String
    ) async throws {}
    func setTyping(roomId: String, typing: Bool) async throws {}
}

private let room = "!r:x"

// MARK: - D12: the attachment and the text

@MainActor
struct OutboxTests {
    let stub = StubOutbox()
    var staged: StagedAttachment { StagedAttachment(client: stub) }

    func send(_ text: String, staged: StagedAttachment, members: [Mentionable] = []) async -> SendResult {
        await Outbox.send(
            text: text, in: room, mentioning: members, staged: staged, replies: ReplyTarget(),
            client: stub)
    }

    @Test("text typed with an attachment goes as its caption, in one event")
    func textIsTheCaption() async {
        // 2026-09-24: sent as a second message, the question reached a bridged
        // agent mid-turn and met "Session is busy". One event, one turn.
        let staged = self.staged
        await staged.stage(path: "/tmp/shot.png", in: room)
        let result = await send("here's the screenshot", staged: staged)
        #expect(result == .sent)
        let calls = await stub.calls
        #expect(calls == [.stage("/tmp/shot.png"), .sendAttachment("tok-1", caption: "here's the screenshot")])
    }

    @Test("an attachment alone has no caption")
    func bareAttachment() async {
        let staged = self.staged
        await staged.stage(path: "/tmp/shot.png", in: room)
        let result = await send("   ", staged: staged)
        #expect(result == .sent)
        let calls = await stub.calls
        #expect(calls == [.stage("/tmp/shot.png"), .sendAttachment("tok-1", caption: nil)])
    }

    @Test("a failed attachment keeps the text back")
    func failedAttachmentHoldsText() async {
        // Mutation seen failing: dropping the early return on attachment
        // failure, so the caption went without its picture — D12 itself.
        let staged = self.staged
        await staged.stage(path: "/tmp/shot.png", in: room)
        await stub.set(failAttachmentSend: true)

        let result = await send("here's the screenshot", staged: staged)

        guard case .attachmentFailed = result else {
            Issue.record("expected attachmentFailed, got \(result)")
            return
        }
        let calls = await stub.calls
        #expect(!calls.contains { if case .message = $0 { return true } else { return false } },
                "the text was sent without its attachment")
    }

    @Test("a chip with nothing behind it holds the text back too")
    func refusedChipHoldsText() async {
        // Mutation seen failing: `isPresent` ignoring `failure`, so a chip
        // that says "too large" let the text go alone.
        let staged = self.staged
        await stub.set(failStaging: true)
        await staged.stage(path: "/tmp/huge.mov", in: room)
        #expect(staged.blocksSend(in: room))

        let result = await send("the recording", staged: staged)

        guard case .attachmentFailed = result else {
            Issue.record("expected attachmentFailed, got \(result)")
            return
        }
        let calls = await stub.calls
        #expect(calls == [.stage("/tmp/huge.mov")])
    }

    @Test("while replying, the file goes first and the text follows as the reply")
    func replyIsNotACaption() async {
        // A caption cannot carry `in_reply_to`, so a reply keeps the two-step
        // send — and the order that keeps a failure's survivor meaningful.
        let staged = self.staged
        await staged.stage(path: "/tmp/shot.png", in: room)
        let replies = ReplyTarget()
        replies.start(ReplyTargetTests.row(id: "$q", sender: "Ganesha", preview: "the question"), in: room)

        let result = await Outbox.send(
            text: "here it is", in: room, mentioning: [], staged: staged, replies: replies, client: stub)

        #expect(result == .sent)
        let calls = await stub.calls
        #expect(calls == [.stage("/tmp/shot.png"), .sendAttachment("tok-1", caption: nil), .reply("here it is")])
    }

    @Test("a reply's text failing after the file went says so, and the chip is gone")
    func textFailsAfterAttachment() async {
        let staged = self.staged
        await staged.stage(path: "/tmp/shot.png", in: room)
        await stub.set(failText: true)
        let replies = ReplyTarget()
        replies.start(ReplyTargetTests.row(id: "$q", sender: "Ganesha", preview: "the question"), in: room)

        let result = await Outbox.send(
            text: "here it is", in: room, mentioning: [], staged: staged, replies: replies, client: stub)

        guard case .textFailed = result else {
            Issue.record("expected textFailed, got \(result)")
            return
        }
        #expect(!staged.isPresent(in: room), "a chip for an attachment that already went")
    }

    @Test("an inserted mention reaches m.mentions")
    func mentionsReachTheWire() async {
        // Mutation seen failing: passing `members: []` to collectMentions,
        // which is what Session.send did before — every mention was empty.
        let atlas = PersonDto(
            userId: "@atlas:x", name: "Atlas", initial: "A",
            runtime: RuntimeDto(harness: "claude", host: "ci"), avatarUrl: nil)
        let query = try! #require(MentionComposing.activeQuery(in: "@at"))
        let text = MentionComposing.insert(atlas, for: query, in: "@at") + "please look"
        let members = [Mentionable(userId: atlas.userId, displayName: MentionComposing.label(for: atlas))]

        _ = await send(text, staged: staged, members: members)

        let calls = await stub.calls
        #expect(calls == [.message("@Atlas please look", mentions: ["@atlas:x"])])
    }
}

// MARK: - D12: the token after a failed send

@MainActor
struct StagedAttachmentTests {
    let stub = StubOutbox()

    @Test("a failed send puts a fresh token behind the chip and keeps the error on it")
    func failedSendRestages() async {
        // Mutation seen failing: keeping the old (possibly consumed) token
        // instead of re-staging — `file.token` stayed "tok-1".
        let staged = StagedAttachment(client: stub)
        await staged.stage(path: "/tmp/shot.png", in: room)
        await stub.set(failAttachmentSend: true)

        let message = await staged.send(in: room)

        #expect(message != nil)
        #expect(staged.file?.token == "tok-2", "the chip holds the fresh token the core re-staged")
        #expect(staged.failure?.filename == "shot.png")
        #expect(staged.isPresent(in: room))
        #expect(!staged.blocksSend(in: room), "a re-staged file can be retried")
    }

    @Test("a recording is marked as a voice message on the token it was staged with")
    func marksVoice() async {
        // Unmarked, a recording went as a plain audio file: Hermes never
        // transcribed it (Writer Quill, 2026-09-26).
        let staged = StagedAttachment(client: stub)
        await staged.stage(path: "/tmp/Voice message.m4a", in: room)
        await staged.markVoice(.init(durationMs: 4_200, waveform: [0.1, 0.5, 0.9]), in: room)

        let calls = await stub.calls
        #expect(calls.contains(.markVoice("tok-1", durationMs: 4_200, points: 3)))
        #expect(staged.voice?.durationMs == 4_200)
    }

    @Test("marking in another room does nothing")
    func markVoiceIsRoomScoped() async {
        let staged = StagedAttachment(client: stub)
        await staged.stage(path: "/tmp/Voice message.m4a", in: room)
        await staged.markVoice(.init(durationMs: 1_000, waveform: []), in: "!other:x")
        let calls = await stub.calls
        #expect(!calls.contains { if case .markVoice = $0 { return true } else { return false } })
    }

    @Test("when the file cannot be staged again, the chip stays and says why")
    func restageFails() async {
        // Mutation seen failing: clearing the chip on failure — the
        // attachment then vanished silently, which is the other half of D12.
        let staged = StagedAttachment(client: stub)
        await staged.stage(path: "/tmp/shot.png", in: room)
        await stub.set(failAttachmentSend: true, failStagingFrom: 2)

        _ = await staged.send(in: room)

        #expect(staged.file == nil)
        #expect(staged.failure != nil)
        #expect(staged.blocksSend(in: room))
    }

    @Test("a refusal for a token the core no longer holds leaves a failed chip")
    func unknownAttachmentLeavesFailedChip() async {
        let staged = StagedAttachment(client: stub)
        await staged.stage(path: "/tmp/shot.png", in: room)
        await stub.set(refuseAttachmentSend: .UnknownAttachment)

        let message = await staged.send(in: room)

        #expect(message != nil)
        #expect(staged.file == nil)
        #expect(staged.failure?.filename == "shot.png")
        #expect(staged.blocksSend(in: room))
    }

    @Test("a room-changed refusal keeps the chip sendable")
    func roomChangedKeepsChipSendable() async {
        let staged = StagedAttachment(client: stub)
        await staged.stage(path: "/tmp/shot.png", in: room)
        await stub.set(refuseAttachmentSend: .RoomChanged(requested: room, focused: "!other:x"))

        let message = await staged.send(in: room)

        #expect(message != nil)
        #expect(staged.file?.token == "tok-1", "the token was never taken")
        #expect(staged.failure != nil)
        #expect(!staged.blocksSend(in: room))
    }

    @Test("a chip belongs to the room it was staged in")
    func scopedToRoom() async {
        let staged = StagedAttachment(client: stub)
        await staged.stage(path: "/tmp/shot.png", in: room)
        #expect(staged.isPresent(in: room))
        #expect(!staged.isPresent(in: "!other:x"))
    }

    @Test("removing a chip clears its failure")
    func discardClears() async {
        let staged = StagedAttachment(client: stub)
        await staged.refuse(filename: "x.png", message: "nope", in: room)
        #expect(staged.blocksSend(in: room))
        await staged.discard()
        #expect(!staged.isPresent(in: room))
    }
}

// MARK: - D11: one name, and agents off the typing line

@MainActor
struct TypingCastTests {
    static func user(_ id: String, _ label: String) -> TypingUserDto {
        TypingUserDto(userId: id, displayName: label, label: label)
    }

    static let atlas = PersonDto(
        userId: "@atlas:x", name: "atlas-bot", initial: "a",
        runtime: RuntimeDto(harness: "claude", host: "ci"), avatarUrl: nil)

    @Test("an agent typing is named by the header, and is not on the people's line")
    func agentUsesHeaderName() {
        // Mutations seen failing (in the core, where the rule now lives): the
        // counterpart's name ignored (the dock said "atlas-bot"); the line not
        // filtering agents (both the dock and "atlas-bot is typing…" at once).
        let typing = TypingStore()
        typing.recognise(
            roomCastFrom(
                roomId: room, everyone: [Self.atlas], memberIds: ["@atlas:x"], headerName: "Atlas",
                isAgentRoom: true),
            in: room)
        typing.focus(room)
        typing.handle(
            roomId: room, users: [Self.user("@atlas:x", "atlas-bot"), Self.user("@k:x", "Krishna")])

        #expect(typing.typingAgents == ["Atlas"])
        #expect(typing.line == "Krishna is typing…")
    }
}

// MARK: - C4: the `@` query

struct MentionComposingTests {
    @Test("an @ at the start or after a space opens a query")
    func opens() {
        #expect(MentionComposing.activeQuery(in: "@")?.text == "")
        #expect(MentionComposing.activeQuery(in: "hey @at")?.text == "at")
    }

    @Test("an address, a finished mention, or a sentence does not")
    func staysShut() {
        // Mutations seen failing: dropping the whitespace-before rule
        // (`ana@example.org` opened the picker); allowing whitespace after
        // (the picker stayed open after a completion was inserted).
        #expect(MentionComposing.activeQuery(in: "ana@example") == nil)
        #expect(MentionComposing.activeQuery(in: "@Atlas ") == nil)
        #expect(MentionComposing.activeQuery(in: "plain text") == nil)
    }

    @Test("inserting replaces the query and nothing before it")
    func inserts() {
        let person = PersonDto(userId: "@a:x", name: "Ana Lyra", initial: "A", runtime: nil, avatarUrl: nil)
        let text = "ping 🙂 @an"
        let query = try! #require(MentionComposing.activeQuery(in: text))
        #expect(MentionComposing.insert(person, for: query, in: text) == "ping 🙂 @Ana Lyra ")
    }
}
