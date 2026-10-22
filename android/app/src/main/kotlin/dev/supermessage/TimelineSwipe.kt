package dev.supermessage

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * The two horizontal gestures of the timeline, as iOS has them
 * (`TimelineCollectionView.swift:1203-1258`):
 *
 * - a **rightward** swipe on a row that `canReplyOrReact` starts a reply
 *   ("Reply"); on a row that cannot be replied to it does nothing;
 * - a **leftward** swipe on any row slides every row left together and shows
 *   its time at the trailing edge, rubber-banded past [RevealWidthDp]
 *   (`TimelineChrome.swift:24`), springing back on release.
 *
 * The drag is claimed only when it is mostly horizontal (`abs(dx) > abs(dy) *
 * 2` once past touch slop). Anything else is left alone, so the list's own
 * vertical scroll, the long-press menu and a tap all still work.
 */
internal const val RevealWidthDp = 68

/** How far a rightward drag must go, released, to start a reply. */
internal const val ReplyThresholdDp = 64

/** The leftward pull shared by every row: how far they are slid, and whether a finger is on it. */
@Stable
class TimeReveal {
    var offset: Float by mutableFloatStateOf(0f)
    var tracking: Boolean by mutableStateOf(false)
}

/** `TimelineCollectionView.swift:1236-1239`: one-to-one up to [width], a fifth of it beyond. */
internal fun rubberBand(pulled: Float, width: Float): Float =
    if (pulled <= width) pulled else width + (pulled - width) * 0.2f

/**
 * The clock a row shows when the list is pulled left, or null for the rows
 * that stay put: the date divider, the unread marker, system lines and
 * nothing (`TimelineCollectionView.swift:664-669`).
 */
internal fun revealedTime(row: TimelineRowDto, clock: (ULong) -> String): String? {
    when (row.view) {
        ItemView.DateDivider, ItemView.UnreadMarker, is ItemView.System, ItemView.None -> return null
        else -> Unit
    }
    return row.item.timestampMs?.let(clock)
}

private enum class SwipeMode { Undecided, Reply, Reveal, Ignored }

fun Modifier.timelineSwipe(
    row: TimelineRowDto,
    reveal: TimeReveal,
    revealWidthPx: Float,
    onSwipeReply: (TimelineRowDto) -> Unit,
): Modifier = composed {
    val reduce = reducesMotion()
    val replyPx = with(LocalDensity.current) { ReplyThresholdDp.dp.toPx() }
    var replyOffset by remember { mutableFloatStateOf(0f) }
    val revealable = revealedTime(row) { "" } != null
    val canReply = row.canReplyOrReact
    val latestRow = rememberUpdatedState(row)
    val latestReply = rememberUpdatedState(onSwipeReply)
    val springBack = motion(reduce, spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow))

    this
        .semantics {
            if (canReply) {
                customActions = listOf(
                    CustomAccessibilityAction("Reply") { latestReply.value(latestRow.value); true },
                )
            }
        }
        .pointerInput(revealable, canReply, revealWidthPx, replyPx, reduce) {
            coroutineScope {
                var release: Job? = null
                while (true) {
                    var total = Offset.Zero
                    var mode = SwipeMode.Undecided
                    var replied = false
                    awaitPointerEventScope {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        release?.cancel()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                replied = mode == SwipeMode.Reply && total.x >= replyPx
                                break
                            }
                            if (mode == SwipeMode.Ignored || (mode == SwipeMode.Undecided && change.isConsumed)) break
                            total += change.positionChange()
                            if (mode == SwipeMode.Undecided && total.getDistance() >= viewConfiguration.touchSlop) {
                                val horizontal = abs(total.x) > abs(total.y) * 2
                                mode = when {
                                    horizontal && total.x < 0 && revealable -> SwipeMode.Reveal
                                    horizontal && total.x > 0 && canReply -> SwipeMode.Reply
                                    else -> SwipeMode.Ignored
                                }
                            }
                            when (mode) {
                                SwipeMode.Reply -> {
                                    change.consume()
                                    replyOffset = rubberBand(max(total.x, 0f), replyPx)
                                }
                                SwipeMode.Reveal -> {
                                    change.consume()
                                    reveal.tracking = true
                                    reveal.offset = rubberBand(max(-total.x, 0f), revealWidthPx)
                                }
                                else -> Unit
                            }
                        }
                    }
                    if (replied) latestReply.value(latestRow.value)
                    when (mode) {
                        SwipeMode.Reply -> release = launch {
                            animate(replyOffset, 0f, animationSpec = springBack) { v, _ -> replyOffset = v }
                        }
                        SwipeMode.Reveal -> release = launch {
                            try {
                                animate(reveal.offset, 0f, animationSpec = springBack) { v, _ -> reveal.offset = v }
                            } finally {
                                reveal.tracking = false
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
        .offset { IntOffset((replyOffset - (if (revealable) reveal.offset else 0f)).roundToInt(), 0) }
}
