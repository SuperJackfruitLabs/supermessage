import Foundation
import SupermessageFFI
import UserNotifications
import WidgetKit

/// Turns the hub gateway's generic "New message" push into the message.
///
/// The push names a room and an event and nothing else (`event_id_only`). This
/// process — separate from the app, woken by `mutable-content`, given about
/// thirty seconds and roughly 24 MB — builds the core over the app's own
/// stores in the App Group as the `nse` holder of their lock, restores the
/// session without syncing, and asks the core what the notification says
/// (`Core.notificationFor`). The core fetches the event, decrypts it with the
/// app's crypto store, and decides title, body, category and actions exactly
/// as it does for the app's local notifications.
///
/// **Everything that can fail falls back to the push as it came**, plus the
/// room to open when tapped: a notification with generic text is better than
/// none, and the homeserver only pushed because somebody wants the reader.
///
/// It also keeps the widgets current. The app pauses sync in the background so
/// this process can take the stores' lock, which means that while the app is
/// away this is the only thing that sees what happens — so each push it
/// decides is merged into the widgets' snapshot too (`WidgetFeed`, the core's
/// `widget::apply_notification`): a decision asked, an agent's latest line, a
/// finished turn, a gate the board resolved. The app's own roster replaces
/// those increments when it next runs.
///
/// Kept small on purpose: logging at `warn`, no media fetched, nothing written
/// but what the SDK's own stores write and the widgets' one small file.
final class NotificationService: UNNotificationServiceExtension, @unchecked Sendable {
    private let lock = NSLock()
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var fallback: UNNotificationContent?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let push = RemotePush(userInfo: request.content.userInfo)
        let base = Self.fallback(for: request.content, push: push)
        lock.withLock {
            self.contentHandler = contentHandler
            self.fallback = base.copy() as? UNNotificationContent
        }
        guard let push else {
            deliver(base)
            return
        }
        let box = Unchecked(base)
        NotificationWorker.queue.async { [self] in
            deliver(NotificationWorker.shared.content(for: push, base: box.value))
        }
    }

    /// The system is about to give up on this push. Whatever was decided
    /// arrives too late; the push goes out as it came.
    override func serviceExtensionTimeWillExpire() {
        let fallback = lock.withLock { self.fallback }
        deliver(fallback ?? UNNotificationContent())
    }

    /// Hand `content` to the system, once — whichever of the decision and the
    /// deadline gets here first.
    private func deliver(_ content: UNNotificationContent) {
        let handler = lock.withLock { () -> ((UNNotificationContent) -> Void)? in
            defer { contentHandler = nil }
            return contentHandler
        }
        handler?(content)
    }

    /// The push's own content, with the badge from its unread count and the
    /// keys that let a tap open the room.
    private static func fallback(
        for content: UNNotificationContent, push: RemotePush?
    ) -> UNMutableNotificationContent {
        let base =
            (content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        guard let push else { return base }
        if let unread = push.unreadCount { base.badge = NSNumber(value: unread) }
        var info = base.userInfo
        for (key, value) in push.openingUserInfo { info[key] = value }
        base.userInfo = info
        return base
    }
}

/// The core, and the one queue it is called on.
///
/// One per process: iOS keeps an extension process alive between pushes, and
/// a second `Core` over the same stores would be a second client — the thing
/// the store lock exists to keep apart. Every `Core` call blocks, so none runs
/// on a cooperative thread (AGENTS.md, rule 3); they run here, serially.
final class NotificationWorker: @unchecked Sendable {
    static let queue = DispatchQueue(label: "dev.supermessage.nse", qos: .userInitiated)
    static let shared = NotificationWorker()

    /// Touched only on `queue`.
    private var core: Core?

    /// What to show for `push`. Called on `queue`.
    func content(for push: RemotePush, base: UNMutableNotificationContent)
        -> UNNotificationContent
    {
        guard let core = sharedCore(),
            (try? core.restoreSessionQuietly()) == true,
            let note = try? core.notificationFor(roomId: push.roomId, eventId: push.eventId)
        else { return base }
        Self.feedWidgets(note)
        return Self.apply(note, to: base)
    }

    /// Merge `note` into the widgets' snapshot and, when it changed what a
    /// widget shows, ask WidgetKit to redraw. WidgetKit defers and coalesces
    /// reloads asked for from an extension, and budgets them; the core asks
    /// on every change and leaves the pacing to it.
    static func feedWidgets(_ note: NotificationDto, feed: WidgetFeed? = WidgetFeed.shared()) {
        guard let feed, feed.apply(note) else { return }
        WidgetCenter.shared.reloadAllTimelines()
    }

    private func sharedCore() -> Core? {
        if let core { return core }
        guard let options = CoreLocation.current(.notificationService) else { return nil }
        // Warnings only. The core's default is debug-level tracing to stderr,
        // which an extension with a 24 MB ceiling has no use for.
        setenv("SUPERMESSAGE_LOG", "warn", 0)
        let built = Core.withOptions(options: options)
        core = built
        return built
    }

    /// Whether this build may drop a push — the filtering entitlement is in
    /// it (`SM_NSE_FILTERING`, see nse-filtering.yml).
    static let canFilter = NotificationFiltering.isEnabled(
        infoDictionary: Bundle.main.infoDictionary)

    /// `note` over the push's content, as `RemotePresentation` decides.
    ///
    /// A suppressed event — a reaction, an edit, a turn card, this account's
    /// own message — is dropped when this build holds Apple's filtering
    /// entitlement, and otherwise said in one quiet line: passive, silent,
    /// ranked last. Never emptied without it: iOS shows an empty push anyway,
    /// as a blank notification.
    static func apply(
        _ note: NotificationDto, to base: UNMutableNotificationContent,
        canFilter: Bool = NotificationWorker.canFilter
    ) -> UNNotificationContent {
        switch RemotePresentation(note, canFilter: canFilter) {
        case .drop:
            // With the entitlement, an empty content is a dropped push.
            return UNNotificationContent()
        case .quiet(let title, let body):
            if let title { base.title = title }
            base.subtitle = ""
            base.body = body
            base.sound = nil
            base.interruptionLevel = .passive
            base.relevanceScore = 0
            // No actions to offer: tapping opens the room, which the
            // push's keys already say.
            base.categoryIdentifier = LocalNotification.Category.message.rawValue
            base.threadIdentifier = note.threadId
            return base
        case .show(let local):
            base.title = local.title
            base.subtitle = local.subtitle ?? ""
            base.body = local.body
            base.categoryIdentifier = local.category.rawValue
            base.threadIdentifier = note.threadId
            var info = base.userInfo
            for (key, value) in NotificationKeys.userInfo(for: local) { info[key] = value }
            base.userInfo = info
            return base
        }
    }
}

/// A value handed to the worker queue that the compiler cannot prove
/// `Sendable`. The content is created for this push and touched by one queue
/// at a time — here, then there — never both.
private struct Unchecked<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}
