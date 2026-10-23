package dev.supermessage.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import uniffi.supermessage_core.WidgetContent
import uniffi.supermessage_core.WidgetFrame

/** The widgets' copy, verbatim from iOS (`NeedsYouWidgetView.swift`, `NeedsYouWidget.swift`). */
internal object WidgetCopy {
    /** `NeedsYouWidget.swift:11`, and the header (`NeedsYouWidgetView.swift:92`). */
    const val NAME = "Needs you"

    /** `NeedsYouWidget.swift:12`. */
    const val DESCRIPTION = "Decisions waiting on you — answer them from here."

    /** `:386-424`: the empty states' header. */
    const val APP = "supermessage"

    const val SIGNED_OUT = "Sign in to see what needs you."
    const val WAITING = "Open the app once to start."

    /** `:151`. */
    fun more(count: Int) = "$count more in the app"

    /** `AgentsWidget.swift:12-13`, and the header (`AgentsWidgetView.swift:105-109`). */
    const val AGENTS = "Agents"
    const val AGENTS_DESCRIPTION = "What your agents did since you last opened the app."

    /** `AgentsWidgetView.swift:108`: "Since 10:42", the device's short time. */
    fun since(time: String) = "Since $time"

    /** `:111-114`, under a header that says someone is working. */
    fun nothingFinished(time: String?) = if (time == null) "Nothing finished yet" else "Nothing finished since $time"

    /** `:116-119`. */
    fun nothingNew(time: String?) = if (time == null) "Nothing new" else "Nothing new since $time"

    /** `:84`. */
    const val NO_ROOMS = "No agent rooms yet."
    const val QUIET = "Quiet while you were away."

    /** `:141`. */
    fun newCount(count: UInt) = "$count new"

    /** `:341-343`: a decision with no inline button. */
    const val OPEN_TO_ANSWER = "Open to answer"
}

/** The honest empty states (`NeedsYouWidgetView.swift:386-424`). Unavailable never applies on Android. */
@Composable
internal fun WidgetUnavailable(content: WidgetContent) {
    val message = when (content) {
        WidgetContent.SIGNED_OUT -> WidgetCopy.SIGNED_OUT
        else -> WidgetCopy.WAITING
    }
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Text(
            WidgetCopy.APP,
            style = TextStyle(color = WidgetColors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(message, style = TextStyle(color = WidgetColors.contentMuted, fontSize = 12.sp))
    }
}

/** Nothing owed: said as a fact, with what the fleet is doing instead (`:160-175`). */
@Composable
internal fun AllClear(frame: WidgetFrame) {
    Column(modifier = GlanceModifier, horizontalAlignment = Alignment.Start) {
        Text(
            frame.needsYouLine,
            style = TextStyle(color = WidgetColors.content, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
        Text(frame.pulse, style = TextStyle(color = WidgetColors.contentMuted, fontSize = 12.sp))
    }
}
