import Foundation

/// How the app's platform services find the session the views are using.
///
/// `RootView` owns the `Session` and the app delegate never sees it, yet the
/// delegate is where an APNs token and a notification action arrive. Only
/// the session that actually runs calls `start()` or `signIn` — a `Session`
/// built for a preview, or a default argument SwiftUI evaluates and throws
/// away, never does — so "the session that started" is the one to attach to.
@MainActor
public enum SessionHooks {
    /// Called at the top of `Session.start()` and `Session.signIn`.
    public static var willStart: ((Session) -> Void)?
}
