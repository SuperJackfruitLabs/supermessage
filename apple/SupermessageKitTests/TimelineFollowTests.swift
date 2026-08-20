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

    @Test("scrolling near the top asks for older messages")
    func asksForHistoryNearTheTop() {
        // Reported by a reader: nothing older than yesterday would ever load
        // in a room with months of history.
        //
        // The view used to gate this on the topmost visible row being
        // *exactly* the first row in the list. That is a knife edge — a
        // scroll almost never lands on it — so the request was essentially
        // never made and the backlog stayed unreachable. The desktop had it
        // right all along (`offset < TOP_THRESHOLD` in Timeline.svelte);
        // the port dropped the threshold.
        #expect(
            TimelineFollow.wantsOlderHistory(
                distanceFromTop: 0, canPaginate: true, isPaginating: false, hasSettled: true))
        #expect(
            TimelineFollow.wantsOlderHistory(
                distanceFromTop: 150, canPaginate: true, isPaginating: false, hasSettled: true))
    }

    @Test("a reader in the middle of the backlog is not paginating")
    func doesNotPaginateFromTheMiddle() {
        #expect(
            !TimelineFollow.wantsOlderHistory(
                distanceFromTop: 5000, canPaginate: true, isPaginating: false, hasSettled: true))
    }

    @Test("no request while one is in flight, or once history is exhausted")
    func doesNotPileUpRequests() {
        // Two overlapping paginations against one timeline is how a list
        // ends up with duplicated rows.
        #expect(
            !TimelineFollow.wantsOlderHistory(
                distanceFromTop: 0, canPaginate: true, isPaginating: true, hasSettled: true))
        // The core said there is no more history. Asking again is a round
        // trip that can only return nothing.
        #expect(
            !TimelineFollow.wantsOlderHistory(
                distanceFromTop: 0, canPaginate: false, isPaginating: false, hasSettled: true))
    }

    @Test("a room that has not settled yet does not fetch its own history")
    func doesNotWalkToTheBeginningOnOpen() {
        // Observed on an iPad: opening a room scrolled it to "Beginning of the
        // room". While the opening batch is still arriving the offset sits
        // near zero, so each prepended page immediately triggers the next and
        // the room lands on its oldest message instead of its newest.
        #expect(
            !TimelineFollow.wantsOlderHistory(
                distanceFromTop: 0, canPaginate: true, isPaginating: false, hasSettled: false))
    }

    @Test("the trigger fires a screen ahead of the reader")
    func fetchesBeforeTheReaderArrives() {
        // The point of a threshold rather than "at the very top" is that the
        // rows land before they are looked at. Too small and the reader hits
        // a wall and waits; the desktop settled on 200 points.
        #expect(TimelineFollow.topThreshold >= 200)
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
