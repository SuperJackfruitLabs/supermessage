package dev.supermessage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import uniffi.supermessage_core.Acknowledgement
import uniffi.supermessage_core.AcknowledgementKind

/**
 * What the room's last send has come to (iOS `TimelineView.swift:198-236`): a
 * check and "Sent to Atlas", or an ellipsis that steps and "Atlas is on it…".
 * The sentence is the core's, drawn as given; this decides only the glyph. One
 * line, on a floating capsule (`FloatingSurface.swift:30-46`), announced as it
 * changes.
 */
@Composable
fun AcknowledgementDock(acknowledgement: Acknowledgement, modifier: Modifier = Modifier) {
    val colors = SupermessageTheme.colors
    val working = acknowledgement.kind == AcknowledgementKind.ON_IT
    Box(
        modifier
            .testTag("dock")
            .shadow(
                elevation = 4.dp, shape = CircleShape,
                ambientColor = colors.scrim.copy(alpha = 0.1f), spotColor = colors.scrim.copy(alpha = 0.1f),
            )
            .background(colors.surfaceRaised, CircleShape)
            .border(1.dp, colors.border, CircleShape)
            .semantics(mergeDescendants = true) {
                contentDescription = acknowledgement.text
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(
            Modifier
                .testTag(if (working) "dock-working" else "dock-sent")
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (working) WorkingEllipsis()
            else Icon(Icons.Filled.Check, contentDescription = null, tint = colors.ok, modifier = Modifier.size(14.dp))
            Text(
                acknowledgement.text,
                style = MaterialTheme.typography.labelMedium,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Three dots in the accent colour whose brightness steps from one to the next; still when motion is reduced. */
@Composable
private fun WorkingEllipsis() {
    val reduce = reducesMotion()
    val phase = if (reduce) 0f else {
        val p by rememberInfiniteTransition(label = "dock-ellipsis").animateFloat(
            initialValue = 0f, targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "dock-ellipsis-phase",
        )
        p
    }
    Row(
        Modifier.testTag("dock-ellipsis").semantics { pulsing = !reduce },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val lit = reduce || phase.toInt() == i
            Box(
                Modifier.size(4.dp).alpha(if (lit) 1f else 0.3f)
                    .background(SupermessageTheme.colors.accent, CircleShape),
            )
        }
    }
}

/**
 * Who else is writing (iOS `TimelineView.swift:238-257`): a speech bubble with
 * dots, the core's line, on a sunken capsule. [line] is the core's `typingView`
 * line, drawn as given.
 */
@Composable
fun TypingPill(line: String, modifier: Modifier = Modifier) {
    val colors = SupermessageTheme.colors
    Row(
        modifier
            .testTag("typing-pill")
            .background(colors.surfaceSunken, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TypingGlyph(colors.contentFaint)
        Text(
            line,
            style = MaterialTheme.typography.labelMedium,
            color = colors.contentMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A chat bubble with three dots, drawn: the icon set here has none. */
@Composable
private fun TypingGlyph(tint: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val body = h * 0.78f
        drawRoundRect(tint, Offset(0f, 0f), Size(w, body), CornerRadius(h * 0.28f), style = Stroke(width = w * 0.11f))
        val tail = Path().apply {
            moveTo(w * 0.22f, body)
            lineTo(w * 0.22f, h)
            lineTo(w * 0.42f, body)
        }
        drawPath(tail, tint, style = Stroke(width = w * 0.11f))
        val r = w * 0.065f
        for (i in 0..2) drawCircle(tint, r, Offset(w * (0.28f + 0.22f * i), body / 2f))
    }
}

/**
 * The dock, then the typing pill, above the composer, leading-aligned
 * (`TimelineView.swift:123-146`): spacing 4, padding 12, bottom 4 only when
 * something shows. The dock enters from below with a fade, the pill fades; both
 * 0.22 s, none when motion is reduced. Translation and opacity only.
 */
@Composable
internal fun ComposerAccessories(ack: Acknowledgement?, typingLine: String?, modifier: Modifier = Modifier) {
    val reduce = reducesMotion()
    // The last value is kept so the exit has something to draw while it fades.
    var lastAck by remember { mutableStateOf(ack) }
    if (ack != null) lastAck = ack
    var lastLine by remember { mutableStateOf(typingLine) }
    if (typingLine != null) lastLine = typingLine
    Column(
        modifier.padding(start = 12.dp, end = 12.dp, bottom = if (ack != null || typingLine != null) 4.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedVisibility(
            visible = ack != null,
            enter = slideInVertically(motion(reduce, tween(220))) { it } + fadeIn(motion(reduce, tween(220))),
            exit = fadeOut(motion(reduce, tween(220))),
        ) { lastAck?.let { AcknowledgementDock(it) } }
        AnimatedVisibility(
            visible = typingLine != null,
            enter = fadeIn(motion(reduce, tween(220))),
            exit = fadeOut(motion(reduce, tween(220))),
        ) { lastLine?.let { TypingPill(it) } }
    }
}
