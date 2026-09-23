import ActivityKit
import Foundation
import SupermessageKit

/// Mirrors the live turn onto the Lock Screen and the Dynamic Island.
///
/// Driven by `LiveTurnSummary`, which changes when the step or the counts
/// change — not on every streamed token, which would spend ActivityKit's
/// update budget on text the activity does not show.
///
/// **Needs the widget extension.** The activity's layouts live there, and
/// the extension is only built when the project is generated with
/// `SM_EXTENSIONS=YES` (see `apple/SupermessageWidgets/extensions.yml`).
/// Without it there is nothing to draw an activity with, so none is started.
@MainActor
final class LiveActivityController {
    private var activity: Activity<AgentActivityAttributes>?
    private var last: LiveTurnSummary?

    /// Whether this build carries a widget extension to draw with.
    private let hasExtension: Bool = {
        guard let plugins = Bundle.main.builtInPlugInsURL,
            let contents = try? FileManager.default.contentsOfDirectory(atPath: plugins.path)
        else { return false }
        return contents.contains { $0.hasSuffix(".appex") }
    }()

    /// The turn as it now stands, in `roomId`, written by `agentName`.
    func update(summary: LiveTurnSummary?, finished: Bool, roomId: String?, agentName: String) {
        guard hasExtension, ActivityAuthorizationInfo().areActivitiesEnabled else { return }

        guard let summary, let roomId else {
            // The turn ended, or the reader left the room and the store let
            // it go. Only the first is a turn that is known to be done.
            if activity != nil { end(finished: finished) }
            last = nil
            return
        }

        if let current = activity, current.attributes.roomId != roomId {
            end(finished: false)
        }

        let state = AgentActivityAttributes.ContentState(
            step: summary.step, completedSteps: summary.completedSteps,
            totalSteps: summary.totalSteps, finished: false)

        if let activity {
            guard summary != last else { return }
            last = summary
            let handle = ActivityHandle(activity)
            Task { await handle.activity.update(ActivityContent(state: state, staleDate: nil)) }
            return
        }

        last = summary
        let attributes = AgentActivityAttributes(
            agentName: agentName, roomId: roomId, startedAt: Date())
        // Local updates only: `pushType: nil`, because with no gateway there
        // is nobody to push an update from.
        activity = try? Activity.request(
            attributes: attributes, content: ActivityContent(state: state, staleDate: nil),
            pushType: nil)
    }

    /// End whatever is showing, e.g. on sign-out.
    func endAll() {
        end(finished: false)
        for stray in Activity<AgentActivityAttributes>.activities {
            let handle = ActivityHandle(stray)
            Task { await handle.activity.end(nil, dismissalPolicy: .immediate) }
        }
    }

    private func end(finished: Bool) {
        guard let activity else { return }
        self.activity = nil
        let final = AgentActivityAttributes.ContentState(
            step: finished ? "Done" : (last?.step ?? ""),
            completedSteps: last?.completedSteps ?? 0, totalSteps: last?.totalSteps ?? 0,
            finished: finished)
        last = nil
        // A finished turn stays a moment, so a glance at the Lock Screen
        // shows it landed; one abandoned mid-way goes at once rather than
        // claiming progress nobody is tracking any more.
        let policy: ActivityUIDismissalPolicy =
            finished ? .after(Date().addingTimeInterval(120)) : .immediate
        let handle = ActivityHandle(activity)
        Task {
            await handle.activity.end(
                ActivityContent(state: final, staleDate: nil), dismissalPolicy: policy)
        }
    }
}

/// An `Activity` carried into a `Task`.
///
/// `Activity` is not `Sendable`, and Swift 6.2 (Xcode 26) refuses to send it
/// into the task that updates or ends it — which Xcode 16 accepted, so this
/// surfaced only in the TestFlight archive. ActivityKit documents an activity
/// as safe to update and end from any context; this box says so to the
/// compiler, and only this file creates one.
private struct ActivityHandle: @unchecked Sendable {
    let activity: Activity<AgentActivityAttributes>
    init(_ activity: Activity<AgentActivityAttributes>) { self.activity = activity }
}
