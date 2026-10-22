package dev.supermessage

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.DisplayRow
import dev.supermessage.kit.TimelineAnimation
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
 * ## The message menu — this list's job, the way iOS's is
 *
 * `apple/Supermessage/Timeline/TimelineCollectionView.swift:1039-1150` builds
 * one long-press menu on the *collection view*, never on `TimelineRowView`.
 * This container is that layer: a long press on a row opens [MessageMenu]
 * for it (react, Reply, Copy, Edit, Delete as [messageMenuActions] allows),
 * and a row with nothing to offer gets no gesture at all. Reply and edit are
 * reported through [onReply] and [onEdit], which the caller binds to its
 * `ReplyTarget`/`EditTarget` stores; Copy is done here through the clipboard;
 * Delete asks first ([DeleteConfirm]) and then calls [onDelete], whose `false`
 * puts "Not sent. Try again." under the row for a few seconds (no iOS copy:
 * iOS swallows a failed delete). Which row the menu is for is held by id and
 * dropped when that row leaves [rows]. A redacted row does *not* leave: the
 * core keeps a redacted item's id (`crates/supermessage-core/src/timeline.rs`),
 * so its menu stays until the room changes (the caller keys this list by room)
 * or the item drops out of the list, as on iOS. The horizontal swipes live in
 * [timelineSwipe]; they claim only a mostly horizontal drag, so they neither
 * fight this list's vertical scroll nor the long press. No avatar cache: a
 * different task's wiring.
 *
 * ## Reacting
 *
 * [onReact] is [TimelineRow]'s own `onReact: ((String) -> Unit)?`, curried
 * with *which* row here — [TimelineRow] itself has no idea which message it
 * is drawing is "this one" from the caller's point of view, only this
 * container does.
 */
