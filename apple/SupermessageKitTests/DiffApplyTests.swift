import Testing

@testable import SupermessageKit

/// Ported from `src/lib/stores/diff.test.ts`, case for case.
struct ApplyOpsTests {
    @Test("appends") func appends() {
        #expect(applyOps([1], [.append([2, 3])]) == [1, 2, 3])
    }
    @Test("clears") func clears() {
        #expect(applyOps([1, 2], [.clear]) == [])
    }
    @Test("pushes front") func pushesFront() {
        #expect(applyOps([2], [.pushFront(1)]) == [1, 2])
    }
    @Test("pushes back") func pushesBack() {
        #expect(applyOps([1], [.pushBack(2)]) == [1, 2])
    }
    @Test("pops front") func popsFront() {
        #expect(applyOps([1, 2], [.popFront]) == [2])
    }
    @Test("pops back") func popsBack() {
        #expect(applyOps([1, 2], [.popBack]) == [1])
    }
    @Test("inserts") func inserts() {
        #expect(applyOps([1, 3], [.insert(index: 1, value: 2)]) == [1, 2, 3])
    }
    @Test("sets") func sets() {
        #expect(applyOps([1, 9], [.set(index: 1, value: 2)]) == [1, 2])
    }
    @Test("removes") func removes() {
        #expect(applyOps([1, 2, 3], [.remove(index: 1)]) == [1, 3])
    }
    @Test("truncates") func truncates() {
        #expect(applyOps([1, 2, 3], [.truncate(length: 2)]) == [1, 2])
    }
    @Test("resets") func resets() {
        #expect(applyOps([1, 2], [.reset([9])]) == [9])
    }

    @Test("applies a batch in order")
    func batchInOrder() {
        // Order is the whole contract: the ops address positions in the list
        // as it stands *after* the previous op, not as it arrived.
        let result = applyOps([1], [.pushBack(2), .insert(index: 0, value: 0), .popBack])
        #expect(result == [0, 1])
    }

    @Test("does not mutate its input")
    func doesNotMutateInput() {
        let original = [1, 2, 3]
        _ = applyOps(original, [.clear, .append([9])])
        #expect(original == [1, 2, 3])
    }
}

/// The arms that matter most, and matter more here than in the original: in
/// JavaScript an out-of-bounds splice is quietly harmless, but `items[i]` traps
/// in Swift. Every guard is load-bearing rather than tidy.
struct ApplyOpsOutOfRangeTests {
    @Test("ignores set and remove with an out-of-bounds index rather than trapping")
    func outOfBoundsSetAndRemove() {
        #expect(applyOps([1, 2, 3], [.set(index: 99, value: 7)]) == [1, 2, 3])
        #expect(applyOps([1, 2, 3], [.remove(index: 99)]) == [1, 2, 3])
        #expect(applyOps([1, 2, 3], [.set(index: -1, value: 7)]) == [1, 2, 3])
        #expect(applyOps([1, 2, 3], [.remove(index: -1)]) == [1, 2, 3])
    }

    @Test("ignores an out-of-range insert, but permits index == count as an append")
    func insertBoundary() {
        // The boundary is the point: one past the end is an append and must
        // work; two past is a no-op and must not trap.
        #expect(applyOps([1, 2], [.insert(index: 2, value: 3)]) == [1, 2, 3])
        #expect(applyOps([1, 2], [.insert(index: 3, value: 3)]) == [1, 2])
        #expect(applyOps([1, 2], [.insert(index: -1, value: 3)]) == [1, 2])
    }

    @Test("ignores a pop on an empty list rather than trapping")
    func popOnEmpty() {
        #expect(applyOps([Int](), [.popFront]) == [])
        #expect(applyOps([Int](), [.popBack]) == [])
    }

    @Test("a truncate longer than the list leaves it alone")
    func truncateBeyondEnd() {
        #expect(applyOps([1, 2], [.truncate(length: 9)]) == [1, 2])
        #expect(applyOps([1, 2], [.truncate(length: 0)]) == [])
    }
}

struct DiffTrackerTests {
    @Test("accepts sequential envelopes")
    func acceptsSequential() {
        var tracker = DiffTracker<Int>()
        #expect(tracker.apply([.append([1])], seq: 1) == .ok)
        #expect(tracker.apply([.append([2])], seq: 2) == .ok)
        #expect(tracker.items == [1, 2])
    }

    @Test("reports a gap and leaves state untouched when an envelope is missed")
    func reportsGap() {
        // Untouched is the assertion. The ops address positions in a list the
        // sender believes you have, and after a gap you do not have it —
        // folding them in anyway is exactly the corruption this prevents.
        var tracker = DiffTracker<Int>()
        #expect(tracker.apply([.append([1])], seq: 1) == .ok)
        #expect(tracker.apply([.append([3])], seq: 3) == .gap)
        #expect(tracker.items == [1])
    }

    @Test("recovers after a resync")
    func recoversAfterResync() {
        var tracker = DiffTracker<Int>()
        _ = tracker.apply([.append([1])], seq: 1)
        _ = tracker.apply([.append([3])], seq: 3)

        tracker.reset(items: [1, 2, 3], seq: 3)
        #expect(tracker.items == [1, 2, 3])
        // The next live envelope is seq + 1, and must be accepted.
        #expect(tracker.apply([.append([4])], seq: 4) == .ok)
        #expect(tracker.items == [1, 2, 3, 4])
    }

    @Test("ignores a duplicate envelope rather than applying it twice")
    func ignoresDuplicate() {
        // The core can re-send after a reconnect. Applying one twice would
        // double an insert, which reads as a duplicated message.
        var tracker = DiffTracker<Int>()
        _ = tracker.apply([.append([1])], seq: 1)
        #expect(tracker.apply([.append([1])], seq: 1) == .ok)
        #expect(tracker.items == [1])
    }

    @Test("sequences start at one")
    func sequencesStartAtOne() {
        // `dto::SeqCounter` starts at 1, so a tracker that expected 0 would
        // read the very first envelope as a gap and resync on every startup.
        var tracker = DiffTracker<Int>()
        #expect(tracker.apply([.append([1])], seq: 1) == .ok)
        #expect(tracker.items == [1])
    }
}
