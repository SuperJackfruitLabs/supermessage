import Foundation
import Observation
import SupermessageFFI

extension TurnStep: @retroactive Identifiable {}

/// The open room's agent turn, as the core records it.
///
/// **The core owns the record** (`core::turns`): when a turn started and
/// ended, its steps merged by call id, the order of late reports, the step
/// being worked on, the counts, the collapsed record's label, and how long a
/// finished record is kept. This store mirrors it for the room on screen:
/// `apply` takes the core's update, `restore` takes its snapshot when a room
/// opens, and nothing here decides what a turn is.
///
/// **What it still holds itself** is the text, because text does not travel in
/// a turn update: the answer being written (`handleLive`) and the reasoning
/// (`handleThought`). Both arrive on their own channels, after the update that
/// began the turn.
///
/// **None of this is history.** The streamed answer arrives on to-device
/// messages and has not been stored in a room; the real message follows when
/// the turn ends.
@MainActor
@Observable
public final class LiveStore {
    /// What the agent is writing, or `nil` when no turn is live.
    public private(set) var answer: String?
    /// Its reasoning, if it is sharing any. Collapsed by default in the view:
    /// it is context, not the answer.
    public private(set) var thought: String?
    /// Tool calls this turn, in the order they first fired.
    public private(set) var tools: [TurnStep] = []
    /// The core's summary of the turn on screen, or `nil` when it has none.
    public private(set) var summary: TurnSummary?

    /// Whether the turn has finished — by the agent's `done`, by its reply
    /// landing in the room, or by the core's time limit.
    ///
    /// The reasoning and the tool calls **outlive it**: what goes on `done` is
    /// the streamed *answer*, because the real message arrives on the timeline
    /// and says it better; what stays is everything the message does not
    /// carry.
    public var finished: Bool { summary?.endedBy != nil }

    /// Which turn is on screen and how it ended, for a haptic: the pair
    /// changes when a room opens, so the view has to tell a turn *finishing*
    /// from a room opening onto one that had already finished.
    public struct Outcome: Equatable, Sendable {
        public var turnId: UInt64?
        public var endedBy: TurnEnding?
    }

    public var outcome: Outcome {
        Outcome(turnId: summary?.turnId, endedBy: summary?.endedBy)
    }

    /// Whether going from `old` to `new` is a turn finishing well: the same
    /// turn, not ended before and ended now by the agent's `done` or its
    /// reply. Opening a room (the turn is a different one, or none) never
    /// counts, and neither does the time limit: a turn the core gave up on
    /// did not succeed.
    public static func turnFinishedWell(from old: Outcome, to new: Outcome) -> Bool {
        guard let turnId = old.turnId, turnId == new.turnId, old.endedBy == nil else {
            return false
        }
        return new.endedBy == .done || new.endedBy == .reply
    }

    /// Whether a turn is going and has not finished.
    public var inProgress: Bool { summary?.inProgress ?? false }

    /// Whether there is anything to show — a turn in progress, or the record
    /// of the one that just ended.
    ///
    /// Unlike the summary this is `false` for a turn that left nothing behind
    /// (an answer with no reasoning and no steps, once it is done): there is no
    /// card to draw for it.
    public var isLive: Bool { answer != nil || thought != nil || !tools.isEmpty }

    private var roomId: String?
    /// The last sequence seen per text stream, so a late delta cannot rewind
    /// the text.
    private var answerSeq: UInt64 = 0
    private var thoughtSeq: UInt64 = 0

    /// Steps whose index is past the end of `tools`: an update arrived for a
    /// step whose predecessors this store has not seen. They are never
    /// appended past the gap (that would put the step at the wrong position,
    /// and its next report would append it again); they wait for a snapshot
    /// to fill the gap.
    private var pendingSteps: [Int: TurnStep] = [:]
    /// The snapshot request a gap started, so a burst of updates asks once.
    /// Internal for tests.
    private(set) var resyncTask: Task<Void, Never>?

    private let turns: (any TurnFetching)?

    /// - Parameter turns: where a room's record is asked for when it opens.
    ///   `nil` (tests, previews) opens every room on a clean card.
    public init(turns: (any TurnFetching)? = nil) {
        self.turns = turns
    }

    // MARK: - Time

    /// When the turn started, on the reader's clock (the core's).
    public var startedAt: Date? {
        summary.map { Self.date(ms: $0.startedAtMs) }
    }

    /// When the turn finished, so a finished card shows how long it took
    /// rather than a number that keeps climbing.
    public var endedAt: Date? {
        summary?.endedAtMs.map(Self.date(ms:))
    }

    /// When the core last heard anything for this turn. Only as fresh as the
    /// last update: a growing answer or thought does not send one.
    public var lastActivityAt: Date? {
        summary.map { Self.date(ms: $0.lastActivityMs) }
    }

    /// How long the turn has run at `now`: to its end once it has one.
    public func elapsed(at now: Date) -> TimeInterval? {
        guard let startedAt else { return nil }
        return max(0, (endedAt ?? now).timeIntervalSince(startedAt))
    }

    private static func date(ms: UInt64) -> Date {
        Date(timeIntervalSince1970: Double(ms) / 1000)
    }

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

    // MARK: - From the core

