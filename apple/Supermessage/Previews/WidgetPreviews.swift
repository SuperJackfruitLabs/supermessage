#if DEBUG
import SupermessageKit
import SwiftUI
import WidgetKit

/// The widgets, drawn in the app so the preview gate renders them with every
/// other preview (`scripts/snapshot-previews.sh`). The views are the widget
/// extension's own (`apple/SupermessageWidgets/Views`, compiled into both);
/// only the frame around them is a stand-in for the Home and Lock Screens.
private struct WidgetFrame<Content: View>: View {
    let family: WidgetFamily
    @ViewBuilder let content: Content

    private var size: CGSize {
        switch family {
        case .systemSmall: return CGSize(width: 170, height: 170)
        case .systemMedium: return CGSize(width: 364, height: 170)
        case .systemLarge: return CGSize(width: 364, height: 382)
        case .accessoryCircular: return CGSize(width: 76, height: 76)
        case .accessoryRectangular: return CGSize(width: 172, height: 76)
        default: return CGSize(width: 257, height: 26)
        }
    }

    private var isAccessory: Bool {
        [.accessoryCircular, .accessoryRectangular, .accessoryInline].contains(family)
    }

    var body: some View {
        if isAccessory {
            content
                .foregroundStyle(.white)
                .frame(width: size.width, height: size.height)
                .environment(\.colorScheme, .dark)
        } else {
            content
                .padding(16)
                .frame(width: size.width, height: size.height)
                .background(WidgetTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
    }
}

private func entry(_ snapshot: WidgetSnapshot) -> SnapshotEntry {
    SnapshotEntry(date: .now, content: .snapshot(snapshot))
}

private struct Board<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        content
            .padding(24)
            .background(Color(white: 0.55))
    }
}

#Preview("Needs you, small") {
    Board {
        WidgetFrame(family: .systemSmall) {
            NeedsYouWidgetView(entry: entry(WidgetSample.snapshot()), family: .systemSmall)
        }
    }
}

#Preview("Needs you, medium") {
    Board {
        WidgetFrame(family: .systemMedium) {
            NeedsYouWidgetView(entry: entry(WidgetSample.snapshot()), family: .systemMedium)
        }
    }
}

#Preview("Needs you, medium, dark") {
    Board {
        WidgetFrame(family: .systemMedium) {
            NeedsYouWidgetView(entry: entry(WidgetSample.snapshot()), family: .systemMedium)
        }
    }
    .preferredColorScheme(.dark)
}

#Preview("Needs you, large") {
    Board {
        WidgetFrame(family: .systemLarge) {
            NeedsYouWidgetView(
                entry: entry(WidgetSample.snapshot(decisions: 3)), family: .systemLarge)
        }
    }
}

#Preview("Needs you, sent") {
    Board {
        WidgetFrame(family: .systemMedium) {
            NeedsYouWidgetView(
                entry: entry(WidgetSample.snapshot(answered: true)), family: .systemMedium)
        }
    }
}

#Preview("Needs you, all clear") {
    Board {
        VStack(spacing: 16) {
            WidgetFrame(family: .systemSmall) {
                NeedsYouWidgetView(
                    entry: entry(WidgetSample.snapshot(decisions: 0)), family: .systemSmall)
            }
            WidgetFrame(family: .systemMedium) {
                NeedsYouWidgetView(
                    entry: entry(WidgetSample.snapshot(decisions: 0)), family: .systemMedium)
            }
        }
    }
}

#Preview("Needs you, Lock Screen") {
    Board {
        VStack(alignment: .leading, spacing: 16) {
            WidgetFrame(family: .accessoryInline) {
                NeedsYouWidgetView(entry: entry(WidgetSample.snapshot()), family: .accessoryInline)
            }
            HStack(spacing: 16) {
                WidgetFrame(family: .accessoryRectangular) {
                    NeedsYouWidgetView(
                        entry: entry(WidgetSample.snapshot()), family: .accessoryRectangular)
                }
                WidgetFrame(family: .accessoryCircular) {
                    NeedsYouWidgetView(
                        entry: entry(WidgetSample.snapshot()), family: .accessoryCircular)
                }
            }
            HStack(spacing: 16) {
                WidgetFrame(family: .accessoryRectangular) {
                    NeedsYouWidgetView(
                        entry: entry(WidgetSample.snapshot(decisions: 0)),
                        family: .accessoryRectangular)
                }
                WidgetFrame(family: .accessoryCircular) {
                    NeedsYouWidgetView(
                        entry: entry(WidgetSample.snapshot(decisions: 0)),
                        family: .accessoryCircular)
                }
            }
        }
        .padding(8)
        .background(Color.black)
    }
}

#Preview("Needs you, not yet available") {
    Board {
        HStack(spacing: 16) {
            WidgetFrame(family: .systemSmall) {
                NeedsYouWidgetView(
                    entry: SnapshotEntry(date: .now, content: .signedOut), family: .systemSmall)
            }
            WidgetFrame(family: .systemSmall) {
                NeedsYouWidgetView(
                    entry: SnapshotEntry(date: .now, content: .unavailable), family: .systemSmall)
            }
        }
    }
}

#Preview("Agents, medium") {
    Board {
        WidgetFrame(family: .systemMedium) {
            AgentsWidgetView(entry: entry(WidgetSample.snapshot()), family: .systemMedium)
        }
    }
}

#Preview("Agents, large") {
    Board {
        WidgetFrame(family: .systemLarge) {
            AgentsWidgetView(entry: entry(WidgetSample.snapshot()), family: .systemLarge)
        }
    }
}

#Preview("Agents, large, dark") {
    Board {
        WidgetFrame(family: .systemLarge) {
            AgentsWidgetView(entry: entry(WidgetSample.snapshot()), family: .systemLarge)
        }
    }
    .preferredColorScheme(.dark)
}
#endif
