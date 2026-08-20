import Testing

@testable import SupermessageKit

/// What survives the end of a turn, and what does not.
///
/// Reported: "reasoning gets hidden as soon as the complete message is
/// delivered even when I am present in the room. I don't get enough time to
/// read the reasoning." The store threw away the reasoning and the tool calls
/// on `done`, which meant the record of *how* an agent reached its answer was
/// only ever on screen while the answer was still being written — and gone by
/// the time anyone had read the answer it belonged to.
@MainActor
struct LiveStoreTests {
    let room = "!r:x.org"

    func store() -> LiveStore {
        let live = LiveStore()
        live.focus(room)
        return live
    }

    @Test("reasoning outlives the turn that produced it")
    func reasoningSurvivesDone() {
        let live = store()
        live.handleThought(roomId: room, seq: 1, text: "Checking the logs first.", done: false)
        live.handleLive(roomId: room, seq: 1, text: "Looking…", done: false)

        live.handleLive(roomId: room, seq: 2, text: "", done: true)

        #expect(live.thought == "Checking the logs first.", "the reasoning was thrown away")
        #expect(live.finished)
        #expect(live.isLive, "the record disappeared along with the turn")
    }

    @Test("the streamed answer goes, because the real message says it better")
    func answerIsDroppedOnDone() {
        // The one thing that *should* go: it is about to arrive on the
        // timeline as a real message, and two copies of the same sentence
        // stacked on each other is what this avoids.
        let live = store()
        live.handleLive(roomId: room, seq: 1, text: "Half an answ", done: false)
        live.handleLive(roomId: room, seq: 2, text: "", done: true)

        #expect(live.answer == nil, "the streamed answer outlived the message that replaces it")
    }

    @Test("a thought's own done does not hide it either")
    func thoughtDoneKeepsTheText() {
        let live = store()
        live.handleThought(roomId: room, seq: 1, text: "Two options here.", done: false)
        live.handleThought(roomId: room, seq: 2, text: "", done: true)

        #expect(live.thought == "Two options here.")
        #expect(live.finished)
    }

    @Test("tool calls outlive the turn too")
    func toolsSurviveDone() {
        let live = store()
        live.handleTool(
            roomId: room, seq: 1, toolCallId: "c1", title: "Run tests", kind: "execute",
            status: "completed", locations: ["crates/core"], input: "cargo test", output: "ok")
        live.handleLive(roomId: room, seq: 1, text: "", done: true)

        #expect(live.tools.count == 1)
        #expect(live.tools[0].output == "ok")
    }

    @Test("the next turn replaces the last one's record")
    func aNewTurnClearsTheOldRecord() {
        // The record has to end somewhere, and this is where: it is replaced,
        // not expired. Otherwise two turns' reasoning would stack up.
        let live = store()
        live.handleThought(roomId: room, seq: 1, text: "First turn's thinking.", done: false)
        live.handleLive(roomId: room, seq: 1, text: "", done: true)

        live.handleLive(roomId: room, seq: 1, text: "Second turn…", done: false)

        #expect(live.thought == nil, "the last turn's reasoning survived into the next one")
        #expect(!live.finished)
        #expect(live.answer == "Second turn…")
    }

    @Test("a turn's record does not follow the reader into another room")
    func focusClearsIt() {
        let live = store()
        live.handleThought(roomId: room, seq: 1, text: "Room one's thinking.", done: false)
        live.handleLive(roomId: room, seq: 1, text: "", done: true)

        live.focus("!other:x.org")

        #expect(!live.isLive, "one room's turn showed up under another room's name")
        #expect(live.thought == nil)
    }

    @Test("a tool row with nothing behind it does not pretend otherwise")
    func detailIsOptional() {
        // Every harness today reports title, kind and status and nothing
        // else. A disclosure triangle opening onto an empty box says there is
        // something to see.
        let bare = LiveStore.ToolCall(
            id: "c1", title: "Read a file", status: "completed", kind: nil, locations: [],
            input: nil, output: nil)
        #expect(!bare.hasDetail)

        let touched = LiveStore.ToolCall(
            id: "c2", title: "Read a file", status: "completed", kind: nil,
            locations: ["src/main.rs"], input: nil, output: nil)
        #expect(touched.hasDetail)
    }

    @Test("a later report on the same call replaces it rather than stacking")
    func toolUpdatesMerge() {
        let live = store()
        live.handleTool(
            roomId: room, seq: 1, toolCallId: "c1", title: "Run tests", kind: nil,
            status: "in_progress", locations: [], input: nil, output: nil)
        live.handleTool(
            roomId: room, seq: 2, toolCallId: "c1", title: "Run tests", kind: nil,
            status: "completed", locations: [], input: nil, output: "3 passed")

        #expect(live.tools.count == 1, "one call produced two rows")
        #expect(live.tools[0].status == "completed")
        #expect(live.tools[0].output == "3 passed")
    }
}
