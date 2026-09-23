import SwiftUI
import WidgetKit

/// Every agent room, with the roster's word for its state.
struct AgentsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.supermessage.agents", provider: SnapshotProvider()) {
            entry in
            AgentsView(entry: entry)
                .containerBackground(WidgetTheme.surface, for: .widget)
        }
        .configurationDisplayName("Agents")
        .description("What each agent is doing.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

struct AgentsView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch entry.content {
        case let .snapshot(snapshot):
            list(snapshot)
        case .unavailable, .signedOut:
            UnavailableView(content: entry.content)
        }
    }

    private var limit: Int { family == .systemLarge ? 6 : 3 }

    private func list(_ snapshot: WidgetSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Agents").font(.caption.weight(.semibold))
                    .foregroundStyle(WidgetTheme.contentMuted)
                Spacer()
                if snapshot.needsYou > 0 {
                    Text("\(snapshot.needsYou) need you")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(WidgetTheme.accent)
                }
            }
            if snapshot.agents.isEmpty {
                Text("No agent rooms yet.")
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
            ForEach(snapshot.agents.prefix(limit)) { agent in
                Link(destination: AppLink.room(agent.id) ?? URL(string: "supermessage://")!) {
                    AgentRow(agent: agent)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private struct AgentRow: View {
    let agent: WidgetSnapshot.Agent

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(dot)
                .frame(width: 8, height: 8)
            Text(agent.name)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(WidgetTheme.content)
                .lineLimit(1)
            Spacer(minLength: 4)
            Text(agent.state.prefix(1).uppercased() + agent.state.dropFirst())
                .font(.caption)
                .foregroundStyle(agent.needsYou ? WidgetTheme.accent : WidgetTheme.contentMuted)
            if let last = agent.lastActivity {
                Text(last, style: .relative)
                    .font(.caption2)
                    .foregroundStyle(WidgetTheme.contentFaint)
                    .lineLimit(1)
                    .frame(maxWidth: 64, alignment: .trailing)
            }
        }
    }

    private var dot: Color {
        if agent.needsYou { return WidgetTheme.accent }
        if agent.active { return WidgetTheme.ok }
        return WidgetTheme.contentFaint
    }
}
