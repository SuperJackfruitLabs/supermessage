package dev.supermessage.kit

import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.TimelineRow

/**
 * The entries to draw for [rows], in order.
 *
 * The grouping rules (sender runs, reply quotes, runtime suffixes, membership
 * runs and stretches) are the core's, decided once in `timeline_grouping.rs`
 * and carried on every row. What this host keeps is the walk from those row
 * fields to entries, and which collapsed stretches the reader has opened
 * ([expanded], by stretch id).
 *
 * - A row whose view is `ItemView.None` draws nothing and gets no entry. A
 *   cell with no content does not reliably collapse to no height (one turned
 *   up as roughly three hundred points of blank in two rooms), so deliberately
 *   silent has to mean absent, not empty.
 * - A row marked `hidden` (an event the reader reported on this device) gets
 *   no entry either. The core already grouped as if it were absent; the
 *   session pushes the bound account's set (`CoreClient.setHiddenEvents`) at
 *   sign-in and after each report.
 * - A membership row with a placement joins a run entry (`group:<first row>`,
 *   the sentence on the run's first row). While its stretch is collapsed (not
 *   in [expanded]) the whole stretch is one entry (`stretch:<first row>`).
 * - A membership row with no placement, which the core did not settle, is
 *   drawn as the ordinary row it is.
 */
fun displayRows(rows: List<TimelineRow>, expanded: Set<String>): List<DisplayRow> {
    val out = mutableListOf<DisplayRow>()
    // Where each open entry sits in `out`, so later rows of the same run or
    // stretch land in it.
    val entryIndex = HashMap<String, Int>()

    fun join(id: String, text: String?, isStretch: Boolean, row: TimelineRow) {
        val index = entryIndex[id]
        if (index != null) {
            val entry = out[index] as DisplayRow.MembershipRun
            out[index] = entry.copy(rows = entry.rows + row)
        } else if (text != null) {
            entryIndex[id] = out.size
            out.add(DisplayRow.MembershipRun(id = id, text = text, rows = listOf(row), isStretch = isStretch))
        }
    }

    for (row in rows) {
        if (row.view is ItemView.None || row.hidden) continue
        val placement = row.membership
        if (row.item.kind != "membership" || placement == null) {
            out.add(DisplayRow.Row(row))
            continue
        }
        val stretch = placement.stretchId
        if (stretch != null && stretch !in expanded) {
            join(stretch, placement.stretchText, isStretch = true, row = row)
        } else {
            join(placement.runId, placement.runText, isStretch = false, row = row)
        }
    }
    return out
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

    /**
     * A run of membership changes, or (when [isStretch]) a whole stretch of
     * runs folded into one summary line the reader can open.
     */
    data class MembershipRun(
        override val id: String,
        val text: String,
        val rows: List<TimelineRow>,
        val isStretch: Boolean = false,
    ) : DisplayRow()
}
