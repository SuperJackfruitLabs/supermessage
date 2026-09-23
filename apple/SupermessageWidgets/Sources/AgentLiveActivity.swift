import ActivityKit
import SwiftUI
import WidgetKit

/// An agent's turn: who, what it is doing now, how many steps are done, and
/// for how long it has been at it.
struct AgentLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AgentActivityAttributes.self) { context in
            LockScreenView(context: context)
                .activityBackgroundTint(WidgetTheme.surface)
                .activitySystemActionForegroundColor(WidgetTheme.content)
                .widgetURL(AppLink.room(context.attributes.roomId))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.attributes.agentName, systemImage: "sparkles")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(WidgetTheme.accentSoft)
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Elapsed(since: context.attributes.startedAt, finished: context.state.finished)
                        .font(.subheadline.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(context.state.step)
                            .font(.callout)
                            .lineLimit(2)
                        StepProgress(state: context.state)
                    }
                }
            } compactLeading: {
                Image(systemName: context.state.finished ? "checkmark" : "sparkles")
                    .foregroundStyle(WidgetTheme.accentSoft)
            } compactTrailing: {
                Text(StepProgress.count(context.state))
                    .font(.caption.monospacedDigit())
            } minimal: {
                Image(systemName: context.state.finished ? "checkmark" : "sparkles")
                    .foregroundStyle(WidgetTheme.accentSoft)
            }
            .widgetURL(AppLink.room(context.attributes.roomId))
        }
    }
}

private struct LockScreenView: View {
    let context: ActivityViewContext<AgentActivityAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(context.attributes.agentName, systemImage: "sparkles")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(WidgetTheme.accent)
                    .lineLimit(1)
                Spacer()
                Elapsed(since: context.attributes.startedAt, finished: context.state.finished)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
            Text(context.state.finished ? "Done" : context.state.step)
                .font(.body)
                .foregroundStyle(WidgetTheme.content)
                .lineLimit(2)
            StepProgress(state: context.state)
        }
        .padding()
    }
}

private struct Elapsed: View {
    let since: Date
    let finished: Bool

    var body: some View {
        if finished {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(WidgetTheme.ok)
        } else {
            Text(timerInterval: since...Date.distantFuture, countsDown: false)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 72, alignment: .trailing)
        }
    }
}

private struct StepProgress: View {
    let state: AgentActivityAttributes.ContentState

    static func count(_ state: AgentActivityAttributes.ContentState) -> String {
        state.totalSteps == 0 ? "…" : "\(state.completedSteps)/\(state.totalSteps)"
    }

    var body: some View {
        HStack(spacing: 8) {
            if state.totalSteps > 0 {
                ProgressView(value: Double(state.completedSteps), total: Double(state.totalSteps))
                    .tint(WidgetTheme.accent)
                Text("\(state.completedSteps) of \(state.totalSteps) steps")
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
                    .fixedSize()
            } else {
                Text("Starting")
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
        }
    }
}
