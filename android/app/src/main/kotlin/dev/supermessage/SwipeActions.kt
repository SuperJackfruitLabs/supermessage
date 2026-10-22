package dev.supermessage

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * One thing a swipe can do (`RoomListView.swift:260-327`). [run] says whether
 * it landed: a swipe that did nothing must not play the success haptic or
 * change a label.
 */
class SwipeAction(
    val label: String,
    val tint: Color,
    val destructive: Boolean = false,
    val run: suspend () -> Boolean,
)

private enum class SwipeValue { Closed, Trailing, Leading }

/** The width of one revealed trailing button. */
private val ActionWidth = 72.dp

/**
 * Swipe a row sideways for its actions, as iOS's `swipeActions` does.
 *
 * - **Trailing** buttons sit behind the row, [ActionWidth] each, and there is
 *   no full swipe: the row stops once they are all revealed.
 * - A **leading** pull past half the width runs [leading] and settles closed.
 * - Every action is also a TalkBack custom action on the row. This function is
 *   the one writer of those: [content] receives the modifier carrying them and
 *   must apply it to the node that is the row (a second `customActions` on
 *   that node would replace this list, not add to it).
 * - An action that returns `true` plays the success haptic, after it ran.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeActionsRow(
    leading: SwipeAction?,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable (rowSemantics: Modifier) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestLeading = rememberUpdatedState(leading)
    val latestTrailing = rememberUpdatedState(trailing)
    val reduce = reducesMotion()
    val density = LocalDensity.current

    val state = remember { AnchoredDraggableState(SwipeValue.Closed) }
    val fling = androidx.compose.foundation.gestures.AnchoredDraggableDefaults.flingBehavior(
        state = state,
        positionalThreshold = { distance -> distance * 0.5f },
        animationSpec = motion(reduce, androidx.compose.animation.core.spring()),
    )

    suspend fun perform(action: SwipeAction) {
        // Ask first, play the haptic for what landed, then close.
        if (action.run()) haptics.play(Haptic.Success)
        state.animateTo(SwipeValue.Closed)
    }

    // A pull that reached the leading anchor is the leading action.
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { value ->
            if (value == SwipeValue.Leading) latestLeading.value?.let { perform(it) }
        }
    }

    val swipeable = leading != null || trailing.isNotEmpty()
    val actions = remember(leading, trailing) { listOfNotNull(leading) + trailing }
    val rowSemantics = if (swipeable) {
        Modifier.semantics {
            customActions = actions.map { action ->
                CustomAccessibilityAction(action.label) {
                    scope.launch { perform(action) }
                    true
                }
            }
        }
    } else {
        Modifier
    }

    val trailingPx = with(density) { (ActionWidth * trailing.size).toPx() }
    var width by remember { mutableIntStateOf(0) }
    val hasLeading = leading != null
    val hasTrailing = trailing.isNotEmpty()

    // The anchors follow everything that shapes them: the measured width, which
    // actions exist, and how wide the revealed set is. (Keyed here rather than
    // rebuilt only when the pixel size changes, which would leave them stale
    // when an action appears or goes without the row resizing.)
    LaunchedEffect(width, hasLeading, hasTrailing, trailingPx) {
        // Before the first measure the width is 0, and Leading at 0 would sit
        // on top of Closed and settle there, running the action.
        if (width <= 0) return@LaunchedEffect
        state.updateAnchors(
            DraggableAnchors {
                SwipeValue.Closed at 0f
                if (hasTrailing) SwipeValue.Trailing at -trailingPx
                if (hasLeading) SwipeValue.Leading at width.toFloat()
            },
        )
    }
    SwipeRowFrame(
        offset = if (state.offset.isNaN()) 0f else state.offset,
        leading = leading,
        trailing = trailing,
        modifier = modifier.onSizeChanged { size -> width = size.width },
        onTrailing = { action -> scope.launch { perform(action) } },
        slide = { if (swipeable) it.anchoredDraggable(state, Orientation.Horizontal, flingBehavior = fling) else it },
    ) {
        content(rowSemantics)
    }
}

/**
 * The row slid to [offset] (negative: left) over its revealed buttons, or over
 * the leading tint when positive. Drawing only: [SwipeActionsRow] owns the
 * gesture, and previews call this with a fixed offset.
 */
@Composable
internal fun SwipeRowFrame(
    offset: Float,
    leading: SwipeAction?,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    onTrailing: (SwipeAction) -> Unit = {},
    slide: (Modifier) -> Modifier = { it },
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth().clipToBounds()) {
        if (offset < -0.5f) {
            Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                for (action in trailing) {
                    Box(
                        Modifier
                            .width(ActionWidth)
                            .fillMaxHeight()
                            .background(if (action.destructive) SupermessageTheme.colors.danger else action.tint)
                            .clickable { onTrailing(action) }
                            .testTag("swipe-${action.label}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = SupermessageTheme.colors.accentContent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        if (offset > 0.5f && leading != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(leading.tint)
                    .clearAndSetSemantics { }
                    .testTag("swipe-leading-${leading.label}"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    leading.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = SupermessageTheme.colors.accentContent,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .let(slide)
                .background(SupermessageTheme.colors.surface),
        ) {
            content()
        }
    }
}

/** The width of a fully revealed trailing set, for previews. */
internal fun revealedTrailingPx(count: Int, density: androidx.compose.ui.unit.Density): Float =
    with(density) { (ActionWidth * count).toPx() }
