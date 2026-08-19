import CoreGraphics
import Testing

@testable import SupermessageKit

struct TimelineFollowTests {
    @Test("a reader at the bottom follows new messages")
    func followsAtBottom() {
        #expect(TimelineFollow.shouldRepin(distanceFromBottom: 0, grew: true))
        #expect(TimelineFollow.shouldRepin(distanceFromBottom: 40, grew: true))
    }

    @Test("a reader who scrolled up is left where they are")
    func doesNotDragTheReaderDown() {
        // The most annoying thing a timeline can do.
        #expect(!TimelineFollow.shouldRepin(distanceFromBottom: 900, grew: true))
    }

    @Test("nothing happens when the list did not grow")
    func noGrowthNoScroll() {
        #expect(!TimelineFollow.shouldRepin(distanceFromBottom: 0, grew: false))
    }

    @Test("the threshold is a nudge, not zero")
    func thresholdTolerates() {
        // A reader who has moved the list two points has not asked to stop
        // following, and snapping back from that reads as a fight.
        #expect(TimelineFollow.shouldRepin(distanceFromBottom: 2, grew: true))
        #expect(!TimelineFollow.shouldRepin(distanceFromBottom: 65, grew: true))
    }

    @Test("a room opened mid-history settles at the bottom on its first batch")
    func settlesOnFirstContent() {
        // The defect this fixes was found by using the app. The whole backlog
        // arrives as one batch, that batch is the only growth there is, and
        // shouldRepin discards it as a first observation. The view stayed
        // stranded wherever layout put it.
        #expect(TimelineFollow.shouldSettleAtBottom(previous: 0, next: 18, settled: false))
    }

    @Test("it settles once and then leaves the reader alone")
    func settlesOnlyOnce() {
        #expect(!TimelineFollow.shouldSettleAtBottom(previous: 0, next: 18, settled: true))
        #expect(!TimelineFollow.shouldSettleAtBottom(previous: 18, next: 36, settled: false))
    }
}