@Composable
fun Timeline(
    rows: List<TimelineRowDto>,
    revision: ULong,
    typingLine: String?,
    isPaginating: Boolean,
    canPaginate: Boolean,
    onPaginate: () -> Unit,
    onMarkRead: () -> Unit,
    liveAnswer: String? = null,
    liveThought: String? = null,
    liveTools: List<LiveStore.ToolCall> = emptyList(),
    liveFinished: Boolean = false,
    onReact: (row: TimelineRowDto, key: String) -> Unit = { _, _ -> },
    onDecide: suspend (row: TimelineRowDto, answer: GateAnswer) -> Boolean = { _, _ -> false },
    onReply: (row: TimelineRowDto) -> Unit = {},
    onEdit: (row: TimelineRowDto) -> Unit = {},
    onDelete: suspend (row: TimelineRowDto) -> Boolean = { false },
    onSwipeReply: (row: TimelineRowDto) -> Unit = {},
    imageUri: (eventId: String) -> String? = { null },
    faceUri: (mxcUri: String) -> String? = { null },
    loadFace: suspend (mxcUri: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isLive = liveAnswer != null || liveThought != null || liveTools.isNotEmpty()

    // Keyed on `revision`, not `rows` itself. `TimelineStore.revision` is
    // bumped exactly once per wholesale replacement of `items` and nowhere
    // else, so it answers "did the history actually change" in the
    // constant time a `ULong` comparison costs — the whole reason it exists
    // (see `TimelineStore.kt`'s own doc on it). Keying these three off
    // `rows` instead pays a structural, element-by-element comparison of
    // the entire list on every recomposition this container has, including
    // the many-times-a-second ones a live turn's own answer/thought/tools
    // cause while `rows` itself has not moved.
    //
    // Walked in the order `rows` already arrives in — oldest first —
    // because "the row above it" means the row before it in time, not
    // before it in the inverted list this container displays.
    val continuesRun = remember(revision) {
        val out = HashMap<String, Boolean>(rows.size)
        var previous: TimelineRowDto? = null
        for (row in rows) {
            out[row.item.id] = TimelineGrouping.continuesRun(row, previous)
            previous = row
        }
        out
    }
    // The mirror of `continuesRun`: a row ends its run when the row after
    // it (in time) does not continue it. Derived from the same rule rather
    // than a second one, so the two can never disagree about where a run is.
    val endsRun = remember(revision) {
        val out = HashMap<String, Boolean>(rows.size)
        for (i in rows.indices) {
            val next = rows.getOrNull(i + 1)
            out[rows[i].item.id] = next == null || !TimelineGrouping.continuesRun(next, rows[i])
        }
        out
    }
    // Faces for readers, from the messages they have sent here. A receipt
    // carries only a user id; the room has usually already shown that
    // person's face beside something they said.
    val facesBySender = remember(revision) { readerFaces(rows) }
    val singleSpeaker = remember(revision) { TimelineGrouping.hasSingleSpeaker(rows) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The menu and the delete confirmation are about one row, held by id so
    // they survive recomposition and die with the row: a redaction, or a
    // different room's rows, removes the id from `rows` and both close.
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf<String?>(null) }
    var failedDelete by remember { mutableStateOf<String?>(null) }
    val menuRow = menuFor?.let { id -> rows.firstOrNull { it.item.id == id } }
    val confirmRow = confirmingDelete?.let { id -> rows.firstOrNull { it.item.id == id } }
    LaunchedEffect(menuFor, menuRow == null) { if (menuFor != null && menuRow == null) menuFor = null }
    LaunchedEffect(confirmingDelete, confirmRow == null) {
        if (confirmingDelete != null && confirmRow == null) confirmingDelete = null
    }
    LaunchedEffect(failedDelete) {
        if (failedDelete != null) {
            delay(DeleteFailureShownMs)
            failedDelete = null
        }
    }
    // Quote jump: a quote is tappable only when its parent is in the loaded
    // list (`TimelineCollectionView.swift:590-592`). Keyed by display id, so a
    // parent folded into a membership run is not a target.
    var highlightedId by remember { mutableStateOf<String?>(null) }
    var jumpSerial by remember { mutableStateOf(0) }
    val reduceMotion = reducesMotion()
    LaunchedEffect(highlightedId, jumpSerial) {
        if (highlightedId != null) {
            delay(QuoteHighlightMs)
            highlightedId = null
        }
    }
    val reveal = remember { TimeReveal() }
    val revealWidthPx = with(LocalDensity.current) { RevealWidthDp.dp.toPx() }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    // Collapse membership runs first — they are consecutive in the
    // chronological order `rows` already arrives in — then reverse. See the
    // class doc's "Feed order" and "Membership runs" sections.
    val displayRows = remember(revision) { TimelineGrouping.collapseMembershipRuns(rows) }
    val newestFirst = displayRows.asReversed()
    val idByEventId = remember(revision) {
        buildMap {
            for (d in displayRows) if (d is DisplayRow.Row) d.row.item.eventId?.let { put(it, d.id) }
        }
    }
    val liveAtZero = isLive && !liveFinished
    val liveAtOne = isLive && liveFinished && newestFirst.isNotEmpty()
    fun jumpTo(id: String) {
        val position = newestFirst.indexOfFirst { it.id == id }
        if (position < 0) return
        val index = position + if (liveAtZero || (liveAtOne && position >= 1)) 1 else 0
        highlightedId = id
        jumpSerial++
        scope.launch {
            // Bring it in, then nudge it to the middle of the viewport.
            if (reduceMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@launch
            val delta = (item.offset + item.size / 2f) -
                (info.viewportStartOffset + info.viewportEndOffset) / 2f
            if (reduceMotion) listState.scrollBy(delta) else listState.animateScrollBy(delta)
        }
    }

    // Exact, not a tuned threshold — `derivedStateOf` so scrolling a single
    // pixel does not recompose anything that reads this; it only changes
    // (and only then triggers what depends on it) when the boolean itself
    // flips.
    val isAtNewest by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Rule 2 (`TimelineAnimation.animates`): animate an arrival, and nothing
    // else. Latched by `revision`, the same key the grouping above uses and
    // for the same reason `remember(rows)` would not do here: this
    // composable recomposes many times a second while a live turn is being
    // written, and every one of those recompositions is an "unrelated"
    // event by Rule 2's own rule — nothing arrived. Keying on `revision`
    // means the decision is computed exactly once per wholesale replacement
    // of `items`, not once per recomposition that merely happens to follow
    // one. The decision itself has to be in hand in the very composition
    // where the new rows appear, in time to hand `Modifier.animateItem()`
    // to the row being inserted — a `LaunchedEffect` only learns of the
    // change a frame late, past the point that modifier can still catch it
    // — so it stays a plain `remember(revision)` read. `isAtNewest` is
    // already derived above, so away-ness costs nothing extra here.
    val previousRowsHolder = remember { mutableStateOf<List<TimelineRowDto>?>(null) }
    val animatesThisUpdate = remember(revision) {
        val before = previousRowsHolder.value
        val arrived = if (before == null) {
            0
        } else {
            val previousIds = before.mapTo(HashSet(before.size)) { it.item.id }
            rows.count { it.item.id !in previousIds }
        }
        TimelineAnimation.animates(
            arrived = arrived,
            had = before?.size ?: 0,
            hasApplied = before != null,
            wasAway = !isAtNewest,
        )
    }

    // The bookkeeping write is different from the decision above: it only
    // has to be in place before the *next* revision bump, not in this same
    // composition pass, so it does not share the timing requirement that
    // keeps `animatesThisUpdate` a synchronous read. Writing it here too —
    // inside the same `remember(revision)` block that just read it — would
    // mutate state during composition that composition itself had just
    // read, which forces Compose to schedule an extra recomposition to
    // settle: one wasted frame per revision bump for no behavioural gain.
    // `SideEffect` runs after this composition commits, which is still
    // strictly before the next one starts, so `before` above is never wrong
    // the next time `revision` changes.
    SideEffect {
        previousRowsHolder.value = rows
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
    // answer for itself from `revision` and `isAtNewest` alone. Keyed on
    // `revision` rather than `newestFirst` for the same reason the three
    // `remember`s above are: a revision bump is what "the history actually
    // changed" means, constant-time to compare, and not something a
    // structurally-equal-by-coincidence `newestFirst` could mask. The first
    // trigger, "on room change", is the caller's to wire: this composable is
    // never told which room it is showing.
    LaunchedEffect(revision, isAtNewest) {
        if (isAtNewest) onMarkRead()
    }

    // Grouping applied here, the row's own header rendered by TimelineRow.
    // Exhaustive over DisplayRow — no `else` — so a third variant added later
    // is a compile error here rather than a blank row.
    @Composable
    fun HistoryRow(display: DisplayRow, itemModifier: Modifier = Modifier) {
        val rowModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("row-${display.id}")
        when (display) {
            is DisplayRow.Row -> Column(modifier = itemModifier) {
                // No long-press gesture at all when the menu would be empty;
                // the swipes decide for themselves (a reply needs
                // `canReplyOrReact`, a reveal needs a time to show).
                val row = display.row
                val hasMenu = !messageMenuActions(row).isEmpty
                val time = revealedTime(row, ::clockLabel)
                val quoteJump: (() -> Unit)? = row.item.replyTo?.eventId?.let { parent ->
                    idByEventId[parent]?.let { target -> { jumpTo(target) } }
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    TimelineRow(
                        row = row,
                        now = Instant.now(),
                        continuesRun = continuesRun[row.item.id] ?: false,
                        endsRun = endsRun[row.item.id] ?: true,
                        readers = row.item.readBy.map { facesBySender[it] ?: ReaderFace(it, null, null) },
                        highlighted = highlightedId == row.item.id,
                        onQuoteTap = quoteJump,
                        attribution = if (singleSpeaker) row.senderShort else row.senderName,
                        imageUri = imageUri,
                        faceUri = faceUri,
                        loadFace = loadFace,
                        onReact = { key -> onReact(row, key) },
                        onDecide = { answer -> onDecide(row, answer) },
                        modifier = (
                            if (hasMenu) {
                                rowModifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { menuFor = row.item.id },
                                )
                            } else {
                                rowModifier
                            }
                            ).timelineSwipe(
                                row, reveal, revealWidthPx, onSwipeReply,
                                // The row merges its children, which drops the quote's own
                                // action; TalkBack reaches the jump from here.
                                extraActions = quoteJump?.let {
                                    listOf(CustomAccessibilityAction("Show original message") { it(); true })
                                } ?: emptyList(),
                            ),
                    )
                    // The time waits just past the trailing edge and slides in
                    // as the rows slide out, fading in with the pull. Hidden
                    // from accessibility: the row already says when it was
                    // sent (`TimelineChrome.swift:64-67`).
                    if (time != null && reveal.offset > 0f) {
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(RevealWidthDp.dp)
                                .offset { IntOffset((revealWidthPx - reveal.offset).roundToInt(), 0) }
                                .graphicsLayer { alpha = minOf(1f, reveal.offset / revealWidthPx) }
                                .clearAndSetSemantics { semanticsTestTag = "revealed-time" },
                        )
                    }
                }
                if (failedDelete == row.item.id) {
                    Text(
                        "Not sent. Try again.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("delete-failed"),
                    )
                }
            }

            is DisplayRow.MembershipRun ->
                SystemLine(text = display.text, modifier = itemModifier.then(rowModifier))
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
        // A test seam for Rule 2, not a visible affordance: a zero-size node
        // whose `contentDescription` names the decision this composition
        // made, the same "geometry over existence, decision over pixel"
        // idiom `RootScaffoldTest` uses for hidden panes. Whether
        // `Modifier.animateItem()` actually plays is not something a Compose
        // UI test can observe directly; this is what is asserted instead —
        // see `TimelineTest`'s own note on why.
        Box(
            Modifier
                .size(0.dp)
                .testTag("timeline-animation-decision")
                .semantics { contentDescription = if (animatesThisUpdate) "animate" else "static" },
        )
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                // Rule 4 (Task 1), made real rather than dead code: a
                // downward drag over the scroll container this list actually
                // is hides the IME. Placed ahead of the LazyColumn's own
                // internal `scrollable` in this chain so its `nestedScroll`
                // connection sees every drag as this list's parent, the same
                // ordering `KeyboardDismissTest`'s own connection-level tests
                // assume of `onPreScroll`.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .dismissKeyboardOnDrag()
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
                    item(key = newest.id) {
                        HistoryRow(newest, itemModifier = if (animatesThisUpdate) Modifier.animateItem() else Modifier)
                    }
                    item(key = "live-turn") { LiveTurnCell() }
                    items(newestFirst.drop(1), key = { it.id }) { row ->
                        HistoryRow(row, itemModifier = if (animatesThisUpdate) Modifier.animateItem() else Modifier)
                    }
                } else {
                    items(newestFirst, key = { it.id }) { row ->
                        HistoryRow(row, itemModifier = if (animatesThisUpdate) Modifier.animateItem() else Modifier)
                    }
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

        menuRow?.let { target ->
            MessageMenu(
                row = target,
                onReact = { key ->
                    haptics.play(Haptic.Selection)
                    menuFor = null
                    onReact(target, key)
                },
                onReply = { menuFor = null; onReply(target) },
                onCopy = {
                    menuFor = null
                    target.item.body?.let { clipboard.setText(AnnotatedString(it)) }
                },
                onEdit = { menuFor = null; onEdit(target) },
                onDelete = { menuFor = null; confirmingDelete = target.item.id },
                onDismiss = { menuFor = null },
            )
        }
        confirmRow?.let { target ->
            DeleteConfirm(
                onConfirm = {
                    confirmingDelete = null
                    scope.launch {
                        if (!onDelete(target)) failedDelete = target.item.id
                    }
                },
                onDismiss = { confirmingDelete = null },
            )
        }

        // A way back, and only when there is somewhere to go back from —
        // "scrolling through history with no route home is the thing that
        // makes a long room feel like a trap."
        if (!isAtNewest) {
            JumpToNewestButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * The way back to the newest message.
 *
 * Its own composable so that something can photograph it. Inline in
 * [Timeline] it was reachable only by scrolling a real list on a real
 * device — no Compose preview rendered it, so the screenshot gate had never
 * seen this button, and the change from `Text("↓")` to an icon passed 281
 * tests and 61 frames without one of them looking at it.
 */
@Composable
internal fun JumpToNewestButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .padding(end = 12.dp, bottom = 20.dp)
            .testTag("jump-to-newest")
            .semantics { contentDescription = "Jump to newest" },
    ) {
        // An icon, not `Text("↓")`.
        //
        // A text arrow is drawn in the body font and sized by the
        // body text style, so it grew with `fontScale` and outgrew
        // the button holding it — the one control on this screen
        // whose whole job is to be a fixed target in the corner. An
        // `Icon` is 24dp regardless.
        //
        // `KeyboardArrowDown` because `material-icons-core` carries
        // 49 icons and a plain `ArrowDownward` is not one of them;
        // that lives in `-extended`, which is the artifact famous for
        // what it adds to a release build. iOS keeps `arrow.down`,
        // so the two platforms differ by a chevron against an arrow —
        // recorded in docs/platform-parity.md rather than resolved by
        // giving iOS the weaker glyph to match a library limit here.
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            // The button already carries "Jump to newest"; naming the
            // glyph again would have a screen reader say it twice.
            contentDescription = null,
        )
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

/** How long "Not sent. Try again." stays under a row whose delete failed. */
private const val DeleteFailureShownMs = 4_000L
