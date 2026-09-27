import SupermessageKit
import UIKit
import UserNotifications

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
        // Expected in any build without the `aps-environment` entitlement,
        // which is every build until push is set up (see
        // apple/SupermessageWidgets/push.yml). Local notifications are
        // unaffected, so there is nothing to tell the reader.
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// A notification arriving while the app is in the foreground.
    ///
    /// Shown: the composer already declined to post anything for the room on
    /// screen, so whatever arrives here is about somewhere else.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
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
