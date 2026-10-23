package dev.supermessage.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAgeTest {
    private val now = 1_800_000_000_000L
    private fun ago(ms: Long) = WidgetAge.since(now - ms, now)
    private val min = 60_000L

    @Test
    fun underAMinuteIsAMinuteNeverNowOrZero() {
        assertEquals("1 min", ago(0))
        assertEquals("1 min", ago(59_000))
        assertEquals("1 min", ago(-5_000))
    }

    @Test
    fun minutesHoursAndDaysAreWholeUnitsRoundedDown() {
        assertEquals("4 min", ago(4 * min + 59_000))
        assertEquals("59 min", ago(59 * min))
        assertEquals("1 hr", ago(60 * min))
        assertEquals("2 hr", ago(179 * min))
        assertEquals("23 hr", ago(23 * 60 * min + 59 * min))
        assertEquals("1 d", ago(24 * 60 * min))
        assertEquals("3 d", ago(3 * 24 * 60 * min + 5 * 60 * min))
    }
}
