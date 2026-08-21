import Foundation
import Testing

@testable import SupermessageKit

/// Ported from `src/lib/stores/gapSync.test.ts`. Each of the three hazards
/// below cost a real incident on the desktop app; the comments say which.
/// Let a resumed continuation run through to its `onUpdate`.
///
/// A resumption is not synchronous with `resume`, so a handful of hops are
/// needed before the effect is observable. Named rather than repeated as bare
/// `Task.yield()` pairs, so a reader can see it is a deliberate settle and not
/// a magic number someone tuned until the suite went green.
@MainActor
private func settle() async {
    for _ in 0..<10 { await Task.yield() }
}

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

        /// Wait until `resync()` has actually suspended on its continuation.
        ///
        /// Replaces a bare `await Task.yield()`, which was a guess about how
        /// many hops the runtime needed and lost that guess intermittently —
        /// `resolve` would fire before the continuation existed and simply do
        /// nothing. Polling the call count is the same wait made honest: the
        /// counter is bumped immediately before the continuation is stored, on
        /// the same actor, so seeing it means the continuation is there.
        func waitUntilCalled(_ count: Int = 1) async {
            for _ in 0..<1_000 {
                if callCount >= count { return }
                await Task.yield()
            }
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
        await gate.waitUntilCalled()
        sync.handle(subject: "s", seq: 6, ops: [.append([6])])  // ignored, in flight

        #expect(published == [[1]], "an envelope was applied during the resync")

        gate.resolve(seq: 6, items: [1, 2, 3])
        await settle()

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
        await gate.waitUntilCalled()
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
        await gate.waitUntilCalled()

        sync.resetForNewSubscription()
        #expect(published.last == [], "a reset must publish an empty list immediately")

        gate.resolve(seq: 9, items: [7, 8, 9])
        await settle()

        #expect(published.last == [], "a stale resync landed and clobbered the new context")
    }

    @Test("a fresh gap in the new context still triggers its own resync")
    func newContextCanResyncAgain() async {
        let gate = Gate()
        var published: [[Int]] = []
        let sync = GapSync<Int>(resync: { await gate.resync() }, onUpdate: { published.append($0) })

        sync.handle(subject: "s", seq: 5, ops: [.append([5])])
        await gate.waitUntilCalled()
        sync.resetForNewSubscription()
        gate.resolve(seq: 9, items: [7])
        await settle()

        // The stale one has cleared; the new context must be able to recover.
        sync.handle(subject: "s", seq: 4, ops: [.append([4])])
        await gate.waitUntilCalled(2)
        gate.resolve(seq: 4, items: [1, 2])
        await settle()

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
        await gate.waitUntilCalled()
        gate.resolve(subject: "!a:x.org", seq: 12, items: [98, 99])
        await settle()

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
        await gate.waitUntilCalled()
        gate.resolve(seq: 3, items: [1, 2, 3])
        await settle()

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
    /// The second half of issue #28.
    ///
    /// `stop()` sets a latch that nothing cleared: `stopped = false` appeared
    /// exactly once in the file, at its declaration. Both gates read it, so a
    /// sync stopped on sign-out stayed stopped for the life of the process,
    /// and signing back in produced a client that received nothing.
    @Test("a stopped sync folds envelopes again once it is resumed")
    func resumeUnlatchesAStoppedSync() async {
        var published: [[Int]] = []
        let sync = GapSync<Int>(
            resync: { Snapshot(subject: "s", seq: 0, items: []) },
            onUpdate: { published.append($0) })

        sync.handle(subject: "s", seq: 1, ops: [.append([1])])
        #expect(published.last == [1])

        sync.stop()
        sync.handle(subject: "s", seq: 2, ops: [.append([2])])
        #expect(published.last == [1], "a stopped sync kept folding")

        sync.resume()
        sync.handle(subject: "s", seq: 2, ops: [.append([2])])
        #expect(published.last == [1, 2], "a resumed sync never started folding again")
    }

    /// Resuming must not let the *previous* session's resync land.
    ///
    /// A resync in flight when sign-out happened would otherwise resolve after
    /// sign-in and repopulate the new session with the old one's rows — the
    /// same hazard `resetForNewSubscription` bumps the generation for, on a
    /// path that had no bump at all.
    @Test("a resync in flight across a stop is discarded by resume")
    func resumeDiscardsAnInFlightResync() async {
        var published: [[Int]] = []
        let gate = Gate()
        let sync = GapSync<Int>(resync: { await gate.resync() }, onUpdate: { published.append($0) })

        sync.handle(subject: "s", seq: 1, ops: [.append([1])])
        sync.handle(subject: "s", seq: 5, ops: [.append([5])])  // gap -> resync
        await gate.waitUntilCalled()

        sync.stop()
        sync.resume()

        // The old session's resync lands now, carrying its rows.
        gate.resolve(seq: 9, items: [99])
        await settle()

        #expect(
            published.last != [99],
            "a resync from before the stop repopulated the session that replaced it")
    }
}
