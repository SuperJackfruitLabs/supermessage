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
    /**
     * The sizes the launcher is asked to draw at. With `Responsive`, [LocalSize] is the bucket
     * chosen, **not** the widget's real size: the largest declared one that fits inside it. So the
     * buckets are the sizes the layout is designed for, and a launcher cell count lands on the
     * one below it: 2x2 -> [SMALL], 4x2 -> [WIDE] (one card), 4x3 -> [MEDIUM] (two cards, iOS's
     * medium), 4x4 and up -> [LARGE] (three).
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val now = System.currentTimeMillis()
        // A file read and a core call: off the main thread.
        val view = withContext(Dispatchers.IO) { AppCore.widgets(context).view(now) }
        provideContent { NeedsYouContent(view, now) }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 180.dp)
        val LARGE = DpSize(250.dp, 250.dp)
    }
}

/**
 * How many decisions a bucket lists at most: small and wide 1 (compact when small), medium 2,
 * large 3 (`NeedsYouWidgetView.swift:57-60`).
 */
internal fun listLimit(size: DpSize): Int = when {
    size.width < NeedsYouWidget.WIDE.width -> 1
    size.height < NeedsYouWidget.MEDIUM.height -> 1
    size.height < NeedsYouWidget.LARGE.height -> 2
    else -> 3
}

/** How much of a card is drawn: [FULL] with its answer buttons, [TEXT] the question and "Open to answer", [BARE] the question alone. */
internal enum class CardMode { FULL, TEXT, BARE }

/** What fits: [cards] of them, with the "N more" line or not, and in which [mode]. */
internal data class CardFit(val cards: Int, val more: Boolean, val mode: CardMode)

/**
 * Up to [limit] cards, fewer when they do not fit (a larger text size, a taller card), so the
 * stack never overflows and the header is never cropped (`:124-137`, iOS's `ViewThatFits`):
 * the most cards, each count tried with its "N more" line first and then without (a card is
 * worth more than the line counting it). Cards keep their answer buttons while one fits with
 * them; when not even one does (large text in a small bucket) they shrink to the question and
 * "Open to answer", then to the question alone, and a tap opens the decision either way. The
 * compact (small) card has no middle step. Glance cannot measure, so this estimates, in dp,
 * from the font scale; `NeedsYouWidgetFrameTest` measures the real layout against the bucket.
 */
internal fun cardsThatFit(limit: Int, count: Int, height: Dp, fontScale: Float, compact: Boolean = false): CardFit {
    val available = height.value - 2 * PADDING.value
    val most = maxOf(1, minOf(limit, count))
    for (mode in if (compact) listOf(CardMode.FULL, CardMode.BARE) else CardMode.values().toList()) {
        val card = cardHeight(mode, compact, fontScale)
        for (cards in most downTo 1) {
            val base = HEADER * fontScale + cards * card + (cards - 1) * CARD_GAP
            if (count > cards && base + MORE * fontScale + MORE_GAP <= available) return CardFit(cards, true, mode)
            if (base <= available) return CardFit(cards, false, mode)
        }
    }
    return CardFit(1, false, CardMode.BARE)
}

private fun cardHeight(mode: CardMode, compact: Boolean, fontScale: Float): Float = fontScale * when (mode) {
    CardMode.BARE -> BARE
    CardMode.TEXT -> TEXT_ONLY
    CardMode.FULL -> when {
        compact -> COMPACT_WITH_BUTTONS
        stacksButtons(fontScale) -> CARD_HEIGHT + STACKED_EXTRA
        else -> CARD_HEIGHT
    }
}

private typealias Dp = androidx.compose.ui.unit.Dp

/** At large text the buttons will not fit beside the name: they go under the question. */
internal fun stacksButtons(fontScale: Float) = fontScale >= 1.3f

private const val STACKED_EXTRA = 44f
private val PADDING = 8.dp
private const val HEADER = 20f
private const val CARD_HEIGHT = 61f
private const val COMPACT_WITH_BUTTONS = 74f
private const val TEXT_ONLY = 66f
private const val BARE = 26f
private const val CARD_GAP = 4f
private const val MORE = 14f
private const val MORE_GAP = 4f

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
        Header(frame, headerSize(size, fontScale))
        Spacer(GlanceModifier.height(4.dp))
        val decisions = view.decisions
        if (decisions.isEmpty()) return@Column AllClear(frame)
        val limit = listLimit(size)
        val fit = cardsThatFit(limit, decisions.size, size.height, fontScale, compact = limit == 1 && size.width < NeedsYouWidget.WIDE.width)
        if (limit == 1 && size.width < NeedsYouWidget.WIDE.width) {
            WidgetDecisionCard(decisions.first(), nowMs, compact = true, mode = fit.mode)
        } else {
            DecisionList(decisions, nowMs, fit, stacksButtons(fontScale))
        }
    }
}

/**
 * The header's size. In the narrow bucket ([NeedsYouWidget.SMALL]) the title and the count must
 * share 94dp, which twice the text does not allow ("Need..."), so the header's text stops
 * growing at 1.2x there: truncating the title would crop the one thing that names the widget.
 */
internal fun headerSize(size: DpSize, fontScale: Float): androidx.compose.ui.unit.TextUnit =
    if (size.width < NeedsYouWidget.WIDE.width && fontScale > 1.2f) (12f * 1.2f / fontScale).sp else 12.sp

/** "Needs you", and the count only while something is owed (`:92-109`). */
@Composable
private fun Header(frame: WidgetFrame, textSize: androidx.compose.ui.unit.TextUnit) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            WidgetCopy.NAME,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = WidgetColors.contentMuted, fontSize = textSize, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        // Nothing owed: the body says so, with the fleet's line, with no count of zero here to repeat it.
        if (frame.needsYou > 0u) {
            Text(
                frame.needsYouCount,
                style = TextStyle(color = WidgetColors.accent, fontSize = textSize, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DecisionList(decisions: List<WidgetDecisionView>, nowMs: Long, fit: CardFit, stacked: Boolean) {
    decisions.take(fit.cards).forEachIndexed { index, item ->
        if (index > 0) Spacer(GlanceModifier.height(CARD_GAP.dp))
        WidgetDecisionCard(item, nowMs, compact = false, stacked = stacked, mode = fit.mode)
    }
    if (fit.more) {
        Spacer(GlanceModifier.height(MORE_GAP.dp))
        Text(
            WidgetCopy.more(decisions.size - fit.cards),
            style = TextStyle(color = WidgetColors.contentFaint, fontSize = 10.sp),
            maxLines = 1,
        )
    }
}
