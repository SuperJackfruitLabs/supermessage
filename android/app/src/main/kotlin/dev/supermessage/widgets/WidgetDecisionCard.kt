package dev.supermessage.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.supermessage.R
import uniffi.supermessage_core.WidgetDecisionView
import uniffi.supermessage_core.WidgetOption

/** Amber is the owed card's, and only while owed; sent is the muted rank (`NeedsYouWidgetView.swift:359-372`). */
internal data class CardPalette(val outline: ColorProvider, val ground: ColorProvider)

internal fun cardPalette(owed: Boolean) =
    if (owed) CardPalette(WidgetColors.signal, WidgetColors.signalSoft)
    else CardPalette(WidgetColors.border, WidgetColors.surfaceSunken)

/** The test tag of a card, by whether it is owed. */
internal fun cardTag(owed: Boolean, eventId: String) = (if (owed) "decision-owed:" else "decision-sent:") + eventId

/** The test tag of an answer button. */
internal fun answerTag(eventId: String, optionId: String) = "answer:$eventId:$optionId"

/**
 * One decision on a widget: who asks, what, how long ago, and the answers a button may
 * send (`NeedsYouWidgetView.swift:317-380`).
 *
 * **The widgets' `DecisionCard`, and the only widget view that may draw amber**, and only
 * while the decision is owed. Once sent it says so in the core's words ("Sent: Approve ·
 * waiting for the board"), in the muted rank: sent is not resolved, and the board's
 * receipt is what takes it away. The text opens the card in the app; the buttons answer in
 * place. "Request changes" has no button, because the core's `buttons` does not list it
 * (feedback needs words, and the card is where they are typed).
 *
 * @param compact the small widget: no card around it, and the question gives its height to the buttons.
 */
@Composable
internal fun WidgetDecisionCard(
    item: WidgetDecisionView,
    nowMs: Long,
    compact: Boolean,
    /** Buttons under the question rather than beside the name: the small widget, and large text. */
    stacked: Boolean = compact,
    /** What the room allows: the buttons, or (large text in a small bucket) less, with a tap to open. */
    mode: CardMode = CardMode.FULL,
) {
    val buttons = mode == CardMode.FULL
    val decision = item.decision
    val owed = decision.answered == null
    val palette = cardPalette(owed)
    val tag = cardTag(owed, decision.eventId)
    val showButtons = owed && buttons && item.buttons.isNotEmpty()
    val context = LocalContext.current
    // The text is a link to the card in the app; the buttons inside it answer in place.
    val open = GlanceModifier.clickable(
        actionStartActivity(AnswerFromWidget.decisionIntent(context, decision.roomId, decision.eventId)),
    )
    val body: @Composable () -> Unit = {
        Column(modifier = GlanceModifier.fillMaxWidth().then(open)) {
            if (mode != CardMode.BARE) Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    decision.agent,
                    style = TextStyle(
                        color = if (owed) WidgetColors.content else WidgetColors.contentMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    WidgetAge.since(decision.askedAtMs.toLong(), nowMs),
                    style = TextStyle(color = WidgetColors.contentFaint, fontSize = 10.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.defaultWeight())
                if (showButtons && !stacked) DecisionButtons(item, compact = false)
            }
            Text(
                decision.question,
                style = TextStyle(color = WidgetColors.content, fontSize = 12.sp),
                // The small widget gives its height to the stacked buttons; iOS's 3 lines when there are none.
                maxLines = if (mode == CardMode.BARE || (compact && showButtons)) 1 else if (compact) 3 else 2,
            )
            val answered = decision.answered
            if (mode == CardMode.BARE) {
                // The question alone: the whole card is the tap that opens it.
            } else if (answered != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_sent),
                        contentDescription = null,
                        modifier = GlanceModifier.size(10.dp),
                        colorFilter = ColorFilter.tint(WidgetColors.contentMuted),
                    )
                    Spacer(GlanceModifier.width(3.dp))
                    Text(
                        answered.line,
                        style = TextStyle(color = WidgetColors.contentMuted, fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
            } else if (item.buttons.isEmpty() || !buttons) {
                Text(
                    WidgetCopy.OPEN_TO_ANSWER,
                    style = TextStyle(color = WidgetColors.contentMuted, fontSize = 10.sp),
                )
            }
            if (showButtons && stacked) {
                Spacer(GlanceModifier.size(1.dp))
                DecisionButtons(item, compact = true)
            }
        }
    }
    if (compact) {
        Box(modifier = GlanceModifier.fillMaxWidth().semantics { testTag = tag }) { body() }
        return
    }
    // The card's outline: a ground one dp inside a border-coloured box (Glance has no stroke).
    Box(
        modifier = GlanceModifier.fillMaxWidth()
            .semantics { testTag = tag }
            .background(palette.outline)
            .cornerRadius(CARD_RADIUS)
            .padding(1.dp),
    ) {
        Box(
            modifier = GlanceModifier.fillMaxWidth()
                .background(palette.ground)
                .cornerRadius(CARD_RADIUS)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) { body() }
    }
}

private val CARD_RADIUS = 8.dp

/** Side by side beside the agent's name; stacked, full width, under the question in the small widget. */
@Composable
private fun DecisionButtons(item: WidgetDecisionView, compact: Boolean) {
    val decision = item.decision
    val buttons: @Composable () -> Unit = {
        item.buttons.forEachIndexed { index, option ->
            if (index > 0) {
                if (compact) Spacer(GlanceModifier.size(1.dp)) else Spacer(GlanceModifier.width(6.dp))
            }
            DecisionButton(option, decision.roomId, decision.eventId, compact)
        }
    }
    if (compact) Column(modifier = GlanceModifier.fillMaxWidth()) { buttons() } else Row { buttons() }
}

/**
 * A declining option is muted and outlined; any other is amber (`:355-380`). Not Glance's
 * `Button`, whose padding and capitals do not fit a card in a small widget.
 */
@Composable
private fun DecisionButton(option: WidgetOption, roomId: String, eventId: String, compact: Boolean) {
    val onClick = actionRunCallback<AnswerDecisionAction>(
        actionParametersOf(
            AnswerDecisionAction.RoomKey to roomId,
            AnswerDecisionAction.EventKey to eventId,
            AnswerDecisionAction.OptionKey to option.id,
        ),
    )
    val declines = option.declines
    val label: @Composable () -> Unit = {
        Text(
            option.label,
            style = TextStyle(
                color = if (declines) WidgetColors.contentMuted else WidgetColors.signalSoft,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
    val vertical = if (compact) 2.dp else 5.dp
    val tap = (if (compact) GlanceModifier.fillMaxWidth() else GlanceModifier)
        .semantics {
            testTag = answerTag(eventId, option.id)
            contentDescription = option.label
        }
        .clickable(onClick)
    if (declines) {
        Box(modifier = tap.background(WidgetColors.border).cornerRadius(BUTTON_RADIUS).padding(1.dp)) {
            Box(
                modifier = (if (compact) GlanceModifier.fillMaxWidth() else GlanceModifier)
                    .background(WidgetColors.surface).cornerRadius(BUTTON_RADIUS)
                    .padding(horizontal = 8.dp, vertical = vertical - 1.dp),
                contentAlignment = Alignment.Center,
            ) { label() }
        }
    } else {
        Box(
            modifier = tap.background(WidgetColors.signal).cornerRadius(BUTTON_RADIUS)
                .padding(horizontal = 8.dp, vertical = vertical),
            contentAlignment = Alignment.Center,
        ) { label() }
    }
}

private val BUTTON_RADIUS = 14.dp
