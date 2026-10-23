package dev.supermessage

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.RoomStatus
import uniffi.supermessage_ffi.roomStatusWord

/** Whether the status dot is animating, so a test can read it (the pulse has no other trace). */
internal val PulsingKey = SemanticsPropertyKey<Boolean>("Pulsing")
internal var SemanticsPropertyReceiver.pulsing by PulsingKey

/**
 * What an agent's room is doing, as a dot and the core's word for it
 * (`RoomScreen.swift:222-266`). The word is `roomStatusWord`'s; the colours
 * are the design language's. Working pulses (opacity only) unless motion is
 * reduced. The accessibility label is the word alone.
 */
@Composable
fun StatusPill(status: RoomStatus, compact: Boolean = true, modifier: Modifier = Modifier) {
    val colors = SupermessageTheme.colors
    val word = roomStatusWord(status)
    val needsYou = status == RoomStatus.NEEDS_YOU
    Row(
        modifier = modifier
            .testTag("status-pill")
            .semantics(mergeDescendants = true) { contentDescription = word }
            .background(if (needsYou) colors.signalSoft else colors.surfaceRaised, CircleShape)
            .padding(horizontal = if (compact) 7.dp else 10.dp, vertical = if (compact) 1.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusDot(status, if (compact) 6 else 8)
        Text(
            text = word,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = if (needsYou) colors.signal else colors.contentMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusDot(status: RoomStatus, sizeDp: Int) {
    val colors = SupermessageTheme.colors
    val colour: Color = when (status) {
        RoomStatus.WORKING, RoomStatus.ACTIVE -> colors.ok
        RoomStatus.NEEDS_YOU -> colors.signal
        RoomStatus.IDLE -> colors.contentFaint
        RoomStatus.QUIET -> colors.border
    }
    val pulses = status == RoomStatus.WORKING && !reducesMotion()
    val alpha = if (pulses) {
        val alpha by rememberInfiniteTransition(label = "status-pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
            label = "status-pulse-alpha",
        )
        alpha
    } else {
        1f
    }
    Box(
        Modifier
            .testTag("status-dot")
            .semantics { pulsing = pulses }
            .size(sizeDp.dp)
            .alpha(alpha)
            .background(colour, CircleShape),
    )
}
