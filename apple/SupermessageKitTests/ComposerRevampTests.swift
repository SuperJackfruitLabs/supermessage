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
    var failStaging = false
    /// Fail staging only from the Nth call on (1-based), to fail a *re*-stage.
    var failStagingFrom: Int?
    var failText = false

    func set(failAttachmentSend: Bool = false, failStaging: Bool = false, failStagingFrom: Int? = nil, failText: Bool = false) {
        self.failAttachmentSend = failAttachmentSend
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

    func attachmentSend(roomId: String, token: String, caption: String?) async throws {
        calls.append(.sendAttachment(token, caption: caption))
        if failAttachmentSend { throw FfiError.Network(detail: "upload failed") }
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
        #expect(staged.file?.token == "tok-2", "the chip still holds the token the core may have consumed")
        #expect(staged.failure?.filename == "shot.png")
        #expect(staged.isPresent(in: room))
        #expect(!staged.blocksSend(in: room), "a re-staged file can be retried")
        let calls = await stub.calls
        #expect(calls.contains(.discard("tok-1")), "a live token would be orphaned in the core")
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

    @Test("a failed send re-marks the fresh token, so the retry is still a voice message")
    func restagedVoiceIsMarkedAgain() async {
        // Mutation seen failing: re-staging without re-marking — the retry
        // went as a plain file.
        let staged = StagedAttachment(client: stub)
        await staged.stage(path: "/tmp/Voice message.m4a", in: room)
        await staged.markVoice(.init(durationMs: 1_000, waveform: []), in: room)
        await stub.set(failAttachmentSend: true)

        _ = await staged.send(in: room)

        let calls = await stub.calls
        #expect(calls.contains(.markVoice("tok-2", durationMs: 1_000, points: 0)))
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

// MARK: - A2: the activity card's facts

@MainActor
struct LiveTimingTests {
    final class Clock {
        var now = Date(timeIntervalSince1970: 1_000)
    }

    func tool(_ live: LiveStore, _ id: String, _ phase: ToolPhase, seq: UInt64) {
        live.handleTool(
            roomId: room, seq: seq, toolCallId: id, title: "step \(id)", kind: nil, status: "",
            phase: phase, statusLabel: "", locations: [], input: nil, output: nil)
    }

    @Test("elapsed runs from the first delta and stops at the end")
    func elapsed() {
        // Mutations seen failing: stamping `startedAt` on every delta (the
        // clock restarted, 0s); not stamping `endedAt` (a finished turn
        // kept counting to `now`).
        let clock = Clock()
        let live = LiveStore(clock: { clock.now })
        live.focus(room)
        live.handleThought(roomId: room, seq: 1, text: "hm", done: false)
        clock.now += 30
        live.handleLive(roomId: room, seq: 1, text: "Looking", done: false)
        #expect(live.elapsed(at: clock.now) == 30)

        clock.now += 12
        live.handleLive(roomId: room, seq: 2, text: "", done: true)
        #expect(live.elapsed(at: clock.now + 600) == 42, "a finished turn kept counting")
    }

    @Test("the next turn starts its own clock")
    func nextTurnResets() {
        let clock = Clock()
        let live = LiveStore(clock: { clock.now })
        live.focus(room)
        live.handleLive(roomId: room, seq: 1, text: "a", done: false)
        live.handleLive(roomId: room, seq: 2, text: "", done: true)
        clock.now += 100
        live.handleLive(roomId: room, seq: 1, text: "b", done: false)
        clock.now += 5
        #expect(live.elapsed(at: clock.now) == 5)
    }

    @Test("steps: the running one, how many are done, the failed one")
    func steps() {
        // Mutation seen failing: `currentStep` as `tools.last` — a finished
        // step after a running one was named as what the agent is doing.
        let live = LiveStore()
        live.focus(room)
        tool(live, "1", .done, seq: 1)
        tool(live, "2", .running, seq: 2)
        tool(live, "3", .failed, seq: 3)
        tool(live, "4", .done, seq: 4)
        #expect(live.currentStep?.id == "2")
        #expect(live.completedSteps == 2)
        #expect(live.failedStep?.id == "3")
        #expect(live.inProgress)
    }

    @Test("the header's name is kept per room")
    func agentName() {
        let live = LiveStore()
        live.setAgentName("Atlas", for: room)
        live.focus(room)
        #expect(live.agentName == "Atlas", "focusing the room threw the name away")
        live.focus("!other:x")
        #expect(live.agentName == nil)
    }
}

struct ElapsedTimeTests {
    @Test("seconds, then minutes with padded seconds, then hours")
    func labels() {
        // Mutation seen failing: unpadded seconds ("1m 4s").
        #expect(ElapsedTime.label(8.9) == "8s")
        #expect(ElapsedTime.label(64) == "1m 04s")
        #expect(ElapsedTime.label(600) == "10m 00s")
        #expect(ElapsedTime.label(3_725) == "1h 02m")
        #expect(ElapsedTime.label(-3) == "0s")
    }
}

// MARK: - A3: the acknowledgement

struct AcknowledgementTests {
    static func row(
        _ id: String, own: Bool, sender: String, reactions: [ReactionDto] = [],
        membership: String? = nil
    ) -> TimelineRow {
        let item = TimelineItemDto(
            id: id, eventId: id, kind: "message", msgtype: "m.text", detail: nil, sender: sender,
            senderDisplayName: sender, senderAvatar: nil, body: "b", formattedBody: nil, media: nil,
            customPayload: nil, timestampMs: 1, isOwn: own, sendState: nil, replyTo: nil,
            edited: false, reactions: reactions, readBy: [], editable: false, membershipSubject: nil)
        return TimelineRow(
            item: item, view: .bubble(muted: false, blocks: []), senderName: sender,
            senderShort: sender, senderInitial: "?", membershipVerb: membership, replyQuote: nil,
            canReplyOrReact: true, replyPreview: nil)
    }

    static let agents: Set<String> = ["@atlas:x"]

    func state(
        _ rows: [TimelineRow], sent: Bool = true, typing: [String] = [], live: Bool = false,
        addressee: String? = "Atlas", agents: Set<String> = agents
    ) -> Acknowledgement? {
        Acknowledgement.state(
            rows: rows, addressee: addressee, agentIds: agents, sentThisSession: sent,
            typingAgents: typing, turnInProgress: live)
    }

    @Test("sent, with no sign of the agent yet")
    func sent() {
        #expect(state([Self.row("1", own: true, sender: "@me:x")]) == .sent(to: "Atlas"))
    }

    @Test("a reaction or typing means it is on it; a live turn shows its own card")
    func onIt() {
        // Mutation seen failing: ignoring reactions (stayed "Sent to").
        let mine = Self.row("1", own: true, sender: "@me:x")
        let reacted = Self.row(
            "1", own: true, sender: "@me:x",
            reactions: [ReactionDto(key: "👀", displayKey: "👀", count: 1, byMe: false, senders: ["@atlas:x"])])
        #expect(state([reacted]) == .onIt(["Atlas"]))
        #expect(state([mine], typing: ["Atlas"]) == .onIt(["Atlas"]))
        // A live turn speaks for itself in its card; no pill under it.
        #expect(state([mine], live: true) == nil)
    }

    @Test("the agent's done or failed mark ends it, with or without a reply")
    func finishedMark() {
        // krishna, 2026-09-26 15:06: "Okay" got ✅ and, on purpose, no reply
        // (the agent chose NO_REPLY). The dock read ✅ as "on it" and said
        // "Krishna is on it…" until the room was left. 👀 is working; ✅ and ❌
        // are the AgentPod hub saying the turn is over.
        for mark in ["✅", "❌"] {
            let marked = Self.row(
                "1", own: true, sender: "@me:x",
                reactions: [ReactionDto(key: mark, displayKey: mark, count: 1, byMe: false, senders: ["@atlas:x"])])
            #expect(state([marked]) == nil, "\(mark) should end the dock")
        }
        // The reader's own ✅ is not the agent's verdict.
        let mine = Self.row(
            "1", own: true, sender: "@me:x",
            reactions: [ReactionDto(key: "✅", displayKey: "✅", count: 1, byMe: true, senders: ["@me:x"])])
        #expect(state([mine]) == .sent(to: "Atlas"))
        // Typing still says working, whatever mark is there.
        let done = Self.row(
            "1", own: true, sender: "@me:x",
            reactions: [ReactionDto(key: "✅", displayKey: "✅", count: 1, byMe: false, senders: ["@atlas:x"])])
        #expect(state([done], typing: ["Atlas"]) == .onIt(["Atlas"]))
    }

    @Test("the reader's own reaction is not the agent's")
    func ownReaction() {
        let reacted = Self.row(
            "1", own: true, sender: "@me:x",
            reactions: [ReactionDto(key: "👍", displayKey: "👍", count: 1, byMe: true, senders: ["@me:x"])])
        #expect(state([reacted]) == .sent(to: "Atlas"))
    }

    @Test("the agent's answer ends it; someone else's does not")
    func answered() {
        // Mutation seen failing: counting any other member's message as the
        // answer when the agents are known — a colleague's "+1" cleared it.
        let mine = Self.row("1", own: true, sender: "@me:x")
        #expect(state([mine, Self.row("2", own: false, sender: "@atlas:x")]) == nil)
        #expect(state([mine, Self.row("2", own: false, sender: "@krishna:x")]) == .sent(to: "Atlas"))
        // A membership line is not an answer.
        #expect(
            state([mine, Self.row("2", own: false, sender: "@atlas:x", membership: "joined")])
                == .sent(to: "Atlas"))
    }

    @Test("an answer from before the latest send does not count")
    func answerBeforeSend() {
        // Mutation seen failing: searching the whole timeline for an agent
        // message instead of only after the last own one.
        let rows = [
            Self.row("1", own: true, sender: "@me:x"),
            Self.row("2", own: false, sender: "@atlas:x"),
            Self.row("3", own: true, sender: "@me:x"),
        ]
        #expect(state(rows) == .sent(to: "Atlas"))
    }

    @Test("nothing to say without a send this session, or without an agent")
    func silent() {
        let mine = Self.row("1", own: true, sender: "@me:x")
        #expect(state([mine], sent: false) == nil, "last week's message announced as just sent")
        #expect(state([mine], addressee: nil) == nil)
    }

    @Test("several agents typing in a room with no one addressee are all named")
    func severalAgents() {
        let result = state([], sent: false, typing: ["Atlas", "Quill"], addressee: nil)
        #expect(result == .onIt(["Atlas", "Quill"]))
        #expect(result?.text == "Atlas and Quill are on it…")
    }
}

// MARK: - D11: one name, and agents off the typing line

@MainActor
struct TypingCastTests {
    static func user(_ id: String, _ label: String) -> TypingUserDto {
        TypingUserDto(userId: id, displayName: label, label: label)
    }

    @Test("an agent typing is named by the header, and is not on the people's line")
    func agentUsesHeaderName() {
        // Mutations seen failing: `name(of:)` returning the label (the dock
        // said "atlas-bot"); the line not filtering agents (both the dock and
        // "atlas-bot is typing…" at once).
        let typing = TypingStore()
        typing.recognise(
            .init(agentIds: ["@atlas:x"], counterpart: .init(userId: "@atlas:x", name: "Atlas")),
            in: room)
        typing.focus(room)
        typing.handle(
            roomId: room, users: [Self.user("@atlas:x", "atlas-bot"), Self.user("@k:x", "Krishna")])

        #expect(typing.typingAgents == ["Atlas"])
        #expect(typing.line == "Krishna is typing…")
    }

    @Test("with no cast, everyone typing is on the line, as before")
    func noCast() {
        let typing = TypingStore()
        typing.focus(room)
        typing.handle(roomId: room, users: [Self.user("@atlas:x", "atlas-bot")])
        #expect(typing.line == "atlas-bot is typing…")
        #expect(typing.typingAgents.isEmpty)
    }

    @Test("a send is remembered per room, and forgotten at sign-out")
    func sent() {
        let typing = TypingStore()
        typing.noteSent(in: room)
        typing.focus("!other:x")
        #expect(typing.hasSent(in: room))
        #expect(!typing.hasSent(in: "!other:x"))
        typing.focus(nil)
        #expect(!typing.hasSent(in: room))
    }
}

@MainActor
struct RoomCastTests {
    static func person(_ id: String, _ name: String, agent: Bool) -> PersonDto {
        PersonDto(
            userId: id, name: name, initial: String(name.prefix(1)),
            runtime: agent ? RuntimeDto(harness: "claude", host: "ci") : nil, avatarUrl: nil)
    }

    static let atlas = person("@atlas:x", "atlas-bot", agent: true)
    static let quill = person("@quill:x", "Quill", agent: true)
    static let krishna = person("@k:x", "Krishna", agent: false)

    @Test("an agent's room with one agent in it: that agent is the header")
    func counterpart() {
        // Mutation seen failing: dropping the one-agent check, so a room with
        // two agents called whichever came first by the room's name.
        #expect(
            RoomCast.counterpart(among: [Self.atlas, Self.krishna], headerName: "Atlas", isAgentRoom: true)
                == .init(userId: "@atlas:x", name: "Atlas"))
        #expect(
            RoomCast.counterpart(among: [Self.atlas, Self.quill], headerName: "Atlas", isAgentRoom: true)
                == nil)
        #expect(
            RoomCast.counterpart(among: [Self.atlas], headerName: "Ops", isAgentRoom: false) == nil)
    }

    @Test("the cast is the room's members, not everyone known")
    func scopedToMembers() async {
        let cast = RoomCast()
        await cast.load(
            roomId: room, headerName: "Atlas", isAgentRoom: true,
            people: { [Self.atlas, Self.quill, Self.krishna] },
            memberIds: { ["@atlas:x", "@k:x", "@me:x"] })
        #expect(cast.people.map(\.userId) == ["@atlas:x", "@k:x"])
        #expect(cast.counterpart?.name == "Atlas")
        #expect(cast.addressee == "Atlas")
        #expect(cast.mentionables.map(\.displayName) == ["atlas-bot", "Krishna"])
    }

    @Test("without the members, no counterpart is guessed")
    func noMembers() async {
        let cast = RoomCast()
        await cast.load(
            roomId: room, headerName: "Atlas", isAgentRoom: true,
            people: { [Self.atlas] }, memberIds: { nil })
        #expect(cast.people.count == 1)
        #expect(cast.counterpart == nil)
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
