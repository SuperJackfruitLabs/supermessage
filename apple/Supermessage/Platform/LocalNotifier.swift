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
    /// has its actions.
    ///
    /// Every answer is `.authenticationRequired` and none is `.foreground`:
    /// the device must be unlocked to answer, and answering does not open
    /// the app. The app delegate sends it in the background
    /// (`NotificationAnswerer`), whether or not the app was running.
    static func registerCategories() {
        let allow = UNNotificationAction(
            identifier: NotificationKeys.allowAction, title: "Allow once",
            options: [.authenticationRequired])
        let reject = UNNotificationAction(
            identifier: NotificationKeys.rejectAction, title: "Reject",
            options: [.destructive, .authenticationRequired])
        // Gates are answered from the notification too — the operator's
        // decision of 2026-09-27, reversing the earlier rule that a gate
        // only opened (its details sit behind the card's disclosure). The
        // notification carries the gate's question, and an action the gate
        // does not offer opens it instead (`NotificationKeys.response`).
        let approve = UNNotificationAction(
            identifier: NotificationKeys.approveGateAction, title: "Approve",
            options: [.authenticationRequired])
        let requestChanges = UNTextInputNotificationAction(
            identifier: NotificationKeys.requestChangesAction, title: "Request changes",
            options: [.authenticationRequired], textInputButtonTitle: "Send",
            textInputPlaceholder: "What should change?")
        let rejectGate = UNNotificationAction(
            identifier: NotificationKeys.rejectGateAction, title: "Reject",
            options: [.destructive, .authenticationRequired])
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
                identifier: LocalNotification.Category.gate.rawValue,
                actions: [approve, requestChanges, rejectGate, open], intentIdentifiers: []),
            // Read it in the app first: only "Open".
            UNNotificationCategory(
                identifier: LocalNotification.Category.decision.rawValue, actions: [open],
                intentIdentifiers: []),
        ]
        UNUserNotificationCenter.current().setNotificationCategories(categories)
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
        let note = LocalNotification(
            id: "\(roomId)#answer-failed", roomId: roomId, eventId: nil,
            title: "Your answer wasn't sent", subtitle: nil,
            body: "Open the conversation to answer it there.", category: .decision)
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
