import Foundation
import SupermessageFFI

/// What a Live Activity says about the turn `LiveStore` is showing.
///
/// A value derived from the store, so the rule — when a turn counts as in
/// progress, which step is "current", what counts as completed — is testable
/// without ActivityKit, and so an activity is only updated when this
/// changes rather than on every streamed token.
public struct LiveTurnSummary: Equatable, Sendable {
    public var step: String
    public var completedSteps: Int
    public var totalSteps: Int

    public init(step: String, completedSteps: Int, totalSteps: Int) {
        self.step = step
        self.completedSteps = completedSteps
        self.totalSteps = totalSteps
    }

    /// The summary of a turn in progress, or `nil` when there is none.
    ///
    /// `nil` once `finished`: the store keeps a finished turn's reasoning and
    /// tools on screen (see `LiveStore.finished`), but on the Lock Screen a
    /// turn that has ended is not "an agent working".
    @MainActor
    public static func of(_ live: LiveStore) -> LiveTurnSummary? {
        of(
            answering: live.answer != nil, thinking: live.thought != nil,
            tools: live.tools.map { ($0.title, $0.phase) }, finished: live.finished)
    }

    public static func of(
        answering: Bool, thinking: Bool, tools: [(title: String, phase: ToolPhase)],
        finished: Bool
    ) -> LiveTurnSummary? {
        guard !finished, answering || thinking || !tools.isEmpty else { return nil }
        let completed = tools.filter { $0.phase == .done || $0.phase == .failed }.count
        let step: String
        if let running = tools.last(where: { $0.phase == .running }) {
            step = running.title
        } else if answering {
            step = "Writing the answer"
        } else if thinking {
            step = "Thinking"
        } else if let queued = tools.last(where: { $0.phase == .queued }) {
            step = queued.title
        } else {
            step = "Working"
        }
        return LiveTurnSummary(step: step, completedSteps: completed, totalSteps: tools.count)
    }
}
