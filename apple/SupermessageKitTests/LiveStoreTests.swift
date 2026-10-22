import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// `LiveStore` mirrors the core's turn record (`core::turns`) for the open
/// room, and keeps the text the record does not carry.
///
/// Fixtures are folded by the core's own `foldTurn`, so the summary and steps
/// in these tests are what the rules produce, not a second copy of them.
///
/// Reported, and still what this guards: "reasoning gets hidden as soon as the
/// complete message is delivered even when I am present in the room." What
/// survives the end of a turn is the reasoning and the steps; what goes is the
/// streamed answer.
@MainActor
struct LiveStoreTests {
    let room = "!r:x.org"
    let other = "!other:x.org"
    let t: UInt64 = 1_757_700_000_000

    /// A fetcher that answers every room with one fixed snapshot.
    struct StubTurns: TurnFetching {
        let snapshot: TurnSnapshot?
        func turn(roomId: String) async -> TurnSnapshot? { snapshot }
    }

    func store(turns: TurnSnapshot? = nil) -> LiveStore {
        let live = LiveStore(turns: StubTurns(snapshot: turns))
        live.select(room)
        return live
    }

    func fold(_ inputs: [TurnInput], room: String? = nil) -> TurnSnapshot {
        foldTurn(roomId: room ?? self.room, inputs: inputs)!
    }

    func answer(_ ms: UInt64, done: Bool = false, seq: UInt64 = 1) -> TurnInput {
        .answer(seq: seq, done: done, atMs: t + ms)
    }

    func tool(_ id: String, _ phase: ToolPhase, seq: UInt64, ms: UInt64) -> TurnInput {
        .tool(step: .fixture(id: id, title: "step \(id)", phase: phase, seq: seq), atMs: t + ms)
    }

    func update(_ snapshot: TurnSnapshot, revision: UInt64? = nil, turnId: UInt64? = nil)
        -> TurnUpdate
    {
        var summary = snapshot.summary
        if let revision { summary.revision = revision }
        if let turnId { summary.turnId = turnId }
        return TurnUpdate(summary: summary, step: nil)
    }

    // --- what survives the end of a turn --------------------------------

