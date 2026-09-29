// Compiled into the widget extension, and into the app so the widgets'
// previews render with its other previews.

import SwiftUI
import WidgetKit

#if !SM_WIDGET_EXTENSION
import SupermessageKit
#endif

/// What the agents did since the app was last opened: one row per agent that
/// did something — failed first, then finished, then said — and a tap into
/// its room.
///
/// A recap, not a live view. The Home Screen cannot be live (WidgetKit
/// defers and budgets the reloads a push asks for); the Lock Screen's fleet
/// Live Activity is what is live. Which agents are listed, in what order and
/// with what line is the core's (`core::widget`, `WidgetSnapshot.recap`);
/// an agent that did nothing is not listed at all.
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
        let most = max(1, min(limit, snapshot.recap.count))
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
                Text(since(snapshot))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(WidgetTheme.contentMuted)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text(frame.pulse)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(frame.needsYou > 0 ? WidgetTheme.accent : WidgetTheme.contentFaint)
                    .lineLimit(1)
            }
            if snapshot.recap.isEmpty {
                Text(nothingNew(snapshot))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(WidgetTheme.content)
                Text(snapshot.agents.isEmpty ? "No agent rooms yet." : "Quiet while you were away.")
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
            ForEach(snapshot.recap.prefix(rows)) { row in
                Link(destination: AppLink.room(row.roomId) ?? URL(string: "supermessage://")!) {
                    WidgetRecapRow(row: row, now: entry.date)
                }
            }
            if snapshot.recap.count > rows {
                Text("\(snapshot.recap.count - rows) more in the app")
                    .font(.caption2)
                    .foregroundStyle(WidgetTheme.contentFaint)
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    /// "Since 10:42" — when the app was last opened — or plain "Agents"
    /// before it has been.
    private func since(_ snapshot: WidgetSnapshot) -> String {
        guard let opened = snapshot.openedAt else { return "Agents" }
        return "Since \(opened.formatted(date: .omitted, time: .shortened))"
    }

    private func nothingNew(_ snapshot: WidgetSnapshot) -> String {
        guard let opened = snapshot.openedAt else { return "Nothing new" }
        return "Nothing new since \(opened.formatted(date: .omitted, time: .shortened))"
    }
}

/// One agent in the recap: a dot for how it went, its name, how many pushes
/// and how long ago, and under its name the line — "2 of 7 steps failed",
/// "Finished · 7 steps", or what it said.
struct WidgetRecapRow: View {
    let row: WidgetSnapshot.Recap
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 6) {
                Circle()
                    .fill(dot)
                    .frame(width: 7, height: 7)
                Text(row.name)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(WidgetTheme.content)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if row.unread > 1 {
                    Text("\(row.unread) new")
                        .font(.caption2)
                        .foregroundStyle(WidgetTheme.contentMuted)
                        .lineLimit(1)
                }
                Text(WidgetAge.since(row.at, now: now))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(WidgetTheme.contentFaint)
                    .lineLimit(1)
                    .frame(maxWidth: 58, alignment: .trailing)
            }
            Text(row.line)
                .font(.caption)
                .foregroundStyle(row.outcome == .failed ? WidgetTheme.danger : WidgetTheme.contentMuted)
                .lineLimit(1)
                .padding(.leading, 13)
        }
    }

    private var dot: Color {
        switch row.outcome {
        case .failed: return WidgetTheme.danger
        case .finished: return WidgetTheme.ok
        case .said: return WidgetTheme.accent
        }
    }
}
