import SwiftUI
import WidgetKit

/// The decisions waiting on the reader, answerable in place.
struct NeedsYouWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.needsYou, provider: SnapshotProvider()) { entry in
            NeedsYouFamilyView(entry: entry)
                .containerBackground(WidgetTheme.surface, for: .widget)
        }
        .configurationDisplayName("Needs you")
        .description("Decisions waiting on you — answer them from here.")
        .supportedFamilies([
            .systemSmall, .systemMedium, .systemLarge,
            .accessoryCircular, .accessoryRectangular, .accessoryInline,
        ])
    }
}

private struct NeedsYouFamilyView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        NeedsYouWidgetView(entry: entry, family: family)
    }
}