    @Test("reasoning outlives the turn that produced it")
    func reasoningSurvivesDone() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): clearing `thought` in `handleLive` on `done`.
        let live = store()
        let snapshot = fold([.thought(seq: 1, text: "Checking.", done: false, atMs: t), answer(1000)])
        live.apply(update(snapshot, revision: 1))
        live.handleThought(roomId: room, seq: 1, text: "Checking the logs first.", done: false)
        live.handleLive(roomId: room, seq: 1, text: "Looking…", done: false)

        live.apply(update(fold([.thought(seq: 1, text: "x", done: false, atMs: t), answer(2000, done: true, seq: 2)]), revision: 2))
        live.handleLive(roomId: room, seq: 2, text: "", done: true)

        #expect(live.thought == "Checking the logs first.", "the reasoning was thrown away")
        #expect(live.finished)
        #expect(live.isLive, "the record disappeared along with the turn")
    }

    @Test("the streamed answer goes, because the real message says it better")
    func answerIsDroppedOnDone() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): leaving `answer` set on `done`.
        let live = store()
        live.handleLive(roomId: room, seq: 1, text: "Half an answ", done: false)
        live.handleLive(roomId: room, seq: 2, text: "", done: true)
        #expect(live.answer == nil, "the streamed answer outlived the message that replaces it")
    }

    @Test("a reply that ends the turn takes the streamed answer with it")
    func replyEndingClearsTheAnswer() {
        // The core ends a turn when the agent's message lands, with no `done`.
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): clearing the answer only in `handleLive`.
        let live = store()
        let running = fold([answer(0)])
        live.apply(update(running, revision: 1))
        live.handleLive(roomId: room, seq: 1, text: "Streaming", done: false)
        var ended = fold([answer(0), .reply(eventMs: t + 500, atMs: t + 600)])
        ended.summary.revision = 2
        live.apply(update(ended))
        #expect(live.finished)
        #expect(live.answer == nil, "the card kept an answer the room now has")
    }

    @Test("a thought's own done does not hide it either")
    func thoughtDoneKeepsTheText() {
        let live = store()
        live.handleThought(roomId: room, seq: 1, text: "Two options here.", done: false)
        live.handleThought(roomId: room, seq: 2, text: "", done: true)
        #expect(live.thought == "Two options here.")
    }

    @Test("a late text delta cannot rewind the text")
    func textOrder() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): dropping the `seq >= answerSeq` guard.
        let live = store()
        live.handleLive(roomId: room, seq: 5, text: "newer", done: false)
        live.handleLive(roomId: room, seq: 4, text: "older", done: false)
        #expect(live.answer == "newer")
    }

    // --- following the core ----------------------------------------------

    @Test("an update for another room is not this pane's business")
    func otherRoomIgnored() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): dropping the `accept` guard in `apply`.
        let live = store()
        live.apply(update(fold([answer(0)], room: other), revision: 1))
        #expect(live.summary == nil)
        #expect(!live.isLive)
    }

    @Test("an older revision does not rewind the card")
    func olderRevisionIgnored() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): dropping the revision comparison in `apply`.
        let live = store()
        let snapshot = fold([tool("a", .running, seq: 1, ms: 0)])
        live.apply(update(snapshot, revision: 7))
        var older = update(snapshot, revision: 3)
        older.summary.currentStepTitle = "stale"
        live.apply(older)
        #expect(live.summary?.revision == 7)
        #expect(live.summary?.currentStepTitle == "step a")
    }

    @Test("a new turn drops the last one's steps, reasoning and answer")
    func newTurnStartsClean() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): not clearing when `turnId` changes.
        let live = store()
        let first = fold([tool("a", .done, seq: 1, ms: 0), answer(10)])
        live.apply(
            TurnUpdate(
                summary: first.summary,
                step: TurnStepChange(index: 0, step: first.steps[0])))
        live.handleThought(roomId: room, seq: 1, text: "First turn's thinking.", done: false)
        live.handleLive(roomId: room, seq: 1, text: "First answer", done: false)
        #expect(live.tools.count == 1)

        var next = first.summary
        next.turnId += 1
        next.revision += 5
        live.apply(TurnUpdate(summary: next, step: nil))

        #expect(live.tools.isEmpty)
        #expect(live.thought == nil, "the last turn's reasoning survived into the next one")
        #expect(live.answer == nil)
        live.handleThought(roomId: room, seq: 1, text: "Second turn.", done: false)
        #expect(live.thought == "Second turn.", "the old sequence blocked the new turn's text")
    }

    @Test("a step change lands at the index the core gave")
    func stepChangeIndex() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): always appending.
        let live = store()
        let snapshot = fold([tool("a", .running, seq: 1, ms: 0), tool("b", .running, seq: 2, ms: 1)])
        live.apply(TurnUpdate(summary: snapshot.summary, step: TurnStepChange(index: 0, step: snapshot.steps[0])))
        live.apply(TurnUpdate(summary: snapshot.summary, step: TurnStepChange(index: 1, step: snapshot.steps[1])))
        var done = snapshot.steps[0]
        done.phase = .done
        done.seq = 3
        var summary = snapshot.summary
        summary.revision += 1
        live.apply(TurnUpdate(summary: summary, step: TurnStepChange(index: 0, step: done)))

        #expect(live.tools.map(\.id) == ["a", "b"], "one call produced two rows or the order moved")
        #expect(live.tools[0].phase == .done)
    }

    @Test("the summary's words are the core's")
    func summaryWords() {
        let live = store()
        let snapshot = fold([
            tool("1", .done, seq: 1, ms: 0), tool("2", .running, seq: 2, ms: 1),
            tool("3", .failed, seq: 3, ms: 2), tool("4", .done, seq: 4, ms: 3),
        ])
        live.apply(update(snapshot))
        #expect(live.summary?.currentStepTitle == "step 2")
        #expect(live.summary?.stepIsTool == true)
        #expect(live.summary?.completedSteps == 2)
        #expect(live.summary?.failedStep == "step 3")
        #expect(live.inProgress)
    }

    @Test("a tool row with nothing behind it does not pretend otherwise")
    func detailIsOptional() {
        #expect(!TurnStep.fixture(id: "c1", title: "Read a file", phase: .done, seq: 1).hasDetail)
        #expect(
            TurnStep.fixture(
                id: "c2", title: "Read a file", phase: .done, seq: 1, locations: ["src/main.rs"]
            ).hasDetail)
    }

    // --- time -------------------------------------------------------------

    @Test("elapsed runs from the first event and stops at the end")
    func elapsed() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): measuring a finished turn to `now`.
        let live = store()
        live.apply(update(fold([answer(0), answer(30_000, done: true, seq: 2)])))
        #expect(live.elapsed(at: Date(timeIntervalSince1970: Double(t) / 1000 + 600)) == 30)
    }

    // --- leaving and coming back -----------------------------------------

    @Test("a record belongs to its room")
    func focusClearsIt() {
        let live = store()
        live.apply(update(fold([.thought(seq: 1, text: "Room one.", done: false, atMs: t)])))
        live.handleThought(roomId: room, seq: 1, text: "Room one's thinking.", done: false)
        live.select(other)
        #expect(!live.isLive, "one room's turn showed up under another room's name")
        #expect(live.thought == nil)
        #expect(live.summary == nil)
    }

    @Test("opening a room restores its turn from the core")
    func opening() async {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): `focus` not asking the core.
        let finished = fold([
            .thought(seq: 1, text: "Weighing two options.", done: false, atMs: t),
            tool("a", .done, seq: 1, ms: 5),
            answer(9000, done: true, seq: 2),
        ])
        let live = LiveStore(turns: StubTurns(snapshot: finished))
        await live.focus(room)

        #expect(live.finished)
        #expect(live.thought == "Weighing two options.", "coming back lost the reasoning")
        #expect(live.tools.count == 1)
        #expect(live.answer == nil, "the answer is the room's message now, not the card's")
        #expect(live.summary?.recordLabel != nil)
    }

    @Test("a room with no record opens clean")
    func openingNothing() async {
        let live = LiveStore(turns: StubTurns(snapshot: nil))
        await live.focus(room)
        #expect(!live.isLive)
        #expect(live.summary == nil)
    }

    @Test("a snapshot older than what the live channel showed does not rewind the card")
    func staleSnapshot() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): restoring without the revision check.
        let live = store()
        let snapshot = fold([tool("a", .running, seq: 1, ms: 0)])
        var held = snapshot.summary
        held.revision = 9
        live.apply(TurnUpdate(summary: held, step: TurnStepChange(index: 0, step: snapshot.steps[0])))
        #expect(live.tools.count == 1)
        var old = snapshot
        old.summary.revision = 2
        old.steps = []
        live.restore(old)
        #expect(live.summary?.revision == 9)
        #expect(live.tools.map(\.id) == ["a"], "an older snapshot replaced newer steps")
    }

    @Test("a step that arrives while the room opens does not leave a gap or a duplicate")
    func updateRacesRestore() async {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): `apply` appending a step past the end of `tools`.
        // The core took the snapshot at revision R (two steps); then an update
        // for the third step, at R+1, reached the store before `focus` resumed.
        let steps = fold([
            tool("a", .done, seq: 1, ms: 0), tool("b", .running, seq: 2, ms: 1),
            tool("c", .running, seq: 3, ms: 2),
        ])
        let atR = fold([tool("a", .done, seq: 1, ms: 0), tool("b", .running, seq: 2, ms: 1)])
        let live = store(turns: atR)
        var newer = atR.summary
        newer.revision += 1
        live.apply(TurnUpdate(summary: newer, step: TurnStepChange(index: 2, step: steps.steps[2])))
        #expect(live.tools.isEmpty, "a step past a gap was put at the wrong place")

        live.restore(atR)
        #expect(live.tools.map(\.id) == ["a", "b", "c"])
        #expect(live.summary?.revision == newer.revision)
        await live.resyncTask?.value

        // The next report for the same call replaces the row; it does not add one.
        var done = steps.steps[2]
        done.phase = .done
        done.seq = 4
        var later = newer
        later.revision += 1
        live.apply(TurnUpdate(summary: later, step: TurnStepChange(index: 2, step: done)))
        #expect(live.tools.map(\.id) == ["a", "b", "c"], "one call produced two rows")
        #expect(live.tools[2].phase == .done)
    }

    @Test("a step with a gap before it asks the core for the record")
    func gapResyncs() async {
        let full = fold([tool("a", .done, seq: 1, ms: 0), tool("b", .running, seq: 2, ms: 1)])
        let live = store(turns: full)
        var summary = full.summary
        summary.revision += 1
        live.apply(TurnUpdate(summary: summary, step: TurnStepChange(index: 1, step: full.steps[1])))
        await live.resyncTask?.value
        #expect(live.tools.map(\.id) == ["a", "b"])
    }

    // --- the haptic ---------------------------------------------------------

    private func outcome(_ turnId: UInt64?, _ endedBy: TurnEnding?) -> LiveStore.Outcome {
        LiveStore.Outcome(turnId: turnId, endedBy: endedBy)
    }

    @Test("the success haptic is for one turn finishing well, not a room opening")
    func hapticOnlyWithinOneTurn() {
        // Mutation (not run: no Swift toolchain here; the ios CI job runs the suite): comparing only `endedBy`, or dropping the `turnId` equality.
        // The turn goes from running to done or reply: yes.
        #expect(LiveStore.turnFinishedWell(from: outcome(3, nil), to: outcome(3, .done)))
        #expect(LiveStore.turnFinishedWell(from: outcome(3, nil), to: outcome(3, .reply)))
        // The core's time limit is not a success.
        #expect(!LiveStore.turnFinishedWell(from: outcome(3, nil), to: outcome(3, .timedOut)))
        // A room opening: cleared, then restored onto a turn that had ended.
        #expect(!LiveStore.turnFinishedWell(from: outcome(nil, nil), to: outcome(3, .done)))
        // Another room's turn, or a new turn after an old one.
        #expect(!LiveStore.turnFinishedWell(from: outcome(2, nil), to: outcome(3, .done)))
        #expect(!LiveStore.turnFinishedWell(from: outcome(2, .done), to: outcome(3, .done)))
        // Already ended.
        #expect(!LiveStore.turnFinishedWell(from: outcome(3, .reply), to: outcome(3, .done)))
    }

    @Test("opening a finished room changes the outcome but is not a finish")
    func openingIsNotAFinish() async {
        let finished = fold([answer(0), answer(900, done: true, seq: 2)])
        let live = LiveStore(turns: StubTurns(snapshot: finished))
        live.select(room)
        let before = live.outcome
        await live.focus(room)
        #expect(live.outcome != before)
        #expect(!LiveStore.turnFinishedWell(from: before, to: live.outcome))
    }

    @Test("a turn the core gave up on is called stopped, not done")
    func timedOutWord() {
        #expect(turnStatusWord(endedBy: nil) == "Working")
        #expect(turnStatusWord(endedBy: .done) == "Done")
        #expect(turnStatusWord(endedBy: .reply) == "Done")
        #expect(turnStatusWord(endedBy: .timedOut) == "Stopped")
    }

    @Test("signing out forgets the turn on screen")
    func signOutForgets() {
        let live = store()
        live.apply(update(fold([.thought(seq: 1, text: "Private.", done: false, atMs: t)])))
        live.handleThought(roomId: room, seq: 1, text: "Private reasoning.", done: false)
        live.clear()
        #expect(!live.isLive)
        #expect(live.summary == nil)
        live.handleLive(roomId: room, seq: 1, text: "late", done: false)
        #expect(live.answer == nil, "text for a room nobody has open was kept")
    }

    @Test("the header's name is kept per room")
    func agentName() {
        let live = LiveStore()
        live.setAgentName("Atlas", for: room)
        live.select(room)
        #expect(live.agentName == "Atlas", "focusing the room threw the name away")
        live.select(other)
        #expect(live.agentName == nil)
    }
}
