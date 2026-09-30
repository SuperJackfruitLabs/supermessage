import Foundation
import SupermessageFFI
import UIKit

/// The core's calls that close and reopen the account's stores
/// (`Session::suspend` / `Session::resume`), as a seam a test can count.
public protocol StoreSuspending: Sendable {
    /// Stop sync and close every store connection, waiting for a write
    /// under way to commit. Idempotent.
    func suspendStores() async
    /// Reopen what `suspendStores` closed, without starting sync. Idempotent.
    func resumeStores() async
}

extension CoreClient: StoreSuspending {}

/// `CoreClient.shared`, reached only when a call is made — so that asking
/// the shared guard where the app is (`presence`), as `RootView` does even in
/// a preview, never builds a core.
private struct SharedCoreStores: StoreSuspending {
    func suspendStores() async { await CoreClient.shared.suspendStores() }
    func resumeStores() async { await CoreClient.shared.resumeStores() }
}

/// One `beginBackgroundTask`, by its number.
public struct BackgroundTaskID: Hashable, Sendable {
    public let raw: Int
    public init(_ raw: Int) { self.raw = raw }
}

/// `UIApplication.beginBackgroundTask` and `endBackgroundTask`, as a seam.
@MainActor
public protocol BackgroundTasking: Sendable {
    /// Ask for time to finish `name`. `expiration` is called on the main
    /// thread shortly before that time runs out.
    func begin(_ name: String, expiration: @escaping @MainActor @Sendable () -> Void)
        -> BackgroundTaskID
    func end(_ id: BackgroundTaskID)
}

/// The real one.
@MainActor
public struct UIKitBackgroundTasks: BackgroundTasking {
    public init() {}

    public func begin(_ name: String, expiration: @escaping @MainActor @Sendable () -> Void)
        -> BackgroundTaskID
    {
        BackgroundTaskID(
            UIApplication.shared.beginBackgroundTask(withName: name) { expiration() }.rawValue)
    }

    public func end(_ id: BackgroundTaskID) {
        let task = UIBackgroundTaskIdentifier(rawValue: id.raw)
        // `.invalid` is what `begin` returns when no time is left; there is
        // nothing to end.
        guard task != .invalid else { return }
        UIApplication.shared.endBackgroundTask(task)
    }
}

/// Keeps the app from ever being suspended holding a lock in the App Group.
///
/// The account's stores are open exactly while the app is in the foreground
/// or something holds them (`hold`); otherwise they are closed
/// (`Session::suspend`: sync stopped, every connection dropped, any write
/// under way committed first). And every step that ends with them closed —
/// going into the background, the end of a hold — runs inside a
/// `beginBackgroundTask`, so iOS does not suspend the app half way through
/// closing them.
///
/// `0xdead10cc`, TestFlight build 40, is what happens otherwise: iOS kills an
/// app it suspends while that app holds a lock on a file in a shared
/// container, and a SQLite write holds its locks until it commits. See
/// `BackgroundPolicy` for what the app starts in the background at all.
///
/// Transitions are applied one after another, each reading what is wanted
/// *when it runs* (`settle`), so a quick background → foreground never ends
/// with the stores closed under a reader. Each is applied whether or not it
/// looks necessary: the core's calls are idempotent and cheap, and a guard
/// that trusted its own memory of the stores' state would be wrong the first
/// time something else reopened them.
@MainActor
public final class StoreGuard {
    /// The app's one guard, over the app's one core.
    public static let shared = StoreGuard(stores: SharedCoreStores(), tasks: UIKitBackgroundTasks())

    public private(set) var presence: AppPresence

    private let stores: any StoreSuspending
    private let tasks: any BackgroundTasking
    /// The holds still running and not expired.
    private var holds: Set<Int> = []
    private var nextHold = 0
    /// The last transition queued; the next waits for it.
    private var tail: Task<Void, Never>?

    /// `presence` defaults to the background: until the app says it is on
    /// screen, the safe assumption is that it may be suspended.
    public init(
        stores: any StoreSuspending, tasks: any BackgroundTasking,
        presence: AppPresence = .background
    ) {
        self.stores = stores
        self.tasks = tasks
        self.presence = presence
    }

    /// Whether the stores should be open now.
    public var wantsStoresOpen: Bool {
        presence == .foreground || !holds.isEmpty
    }

    /// The app moved to `presence` — launched there, or `willEnterForeground`
    /// / `didEnterBackground`.
    ///
    /// Into the background, a background task is begun **before this
    /// returns**, synchronously on the main thread that delivered the
    /// notification, and held until the stores are closed. The returned task
    /// is that work, for a caller (a test) that wants to wait for it.
    @discardableResult
    public func entered(_ presence: AppPresence) -> Task<Void, Never> {
        self.presence = presence
        switch presence {
        case .foreground:
            return Task { await self.settle() }
        case .background:
            let ticket = Ticket()
            ticket.id = tasks.begin("Closing the stores") { [tasks = self.tasks] in
                // Out of time. The close already queued is all that can be
                // done; the task must end now or iOS kills the app for that.
                ticket.end(tasks)
            }
            return Task {
                await self.settle()
                ticket.end(self.tasks)
            }
        }
    }

    /// Run `work`, which needs the stores, with them open and the app kept
    /// awake for it; close them after if the app is in the background and
    /// nothing else holds them.
    ///
    /// For what iOS wakes the app to do — answer a notification or a
    /// widget's button, refresh the widgets, restore a session in a
    /// background launch — and for foreground work that must not be cut off
    /// by the app leaving the screen.
    ///
    /// If the background task expires first, the stores are closed then,
    /// under `work` if need be: the core waits for a write under way to
    /// commit, and whatever `work` tries next fails rather than taking a lock.
    public func hold<T: Sendable>(_ name: String, _ work: @MainActor () async -> T) async -> T {
        let hold = nextHold
        nextHold += 1
        holds.insert(hold)
        let ticket = Ticket()
        ticket.id = tasks.begin(name) { [weak self, tasks = self.tasks] in
            if let self, self.holds.remove(hold) != nil {
                Task { await self.settle() }
            }
            ticket.end(tasks)
        }
        await settle()
        let value = await work()
        holds.remove(hold)
        await settle()
        ticket.end(tasks)
        return value
    }

    /// Apply what is wanted now, after every transition queued before it.
    private func settle() async {
        let previous = tail
        let step = Task { @MainActor in
            await previous?.value
            if self.wantsStoresOpen {
                await self.stores.resumeStores()
            } else {
                await self.stores.suspendStores()
            }
        }
        tail = step
        await step.value
    }
}

/// A background task that is ended exactly once — by its owner, or by its
/// expiration handler, whichever comes first.
@MainActor
private final class Ticket {
    var id: BackgroundTaskID?

    func end(_ tasks: any BackgroundTasking) {
        guard let id else { return }
        self.id = nil
        tasks.end(id)
    }
}
