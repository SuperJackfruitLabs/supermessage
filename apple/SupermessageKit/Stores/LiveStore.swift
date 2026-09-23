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
        /// ACP's raw status. Not for display — see `phase` and `statusLabel`.
        public let status: String
        /// Where the call is, decided by the core. Glyph and colour key off
        /// this, never off `status`.
        public let phase: ToolPhase
        /// The core's word for `phase` — "Running", "Done". Rendered as given.
        public let statusLabel: String
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

    // MARK: - Time

    /// When this turn's first delta arrived — the reader's clock, not the
    /// agent's. `nil` when nothing is live.
    ///
    /// The reader's clock because the question the activity card answers is
    /// "how long have I been waiting", and the events carry no timestamp of
    /// their own to disagree with it.
    public private(set) var startedAt: Date?
    /// When the turn finished, so a finished card shows how long it took
    /// rather than a number that keeps climbing.
    public private(set) var endedAt: Date?
    /// When anything last arrived for this turn. What a still rendering
    /// measures against, since it cannot ask for the time.
    public private(set) var lastActivityAt: Date?

    private let clock: @MainActor () -> Date

    /// - Parameter clock: the time source, injectable so a test can say how
    ///   long a turn took without waiting for it.
    public init(clock: @escaping @MainActor () -> Date = { Date() }) {
        self.clock = clock
    }

    /// How long the turn has run at `now`: to its end once it has one.
    public func elapsed(at now: Date) -> TimeInterval? {
        guard let startedAt else { return nil }
        return max(0, (endedAt ?? now).timeIntervalSince(startedAt))
    }

    private func noteActivity() {
        let now = clock()
        if startedAt == nil { startedAt = now }
        lastActivityAt = now
    }

    private func noteFinished() {
        if !finished { endedAt = clock() }
        finished = true
    }

    // MARK: - Steps

    /// The step being worked on: the latest running call, else the latest
    /// queued one. `nil` between steps.
    public var currentStep: ToolCall? {
        tools.last { $0.phase == .running } ?? tools.last { $0.phase == .queued }
    }

    /// How many steps have completed.
    public var completedSteps: Int {
        tools.filter { $0.phase == .done }.count
    }

    /// The latest step that failed — the one state that still matters after
    /// the turn ends, so the card names it ahead of any running one.
    public var failedStep: ToolCall? {
        tools.last { $0.phase == .failed }
    }

    /// Whether a turn is streaming and has not finished.
    public var inProgress: Bool { isLive && !finished }

    // MARK: - Who

    /// What to call the agent, by room — set by the view that knows the
    /// room's header (D11). Keyed by room, not cleared by `focus`, for the
    /// same race `TypingStore.recognise` records.
    private var agentNames: [String: String] = [:]

    public func setAgentName(_ name: String?, for roomId: String) {
        agentNames[roomId] = name
    }

    /// The header's name for the agent in the focused room, when it has one.
    public var agentName: String? { roomId.flatMap { agentNames[$0] } }

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
            noteFinished()
            return
        }
        beginTurnIfFinished()
        noteActivity()
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
            noteFinished()
            return
        }
        beginTurnIfFinished()
        noteActivity()
        guard seq >= thoughtSeq else { return }
        thoughtSeq = seq
        thought = text
    }

    public func handleTool(
        roomId: String, seq: UInt64, toolCallId: String, title: String, kind: String?,
        status: String, phase: ToolPhase, statusLabel: String, locations: [String],
        input: String?, output: String?
    ) {
        guard accept(roomId) else { return }
        beginTurnIfFinished()
        noteActivity()
        let call = ToolCall(
            id: toolCallId, title: title, status: status, phase: phase, statusLabel: statusLabel,
            kind: kind, locations: locations,
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
        startedAt = nil
        endedAt = nil
        lastActivityAt = nil
    }

    /// Whether this belongs to the room on screen.
    ///
    /// A turn in another room is not this pane's business — showing it would
    /// put one agent's writing under another's name.
    private func accept(_ roomId: String) -> Bool {
        self.roomId == roomId
    }
}
