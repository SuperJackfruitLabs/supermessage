import Foundation
import Testing

@testable import SupermessageKit

// What runs while the app is in the background, and what keeps it from being
// suspended holding the App Group's locks (`0xdead10cc`, TestFlight build 40).

struct BackgroundPolicyTests {
    @Test("in the background only the Live Activity's tokens go out")
    func backgroundAllowsOnlyTokens() {
        for work in BackgroundPolicy.Work.allCases {
            #expect(
                BackgroundPolicy.allows(work, in: .background) == (work == .liveActivityTokens),
                "\(work) in the background")
        }
    }

    @Test("in the foreground everything runs")
    func foregroundAllowsEverything() {
        for work in BackgroundPolicy.Work.allCases {
            #expect(BackgroundPolicy.allows(work, in: .foreground), "\(work) in the foreground")
        }
    }

    @Test("a background launch restores quietly; a foreground one starts the streams")
    func restore() {
        #expect(BackgroundPolicy.restore(in: .background) == .quietly)
        #expect(BackgroundPolicy.restore(in: .foreground) == .withStreams)
    }
}

/// Everything the guard did, in order, across the stores and the tasks.
@MainActor
private final class Log {
    enum Entry: Equatable {
        case begin(String, Int)
        case end(Int)
        case suspend
        case resume
        case work(String)
    }

    var entries: [Entry] = []

    var storeCalls: [Entry] { entries.filter { $0 == .suspend || $0 == .resume } }

    /// Every task begun was ended, and none twice.
    var tasksBalanced: Bool {
        var open: Set<Int> = []
        for entry in entries {
            switch entry {
            case let .begin(_, id): open.insert(id)
            case let .end(id):
                guard open.remove(id) != nil else { return false }
            default: break
            }
        }
        return open.isEmpty
    }
}

private struct StubStores: StoreSuspending {
    let log: Log
    /// How long closing takes — the core waits for a write under way.
    var closing: Duration = .zero

    func suspendStores() async {
        if closing > .zero { try? await Task.sleep(for: closing) }
        await MainActor.run { log.entries.append(.suspend) }
    }

    func resumeStores() async {
        await MainActor.run { log.entries.append(.resume) }
    }
}

@MainActor
private final class StubTasks: BackgroundTasking {
    let log: Log
    private var next = 1
    private var expirations: [Int: @MainActor @Sendable () -> Void] = [:]

    init(log: Log) { self.log = log }

    func begin(_ name: String, expiration: @escaping @MainActor @Sendable () -> Void)
        -> BackgroundTaskID
    {
        let id = next
        next += 1
        expirations[id] = expiration
        log.entries.append(.begin(name, id))
        return BackgroundTaskID(id)
    }

    func end(_ id: BackgroundTaskID) {
        expirations[id.raw] = nil
        log.entries.append(.end(id.raw))
    }

    /// What iOS does when the time runs out.
    func expire(_ id: Int) {
        expirations[id]?()
    }
}

@MainActor
private func guardUnderTest(_ presence: AppPresence, closing: Duration = .zero) -> (
    StoreGuard, Log, StubTasks
) {
    let log = Log()
    let tasks = StubTasks(log: log)
    let stores = StubStores(log: log, closing: closing)
    return (StoreGuard(stores: stores, tasks: tasks, presence: presence), log, tasks)
}

/// Let queued main-actor tasks run until `done` holds, or give up.
@MainActor
private func settle(until done: () -> Bool) async {
    for _ in 0..<200 where !done() {
        try? await Task.sleep(for: .milliseconds(5))
    }
}

@MainActor
struct StoreGuardTests {
    @Test("going into the background closes the stores inside a background task")
    func backgroundCloses() async {
        let (storeGuard, log, _) = guardUnderTest(.foreground)
        let closing = storeGuard.entered(.background)
        // Begun before `entered` returned — before anything was awaited —
        // so iOS cannot suspend the app between the notification and it.
        #expect(log.entries == [.begin("Closing the stores", 1)])
        await closing.value
        #expect(log.entries == [.begin("Closing the stores", 1), .suspend, .end(1)])
    }

    @Test("coming back to the foreground reopens the stores")
    func foregroundReopens() async {
        let (storeGuard, log, _) = guardUnderTest(.background)
        await storeGuard.entered(.foreground).value
        #expect(log.entries == [.resume])
        #expect(storeGuard.wantsStoresOpen)
    }

