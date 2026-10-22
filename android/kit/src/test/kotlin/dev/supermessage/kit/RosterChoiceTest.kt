package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which arrangements the Account panel offers, and what a stored one that is
 * no longer offered reads as (`RosterChoice.offered`, iOS
 * `RoomListView.swift:46-52`). Pure Kotlin: no native library needed, unlike
 * [RosterArrangementTest].
 */
class RosterChoiceTest {
    @Test
    fun theOfferedArrangementsAreRecentThenWaiting() {
        assertEquals(listOf(RosterChoice.RECENT, RosterChoice.WAITING), RosterChoice.offered)
    }

    @Test
    fun aStoredMachineArrangementReadsAsWaiting() {
        assertEquals(RosterChoice.WAITING, RosterChoice.offeredOrDefault(RosterChoice.MACHINE))
    }

    @Test
    fun anOfferedArrangementIsKept() {
        assertEquals(RosterChoice.RECENT, RosterChoice.offeredOrDefault(RosterChoice.RECENT))
        assertEquals(RosterChoice.WAITING, RosterChoice.offeredOrDefault(RosterChoice.WAITING))
    }
}
