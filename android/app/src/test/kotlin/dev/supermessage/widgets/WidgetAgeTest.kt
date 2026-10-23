package dev.supermessage.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAgeTest {
    private val now = 1_800_000_000_000L
    private fun ago(ms: Long) = WidgetAge.since(now - ms, now)
    private val min = 60_000L

    @Test
    fun underAMinuteIsOneMNeverNowOrZero() {
        assertEquals("1m", ago(0))
        assertEquals("1m", ago(59_000))
        assertEquals("1m", ago(-5_000))
    }

    /** `DateComponentsFormatter` `.abbreviated`, one unit, in English: "4m", "2h", "3d" (no space, no clock times). */
    @Test
    fun minutesHoursAndDaysAreWholeUnitsRoundedDown() {
        assertEquals("4m", ago(4 * min + 59_000))
        assertEquals("59m", ago(59 * min))
        assertEquals("1h", ago(60 * min))
        assertEquals("2h", ago(179 * min))
        assertEquals("23h", ago(23 * 60 * min + 59 * min))
        assertEquals("1d", ago(24 * 60 * min))
        assertEquals("3d", ago(3 * 24 * 60 * min + 5 * 60 * min))
    }
}
