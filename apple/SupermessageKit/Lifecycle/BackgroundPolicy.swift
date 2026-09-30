import Foundation

/// Whether the app is on screen, for what it may do there.
///
/// `background` is from `didEnterBackground` (or a launch iOS made in the
/// background — ActivityKit delivering a token, a widget's button, a
/// notification's action, a background refresh) until `willEnterForeground`.
/// An inactive app — the notification centre pulled down over it — is still
/// in the foreground: iOS does not suspend it.
public enum AppPresence: Sendable, Equatable {
    case foreground
    case background
}

/// What the app may start on its own while it is in the background.
///
/// **Why there is a rule at all.** The account's SQLite stores live in the
/// App Group the Notification Service Extension shares, and iOS kills an app
/// it suspends while that app holds a lock on a file there — `0xdead10cc`,
/// TestFlight build 40: launched in the background, it restored the session,
/// started sync, and was suspended 2.3 s later with a store write under way.
/// A background launch is given a few seconds, not the thirty a background
/// task gets, and it can end at any moment.
///
/// So in the background the app starts only what needs no store: relaying
/// ActivityKit's tokens to the hub, which is an HTTP call with the session's
/// access token (`Session::register_live_activity_token`). Everything that
/// reads or writes the stores, or the App Group's other files, waits for the
/// foreground — or runs inside `StoreGuard.hold`, which keeps the app awake
/// until it is done and closes the stores after it.
public enum BackgroundPolicy {
    /// The work the app starts by itself, as opposed to work iOS woke it for
    /// (an answer, a refresh), which runs under `StoreGuard.hold`.
    public enum Work: CaseIterable, Sendable {
        /// Sync, and the room list, timeline and block-list streams on it.
        case sync
        /// ActivityKit's push-to-start and update tokens, to the hub.
        case liveActivityTokens
        /// The APNs pusher and the account's quiet push rules.
        case pushRegistration
        /// The widgets' snapshot, written from the roster (a `flock` in the
        /// App Group).
        case widgetSnapshot
        /// Agents' pictures, fetched into the App Group for the Live Activity.
        case agentAvatars
    }

    /// Whether `work` may start while the app is `presence`.
    public static func allows(_ work: Work, in presence: AppPresence) -> Bool {
        switch presence {
        case .foreground:
            return true
        case .background:
            return work == .liveActivityTokens
        }
    }

    /// How a launch restores the stored session.
    public enum Restore: Sendable, Equatable {
        /// Build the client and start sync and the streams: the reader is
        /// looking.
        case withStreams
        /// Build the client only — enough to relay tokens and to answer —
        /// and start the streams when the app comes to the foreground.
        case quietly
    }

    /// How a launch in `presence` restores.
    public static func restore(in presence: AppPresence) -> Restore {
        allows(.sync, in: presence) ? .withStreams : .quietly
    }
}
