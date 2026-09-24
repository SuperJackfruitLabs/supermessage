import SupermessageKit
import UserNotifications

/// Posts what `NotificationComposer` decided, through the system.
///
/// Local notifications only. They work without a push gateway and without
/// the `aps-environment` entitlement — which is why they are all this app
/// can show until a gateway is deployed (AGENTS.md: Sygnal is not running).
/// The catch is that they are posted by this process, so they only happen
/// while it is alive: in the foreground, and for the short while iOS lets a
/// backgrounded app keep running.
enum LocalNotifier {
    /// The three categories. Registered at launch, before any notification
    /// can be delivered, so a tap on one posted by a previous launch still
    /// has its actions.
    static func registerCategories() {
        let allow = UNNotificationAction(
            identifier: NotificationKeys.allowAction, title: "Allow once",
            options: [.authenticationRequired])
        let reject = UNNotificationAction(
            identifier: NotificationKeys.rejectAction, title: "Reject",
            options: [.destructive, .authenticationRequired])
        // A gate is never answered from the Lock Screen: its details are
        // behind the card's disclosure, and approving work unread is exactly
        // the failure a gate exists to prevent. "Open" is the only action.
        let open = UNNotificationAction(
            identifier: NotificationKeys.openAction, title: "Open", options: [.foreground])

        let categories: Set<UNNotificationCategory> = [
            UNNotificationCategory(
                identifier: LocalNotification.Category.message.rawValue, actions: [],
                intentIdentifiers: []),
            UNNotificationCategory(
                identifier: LocalNotification.Category.permission.rawValue,
                actions: [allow, reject], intentIdentifiers: []),
            UNNotificationCategory(
                identifier: LocalNotification.Category.gate.rawValue, actions: [open],
                intentIdentifiers: []),
        ]
        UNUserNotificationCenter.current().setNotificationCategories(categories)
    }

    static func post(_ note: LocalNotification) {
        let content = UNMutableNotificationContent()
        content.title = note.title
        if let subtitle = note.subtitle { content.subtitle = subtitle }
        content.body = note.body
        content.categoryIdentifier = note.category.rawValue
        // One conversation per room in Notification Centre.
        content.threadIdentifier = note.roomId
        content.userInfo = NotificationKeys.userInfo(for: note)
        content.sound = .default
        // `.active`, not `.timeSensitive`, for decisions too: time-sensitive
        // delivery needs its own entitlement, which the current provisioning
        // profile does not carry.
        content.interruptionLevel = .active

        let request = UNNotificationRequest(identifier: note.id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// Something the reader tried from a notification did not land. Said
    /// plainly, and tapping it opens the room so they can answer there.
    static func postFailure(roomId: String) {
        let note = LocalNotification(
            id: "\(roomId)#answer-failed", roomId: roomId, eventId: nil,
            title: "Your answer wasn't sent", subtitle: nil,
            body: "Open the conversation to answer it there.", category: .gate)
        post(note)
    }

    /// Everything delivered, on sign-out. A notification from an account
    /// that is no longer signed in would open a room this app cannot show.
    static func removeAll() {
        let center = UNUserNotificationCenter.current()
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
    }
}
