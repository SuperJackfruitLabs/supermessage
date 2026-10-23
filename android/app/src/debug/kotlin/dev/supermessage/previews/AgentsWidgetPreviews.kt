package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import dev.supermessage.widgets.AgentsContent
import dev.supermessage.widgets.AgentsWidget
import uniffi.supermessage_core.WidgetContent
import uniffi.supermessage_core.WidgetFrame
import uniffi.supermessage_core.WidgetOutcome
import uniffi.supermessage_core.WidgetRecap
import uniffi.supermessage_core.WidgetView

/**
 * The Agents widget's states, as Glance previews for Android Studio.
 *
 * Like the Needs you widget's, these are Glance's `@Preview`, which this repo's Roborazzi scanner
 * does not look for, so they are not rendered in the unit-test gate. The frames the repo does
 * record come from `AgentsWidgetFrameTest`, which renders the real `RemoteViews` for the same
 * states, light and dark, into `src/test/previews/AgentsWidget*.png`.
 */
private const val NOW = 1_800_000_000_000L
private const val MINUTE = 60_000L

private fun recap(name: String, outcome: WidgetOutcome, line: String, agoMin: Long, unread: UInt = 1u) =
    WidgetRecap("!$name:hs", name, outcome, line, unread, (NOW - agoMin * MINUTE).toULong())

private val four = listOf(
    recap("Atlas", WidgetOutcome.FAILED, "2 of 7 steps failed", 3),
    recap("Quill", WidgetOutcome.FINISHED, "Finished · 5 steps", 6),
    recap("Hermes", WidgetOutcome.SAID, "Deployed to staging", 1, unread = 2u),
    recap("Lyra", WidgetOutcome.SAID, "Looking into the deploy", 9),
)

private fun frame(pulse: String, busy: String? = null) =
    WidgetFrame(NOW.toULong(), 0u, "0", "Nothing needs you", 0u, pulse, emptyList(), busy)

private fun view(recap: List<WidgetRecap>, pulse: String, busy: String? = null, opened: Boolean = true) = WidgetView(
    WidgetContent.SNAPSHOT, frame(pulse, busy), emptyList(), emptyList(), recap, false,
    if (opened) (NOW - 30 * MINUTE).toULong() else null, null,
)

private fun without(content: WidgetContent) = WidgetView(content, null, emptyList(), emptyList(), emptyList(), false, null, null)

@Composable
private fun Widget(view: WidgetView, size: DpSize) = AgentsContent(view, NOW, size, fontScale = 1f)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 160)
@Composable
internal fun AgentsRecapOfFour() = Widget(view(four, "2 working"), AgentsWidget.MEDIUM)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 110)
@Composable
internal fun AgentsEmptyWithSomeoneBusy() = Widget(view(emptyList(), "1 working", busy = "Atlas is working"), AgentsWidget.WIDE)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 110)
@Composable
internal fun AgentsEmptyAndQuiet() = Widget(view(emptyList(), "All quiet"), AgentsWidget.WIDE)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 110)
@Composable
internal fun AgentsNoAgents() = Widget(view(emptyList(), "All quiet"), AgentsWidget.WIDE)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 110)
@Composable
internal fun AgentsSignedOut() = Widget(without(WidgetContent.SIGNED_OUT), AgentsWidget.WIDE)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 250)
@Composable
internal fun AgentsLargeWithSeven() = Widget(
    view(four + listOf(recap("Orion", WidgetOutcome.SAID, "Idle", 12), recap("Vega", WidgetOutcome.SAID, "Idle", 15), recap("Rigel", WidgetOutcome.SAID, "Idle", 18)), "3 working"),
    AgentsWidget.LARGE,
)
