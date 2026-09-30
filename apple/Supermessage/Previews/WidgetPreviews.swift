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
    /// The smallest phone's widgets (iPhone 13 mini), where rows run out
    /// first.
    var mini = false
    @ViewBuilder let content: Content

    private var size: CGSize {
        if mini {
            switch family {
            case .systemMedium: return CGSize(width: 329, height: 155)
            case .systemLarge: return CGSize(width: 329, height: 345)
            default: break
            }
        }
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

private func entry(_ snapshot: WidgetSnapshot, at date: Date = .now) -> SnapshotEntry {
    SnapshotEntry(date: date, content: .snapshot(snapshot))
}

/// A fixed moment for the frames that print a time of day ("Since 12:25"),
/// which a moment taken from the wall clock would change on every render.
private let fixedNow = Date(timeIntervalSince1970: 1_790_670_135)

/// The recap as the Agents widget draws it, at the fixed moment.
private func recap(_ snapshot: WidgetSnapshot) -> SnapshotEntry {
    entry(snapshot, at: fixedNow)
}

/// The sample with six agents, the most a widget lists, all of whom did
/// something since the app was last opened.
private func fleet() -> WidgetSnapshot {
    var snapshot = WidgetSample.snapshot(now: fixedNow, decisions: 0)
    let ms = UInt64(fixedNow.timeIntervalSince1970 * 1000)
    let hour: UInt64 = 3_600_000
    snapshot.agents += [
        .init(
            roomId: "!ray", name: "Research Ray", lastActivityMs: ms - 26 * hour,
            line: "Approved, with one required factual correction in the second section"),
        .init(
            roomId: "!quill", name: "Writer Quill", lastActivityMs: ms - 50 * hour,
            line: "Hey! Doing great, thanks for checking in on the draft"),
    ]
    for index in snapshot.frames.indices {
        snapshot.frames[index].states += [
            .init(word: "quiet", tone: .quiet), .init(word: "quiet", tone: .quiet),
        ]
    }
    snapshot.recap += [
        .init(
            roomId: "!ray", name: "Research Ray", outcome: .said,
            line: "Approved, with one required factual correction in the second section",
            unread: 3, atMs: ms - 26 * 60_000),
        .init(
            roomId: "!krishna", name: "Krishna", outcome: .said,
            line: "Drafted the release notes", unread: 1, atMs: ms - 44 * 60_000),
    ]
    return snapshot
}

/// Nothing happened since the app was last opened.
private func quiet() -> WidgetSnapshot {
    var snapshot = WidgetSample.snapshot(now: fixedNow, decisions: 0)
    snapshot.recap = []
    return snapshot
}

/// Nothing new, and nobody working either: the one case "Nothing new since"
/// is said in.
private func allQuiet() -> WidgetSnapshot {
    var snapshot = quiet()
    snapshot.frames = snapshot.frames.map { frame in
        var frame = frame
        frame.working = 0
        frame.pulse = "All quiet"
        frame.busy = nil
        frame.states = frame.states.map { _ in .init(word: "idle", tone: .idle) }
        return frame
    }
    return snapshot
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
            AgentsWidgetView(
                entry: recap(WidgetSample.snapshot(now: fixedNow)), family: .systemMedium)
        }
    }
}

#Preview("Agents, large") {
    Board {
        WidgetFrame(family: .systemLarge) {
            AgentsWidgetView(entry: recap(fleet()), family: .systemLarge)
        }
    }
}

#Preview("Agents, mini, larger text") {
    Board {
        VStack(spacing: 16) {
            WidgetFrame(family: .systemMedium, mini: true) {
                AgentsWidgetView(entry: recap(fleet()), family: .systemMedium)
            }
            WidgetFrame(family: .systemLarge, mini: true) {
                AgentsWidgetView(entry: recap(fleet()), family: .systemLarge)
            }
        }
    }
    .dynamicTypeSize(.xLarge)
}

#Preview("Agents, nothing new") {
    Board {
        WidgetFrame(family: .systemMedium, mini: true) {
            AgentsWidgetView(entry: recap(quiet()), family: .systemMedium)
        }
    }
}

#Preview("Agents, nothing new, all quiet") {
    Board {
        WidgetFrame(family: .systemMedium, mini: true) {
            AgentsWidgetView(entry: recap(allQuiet()), family: .systemMedium)
        }
    }
}

#Preview("Needs you, mini, larger text") {
    Board {
        WidgetFrame(family: .systemMedium, mini: true) {
            NeedsYouWidgetView(
                entry: entry(WidgetSample.snapshot(decisions: 3)), family: .systemMedium)
        }
    }
    .dynamicTypeSize(.xLarge)
}

#Preview("Agents, large, dark") {
    Board {
        WidgetFrame(family: .systemLarge) {
            AgentsWidgetView(entry: recap(fleet()), family: .systemLarge)
        }
    }
    .preferredColorScheme(.dark)
}
#endif
