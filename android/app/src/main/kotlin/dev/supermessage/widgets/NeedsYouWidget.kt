package dev.supermessage.widgets

import android.content.Context
import androidx.compose.runtime.Composable
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
import dev.supermessage.AppCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.supermessage_core.WidgetContent
import uniffi.supermessage_core.WidgetDecisionView
import uniffi.supermessage_core.WidgetFrame
import uniffi.supermessage_core.WidgetView

/**
 * The decisions waiting on the reader, answerable in place (`NeedsYouWidget.swift`).
 *
 * Draws the core's snapshot (`widget::view`) and decides nothing: which decisions exist,
 * what each asks, which answers a button may send, what a sent one says and the counts are
 * the core's. The lock-screen accessory families have no phone equivalent here.
 */
class NeedsYouWidget : GlanceAppWidget() {
    /** Three sizes stand in for the families: small 110x110, medium 250x110, large 250x250. */
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val now = System.currentTimeMillis()
        // A file read and a core call: off the main thread.
        val view = withContext(Dispatchers.IO) { AppCore.widgets(context).view(now) }
        provideContent { NeedsYouContent(view, now) }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
        val LARGE = DpSize(250.dp, 250.dp)
    }
}

/** How many decisions a size lists at most: small 1 (compact), medium 2, large 3 (`NeedsYouWidgetView.swift:57-60`). */
internal fun listLimit(size: DpSize): Int = when {
    size.width < NeedsYouWidget.MEDIUM.width -> 1
    size.height < NeedsYouWidget.LARGE.height -> 2
    else -> 3
}

/**
 * Up to [limit] cards, fewer when they do not fit (a larger text size, a taller card), so the
 * stack never overflows and the header is never cropped; the rest are counted instead
 * (`:124-137`, iOS's `ViewThatFits`). Glance cannot measure, so this estimates, in dp, from the
 * font scale; it always keeps one.
 */
internal fun cardsThatFit(limit: Int, count: Int, height: Dp, fontScale: Float): Int {
    val available = height.value - 2 * PADDING.value
    val header = 16f * fontScale
    val card = ((CARD_HEIGHT + if (stacksButtons(fontScale)) STACKED_EXTRA else 0f) * fontScale) + CARD_GAP
    val more = 14f * fontScale
    var cards = minOf(limit, count)
    while (cards > 1) {
        val rest = if (count > cards) more else 0f
        if (header + cards * card + rest <= available) break
        cards--
    }
    return maxOf(cards, 1)
}

private typealias Dp = androidx.compose.ui.unit.Dp

/** At large text the buttons will not fit beside the name: they go under the question. */
internal fun stacksButtons(fontScale: Float) = fontScale >= 1.3f

private const val STACKED_EXTRA = 44f
private val PADDING = 8.dp
private const val CARD_HEIGHT = 61f
private const val CARD_GAP = 4f

/** The widget's content for [view] at [nowMs]; the size and the font scale are parameters for a test. */
@Composable
internal fun NeedsYouContent(
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
            .padding(PADDING),
    ) {
        val frame = view.frame
        if (view.content != WidgetContent.SNAPSHOT || frame == null) {
            WidgetUnavailable(view.content)
            return@Column
        }
        Header(frame)
        Spacer(GlanceModifier.height(4.dp))
        val decisions = view.decisions
        val limit = listLimit(size)
        when {
            decisions.isEmpty() -> AllClear(frame)
            limit == 1 -> WidgetDecisionCard(decisions.first(), nowMs, compact = true)
            else -> DecisionList(decisions, nowMs, cardsThatFit(limit, decisions.size, size.height, fontScale), stacksButtons(fontScale))
        }
    }
}

/** "Needs you", and the count only while something is owed (`:92-109`). */
@Composable
private fun Header(frame: WidgetFrame) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            WidgetCopy.NAME,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = WidgetColors.contentMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        // Nothing owed: the body says so, with the fleet's line, with no count of zero here to repeat it.
        if (frame.needsYou > 0u) {
            Text(
                frame.needsYouCount,
                style = TextStyle(color = WidgetColors.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DecisionList(decisions: List<WidgetDecisionView>, nowMs: Long, cards: Int, stacked: Boolean) {
    decisions.take(cards).forEachIndexed { index, item ->
        if (index > 0) Spacer(GlanceModifier.height(CARD_GAP.dp))
        WidgetDecisionCard(item, nowMs, compact = false, stacked = stacked)
    }
    if (decisions.size > cards) {
        Spacer(GlanceModifier.height(4.dp))
        Text(
            WidgetCopy.more(decisions.size - cards),
            style = TextStyle(color = WidgetColors.contentFaint, fontSize = 10.sp),
            maxLines = 1,
        )
    }
}
