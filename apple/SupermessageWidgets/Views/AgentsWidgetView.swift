// Compiled into the widget extension, and into the app so the widgets'
// previews render with its other previews.

import SwiftUI
import WidgetKit

#if !SM_WIDGET_EXTENSION
import SupermessageKit
#endif

/// Every agent room: its state, what it last said or is doing now, and a
/// tap into its room.
///
/// The state word and its tone for this moment come from the entry's frame
/// (`core::widget`), so an agent reads "idle" fifteen minutes after it last
/// spoke without anybody writing anything.
struct AgentsWidgetView: View {
    let entry: SnapshotEntry
    let family: WidgetFamily

    var body: some View {
        switch entry.content {
        case let .snapshot(snapshot):
            if let frame = entry.frame {
                list(snapshot, frame)
            } else {
                WidgetUnavailableView(content: .waiting, family: family)
            }
        case .unavailable, .signedOut, .waiting:
            WidgetUnavailableView(content: entry.content, family: family)
        }
    }

    /// The most rows the family is asked for. Fewer are drawn when these do
    /// not fit — a larger text size, a long name — rather than letting the
    /// stack overflow and the widget crop its header and last row.
    private var limit: Int { family == .systemLarge ? 6 : 3 }

    private func list(_ snapshot: WidgetSnapshot, _ frame: WidgetSnapshot.Frame) -> some View {
        let most = max(1, min(limit, snapshot.agents.count))
        return ViewThatFits(in: .vertical) {
            ForEach((1...most).reversed(), id: \.self) { rows in
                list(snapshot, frame, rows: rows)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func list(_ snapshot: WidgetSnapshot, _ frame: WidgetSnapshot.Frame, rows: Int) -> some View {
        VStack(alignment: .leading, spacing: family == .systemLarge ? 10 : 6) {
            HStack(alignment: .firstTextBaseline) {
                Text("Agents")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(WidgetTheme.contentMuted)
                Spacer(minLength: 4)
                Text(frame.pulse)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(frame.needsYou > 0 ? WidgetTheme.accent : WidgetTheme.contentFaint)
                    .lineLimit(1)
            }
            if snapshot.agents.isEmpty {
                Text("No agent rooms yet.")
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
            ForEach(Array(snapshot.agents.prefix(rows).enumerated()), id: \.element.id) {
                index, agent in
                Link(destination: AppLink.room(agent.roomId) ?? URL(string: "supermessage://")!) {
                    WidgetAgentRow(
                        agent: agent,
                        state: index < frame.states.count ? frame.states[index] : nil,
                        now: entry.date)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}

/// One agent: a dot and its state, when it last spoke, and under its name
/// the step it is on or the last thing it said.
struct WidgetAgentRow: View {
    let agent: WidgetSnapshot.Agent
    let state: WidgetSnapshot.AgentState?
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 6) {
                Circle()
                    .fill(dot)
                    .frame(width: 7, height: 7)
                Text(agent.name)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(WidgetTheme.content)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if let state {
                    Text(state.word)
                        .font(.caption2)
                        .foregroundStyle(state.tone == .needsYou ? WidgetTheme.accent : WidgetTheme.contentMuted)
                        .lineLimit(1)
                }
                if let last = agent.lastActivity {
                    Text(WidgetAge.since(last, now: now))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(WidgetTheme.contentFaint)
                        .lineLimit(1)
                        .frame(maxWidth: 58, alignment: .trailing)
                }
            }
            if let detail {
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
                    .lineLimit(1)
                    .padding(.leading, 13)
            }
        }
    }

    /// The step while it works; otherwise what it last said.
    private var detail: String? {
        if state?.tone == .working, let step = agent.step { return step }
        return agent.line
    }

    private var dot: Color {
        switch state?.tone {
        case .needsYou?: return WidgetTheme.accent
        case .working?, .active?: return WidgetTheme.ok
        default: return WidgetTheme.contentFaint
        }
    }
}
