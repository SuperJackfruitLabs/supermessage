import BackgroundTasks
import Foundation
import SupermessageKit
import WidgetKit

/// The widgets' background refresh — a `BGAppRefreshTask`.
///
/// Pushes keep the widgets current while the app is away (the Notification
/// Service Extension merges each one), but a push only says what happened in
/// one room. Now and then the system gives the app a short background turn;
/// this spends it catching the roster up — sync resumed for a few seconds,
/// the roster written as the widgets' authoritative picture, sync paused
/// again — and asks for the next.
///
/// When iOS launched the app for the refresh alone there is no scene, so no
/// `Session` and no roster to read. Then this only reloads the widgets, which
/// re-reads the snapshot and its frames: honest, if no newer.
enum WidgetRefresh {
    /// Also in Info.plist's `BGTaskSchedulerPermittedIdentifiers`.
    static let identifier = "dev.supermessage.ios.widgets-refresh"

    /// No sooner than this. The system decides when, from how the app is
    /// used; this is a floor, not a schedule.
    static let interval: TimeInterval = 20 * 60

    /// Register the handler. Must run before launch finishes, or the system
    /// has nothing to hand the task to.
    @MainActor
    static func register(refresh: @escaping @MainActor @Sendable () async -> Bool) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            let task = UncheckedTask(task)
            MainActor.assumeIsolated {
                schedule()
                let work = Task { @MainActor in
                    let done = await refresh()
                    WidgetCenter.shared.reloadAllTimelines()
                    task.value.setTaskCompleted(success: done)
                }
                task.value.expirationHandler = { work.cancel() }
            }
        }
    }

    /// Ask for the next refresh. Harmless to repeat: a new request replaces
    /// the pending one with the same identifier.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)
        try? BGTaskScheduler.shared.submit(request)
    }
}

/// A `BGTask` handed to the main queue it was delivered on. Not `Sendable`
/// as declared; used only on the main actor here.
private struct UncheckedTask: @unchecked Sendable {
    let value: BGTask
    init(_ value: BGTask) { self.value = value }
}
