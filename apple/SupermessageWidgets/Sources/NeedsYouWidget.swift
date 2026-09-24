import SwiftUI
import WidgetKit

/// How many rooms owe somebody an answer from the reader.
struct NeedsYouWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.supermessage.needs-you", provider: SnapshotProvider()) {
            entry in
            NeedsYouView(entry: entry)
                .containerBackground(WidgetTheme.surface, for: .widget)
        }
        .configurationDisplayName("Needs you")
        .description("Decisions waiting on you.")
        .supportedFamilies([
            .systemSmall, .accessoryCircular, .accessoryRectangular, .accessoryInline,
        ])
    }
}

struct NeedsYouView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch entry.content {
        case let .snapshot(snapshot):
            counted(snapshot.needsYou)
        case .unavailable, .signedOut:
            if family == .systemSmall {
                UnavailableView(content: entry.content)
            } else {
                Text("—").accessibilityLabel("No data")
            }
        }
    }

    @ViewBuilder
    private func counted(_ count: Int) -> some View {
        switch family {
        case .accessoryInline:
            Text(count == 0 ? "Nothing needs you" : "\(count) need\(count == 1 ? "s" : "") you")
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Text("\(count)").font(.title2.weight(.semibold))
                    Image(systemName: "hand.raised").font(.caption2)
                }
            }
            .accessibilityLabel("\(count) need you")
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text("Needs you").font(.headline)
                Text(count == 0 ? "All clear" : "\(count) waiting").font(.body)
            }
        default:
            VStack(alignment: .leading, spacing: 6) {
                Label("Needs you", systemImage: "hand.raised")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(WidgetTheme.contentMuted)
                Spacer(minLength: 0)
                Text("\(count)")
                    .font(.system(size: 44, weight: .semibold, design: .rounded))
                    .foregroundStyle(count == 0 ? WidgetTheme.contentFaint : WidgetTheme.accent)
                    .contentTransition(.numericText())
                Text(count == 0 ? "All clear" : (count == 1 ? "decision waiting" : "decisions waiting"))
                    .font(.caption)
                    .foregroundStyle(WidgetTheme.contentMuted)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }
}
