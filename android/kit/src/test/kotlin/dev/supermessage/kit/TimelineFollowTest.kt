package dev.supermessage.kit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineFollowTest {

    /** a reader at the bottom follows new messages */
    @Test
    fun followsAtBottom() {
        assertTrue(TimelineFollow.shouldRepin(distanceFromBottom = 0f, grew = true))
        assertTrue(TimelineFollow.shouldRepin(distanceFromBottom = 40f, grew = true))
    }

    /** a reader who scrolled up is left where they are */
    @Test
    fun doesNotDragTheReaderDown() {
        // The most annoying thing a timeline can do.
        assertFalse(TimelineFollow.shouldRepin(distanceFromBottom = 900f, grew = true))
    }

    /** nothing happens when the list did not grow */
    @Test
    fun noGrowthNoScroll() {
        assertFalse(TimelineFollow.shouldRepin(distanceFromBottom = 0f, grew = false))
    }

    /** scrolling near the top asks for older messages */
    @Test
    fun asksForHistoryNearTheTop() {
        // Reported by a reader: nothing older than yesterday would ever load
        // in a room with months of history.
        //
        // The view used to gate this on the topmost visible row being
        // *exactly* the first row in the list. That is a knife edge — a
        // scroll almost never lands on it — so the request was essentially
        // never made and the backlog stayed unreachable. The desktop had it
        // right all along (`offset < TOP_THRESHOLD` in Timeline.svelte);
        // the port dropped the threshold.
        assertTrue(
            TimelineFollow.wantsOlderHistory(
                distanceFromTop = 0f, canPaginate = true, isPaginating = false, hasSettled = true,
            )
        )
        assertTrue(
            TimelineFollow.wantsOlderHistory(
                distanceFromTop = 150f, canPaginate = true, isPaginating = false, hasSettled = true,
            )
        )
    }

    /** a reader in the middle of the backlog is not paginating */
    @Test
    fun doesNotPaginateFromTheMiddle() {
        assertFalse(
            TimelineFollow.wantsOlderHistory(
                distanceFromTop = 5000f, canPaginate = true, isPaginating = false, hasSettled = true,
            )
        )
    }

    /** no request while one is in flight, or once history is exhausted */
    @Test
    fun doesNotPileUpRequests() {
        // Two overlapping paginations against one timeline is how a list
        // ends up with duplicated rows.
        assertFalse(
            TimelineFollow.wantsOlderHistory(
                distanceFromTop = 0f, canPaginate = true, isPaginating = true, hasSettled = true,
            )
        )
        // The core said there is no more history. Asking again is a round
        // trip that can only return nothing.
        assertFalse(
            TimelineFollow.wantsOlderHistory(
                distanceFromTop = 0f, canPaginate = false, isPaginating = false, hasSettled = true,
            )
        )
    }

    /** a room that has not settled yet does not fetch its own history */
    @Test
    fun doesNotWalkToTheBeginningOnOpen() {
        // Observed on an iPad: opening a room scrolled it to "Beginning of
        // the room". While the opening batch is still arriving the offset
        // sits near zero, so each prepended page immediately triggers the
        // next and the room lands on its oldest message instead of its
        // newest.
        assertFalse(
            TimelineFollow.wantsOlderHistory(
                distanceFromTop = 0f, canPaginate = true, isPaginating = false, hasSettled = false,
            )
        )
    }

    /** the trigger fires a screen ahead of the reader */
    @Test
    fun fetchesBeforeTheReaderArrives() {
        // The point of a threshold rather than "at the very top" is that
        // the rows land before they are looked at. Too small and the reader
        // hits a wall and waits; the desktop settled on 200 points.
        assertTrue(TimelineFollow.topThreshold >= 200f)
    }

    /** the threshold is a nudge, not zero */
    @Test
    fun thresholdTolerates() {
        // A reader who has moved the list two points has not asked to stop
        // following, and snapping back from that reads as a fight.
        assertTrue(TimelineFollow.shouldRepin(distanceFromBottom = 2f, grew = true))
        assertFalse(TimelineFollow.shouldRepin(distanceFromBottom = 65f, grew = true))
    }

    /** a room opened mid-history settles at the bottom on its first batch */
    @Test
    fun settlesOnFirstContent() {
        // The defect this fixes was found by using the app. The whole
        // backlog arrives as one batch, that batch is the only growth
        // there is, and shouldRepin discards it as a first observation. The
        // view stayed stranded wherever layout put it.
        assertTrue(TimelineFollow.shouldSettleAtBottom(previous = 0, next = 18, settled = false))
    }

    /** it settles once and then leaves the reader alone */
    @Test
    fun settlesOnlyOnce() {
        assertFalse(TimelineFollow.shouldSettleAtBottom(previous = 0, next = 18, settled = true))
        assertFalse(TimelineFollow.shouldSettleAtBottom(previous = 18, next = 36, settled = false))
    }
}
