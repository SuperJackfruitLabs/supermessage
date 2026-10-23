import SupermessageFFI
import SupermessageKit
import UserNotifications

/// Posts what `NotificationComposer` decided, through the system.
///
/// Local notifications. They work without a push gateway and without the
/// `aps-environment` entitlement, but they are posted by this process, so
/// they only happen while it is alive. With remote push registered (the
/// AgentPod hub's gateway, `SM_PUSH`), a message that arrives while the app
/// is away is the Notification Service Extension's to show, and these are
/// only what the open app sees first (`NotificationContext.remotePush`).
/// The categories below are registered for both.
enum LocalNotifier {
    /// The four categories. Registered at launch, before any notification
    /// can be delivered, so a tap on one posted by a previous launch still
    /// has its actions. Their identifiers, actions, copy and flags are the
    /// core's (`core::notification_policy::actions`).
    static func registerCategories() {
        let categories = Set(
            ([.message, .permission, .gate, .decision] as [NotificationCategory]).map { category in
                UNNotificationCategory(
                    identifier: notificationCategoryIdentifier(category: category),
                    actions: notificationActions(category: category).map(action(_:)),
                    intentIdentifiers: [])
            })
        UNUserNotificationCenter.current().setNotificationCategories(categories)
    }

    private static func action(_ spec: NotificationActionSpec) -> UNNotificationAction {
        var options: UNNotificationActionOptions = []
        if spec.destructive { options.insert(.destructive) }
        if spec.requiresUnlock { options.insert(.authenticationRequired) }
        if spec.opensApp { options.insert(.foreground) }
        if let input = spec.textInput {
            return UNTextInputNotificationAction(
                identifier: spec.id, title: spec.title, options: options,
                textInputButtonTitle: input.buttonTitle, textInputPlaceholder: input.placeholder)
        }
        return UNNotificationAction(identifier: spec.id, title: spec.title, options: options)
    }

    private static func request(for note: LocalNotification) -> UNNotificationRequest {
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

        return UNNotificationRequest(identifier: note.id, content: content, trigger: nil)
    }

    static func post(_ note: LocalNotification) {
        UNUserNotificationCenter.current().add(request(for: note))
    }

    /// Something the reader tried from a notification did not land. Said
    /// plainly, and tapping it opens the room so they can answer there.
    ///
    /// Awaits the system taking it: the caller may be a process launched in
    /// the background only to answer, about to be suspended the moment it
    /// reports back.
    static func postFailure(roomId: String) async {
        let note = LocalNotification(planned: answerFailedNotification(roomId: roomId))
        try? await UNUserNotificationCenter.current().add(request(for: note))
    }

    /// Everything delivered, on sign-out. A notification from an account
    /// that is no longer signed in would open a room this app cannot show.
    static func removeAll() {
        let center = UNUserNotificationCenter.current()
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
    }
}
