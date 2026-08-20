import Foundation
import Observation
import SupermessageFFI

/// An agent's turn while it is still being written.
///
/// **None of this is history.** It arrives on to-device messages, nothing here
/// has been stored in a room, and the real message follows when the turn ends.
/// So it is kept only for the focused room and thrown away the moment the turn
/// lands — anything else would leave a ghost above a message that already says
/// the same thing.
@MainActor
@Observable
public final class LiveStore {
    /// What the agent is writing, or `nil` when no turn is live.
    public private(set) var answer: String?
    /// Its reasoning, if it is sharing any. Collapsed by default in the view:
    /// it is context, not the answer.
    public private(set) var thought: String?
    /// Tool calls this turn, in the order they fired.
    public private(set) var tools: [ToolCall] = []
    /// Whether the turn has finished.
    ///
    /// The reasoning and the tool calls **outlive it**. They used to be
    /// thrown away the instant the turn landed, which meant the record of how
    /// an agent reached its answer was on screen only while it was still
    /// being written — and gone by the time anyone had read the answer it
    /// belongs to. What goes on `done` is the streamed *answer*, because the
    /// real message arrives on the timeline and says it better; what stays is
    /// everything the message does not carry.
    public private(set) var finished = false

    public struct ToolCall: Identifiable, Equatable {
        public let id: String
        public let title: String
        public let status: String
        /// ACP's tool kind, when the harness said. Display text.
        public let kind: String?
        /// What the call touched — paths, mostly.
        public let locations: [String]
        /// What it was given and what it produced, bounded by the core.
        ///
        /// `nil` from a harness that does not report them — which is every
        /// harness today. `dev.agentpod.tool.update` carries the fields; the
        /// agent side has to start filling them in.
        public let input: String?
        public let output: String?

        /// Whether there is anything to open this row onto.
        public var hasDetail: Bool {
            input != nil || output != nil || !locations.isEmpty
        }
    }

    private var roomId: String?
    /// The last sequence seen per stream, so a late delta cannot rewind the
    /// text. The core numbers these for the same reason the diff channels are
    /// numbered.
    private var answerSeq: UInt64 = 0
    private var thoughtSeq: UInt64 = 0

    public init() {}

    /// Whether there is anything to show — a turn in progress, or the record
    /// of the one that just ended.
    public var isLive: Bool { answer != nil || thought != nil || !tools.isEmpty }

    public func handleLive(roomId: String, seq: UInt64, text: String, done: Bool) {
        guard accept(roomId) else { return }
        if done {
            // The turn landed. The streamed answer goes, because the real
            // message is arriving on the timeline channel and says it better
            // — but the reasoning and the tool calls stay, because nothing
            // else on screen carries them. They go when the *next* turn
            // starts, or when the reader leaves the room.
            answer = nil
            answerSeq = 0
            finished = true
            return
        }
        beginTurnIfFinished()
        guard seq >= answerSeq else { return }
        answerSeq = seq
        answer = text
    }

    public func handleThought(roomId: String, seq: UInt64, text: String, done: Bool) {
        guard accept(roomId) else { return }
        if done {
            // Kept, for the same reason as the tool calls above: reasoning
            // that vanishes the moment the answer appears is reasoning nobody
            // has had time to read.
            finished = true
            return
        }
        beginTurnIfFinished()
        guard seq >= thoughtSeq else { return }
        thoughtSeq = seq
        thought = text
    }

    public func handleTool(
        roomId: String, seq: UInt64, toolCallId: String, title: String, kind: String?,
        status: String, locations: [String], input: String?, output: String?
    ) {
        guard accept(roomId) else { return }
        beginTurnIfFinished()
        let call = ToolCall(
            id: toolCallId, title: title, status: status, kind: kind, locations: locations,
            input: input, output: output)
        if let index = tools.firstIndex(where: { $0.id == toolCallId }) {
            // A call reports again as it progresses — running, then completed.
            // Replacing rather than appending is what keeps one row per call.
            tools[index] = call
        } else {
            tools.append(call)
        }
    }

    /// Focus a room, discarding anything belonging to the last one.
    public func focus(_ roomId: String?) {
        self.roomId = roomId
        clear()
    }

    /// The first delta of a new turn clears the last one's record.
    ///
    /// Here rather than on `done` because that is the whole point: the record
    /// has to survive the end of its own turn. It ends when it is replaced.
    private func beginTurnIfFinished() {
        guard finished else { return }
        clear()
    }

    public func clear() {
        answer = nil
        thought = nil
        tools = []
        answerSeq = 0
        thoughtSeq = 0
        finished = false
    }

    /// Whether this belongs to the room on screen.
    ///
    /// A turn in another room is not this pane's business — showing it would
    /// put one agent's writing under another's name.
    private func accept(_ roomId: String) -> Bool {
        self.roomId == roomId
    }
}
