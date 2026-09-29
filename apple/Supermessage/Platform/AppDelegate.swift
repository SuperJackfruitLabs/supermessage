import SupermessageKit
import UIKit
import UserNotifications
import WidgetKit

/// The three things only an application delegate can receive: the APNs
/// device token, a notification tapped while the app was not running, and a
/// notification arriving while it is in the foreground.
///
/// It does not own the `Session` — `RootView` does. It attaches the platform
/// services to whichever session starts (`SessionHooks.willStart`), which is
/// the one the views are using.
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private var platform: PlatformCoordinator?
    /// A token that arrived before any session started.
    private var pendingToken: Data?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Before this returns, or a tap that launched the app is lost.
        UNUserNotificationCenter.current().delegate = self
        LocalNotifier.registerCategories()
        SessionHooks.willStart = { [weak self] session in self?.attach(session) }
        // A widget's button runs here, in this process (see
        // `AnswerDecisionIntent` for why) — installed before launch returns,
        // because a background launch for the intent runs it right after.
        DecisionIntentHandling.run = { roomId, eventId, optionId in
            await AppDelegate.answerFromWidget(roomId: roomId, eventId: eventId, optionId: optionId)
        }
        WidgetRefresh.register { [weak self] in
            await self?.platform?.refreshWidgetsInBackground() ?? false
        }
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { _ in WidgetRefresh.schedule() }
        return true
    }

    private func attach(_ session: Session) {
        guard platform?.session !== session else { return }
        let platform = PlatformCoordinator(session: session)
        self.platform = platform
        platform.start()
        if let pendingToken {
            platform.deviceTokenReceived(pendingToken)
            self.pendingToken = nil
        }
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        if let platform { platform.deviceTokenReceived(deviceToken) } else { pendingToken = deviceToken }
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: any Error
    ) {
        // Expected in any build without the `aps-environment` entitlement —
        // one generated without SM_PUSH (see apple/SupermessageWidgets/push.yml).
        // Local notifications are unaffected, so there is nothing to tell the
        // reader.
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// A notification arriving while the app is in the foreground.
    ///
    /// A local one is shown: the composer already declined to post anything
    /// for the room on screen. A remote one — the push the extension decided —
    /// is shown unless it is about the room on screen, or is an event the
    /// open timeline already notified for (`NotificationComposer.presentsRemote`).
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let shown: UNNotificationPresentationOptions = [.banner, .list, .sound]
        guard notification.request.trigger is UNPushNotificationTrigger else { return shown }
        // Read here, off the main actor, so nothing non-Sendable crosses.
        let info = notification.request.content.userInfo
        let roomId =
            info[NotificationKeys.roomId] as? String ?? info[RemotePush.roomIdKey] as? String
        let eventId =
            info[NotificationKeys.eventId] as? String ?? info[RemotePush.eventIdKey] as? String
        let presents = await presentsRemote(roomId: roomId, eventId: eventId)
        return presents ? shown : []
    }

    private func presentsRemote(roomId: String?, eventId: String?) -> Bool {
        platform?.presentsRemote(roomId: roomId, eventId: eventId) ?? true
    }

    /// A notification's action. The system waits for this to return before
    /// it considers the action handled — and, for an app it launched in the
    /// background to deliver the action, before it suspends it again.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        let action = response.actionIdentifier
        let typed = (response as? UNTextInputNotificationResponse)?.userText
        // Decoded here, off the main actor, so nothing non-Sendable crosses.
        let decoded = NotificationKeys.response(
            actionIdentifier: action, userInfo: response.notification.request.content.userInfo,
            userText: typed)
        await respond(decoded)
    }

    private func respond(_ decoded: NotificationKeys.Response) async {
        switch decoded {
        case .ignore:
            return
        case let .open(roomId):
            NotificationRouter.shared.request(roomId: roomId)
        case let .answer(answer):
            await send(answer)
        }
    }

    /// A widget's button: answer the decision it names, if the widgets'
    /// snapshot still owes it, the way a notification's action does.
    ///
    /// The snapshot is marked sent the moment this starts, so the widget
    /// stops offering the choice; a send that does not land makes it owed
    /// again and says so, as a failed notification answer does. Nothing is
    /// sent for a decision the snapshot no longer has — the board resolved
    /// it, or it was answered already — and the widget is redrawn without it.
    private static func answerFromWidget(roomId: String, eventId: String, optionId: String) async {
        guard let feed = WidgetFeed.shared() else { return }
        let activity = BackgroundActivity(name: "Answer from a widget")
        defer { activity.end() }
        let outcome = await WidgetAnswering.answer(
            roomId: roomId, eventId: eventId, optionId: optionId, feed: feed,
            via: CoreClient.shared, reload: { WidgetCenter.shared.reloadAllTimelines() })
        if outcome == .failed {
            await LocalNotifier.postFailure(roomId: roomId)
        }
    }

    /// Answer with no room open, and without waiting for any screen.
    ///
    /// An action on a notification delivered earlier can launch this process
    /// in the background, where no scene connects — so no `RootView`, and no
    /// `Session` ever starts. Waiting for one (as this once did, for up to
    /// forty seconds across two loops) outlived iOS's ~30 s budget and never
    /// sent. Instead this goes through the app's one core directly: restore
    /// the stored session without sync if nothing has, send to the room, and
    /// report — all inside `NotificationAnswerer.budget`. When a `Session` is
    /// live the restore is a no-op and the same client sends; when one
    /// starts later it starts sync on the client this restored.
    private func send(_ answer: NotificationAnswer) async {
        let activity = BackgroundActivity(name: "Answer from a notification")
        defer { activity.end() }
        if !(await NotificationAnswerer.send(answer, via: CoreClient.shared)) {
            await LocalNotifier.postFailure(roomId: answer.roomId)
        }
    }
}

/// A `beginBackgroundTask` that is ended exactly once — by its owner, or by
/// the system's expiration handler, whichever comes first.
@MainActor
private final class BackgroundActivity {
    private var id: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            self?.end()
        }
    }

    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
