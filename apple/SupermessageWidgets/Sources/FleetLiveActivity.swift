import ActivityKit
import SwiftUI
import WidgetKit

/// The fleet's Live Activity: started, updated and ended by the hub's pushes
/// (spec 2026-09-29), drawn here. The Lock Screen card is the priority — the
/// operator's phone has no Dynamic Island — and is `FleetActivityCard`,
/// previewed with the app's other previews; the island is kept simple.
struct FleetLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FleetActivityAttributes.self) { context in
            FleetActivityContent(state: context.state, isStale: context.isStale)
                .activityBackgroundTint(WidgetTheme.surface)
                .activitySystemActionForegroundColor(WidgetTheme.content)
                .widgetURL(Self.link(context.state))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(context.state.pulse)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if let top = context.state.agents.first {
                        FleetRow(agent: top, isStale: context.isStale)
                    }
                }
            } compactLeading: {
                FleetIslandCount(state: context.state)
            } compactTrailing: {
                Text(context.state.agents.first?.name ?? "")
                    .font(.caption)
                    .lineLimit(1)
                    .frame(maxWidth: 72)
            } minimal: {
                FleetIslandCount(state: context.state)
            }
            .widgetURL(Self.link(context.state))
        }
        // The watch's Smart Stack (watchOS 11), which shows the phone's Live
        // Activities in the small family (spec 2026-09-30, B3).
        .supplementalActivityFamilies([.small])
    }

    /// A tap opens what the card leads with: the decision, else the first
    /// agent's room.
    static func link(_ state: FleetActivityAttributes.ContentState) -> URL? {
        if let decision = state.decision {
            return AppLink.decision(roomId: decision.roomId, eventId: decision.eventId)
        }
        return state.agents.first.flatMap { AppLink.room($0.roomId) }
    }
}

/// The Lock Screen's card, or on the watch's Smart Stack its small form: the
/// family is only known from the environment.
private struct FleetActivityContent: View {
    let state: FleetActivityAttributes.ContentState
    let isStale: Bool

    @Environment(\.activityFamily) private var family

    var body: some View {
        switch family {
        case .small:
            FleetWatchCard(state: state, isStale: isStale)
        default:
            FleetActivityCard(state: state, isStale: isStale)
        }
    }
}
