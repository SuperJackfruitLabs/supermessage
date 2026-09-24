// Compiled into TWO targets: SupermessageKit, and the SupermessageWidgets
// extension (see apple/SupermessageWidgets/extensions.yml). ActivityKit
// matches an activity to its widget configuration by this type, so both
// sides must declare the very same shape — one file is how they cannot
// drift. Foundation and ActivityKit only: nothing here may reach the core.

import Foundation

#if canImport(ActivityKit)
import ActivityKit

/// An agent's turn, on the Lock Screen and in the Dynamic Island.
public struct AgentActivityAttributes: ActivityAttributes {
    /// What changes while the turn runs.
    public struct ContentState: Codable, Hashable, Sendable {
        /// What the agent is doing now: the running tool's title, or the
        /// phase of the turn when no tool is running.
        public var step: String
        /// Tool calls that have finished, whether they succeeded or failed.
        public var completedSteps: Int
        /// Tool calls this turn has made so far.
        public var totalSteps: Int
        /// Whether the turn has ended. The final update before the activity
        /// is dismissed says so, so the Lock Screen shows "Done" rather than
        /// freezing on the last step.
        public var finished: Bool

        public init(step: String, completedSteps: Int, totalSteps: Int, finished: Bool) {
            self.step = step
            self.completedSteps = completedSteps
            self.totalSteps = totalSteps
            self.finished = finished
        }
    }

    /// The agent's name, as the room header shows it.
    public var agentName: String
    /// The room the turn is in, so a tap opens it.
    public var roomId: String
    /// When the turn started, for the elapsed-time counter.
    public var startedAt: Date

    public init(agentName: String, roomId: String, startedAt: Date) {
        self.agentName = agentName
        self.roomId = roomId
        self.startedAt = startedAt
    }
}
#endif
