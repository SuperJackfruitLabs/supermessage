import Foundation
import os

/// Sends an answer given from a notification — with no room open, and
/// possibly with no app on screen.
///
/// A notification action can launch the app in the background with no scene,
/// so no `RootView`, no `Session`, and no sync. This needs none of them: it
/// restores the stored session **without** starting sync
/// (`restoreSessionQuietly`, a no-op when a session is already live) and
/// sends straight to the room, returning once the homeserver has the event.
///
/// **Bounded.** iOS gives a background action roughly thirty seconds before
/// it kills the process. The answer gets `budget` of that, and past it this
/// reports failure so the caller can still say so and finish in time. The
/// core call itself cannot be cancelled — it is a blocking Rust call — so a
/// late answer may yet land after "wasn't sent" was posted. That is the
/// honest side to err on: the reader is told to look, and finds it answered.
public enum NotificationAnswerer {
    /// Well inside the system's ~30 s, leaving room to post a failure and
    /// call the completion handler.
    public static let budget: Duration = .seconds(15)

    /// Send `answer` through `client`. Returns whether it landed.
    public static func send(
        _ answer: NotificationAnswer, via client: any NotificationAnswering,
        within budget: Duration = budget
    ) async -> Bool {
        await withDeadline(budget) { await deliver(answer, via: client) }
    }

    static func deliver(_ answer: NotificationAnswer, via client: any NotificationAnswering) async
        -> Bool
    {
        do {
            // Signed out, or nothing stored: there is nobody to answer as.
            guard try await client.restoreSessionQuietly() else { return false }
            switch answer {
            case let .permission(roomId, optionId):
                try await client.sendPermissionAnswer(roomId: roomId, optionId: optionId)
            case let .gate(roomId, gateEventId, gateId, optionId, comment, prompt):
                try await client.sendGateDecisionTo(
                    roomId: roomId, gateId: gateId, optionId: optionId, comment: comment,
                    inReplyTo: gateEventId, prompt: prompt)
            }
            return true
        } catch {
            return false
        }
    }

    /// `work`'s answer, or `false` once `limit` has passed — whichever comes
    /// first. `work` is not cancelled (it cannot be); it is stopped being
    /// waited for.
    ///
    /// Not a task group: a group waits for every child before it returns,
    /// so a hung child would hold the caller past the deadline — the one
    /// thing this exists to prevent.
    static func withDeadline(
        _ limit: Duration, _ work: @escaping @Sendable () async -> Bool
    ) async -> Bool {
        let settled = OSAllocatedUnfairLock(initialState: false)
        return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            let finish: @Sendable (Bool) -> Void = { value in
                let first = settled.withLock { done -> Bool in
                    if done { return false }
                    done = true
                    return true
                }
                if first { continuation.resume(returning: value) }
            }
            let timer = Task {
                // Cancelled means the work finished first: say nothing.
                do { try await Task.sleep(for: limit) } catch { return }
                finish(false)
            }
            Task {
                let value = await work()
                finish(value)
                timer.cancel()
            }
        }
    }
}