    /// The core's update for a turn. Everything but the focused room's is
    /// ignored, and so is anything older than what is held.
    ///
    /// A different `turn_id` is a new turn: the last one's steps, reasoning and
    /// answer go. The core sends the update **before** the text that began the
    /// turn, so clearing here does not wipe it.
    public func apply(_ update: TurnUpdate) {
        let incoming = update.summary
        guard accept(incoming.roomId) else { return }
        if let held = summary, incoming.revision < held.revision { return }
        if summary?.turnId != incoming.turnId { forgetTurn() }
        summary = incoming
        if incoming.endedBy != nil {
            // However it ended — `done`, the reply landing, the time limit —
            // the streamed answer is over: the real message replaces it, or
            // nothing more is coming.
            answer = nil
            answerSeq = 0
        }
        guard let change = update.step else { return }
        place(change.step, at: Int(change.index))
        if !pendingSteps.isEmpty { resync() }
    }

    /// Put a step at the index the core gave it. `index == count` is an
    /// append; past it an update was missed, so the step waits (see
    /// `pendingSteps`) rather than landing in the wrong place.
    private func place(_ step: TurnStep, at index: Int) {
        if index < tools.count {
            tools[index] = step
        } else if index == tools.count {
            tools.append(step)
            while let next = pendingSteps.removeValue(forKey: tools.count) {
                tools.append(next)
            }
        } else {
            pendingSteps[index] = step
        }
    }

    /// Fold the core's steps into what is held, step by step, keeping the one
    /// with the higher sequence (a call's own, which only grows). Held steps
    /// the snapshot has not reached yet survive, and so do waiting ones that
    /// now fit.
    private func adopt(_ steps: [TurnStep]) {
        var merged = steps
        for (index, held) in tools.enumerated() {
            if index < merged.count {
                if held.seq > merged[index].seq { merged[index] = held }
            } else {
                merged.append(held)
            }
        }
        for index in pendingSteps.keys.sorted() {
            guard let waiting = pendingSteps[index] else { continue }
            if index < merged.count {
                if waiting.seq > merged[index].seq { merged[index] = waiting }
                pendingSteps[index] = nil
            } else if index == merged.count {
                merged.append(waiting)
                pendingSteps[index] = nil
            }
        }
        tools = merged
    }

    /// Ask the core for the whole record again, after a step arrived with a
    /// gap before it.
    private func resync() {
        guard resyncTask == nil, let roomId, let turns else { return }
        resyncTask = Task { [weak self] in
            let snapshot = await turns.turn(roomId: roomId)
            // Cancelled by a room change: the answer is for the last room.
            guard !Task.isCancelled, let self else { return }
            self.resyncTask = nil
            self.restore(snapshot)
        }
    }

    /// A room's whole record, from the core, when it opens.
    ///
    /// The live channel may have shown something newer in the time the
    /// question took. For another turn, that wins outright. For the same
    /// turn, the summary stays at the newer revision, but the steps are
    /// merged: an update that arrived first carries one step, and the
    /// snapshot has the ones before it.
    public func restore(_ snapshot: TurnSnapshot?) {
        guard let snapshot, accept(snapshot.summary.roomId) else { return }
        let sameTurn = summary?.turnId == snapshot.summary.turnId
        if let held = summary, !sameTurn, snapshot.summary.revision < held.revision { return }
        if !sameTurn { forgetTurn() }
        let newer = summary.map { snapshot.summary.revision >= $0.revision } ?? true
        if newer { summary = snapshot.summary }
        adopt(snapshot.steps)
        // A thought streamed in since the question was asked is newer.
        if thoughtSeq == 0 { thought = snapshot.thought }
    }

    // MARK: - Text

    public func handleLive(roomId: String, seq: UInt64, text: String, done: Bool) {
        guard accept(roomId) else { return }
        if done {
            // The turn landed. The streamed answer goes, because the real
            // message is arriving on the timeline channel and says it better.
            // The reasoning and the steps stay: nothing else on screen
            // carries them, and the core's record keeps them.
            answer = nil
            answerSeq = 0
            return
        }
        guard seq >= answerSeq else { return }
        answerSeq = seq
        answer = text
    }

    public func handleThought(roomId: String, seq: UInt64, text: String, done: Bool) {
        guard accept(roomId) else { return }
        // A thought's own `done` keeps the text, for the same reason: reasoning
        // that vanishes the moment the answer appears is reasoning nobody has
        // had time to read.
        if done { return }
        guard seq >= thoughtSeq else { return }
        thoughtSeq = seq
        thought = text
    }

    // MARK: - Focus

    /// Point the store at a room, with nothing of the last one's on screen.
    /// Synchronous, for callers that cannot wait on the core.
    public func select(_ roomId: String?) {
        self.roomId = roomId
        forgetTurn()
        summary = nil
    }

    /// Focus a room: reset, then show the core's record of its turn.
    ///
    /// The core keeps a record for every room, including one still in
    /// progress, so leaving a room and coming back loses nothing. The streamed
    /// answer is not part of the record: a turn restored mid-answer shows the
    /// rest of it from the next delta.
    public func focus(_ roomId: String?) async {
        select(roomId)
        guard let roomId, let turns else { return }
        let snapshot = await turns.turn(roomId: roomId)
        restore(snapshot)
    }

    /// Forget everything — signing out. The core clears its own book.
    public func clear() {
        roomId = nil
        forgetTurn()
        summary = nil
    }

    /// Drop the turn on screen but not the summary, which the caller sets.
    private func forgetTurn() {
        answer = nil
        thought = nil
        tools = []
        answerSeq = 0
        thoughtSeq = 0
        pendingSteps = [:]
        resyncTask?.cancel()
        resyncTask = nil
    }

    /// Whether this belongs to the room on screen.
    ///
    /// A turn in another room is not this pane's business — showing it would
    /// put one agent's writing under another's name.
    private func accept(_ roomId: String) -> Bool {
        self.roomId == roomId
    }
}
