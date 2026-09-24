import SwiftUI
import WidgetKit

/// What a widget has to draw.
struct SnapshotEntry: TimelineEntry, Sendable {
    enum Content: Sendable {
        /// The App Group is not provisioned for this build, so the app has
        /// no way to hand anything over. Said honestly, rather than drawn as
        /// "nothing needs you", which would be a claim.
        case unavailable
        case signedOut
        case snapshot(WidgetSnapshot)
    }

    let date: Date
    let content: Content

    static let placeholder = SnapshotEntry(
        date: .now,
        content: .snapshot(
            WidgetSnapshot(
                signedIn: true, needsYou: 2,
                agents: [
                    .init(id: "a", name: "Atlas", state: "active", needsYou: false, active: true,
                          lastActivity: .now),
                    .init(id: "b", name: "Hermes", state: "needs you", needsYou: true,
                          active: false, lastActivity: .now.addingTimeInterval(-600)),
                    .init(id: "c", name: "Ganesha", state: "idle", needsYou: false,
                          active: false, lastActivity: .now.addingTimeInterval(-7200)),
                ], updatedAt: .now)))
}

/// Reads the app's last snapshot. The app asks WidgetKit to reload whenever
/// it writes a new one, so the timeline here is a single entry; the hourly
/// refresh only keeps the relative times honest.
struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry { .placeholder }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (SnapshotEntry) -> Void) {
        completion(context.isPreview ? .placeholder : current())
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<SnapshotEntry>) -> Void) {
        completion(Timeline(entries: [current()], policy: .after(.now.addingTimeInterval(3600))))
    }

    private func current() -> SnapshotEntry {
        guard WidgetSnapshotStore.isAvailable else {
            return SnapshotEntry(date: .now, content: .unavailable)
        }
        guard let snapshot = WidgetSnapshotStore.read(), snapshot.signedIn else {
            return SnapshotEntry(date: .now, content: .signedOut)
        }
        return SnapshotEntry(date: .now, content: .snapshot(snapshot))
    }
}

/// The empty states, shared by both widgets.
struct UnavailableView: View {
    let content: SnapshotEntry.Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("supermessage")
                .font(.caption.weight(.semibold))
                .foregroundStyle(WidgetTheme.accent)
            Text(message)
                .font(.caption)
                .foregroundStyle(WidgetTheme.contentMuted)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var message: String {
        switch content {
        case .unavailable: return "Open the app to see what needs you."
        case .signedOut: return "Sign in to see what needs you."
        case .snapshot: return ""
        }
    }
}
