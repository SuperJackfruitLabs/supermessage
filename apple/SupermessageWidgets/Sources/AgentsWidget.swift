import SwiftUI
import WidgetKit

/// Every agent room, what each is doing, and a tap into its room.
struct AgentsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.agents, provider: SnapshotProvider()) { entry in
            AgentsFamilyView(entry: entry)
                .containerBackground(WidgetTheme.surface, for: .widget)
        }
        .configurationDisplayName("Agents")
        .description("What each agent is doing, and what it last said.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

private struct AgentsFamilyView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        AgentsWidgetView(entry: entry, family: family)
    }
}
