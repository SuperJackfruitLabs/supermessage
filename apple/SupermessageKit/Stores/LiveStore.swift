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

    public struct ToolCall: Identifiable, Equatable {
        public let id: String
        public let title: String
        public let status: String
    }

    private var roomId: String?
    /// The last sequence seen per stream, so a late delta cannot rewind the
    /// text. The core numbers these for the same reason the diff channels are
    /// numbered.
    private var answerSeq: UInt64 = 0
    private var thoughtSeq: UInt64 = 0

    public init() {}

    public var isLive: Bool { answer != nil || thought != nil || !tools.isEmpty }

    public func handleLive(roomId: String, seq: UInt64, text: String, done: Bool) {
        guard accept(roomId) else { return }
        if done {
            // The turn landed. The real message is arriving on the timeline
            // channel and will say this better.
            clear()
            return
        }
        guard seq >= answerSeq else { return }
        answerSeq = seq
        answer = text
    }

    public func handleThought(roomId: String, seq: UInt64, text: String, done: Bool) {
        guard accept(roomId) else { return }
        if done {
            thought = nil
            return
        }
        guard seq >= thoughtSeq else { return }
        thoughtSeq = seq
        thought = text
    }

    public func handleTool(
        roomId: String, seq: UInt64, toolCallId: String, title: String, status: String
    ) {
        guard accept(roomId) else { return }
        let call = ToolCall(id: toolCallId, title: title, status: status)
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

    public func clear() {
        answer = nil
        thought = nil
        tools = []
        answerSeq = 0
        thoughtSeq = 0
    }

    /// Whether this belongs to the room on screen.
    ///
    /// A turn in another room is not this pane's business — showing it would
    /// put one agent's writing under another's name.
    private func accept(_ roomId: String) -> Bool {
        self.roomId == roomId
    }
}
