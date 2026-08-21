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
 * ## What this does not do
 *
 * No live turn, no membership-run collapsing, no swipe-to-reply, no avatar
 * cache: none of those are in this composable's signature, and inventing
 * parameters for them here would be guessing at a wiring this task was not
 * given. Grouping ([continuesRun]/attribution) is [TimelineRow]'s own stated
 * gap — "chosen by the list that can see every row" — but nothing in this
 * task's brief or test list asks this container to compute it, so each row
 * renders with [TimelineRow]'s own fallback (`row.senderName`, no run
 * collapsing) rather than this task inventing an untested wiring for it.
 */
@Composable
fun Timeline(
    rows: List<TimelineRowDto>,
    typingLine: String?,
    isPaginating: Boolean,
    canPaginate: Boolean,
    onPaginate: () -> Unit,
    onMarkRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Newest first: see the class doc's "Feed order" section.
    val newestFirst = rows.asReversed()

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
                items(newestFirst, key = { it.item.id }) { row ->
                    TimelineRow(
                        row = row,
                        now = Instant.now(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("row-${row.item.id}"),
                    )
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
