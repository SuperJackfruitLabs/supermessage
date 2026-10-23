package dev.supermessage.widgets

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.supermessage_core.WidgetContent
import uniffi.supermessage_core.WidgetFrame
import uniffi.supermessage_core.WidgetView

/**
 * What the agents did since the app was last opened (`AgentsWidget.swift`, `AgentsWidgetView.swift`).
 *
 * A recap, not a live view. It draws the core's snapshot (`widget::view`) and decides nothing:
 * which agents are listed, their order (failed, then finished, then said, newest first), each
 * line, the "N new" count, the pulse and who is busy are the core's. It draws no agent faces,
 * as iOS's does not: the recap names a room, not the agent's Matrix id the avatar cache is keyed
 * by, and the widget never fetches.
 */
class AgentsWidget : GlanceAppWidget() {
    /**
     * The sizes the launcher is asked to draw at (medium and large families only). With
     * `Responsive`, [LocalSize] is the bucket chosen, **not** the real size: the largest declared
     * one inside it. So 4x2 lands in [WIDE] (two rows), 4x3 in [MEDIUM] (three rows, iOS's
     * medium), 4x4 and up in [LARGE] (six).
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(WIDE, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read again in composition after every change, or a running session would keep
        // drawing this first read ([WidgetSnapshots]).
        val seen = WidgetSnapshots.current
        val first = withContext(Dispatchers.IO) { WidgetSnapshots.read(context) }
        provideContent {
            val drawn = followSnapshot(context, first, seen)
            AgentsContent(drawn.view, drawn.nowMs)
        }
    }

    companion object {
        val WIDE = DpSize(250.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 180.dp)
        val LARGE = DpSize(250.dp, 250.dp)
    }
}

/** How many rows a size is asked for at most: 3 on medium and 6 on large (`AgentsWidgetView.swift:40`). */
internal fun recapLimit(size: DpSize): Int = if (size.height < AgentsWidget.LARGE.height) 3 else 6

private const val PADDING_DP = 8f
private const val HEADER_DP = 20f
private const val ROW_DP = 30.5f
private const val ROW_GAP_DP = 2f
private const val MORE_DP = 18f

/** What fits: [rows] of them, with the "N more" line, and with the header (dropped when not even one row fits under it). */
internal data class RowFit(val rows: Int, val more: Boolean, val header: Boolean)

/**
 * How many rows to draw: the most the size is asked for that fit, each count tried with its "N
 * more" line first and then without, because a row is worth more than the line counting it
 * (`:41-55`, iOS's `ViewThatFits`). When not even one row fits under the header (110dp at twice
 * the text) the header goes, rather than the row being cropped. Glance cannot measure, so this
 * estimates, in dp, from the font scale; `AgentsWidgetFrameTest` measures the real layout
 * against the bucket.
 */
internal fun rowsThatFit(limit: Int, count: Int, height: Dp, fontScale: Float): RowFit {
    val available = height.value - 2 * PADDING_DP
    val most = maxOf(1, minOf(limit, count))
    for (header in listOf(true, false)) {
        val top = if (header) HEADER_DP else 0f
        for (rows in most downTo 1) {
            val base = (top + rows * ROW_DP + (rows - 1) * ROW_GAP_DP) * fontScale
            if (count > rows && base + MORE_DP * fontScale <= available) return RowFit(rows, true, header)
            if (base <= available) return RowFit(rows, false, header)
        }
    }
    return RowFit(1, false, false)
}

/** Whether the empty state's explaining line fits under its first (the widget is 110dp high at its least). */
internal fun explainsWhenEmpty(height: Dp, fontScale: Float): Boolean =
    (HEADER_DP + 19f + 16f) * fontScale <= height.value - 2 * PADDING_DP

/** The widget's content for [view] at [nowMs]; the size and the font scale are parameters for a test. */
@Composable
internal fun AgentsContent(
    view: WidgetView,
    nowMs: Long,
    size: DpSize = LocalSize.current,
    fontScale: Float = LocalContext.current.resources.configuration.fontScale,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(WidgetColors.surface)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .padding(PADDING_DP.dp),
    ) {
        val frame = view.frame
        if (view.content != WidgetContent.SNAPSHOT || frame == null) {
            WidgetUnavailable(view.content)
            return@Column
        }
        val context = LocalContext.current
        val opened = view.openedAtMs?.let { shortTime(context, it.toLong()) }
        if (view.recap.isEmpty()) {
            Header(frame, opened, hasRecap = false)
            Spacer(GlanceModifier.height(4.dp))
            NothingNew(frame, opened, noRooms = view.agents.isEmpty(), explain = explainsWhenEmpty(size.height, fontScale))
            return@Column
        }
        val fit = rowsThatFit(recapLimit(size), view.recap.size, size.height, fontScale)
        if (fit.header) {
            Header(frame, opened, hasRecap = true)
            Spacer(GlanceModifier.height(4.dp))
        }
        // A Glance Column holds ten children and drops the rest without a word, so the gaps are
        // padding on the rows and the line, not Spacers (header + spacer + six rows + line = 9).
        view.recap.take(fit.rows).forEachIndexed { index, row ->
            WidgetRecapRow(row, nowMs, GlanceModifier.padding(top = if (index > 0) ROW_GAP_DP.dp else 0.dp))
        }
        if (fit.more) {
            Text(
                WidgetCopy.more(view.recap.size - fit.rows),
                modifier = GlanceModifier.padding(top = 4.dp),
                style = TextStyle(color = WidgetColors.contentFaint, fontSize = 10.sp),
                maxLines = 1,
            )
        }
    }
}

/** The device's short time, `formatted(date: .omitted, time: .shortened)`. */
internal fun shortTime(context: Context, ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

/** The pulse's colour: accent while something needs the reader, faint otherwise (`:63-64`). */
internal fun pulseColor(needsYou: UInt): ColorProvider =
    if (needsYou > 0u) WidgetColors.accent else WidgetColors.contentFaint

/** "Since 10:42" when the app was opened and the recap has something, else "Agents" (`:105-109`). */
@Composable
private fun Header(frame: WidgetFrame, opened: String?, hasRecap: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The title is never cut ("Sin..."); the pulse takes what is left and ends first.
        Text(
            if (opened != null && hasRecap) WidgetCopy.since(opened) else WidgetCopy.AGENTS,
            style = TextStyle(color = WidgetColors.contentMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(
            frame.pulse,
            modifier = GlanceModifier.defaultWeight().padding(start = 4.dp),
            style = TextStyle(
                color = pulseColor(frame.needsYou), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.glance.text.TextAlign.End,
            ),
            maxLines = 1,
        )
    }
}

/**
 * An empty recap. Under a header that says "2 working", never "Nothing new": the frame names
 * who is working instead (`:69-87`, spec 2026-09-30, B5).
 */
@Composable
private fun NothingNew(frame: WidgetFrame, opened: String?, noRooms: Boolean, explain: Boolean) {
    val busy = frame.busy
    val first = busy ?: WidgetCopy.nothingNew(opened)
    val second = if (busy != null) WidgetCopy.nothingFinished(opened) else if (noRooms) WidgetCopy.NO_ROOMS else WidgetCopy.QUIET
    Text(
        first,
        style = TextStyle(color = WidgetColors.content, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
    // At twice the text the explaining line would be cropped: the first line says it.
    if (explain) {
        Text(second, style = TextStyle(color = WidgetColors.contentMuted, fontSize = 12.sp), maxLines = 1)
    }
}