    @Test("work in the background gets the stores, awake, and they close after it")
    func holdInBackground() async {
        let (storeGuard, log, _) = guardUnderTest(.background)
        let value = await storeGuard.hold("Answer") {
            log.entries.append(.work("answer"))
            return 42
        }
        #expect(value == 42)
        #expect(
            log.entries == [.begin("Answer", 1), .resume, .work("answer"), .suspend, .end(1)])
    }

    @Test("work in the foreground leaves the stores open after it")
    func holdInForeground() async {
        let (storeGuard, log, _) = guardUnderTest(.foreground)
        await storeGuard.hold("Refresh") { log.entries.append(.work("refresh")) }
        #expect(!log.storeCalls.contains(.suspend))
        #expect(log.tasksBalanced)
    }

    @Test("leaving the screen during work closes the stores only once the work is done")
    func backgroundDuringHold() async {
        let (storeGuard, log, _) = guardUnderTest(.foreground)
        await storeGuard.hold("Send") {
            await storeGuard.entered(.background).value
            log.entries.append(.work("still sending"))
        }
        let work = log.entries.firstIndex(of: .work("still sending"))!
        #expect(!log.entries[..<work].contains(.suspend), "closed under the work")
        #expect(log.entries.last(where: { $0 == .suspend || $0 == .resume }) == .suspend)
        #expect(log.tasksBalanced)
    }

    @Test("two holds: the stores close when the last one ends, not the first")
    func twoHolds() async {
        let (storeGuard, log, _) = guardUnderTest(.background)
        await storeGuard.hold("Outer") {
            await storeGuard.hold("Inner") { log.entries.append(.work("inner")) }
            log.entries.append(.work("outer"))
        }
        let outer = log.entries.firstIndex(of: .work("outer"))!
        #expect(!log.entries[..<outer].contains(.suspend))
        #expect(log.storeCalls.last == .suspend)
        #expect(log.tasksBalanced)
    }

    @Test("a hold that runs out of time closes the stores under the work, and ends its task once")
    func holdExpires() async {
        let (storeGuard, log, tasks) = guardUnderTest(.background)
        await storeGuard.hold("Slow answer") {
            tasks.expire(1)
            await settle { log.entries.contains(.suspend) }
            #expect(log.entries.contains(.suspend), "closed before the work finished")
            #expect(log.entries.contains(.end(1)), "and the task ended in its handler")
            log.entries.append(.work("late"))
        }
        #expect(log.tasksBalanced, "ended exactly once, though the work finished after")
        #expect(log.storeCalls.last == .suspend)
    }

    @Test("the closing task running out of time is ended, once")
    func closingExpires() async {
        let (storeGuard, log, tasks) = guardUnderTest(.foreground)
        let closing = storeGuard.entered(.background)
        tasks.expire(1)
        await closing.value
        #expect(log.tasksBalanced)
    }

    @Test("background then straight back: the last word is to reopen")
    func quickReturn() async {
        let (storeGuard, log, _) = guardUnderTest(.foreground)
        let away = storeGuard.entered(.background)
        let back = storeGuard.entered(.foreground)
        await away.value
        await back.value
        #expect(log.storeCalls.last == .resume, "a reader must not come back to closed stores")
        #expect(log.tasksBalanced)
    }

    @Test("back while the stores are still closing: they reopen after the close, not before")
    func returnDuringSlowClose() async {
        let (storeGuard, log, _) = guardUnderTest(.foreground, closing: .milliseconds(100))
        let away = storeGuard.entered(.background)
        // The close is under way — waiting for a write to commit.
        try? await Task.sleep(for: .milliseconds(20))
        let back = storeGuard.entered(.foreground)
        await away.value
        await back.value
        #expect(log.storeCalls == [.suspend, .resume], "reopened only once the close finished")
        #expect(log.tasksBalanced)
    }

    @Test("foreground then straight away: the last word is to close")
    func quickLeave() async {
        let (storeGuard, log, _) = guardUnderTest(.background)
        let back = storeGuard.entered(.foreground)
        let away = storeGuard.entered(.background)
        await back.value
        await away.value
        #expect(log.storeCalls.last == .suspend, "an app about to be suspended must not keep them")
        #expect(log.tasksBalanced)
    }
}
