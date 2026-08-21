package dev.supermessage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.DisplayRow
import dev.supermessage.kit.TimelineGrouping
import dev.supermessage.kit.stores.LiveStore
import java.time.Instant
import kotlinx.coroutines.launch
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * The scroll container: an **inverted** `LazyColumn`.
 *
 * Mirrors `apple/Supermessage/Timeline/TimelineCollectionView.swift`, whose
 * header comment is the reasoning this follows, not merely the shape.
 * `ScrollView` + `LazyVStack` in natural order needs three separate
 * mechanisms to behave like a conversation — an anchor, a scroll-position
 * binding and a `ScrollViewReader` — and nothing arbitrates between them.
 * iOS dropped to an inverted `UICollectionView` for exactly this screen;
 * Compose does not need the UIKit escape hatch, because `LazyColumn` already
 * takes `reverseLayout`, but the reasoning it inherits is the same:
 *
 * - *Am I at the newest message?* is `firstVisibleItemIndex == 0 &&
 *   firstVisibleItemScrollOffset == 0` — exact, not a tuned threshold.
 * - *A message arrives.* It lands at index 0, off the far end of the scroll.
 *   Nothing on screen moves, so there is nothing to correct.
 * - *History prepends.* It lands at the tail, also off the far end. The
 *   reading position is untouched.
 *
 * **And a room opens at its newest message by construction**, because that
 * is where a fresh `LazyListState` already rests — no scroll-to-bottom on
 * load, nothing to land wrongly.
 *
 * ## Feed order
 *
 * [rows] arrives **oldest first** — the same chronological order
 * `TimelineStore.items` holds, built by folding `PushFront` (older history)
 * and `PushBack`/`Append` (new arrivals) over the list (`DiffApply.kt`).
 * `reverseLayout` needs index 0 to be the *newest* message, so this reverses
 * before handing rows to `items(...)` — the direct Kotlin counterpart of
 * `TimelineCollectionView.swift`'s explicit `display.reversed()` before it
 * applies its own snapshot.
 *
 * ## Grouping — [TimelineRow]'s stated gap, closed here
 *
 * [TimelineRow] takes `continuesRun`/`attribution` as parameters precisely
 * because, in its own words, that decision is "chosen by the list that can
 * see every row; a single row cannot." This container is where the whole
 * list is in hand, so it is where [TimelineGrouping.continuesRun] and
 * [TimelineGrouping.hasSingleSpeaker] actually run — mirroring
 * `TimelineCollectionView.swift`'s `rowsById`/`singleSpeaker`. A row's
 * attribution is its short name when one agent does all the talking in the
 * room, and its full name otherwise; a row continues the run above it when
 * [TimelineGrouping.continuesRun] says so, computed walking [rows] in the
 * chronological order it already arrives in (not [newestFirst]'s reversed
 * order — "the row above it" means the row before it in time).
 *
 * ## The live turn's place in the list
 *
 * Mirrors `TimelineCollectionView.swift`'s `snapshot(entries:...)`: a turn
 * **in progress** ([liveFinished] false) is the newest thing in the room, so
 * it sits at index 0 of the inverted list — below every history row. A
 * turn that has **finished** sits *above* the message it produced (one
 * position further from index 0), because "the reasoning and the tool
 * calls happened before the answer, and drawing them under it says they
 * happened after." [LiveTurn] itself decides whether there is anything to
 * show ([LiveStore.isLive]); nothing here duplicates that check to decide
 * *whether* to render it, only *where*.
 *
 * ## Membership runs — collapsed after grouping, before reversing
 *
 * [rows] is fed through [TimelineGrouping.collapseMembershipRuns] before
 * anything else touches it, so a run of consecutive "updated their
 * membership" rows becomes one [DisplayRow.MembershipRun] rather than one
 * line per row — the failure [TimelineGrouping.collapseMembershipRuns]'s own
 * doc names, found on a device that drew eight of them in a row. Collapsing
 * happens **before** [newestFirst]'s `asReversed()`: runs are consecutive in
 * the chronological order [rows] already arrives in, so collapsing first and
 * reversing the (shorter) result keeps the newest-first contract with one
 * less thing to reason about than reversing first would.
 *
 * ## What this does not do
 *
 * No swipe-to-reply, no avatar cache: neither is in this composable's
 * signature, and inventing parameters for them here would be guessing at a
 * wiring this task was not given.
 */
