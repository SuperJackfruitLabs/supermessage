import SwiftUI
import WidgetKit

/// What the agents did since the app was last opened, and a tap into each
/// one's room.
struct AgentsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.agents, provider: SnapshotProvider()) { entry in
            AgentsFamilyView(entry: entry)
                .containerBackground(WidgetTheme.surface, for: .widget)
        }
        .configurationDisplayName("Agents")
        .description("What your agents did since you last opened the app.")
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
