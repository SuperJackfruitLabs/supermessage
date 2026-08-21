package dev.supermessage.kit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * How a roster says when.
 *
 * Coarsened the way a person would: minutes for the last hour, a clock time
 * for today, a weekday for this week, a date beyond that. Never "0m" and
 * never a precision nobody asked for.
 *
 * Takes `now` rather than reading the clock, so the rules can be tested at a
 * fixed instant instead of being restated in the test.
 */
object RelativeTime {

    fun label(
        ms: ULong?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (ms == null) return ""
        val then = Instant.ofEpochMilli(ms.toLong())
        val elapsedSeconds = Duration.between(then, now).seconds

        // A message from a second ago is "now", not "0m". The unit only
        // starts being useful once there is one of them.
        if (elapsedSeconds < 60) return "now"
        if (elapsedSeconds < 60 * 60) return "${elapsedSeconds / 60}m"

        val thenDate = then.atZone(zone).toLocalDate()
        val nowDate = now.atZone(zone).toLocalDate()
        if (thenDate == nowDate) {
            return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
                .format(then.atZone(zone))
        }

        // A weekday only means something while it is unambiguous. Beyond six
        // days "Tuesday" could be either of two Tuesdays.
        if (elapsedSeconds < 60L * 60 * 24 * 6) {
            return DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
                .format(then.atZone(zone))
        }

        // No year within this one: it is noise on all but a handful of rows.
        val sameYear = thenDate.year == nowDate.year
        val pattern = if (sameYear) "d MMM" else "d MMM yyyy"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .format(then.atZone(zone))
    }
}
