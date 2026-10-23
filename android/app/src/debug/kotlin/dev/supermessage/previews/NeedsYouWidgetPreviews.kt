package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import dev.supermessage.widgets.NeedsYouContent
import dev.supermessage.widgets.NeedsYouWidget
import uniffi.supermessage_core.WidgetAnswered
import uniffi.supermessage_core.WidgetContent
import uniffi.supermessage_core.WidgetDecision
import uniffi.supermessage_core.WidgetDecisionKind
import uniffi.supermessage_core.WidgetDecisionView
import uniffi.supermessage_core.WidgetFrame
import uniffi.supermessage_core.WidgetOption
import uniffi.supermessage_core.WidgetView

/**
 * The Needs you widget's states, as Glance previews for Android Studio.
 *
 * These are Glance's `@Preview`, which this repo's Roborazzi scanner does not look for (it
 * scans Compose's), so they are not rendered in the unit-test gate. The frames the repo does
 * record come from `NeedsYouWidgetFrameTest`, which renders the real `RemoteViews` for the same
 * states, light and dark, into `src/test/previews/NeedsYouWidget*.png`.
 */
private const val NOW = 1_800_000_000_000L
private const val MINUTE = 60_000L

private val allow = WidgetOption("Allow once", "Allow once", inline = true, declines = false)
private val reject = WidgetOption("Reject", "Reject", inline = true, declines = true)
private val approve = WidgetOption("approve", "Approve", inline = true, declines = false)
private val rejectGate = WidgetOption("reject", "Reject", inline = true, declines = true)
private val requestChanges = WidgetOption("request_changes", "Request changes", inline = false, declines = true)

private fun decision(
    kind: WidgetDecisionKind,
    event: String,
    agent: String,
    question: String,
    options: List<WidgetOption>,
    agoMin: Long,
    answered: WidgetAnswered? = null,
) = WidgetDecision(
    kind, "!room:$event", event, agent, null, question, options, null, question,
    (NOW - agoMin * MINUTE).toULong(), answered,
)

private fun owed(d: WidgetDecision) = WidgetDecisionView(d, d.options.filter { it.inline })
private fun sent(d: WidgetDecision) = WidgetDecisionView(d, emptyList())

private fun frame(needsYou: Int, line: String, pulse: String) =
    WidgetFrame(NOW.toULong(), needsYou.toUInt(), needsYou.toString(), line, 0u, pulse, emptyList(), null)

private fun view(vararg decisions: WidgetDecisionView): WidgetView {
    val owedCount = decisions.count { it.decision.answered == null }
    return WidgetView(
        WidgetContent.SNAPSHOT,
        frame(owedCount, if (owedCount == 0) "Nothing needs you" else "$owedCount need you", if (owedCount == 0) "All quiet" else "$owedCount needs you"),
        decisions.toList(), emptyList(), emptyList(), false, null, null,
    )
}

private fun without(content: WidgetContent) = WidgetView(content, null, emptyList(), emptyList(), emptyList(), false, null, null)

private val push = decision(WidgetDecisionKind.PERMISSION, "\$p", "Hermes", "Run git push?", listOf(allow, reject), 1)
private val ship = decision(WidgetDecisionKind.GATE, "\$g", "Launch board", "Approve \"Ship v2\"?", listOf(approve, requestChanges, rejectGate), 9)
private val shipSent = ship.copy(answered = WidgetAnswered("approve", NOW.toULong(), "Sent: Approve · waiting for the board"))

@Composable
private fun Widget(view: WidgetView, size: DpSize) = NeedsYouContent(view, NOW, size, fontScale = 1f)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 250)
@Composable
internal fun NeedsYouTwoOwed() = Widget(view(owed(push), owed(ship)), NeedsYouWidget.LARGE)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 250)
@Composable
internal fun NeedsYouOneSent() = Widget(view(sent(shipSent)), NeedsYouWidget.LARGE)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 110)
@Composable
internal fun NeedsYouAllClear() = Widget(view(), NeedsYouWidget.MEDIUM)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 110)
@Composable
internal fun NeedsYouSignedOut() = Widget(without(WidgetContent.SIGNED_OUT), NeedsYouWidget.MEDIUM)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 110)
@Composable
internal fun NeedsYouWaiting() = Widget(without(WidgetContent.WAITING), NeedsYouWidget.MEDIUM)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 110, heightDp = 110)
@Composable
internal fun NeedsYouSmall() = Widget(view(owed(push), owed(ship)), NeedsYouWidget.SMALL)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 250)
@Composable
internal fun NeedsYouLargeWithFour() = Widget(
    view(
        owed(push),
        owed(ship),
        owed(decision(WidgetDecisionKind.PERMISSION, "\$q", "Atlas", "Delete the staging branch?", listOf(allow, reject), 14)),
        owed(decision(WidgetDecisionKind.PERMISSION, "\$r", "Lyra", "Install the missing package?", listOf(allow, reject), 22)),
    ),
    NeedsYouWidget.LARGE,
)
