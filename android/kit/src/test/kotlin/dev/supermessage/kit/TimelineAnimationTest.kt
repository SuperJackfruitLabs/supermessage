package dev.supermessage.kit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAnimationTest {
    private fun animates(arrived: Int, had: Int = 5, hasApplied: Boolean = true, wasAway: Boolean = false) =
        TimelineAnimation.animates(arrived, had, hasApplied, wasAway)

    /** One to three arrivals into a room already on screen: the case that animates. */
    @Test fun aHandfulArrivingIsAnArrival() {
        assertTrue(animates(arrived = 1))
        assertTrue(animates(arrived = 3))
    }

    /** A room's first fill is the room appearing, not messages arriving. */
    @Test fun theFirstFillIsNotAnArrival() =
        assertFalse(animates(arrived = 3, hasApplied = false))

    /** A reader scrolled away did not watch it happen. */
    @Test fun nothingAnimatesWhileTheReaderIsAway() =
        assertFalse(animates(arrived = 3, wasAway = true))

    /** An empty room gaining rows is a fill. */
    @Test fun anEmptyRoomGainingRowsIsAFill() =
        assertFalse(animates(arrived = 3, had = 0))

    /**
     * More than a handful is a page of history or a resync — "a conversation
     * does not gain eight messages in one moment, so if it looks like it did,
     * this is not an arrival."
     */
    @Test fun aPageOfHistoryIsNotAnArrival() {
        assertFalse(animates(arrived = 4))
        assertFalse(animates(arrived = 20))
    }

    /** Nothing arrived. */
    @Test fun nothingArrivingDoesNotAnimate() = assertFalse(animates(arrived = 0))
}
