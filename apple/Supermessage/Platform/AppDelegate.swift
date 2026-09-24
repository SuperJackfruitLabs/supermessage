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

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        let action = response.actionIdentifier
        // Decoded here, off the main actor, so nothing non-Sendable crosses.
        let decoded = NotificationKeys.response(
            actionIdentifier: action, userInfo: response.notification.request.content.userInfo)
        await respond(decoded)
    }

    private func respond(_ decoded: NotificationKeys.Response) async {
        if case let .open(roomId) = decoded {
            NotificationRouter.shared.request(roomId: roomId)
            return
        }
        // An action can wake an app that is not running; its session starts
        // when the views do. Wait a little for it rather than dropping the
        // answer — and say so if it never comes.
        for _ in 0..<40 where platform == nil {
            try? await Task.sleep(for: .milliseconds(500))
        }
        if let platform {
            await platform.respond(to: decoded)
        } else if case let .answer(roomId, _) = decoded {
            LocalNotifier.postFailure(roomId: roomId)
        }
    }
}