@Composable
fun Timeline(
    rows: List<TimelineRowDto>,
    typingLine: String?,
    isPaginating: Boolean,
    canPaginate: Boolean,
    onPaginate: () -> Unit,
    onMarkRead: () -> Unit,
    liveAnswer: String? = null,
    liveThought: String? = null,
    liveTools: List<LiveStore.ToolCall> = emptyList(),
    liveFinished: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isLive = liveAnswer != null || liveThought != null || liveTools.isNotEmpty()

    // Walked in the order `rows` already arrives in — oldest first — because
    // "the row above it" means the row before it in time, not before it in
    // the inverted list this container displays.
    val continuesRun = remember(rows) {
        val out = HashMap<String, Boolean>(rows.size)
        var previous: TimelineRowDto? = null
        for (row in rows) {
            out[row.item.id] = TimelineGrouping.continuesRun(row, previous)
            previous = row
        }
        out
    }
    val singleSpeaker = remember(rows) { TimelineGrouping.hasSingleSpeaker(rows) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Collapse membership runs first — they are consecutive in the
    // chronological order `rows` already arrives in — then reverse. See the
    // class doc's "Feed order" and "Membership runs" sections.
    val displayRows = remember(rows) { TimelineGrouping.collapseMembershipRuns(rows) }
    val newestFirst = displayRows.asReversed()

    // Exact, not a tuned threshold — `derivedStateOf` so scrolling a single
    // pixel does not recompose anything that reads this; it only changes
    // (and only then triggers what depends on it) when the boolean itself
    // flips.
    val isAtNewest by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // `rememberUpdatedState` rather than keying `derivedStateOf` itself off
    // these: the derived calculation must always read the *latest* values,
    // but it only needs recreating once, not every time `rows` changes.
    val latestLastIndex = rememberUpdatedState(newestFirst.lastIndex)
    val latestCanPaginate = rememberUpdatedState(canPaginate)
    val latestIsPaginating = rememberUpdatedState(isPaginating)

    // Reaching the older end. Inverted, so "older" is the tail of the list
    // this container was handed, which — per `derivedStateOf`'s point above
    // — must not itself become a recomposition trigger for every pixel of
    // scroll, only when the reader has actually approached it.
    val wantsOlderHistory by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            val lastIndex = latestLastIndex.value
            lastIndex >= 0 && lastVisible != null &&
                lastVisible >= lastIndex - PaginationLookahead &&
                latestCanPaginate.value && !latestIsPaginating.value
        }
    }
    LaunchedEffect(wantsOlderHistory) {
        if (wantsOlderHistory) onPaginate()
    }

    // Marking read: the second of `TimelineView.swift`'s two triggers — "on
    // any history change while at the newest end", which this container can
    // answer for itself from `rows` and `isAtNewest` alone. The first
    // trigger, "on room change", is the caller's to wire: this composable is
    // never told which room it is showing.
    LaunchedEffect(newestFirst, isAtNewest) {
        if (isAtNewest) onMarkRead()
    }

    // Grouping applied here, the row's own header rendered by TimelineRow.
    // Exhaustive over DisplayRow — no `else` — so a third variant added later
    // is a compile error here rather than a blank row.
    @Composable
    fun HistoryRow(display: DisplayRow) {
        val rowModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("row-${display.id}")
        when (display) {
            is DisplayRow.Row ->
                TimelineRow(
                    row = display.row,
                    now = Instant.now(),
                    continuesRun = continuesRun[display.row.item.id] ?: false,
                    attribution = if (singleSpeaker) display.row.senderShort else display.row.senderName,
                    modifier = rowModifier,
                )

            is DisplayRow.MembershipRun ->
                SystemLine(text = display.text, modifier = rowModifier)
        }
    }

    @Composable
    fun LiveTurnCell() {
        LiveTurn(
            answer = liveAnswer,
            thought = liveThought,
            tools = liveTools,
            finished = liveFinished,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("timeline-list"),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // A turn in progress is the newest thing in the room: index 0,
                // below every history row. See the class doc's "The live
                // turn's place in the list".
                if (isLive && !liveFinished) {
                    item(key = "live-turn") { LiveTurnCell() }
                }

                if (isLive && liveFinished && newestFirst.isNotEmpty()) {
                    // A finished turn sits above the message it produced —
                    // "the reasoning and the tool calls happened before the
                    // answer". The newest message stays at the very bottom.
                    val newest = newestFirst.first()
                    item(key = newest.id) { HistoryRow(newest) }
                    item(key = "live-turn") { LiveTurnCell() }
                    items(newestFirst.drop(1), key = { it.id }) { row -> HistoryRow(row) }
                } else {
                    items(newestFirst, key = { it.id }) { row -> HistoryRow(row) }
                }
                if (isPaginating) {
                    item(key = "pagination-spinner") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).testTag("pagination-spinner"),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }

            if (typingLine != null) {
                Text(
                    typingLine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("typing-line"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // A way back, and only when there is somewhere to go back from —
        // "scrolling through history with no route home is the thing that
        // makes a long room feel like a trap."
        if (!isAtNewest) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 20.dp)
                    .testTag("jump-to-newest")
                    .semantics { contentDescription = "Jump to newest" },
            ) {
                Text("↓", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * How many rows of lookahead before the older end counts as "reached".
 *
 * Not the exact last index: a reader almost never lands on the knife edge
 * of the very last row, so gating on that alone would leave most of a
 * room's history unreachable. A handful of rows of lookahead is what makes
 * pagination fire before the reader hits a visible dead stop.
 */
private const val PaginationLookahead = 3
