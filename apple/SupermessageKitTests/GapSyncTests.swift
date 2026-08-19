import Foundation
import Testing

@testable import SupermessageKit

/// Ported from `src/lib/stores/gapSync.test.ts`. Each of the three hazards
/// below cost a real incident on the desktop app; the comments say which.
@MainActor
struct GapSyncTests {
    /// A resync whose completion the test controls, so the in-flight window
    /// can be held open and driven deliberately.
    ///
    /// `@MainActor` rather than a lock: `GapSync` is main-actor isolated and
    /// every caller here is too, so the isolation *is* the mutual exclusion.
    /// An `NSLock` would also be unusable — Swift 6 makes `lock()` unavailable
    /// from an async context, and for good reason: blocking a cooperative
    /// thread is the same mistake `CoreClient` exists to avoid.
    @MainActor
    final class Gate {
        private var continuation: CheckedContinuation<Snapshot<Int>, Never>?
        private(set) var callCount = 0

        func resync() async -> Snapshot<Int> {
            callCount += 1
            return await withCheckedContinuation { self.continuation = $0 }
        }

        func resolve(subject: String = "s", seq: UInt64, items: [Int]) {
            let c = continuation
            continuation = nil
            c?.resume(returning: Snapshot(subject: subject, seq: seq, items: items))
        }
    }

    @Test("applies sequential envelopes and publishes the running list")
    func steadyState() {
        var published: [[Int]] = []
        let sync = GapSync<Int>(
            resync: { Snapshot(subject: "s", seq: 0, items: []) },
            onUpdate: { published.append($0) })

        sync.handle(subject: "s", seq: 1, ops: [.append([1])])
        sync.handle(subject: "s", seq: 2, ops: [.append([2])])

        #expect(published == [[1], [1, 2]])
    }

    @Test("suspends applying envelopes while a resync is in flight, then resumes from the snapshot")
    func suspendsWhileResyncing() async {
        // Hazard 1. While the round trip is in flight the core keeps emitting;
        // applying those against the pre-reset tracker would rediscover the
        // same gap and ask again, forever.
        let gate = Gate()
        var published: [[Int]] = []
        let sync = GapSync<Int>(resync: { await gate.resync() }, onUpdate: { published.append($0) })

        sync.handle(subject: "s", seq: 1, ops: [.append([1])])
        sync.handle(subject: "s", seq: 5, ops: [.append([5])])  // gap -> resync
        await Task.yield()
        sync.handle(subject: "s", seq: 6, ops: [.append([6])])  // ignored, in flight

        #expect(published == [[1]], "an envelope was applied during the resync")

        gate.resolve(seq: 6, items: [1, 2, 3])
        await Task.yield()
        await Task.yield()

        #expect(published.last == [1, 2, 3])
        // The next live envelope is seq + 1 and must resume normally.
        sync.handle(subject: "s", seq: 7, ops: [.append([7])])
        #expect(published.last == [1, 2, 3, 7])
    }

    @Test("never issues two overlapping resyncs for the same channel")
    func noOverlappingResyncs() async {
        let gate = Gate()
        let sync = GapSync<Int>(resync: { await gate.resync() }, onUpdate: { _ in })

        sync.handle(subject: "s", seq: 5, ops: [.append([5])])
        await Task.yield()
        sync.handle(subject: "s", seq: 9, ops: [.append([9])])
        await Task.yield()

        #expect(gate.callCount == 1)
    }

    @Test("a reset publishes an empty list and discards a resync already in flight")
    func resetDiscardsStaleResync() async {
        // Hazard 3. A slow resync landing after the context changed would roll
        // the new room's state back to the old room's data.
        let gate = Gate()
        var published: [[Int]] = []
        let sync = GapSync<Int>(resync: { await gate.resync() }, onUpdate: { published.append($0) })

        sync.handle(subject: "s", seq: 5, ops: [.append([5])])  // gap -> resync
        await Task.yield()

        sync.resetForNewSubscription()
        #expect(published.last == [], "a reset must publish an empty list immediately")

        gate.resolve(seq: 9, items: [7, 8, 9])
        await Task.yield()
        await Task.yield()

        #expect(published.last == [], "a stale resync landed and clobbered the new context")
    }

    @Test("a fresh gap in the new context still triggers its own resync")
    func newContextCanResyncAgain() async {
        let gate = Gate()
        var published: [[Int]] = []
        let sync = GapSync<Int>(resync: { await gate.resync() }, onUpdate: { published.append($0) })

        sync.handle(subject: "s", seq: 5, ops: [.append([5])])
        await Task.yield()
        sync.resetForNewSubscription()
        gate.resolve(seq: 9, items: [7])
        await Task.yield()
        await Task.yield()

        // The stale one has cleared; the new context must be able to recover.
        sync.handle(subject: "s", seq: 4, ops: [.append([4])])
        await Task.yield()
        gate.resolve(seq: 4, items: [1, 2])
        await Task.yield()
        await Task.yield()

        #expect(published.last == [1, 2])
    }

    @Test("drops an envelope for another subject instead of treating it as a gap")
    func dropsForeignSubject() async {
        // Hazard 2, and the one that cost the worst incident: treating this as
        // a gap resyncs off the *previous* room's still-installed handle and
        // installs that room's messages under the new room's header, where
        // they stay until the next room switch.
        let gate = Gate()
        var published: [[Int]] = []
        let sync = GapSync<Int>(
            resync: { await gate.resync() },
            accepts: { $0 == "!b:x.org" },
            onUpdate: { published.append($0) })

        sync.handle(subject: "!a:x.org", seq: 12, ops: [.append([99])])
        await Task.yield()

        #expect(gate.callCount == 0, "somebody else's envelope triggered a resync")
        #expect(published.isEmpty, "somebody else's data was published")
    }

    @Test("discards a resync snapshot belonging to another subject")
    func discardsForeignSnapshot() async {
        // The core serves a resync out of whichever subscription is currently
        // installed, which during a room switch is still the previous room's.
        // Its generation can match ours, so the subject is the only thing that
        // can tell us this snapshot is not ours.
        let gate = Gate()
        var published: [[Int]] = []
        let sync = GapSync<Int>(
            resync: { await gate.resync() },
            accepts: { $0 == "!b:x.org" },
            onUpdate: { published.append($0) })

        sync.handle(subject: "!b:x.org", seq: 5, ops: [.append([5])])  // gap -> resync
        await Task.yield()
        gate.resolve(subject: "!a:x.org", seq: 12, items: [98, 99])
        await Task.yield()
        await Task.yield()

        #expect(published.isEmpty, "another room's snapshot was installed")
    }

    @Test("seeding fetches a snapshot without waiting for a gap")
    func seedFetchesWithoutAGap() async {
        // On iOS this is not an edge case: it is every return from background.
        // The channel only speaks when something changes, so a store that came
        // back to a quiet account would sit empty until the next message.
        let gate = Gate()
        var published: [[Int]] = []
        let sync = GapSync<Int>(resync: { await gate.resync() }, onUpdate: { published.append($0) })

        Task { await sync.seed() }
        await Task.yield()
        gate.resolve(seq: 3, items: [1, 2, 3])
        await Task.yield()
        await Task.yield()

        #expect(published.last == [1, 2, 3])
    }

    @Test("a stopped sync applies nothing further")
    func stopIsFinal() {
        var published: [[Int]] = []
        let sync = GapSync<Int>(
            resync: { Snapshot(subject: "s", seq: 0, items: []) },
            onUpdate: { published.append($0) })

        sync.stop()
        sync.handle(subject: "s", seq: 1, ops: [.append([1])])
        #expect(published.isEmpty)
    }
}
