package dev.supermessage.kit

import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.TimelineRow

/**
 * Whether a row continues the run above it.
 *
 * Written natively rather than ported: grouping thresholds are presentation,
 * and a phone may legitimately differ from a workstation. What is *not*
 * negotiable is the shape of the rule, which is the desktop's — a run breaks
 * on a different sender, on a gap, and on anything that is not an ordinary
 * message.
 */
object TimelineGrouping {
    /** How close two messages from one sender must be to read as one turn. */
    val runWindowMs: ULong = 5uL * 60uL * 1000uL

    /**
     * Whether [row] should drop its header because the row above it already
     * carries one.
     */
    fun continuesRun(row: TimelineRow, previous: TimelineRow?): Boolean {
        if (previous == null) return false
        // Only ordinary messages group. A system line, a card or an image
        // carries its own header and ends the run above it — otherwise a
        // message after a card would look like the card's author said it.
        if (!isGroupable(row) || !isGroupable(previous)) return false
        if (row.item.sender != previous.item.sender) return false
        if (row.item.isOwn != previous.item.isOwn) return false
        val now = row.item.timestampMs ?: return false
        val then = previous.item.timestampMs ?: return false
        return now >= then && now - then <= runWindowMs
    }

    private fun isGroupable(row: TimelineRow): Boolean = row.view is ItemView.Bubble

    /**
     * Whether one agent does all the talking here.
     *
     * A room with a single speaker repeats `(OpenClaw on Ashram)` under every
     * message, where it never changes; a room with several needs it to tell
     * them apart. Counts *peers* — your own messages are attributed by
     * position rather than by name, so they say nothing about this.
     *
     * Stops at two: the answer cannot change after that, and this runs over
     * every row on every update.
     */
    fun hasSingleSpeaker(rows: List<TimelineRow>): Boolean {
        val seen = mutableSetOf<String>()
        for (row in rows) {
            if (row.item.isOwn) continue
            val sender = row.item.sender ?: continue
            seen.add(sender)
            if (seen.size > 1) return false
        }
        return true
    }

    /**
     * How many people a grouped membership line names before it counts.
     *
     * Matches the desktop's `MAX_NAMED`. Two is enough to recognise a run and
     * short enough that the sentence stays one line.
     */
    internal const val maxNamed = 2

    /**
     * Collapse consecutive membership changes that share a verb.
     *
     * Ported from the desktop's `groupTimelineItems`, which iOS never had —
     * so a room drew every single one, ten identical "updated their
     * membership" lines deep in Ganesha's history.
     *
     * Runs break on a **different verb**, so "three joined" and "one left"
     * stay two sentences rather than becoming one that is true of neither.
     * A run of exactly one reads exactly like the ungrouped line the core
     * already composes, never "Alice and 0 others".
     */
    fun collapseMembershipRuns(rows: List<TimelineRow>): List<DisplayRow> {
        val out = mutableListOf<DisplayRow>()
        var run = mutableListOf<TimelineRow>()

        fun flush() {
            val first = run.firstOrNull() ?: return
            out.add(
                DisplayRow.MembershipRun(
                    id = "group:${first.item.id}",
                    text = text(run),
                    rows = run.toList(),
                )
            )
            run = mutableListOf()
        }

        // `ItemView.None` is the core saying "draw nothing". A row for it is
        // still a row: a cell with no content does not reliably collapse to
        // no height, and one turned up on screen as roughly three hundred
        // points of blank in the middle of two different rooms. Deliberately
        // silent should mean *absent*, not empty.
        val filtered = rows.filter { it.view !is ItemView.None }

        for (row in filtered) {
            if (row.item.kind != "membership") {
                flush()
                out.add(DisplayRow.Row(row))
                continue
            }
            val first = run.firstOrNull()
            if (first != null && first.item.detail != row.item.detail) {
                flush()
            }
            run.add(row)
        }
        flush()
        return out
    }

    /**
     * The sentence for one run.
     *
     * Both halves come from the core: the verb is carried on the row *apart*
     * from the rendered sentence precisely so a run can be composed from many
     * names and one verb without parsing that sentence back apart.
     */
    internal fun text(run: List<TimelineRow>): String {
        val verb = run.firstOrNull()?.membershipVerb ?: "updated their membership"
        val names = run.map { it.senderShort }
        if (names.size <= maxNamed) {
            return "${joined(names)} $verb"
        }
        val named = names.take(maxNamed).joinToString(", ")
        val remaining = names.size - maxNamed
        return "$named and $remaining ${if (remaining == 1) "other" else "others"} $verb"
    }

    private fun joined(names: List<String>): String = when (names.size) {
        0 -> "Someone"
        1 -> names[0]
        else -> "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
    }
}

/**
 * A row as the timeline draws it: one item, or a collapsed run of membership
 * changes that would otherwise be a wall of near-identical lines.
 */
sealed class DisplayRow {
    abstract val id: String

    data class Row(val row: TimelineRow) : DisplayRow() {
        override val id: String get() = row.item.id
    }

    data class MembershipRun(
        override val id: String,
        val text: String,
        val rows: List<TimelineRow>,
    ) : DisplayRow()
}
