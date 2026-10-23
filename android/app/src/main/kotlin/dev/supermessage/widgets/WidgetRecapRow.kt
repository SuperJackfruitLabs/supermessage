package dev.supermessage.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.supermessage.R
import uniffi.supermessage_core.WidgetOutcome
import uniffi.supermessage_core.WidgetRecap

/** How a recap row is coloured: the dot for how it went, and the line under the name (`AgentsWidgetView.swift:149-166`). */
internal data class RecapPalette(val dot: ColorProvider, val line: ColorProvider)

/** Failed is `danger` in both; finished is `ok` and said is `accent`, with a muted line. */
internal fun recapPalette(outcome: WidgetOutcome) = when (outcome) {
    WidgetOutcome.FAILED -> RecapPalette(dot = WidgetColors.danger, line = WidgetColors.danger)
    WidgetOutcome.FINISHED -> RecapPalette(dot = WidgetColors.ok, line = WidgetColors.contentMuted)
    WidgetOutcome.SAID -> RecapPalette(dot = WidgetColors.accent, line = WidgetColors.contentMuted)
}

internal const val RECAP_ROW_TAG = "recap"

/** The test tag of a row's line, by how it went. */
internal fun recapLineTag(outcome: WidgetOutcome) = "recap-line:" + outcome.name.lowercase()

/**
 * One agent in the recap: a dot for how it went, its name, how many pushes ("N new" only above
 * one) and how long ago, and under the name the core's line ("2 of 7 steps failed", "Finished ·
 * 7 steps", or what it said). The row opens its room (`AgentsWidgetView.swift:89-93, 125-166`).
 *
 * Everything on it is the core's: the order, the outcome, the line and the count. The age is
 * the one thing the core hands over as a time (`atMs`) and not as words, so it is worded by
 * [WidgetAge], iOS's rule and copy, as the Needs you widget words its own.
 */
@Composable
internal fun WidgetRecapRow(row: WidgetRecap, nowMs: Long, modifier: GlanceModifier = GlanceModifier) {
    val palette = recapPalette(row.outcome)
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth()
            .semantics { testTag = RECAP_ROW_TAG }
            .clickable(actionStartActivity(AnswerFromWidget.roomIntent(context, row.roomId))),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_dot),
                contentDescription = null,
                modifier = GlanceModifier.size(7.dp),
                colorFilter = ColorFilter.tint(palette.dot),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                row.name,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = WidgetColors.content, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (row.unread > 1u) {
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    WidgetCopy.newCount(row.unread),
                    style = TextStyle(color = WidgetColors.contentMuted, fontSize = 10.sp),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(4.dp))
            Text(
                WidgetAge.since(row.atMs.toLong(), nowMs),
                style = TextStyle(color = WidgetColors.contentFaint, fontSize = 10.sp),
                maxLines = 1,
            )
        }
        Text(
            row.line,
            modifier = GlanceModifier.padding(start = 13.dp).semantics { testTag = recapLineTag(row.outcome) },
            style = TextStyle(color = palette.line, fontSize = 10.sp),
            maxLines = 1,
        )
    }
}
