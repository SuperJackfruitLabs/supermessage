package dev.supermessage

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.supermessage_ffi.elapsedLabel

/** How long the mic is held before the press is a hold, not a tap (`ComposerView.swift:422`). */
internal const val HOLD_MS = 300L

/** How far left a held finger slides to throw the recording away (`ComposerView.swift:401`; 80 pt as 80 dp). */
internal val CANCEL_DISTANCE = 80.dp

/**
 * The mic (`MicButton`, `ComposerView.swift:380-440`): 44dp. A tap starts a
 * recording; a hold of [HOLD_MS] starts a held one, release sends it, and
 * sliding left past [CANCEL_DISTANCE] cancels it. While recording it is a
 * `danger` disc, slightly larger.
 *
 * Touch goes through `pointerInput`; TalkBack and keyboards get the same tap
 * through the semantic click, labelled "Record voice message".
 */
@Composable
internal fun MicButton(
    isRecording: Boolean,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: (cancelled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SupermessageTheme.colors
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onHoldStart)
    val end by rememberUpdatedState(onHoldEnd)
    Box(
        modifier
            .size(44.dp)
            .testTag("composer-mic")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var ended = false
                    val up = withTimeoutOrNull(HOLD_MS) {
                        waitForUpOrCancellation().also { ended = true }
                    }
                    if (ended) {
                        if (up != null) {
                            up.consume()
                            tap()
                        }
                        return@awaitEachGesture
                    }
                    // Held.
                    start()
                    val limit = -CANCEL_DISTANCE.toPx()
                    var dx = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        dx = change.position.x - down.position.x
                        change.consume()
                        if (change.changedToUp() || !change.pressed) break
                    }
                    end(dx < limit)
                }
            }
            .semantics {
                contentDescription = "Record voice message"
                role = Role.Button
                onClick { tap(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .scale(if (isRecording) 1.15f else 1f)
                .clip(CircleShape)
                .background(if (isRecording) colors.danger else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (isRecording) colors.accentContent else colors.contentMuted
            Canvas(Modifier.size(20.dp)) {
                val w = size.width
                val h = size.height
                val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                // The capsule.
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.35f, 0f),
                    size = Size(w * 0.3f, h * 0.58f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f),
                )
                // The cradle around it, and the stand.
                drawArc(
                    color = tint, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(w * 0.18f, h * 0.18f), size = Size(w * 0.64f, h * 0.5f), style = stroke,
                )
                drawLine(tint, Offset(w * 0.5f, h * 0.68f), Offset(w * 0.5f, h * 0.92f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * What the field becomes while recording (`RecordingPill`, `ComposerView.swift:448-500`):
 * a pulsing `danger` dot (0.8 s, none under reduce motion), how long
 * ([elapsedLabel], the core's), "Slide left to cancel" while held or "Recording"
 * otherwise, and, when not held, a "Discard recording" trash button.
 *
 * [elapsedMs] is given, not read from a clock, so a still rendering is fixed.
 */
@Composable
internal fun RecordingPill(
    elapsedMs: Long,
    held: Boolean,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SupermessageTheme.colors
    val reduce = reducesMotion()
    val pulse = if (reduce) 1f else {
        val transition = rememberInfiniteTransition(label = "recording-pulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "recording-pulse-alpha",
        )
        alpha
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 10.dp)
            .testTag("composer-recording-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .alpha(pulse)
                .background(colors.danger, CircleShape)
                .testTag("composer-recording-dot"),
        )
        Text(
            elapsedLabel(elapsedMs),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = colors.content,
            modifier = Modifier.testTag("composer-recording-elapsed"),
        )
        Text(
            if (held) "Slide left to cancel" else "Recording",
            style = MaterialTheme.typography.labelMedium,
            color = colors.contentMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!held) {
            IconButton(onClick = onDiscard, modifier = Modifier.size(44.dp).testTag("composer-discard-recording")) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Discard recording",
                    tint = colors.contentMuted,
                )
            }
        }
    }
}
