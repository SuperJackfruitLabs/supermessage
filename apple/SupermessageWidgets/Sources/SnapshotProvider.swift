import SwiftUI
import WidgetKit

enum WidgetKinds {
    static let needsYou = "dev.supermessage.needs-you"
    static let agents = "dev.supermessage.agents"
}

/// Reads the snapshot the app and the Notification Service Extension write.
///
/// One entry per frame the core wrote (`SnapshotEntry.timeline`), so an
/// agent's state moves on time. The writers ask WidgetKit to reload at once
/// for a decision and at most every fifteen minutes for anything else
/// (`core::widget`, "Reloading"), and the timeline asks again after the same
/// fifteen minutes: it cannot know about a write that came after it was
/// built and was not worth a reload, and this is when that write is drawn.
struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: .now, content: .snapshot(WidgetSample.snapshot()))
    }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (SnapshotEntry) -> Void) {
        if context.isPreview {
            completion(placeholder(in: context))
        } else {
            completion(SnapshotEntry(date: .now, content: Self.current()))
        }
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<SnapshotEntry>) -> Void) {
        let now = Date.now
        let entries = SnapshotEntry.timeline(for: Self.current(), now: now)
        completion(
            Timeline(entries: entries, policy: .after(now.addingTimeInterval(WidgetSnapshot.reloadEvery))))
    }

    static func current() -> SnapshotEntry.Content {
        guard WidgetSnapshotStore.isAvailable else { return .unavailable }
        guard let snapshot = WidgetSnapshotStore.read() else { return .waiting }
        return snapshot.signedIn ? .snapshot(snapshot) : .signedOut
    }
}
