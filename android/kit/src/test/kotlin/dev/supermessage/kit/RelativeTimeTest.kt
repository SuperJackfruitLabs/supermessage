package dev.supermessage.kit

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeTimeTest {

    /** Fixed rather than the device's, so these rules are tested at one
     * instant in one zone rather than wherever the runner happens to be. */
    private val zone = ZoneId.of("UTC")

    /**
     * Fixed for the same reason as [zone]: `RelativeTime.label` formats with
     * `Locale.getDefault()` when none is given, and `weekdayThenDate`'s own
     * assertion (`recent.length <= 4`) is only true of English's "Wed" — a
     * locale whose abbreviated weekday runs longer would fail that assertion
     * even though the *rule* being tested (a weekday within the week, a date
     * beyond it) still held. Pinning the locale here is what makes this
     * suite pass or fail on the rule rather than on whatever locale happens
     * to be default wherever it runs.
     */
    private val locale = Locale.US

    /** A Wednesday, mid-afternoon UTC. */
    private val now = Instant.ofEpochSecond(1_755_700_000)

    private fun ago(seconds: Long): ULong =
        ((now.epochSecond - seconds) * 1000).toULong()

    private fun label(seconds: Long): String =
        RelativeTime.label(ago(seconds), now, zone, locale)

    /** "the first minute is now, not zero" */
    @Test
    fun neverZeroMinutes() {
        // "0m" is a unit reporting that it has nothing to report.
        assertEquals("now", label(5))
        assertEquals("now", label(59))
    }

    /** "the first hour counts in minutes" */
    @Test
    fun minutes() {
        assertEquals("1m", label(60))
        assertEquals("18m", label(18 * 60))
    }

    /** "later today is a clock time" */
    @Test
    fun today() {
        // Not "7h": within a day, when something happened is more useful than
        // how long ago it was.
        val result = label(7 * 3600)
        assertTrue("expected a clock time, got $result", result.contains(":"))
    }

    /** "this week is a weekday, beyond it is a date" */
    @Test
    fun weekdayThenDate() {
        // A weekday stops meaning anything once there could be two of them.
        val recent = label(3 * 24 * 3600)
        assertTrue("expected a weekday, got $recent", recent.length <= 4)

        val older = label(20 * 24 * 3600)
        assertTrue("expected a date, got $older", older.length > 4)
    }

    /** "nothing to say says nothing" */
    @Test
    fun noTimestamp() {
        assertEquals("", RelativeTime.label(null, now, zone, locale))
    }
}
