package dev.supermessage.widgets

/**
 * How long ago, in whole units: "3 min", "2 hr", "1 d" (`WidgetAge.since`, `NeedsYouWidgetView.swift:434-443`).
 *
 * iOS's rule, ported with its copy: never under a minute ("1 min"), one unit only, rounded down,
 * days when there are 24 hours. Not the app's `RelativeTime`, which falls back to a clock time
 * after an hour: a widget row says how long, not when. This is host code on iOS too (the core
 * hands over the time, `atMs`).
 */
internal object WidgetAge {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun since(thenMs: Long, nowMs: Long): String {
        val elapsed = maxOf(MINUTE, nowMs - thenMs)
        return when {
            elapsed >= DAY -> "${elapsed / DAY} d"
            elapsed >= HOUR -> "${elapsed / HOUR} hr"
            else -> "${elapsed / MINUTE} min"
        }
    }
}
