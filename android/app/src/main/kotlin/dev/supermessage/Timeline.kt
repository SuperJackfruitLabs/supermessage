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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.DisplayRow
import dev.supermessage.kit.TimelineAnimation
import dev.supermessage.kit.displayRows
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import dev.supermessage.kit.HistoryGlide
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.withFrameNanos
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_core.TurnEnding
import uniffi.supermessage_core.TurnSummary
import java.time.Instant
import kotlinx.coroutines.launch
import uniffi.supermessage_core.TimelineRow as TimelineRowDto
import uniffi.supermessage_core.TurnStep

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
 * ## Grouping — the core's, drawn here
 *
 * Whether a row continues the run above it, ends its run or shows the
 * bridge's runtime suffix is decided once, in the core (`timeline_grouping.rs`),
 * and arrives on every row as `continuesRun`, `endsRun` and `showsRuntime`.
 * [TimelineRow] takes them as parameters; this container only reads them off
 * the row. (`hidesQuote` — a reply directly under its parent — is read by
 * [TimelineRow], which then draws no quote, and here, which then offers no
 * jump.) Rows the core marks `hidden` get
 * no entry ([displayRows]). A row's attribution
 * is its full name where two or more peers speak (`showsRuntime`), and its
 * short name otherwise.
 *
 * ## The live turn's place in the list
 *
 * Mirrors `TimelineCollectionView.swift`'s `snapshot(entries:...)`. A turn
 * **in progress** is one card ([LiveTurnCard]) at index 0 of the inverted
 * list, below every history row, keyed `live-turn` so it is never remounted
 * as its text grows. A **finished** turn has no cell: its record is drawn
 * inside the answer's own row ([WhatIDid], as the row's prelude), on the
 * newest non-own row whose view is a bubble ([recordAnchorId]). What is
 * live comes from the core's turn record, so the card, the glide and the
 * haptic read one state: [liveSummary].
 *
 * ## The history glide
 *
 * While a turn streams, the card grows and the list lays out **without
 * animation** (the streaming cell's resize never animates). The history is
 * then drawn where it was and eased to where it is, together, by a
 * transform alone: 0.32 s on `(0.25, 0.1, 0.25, 1)`, as iOS does
 * (`TimelineCollectionView.swift:187`, `:226`, [HistoryGlide]). Only at the
 * newest message (scrolled back, the list already keeps the reader's place,
 * so a quote jump is never pulled back by the growing card), and not under
 * Reduce Motion.
 *
 * ## Membership runs and stretches — the core groups, the reader opens
 *
 * [rows] is walked by [displayRows] before anything else touches it. A run of
 * consecutive membership rows becomes one [DisplayRow.MembershipRun]; a
 * stretch of three or more runs becomes one summary line the reader taps to
 * open ([expanded], by stretch id, saved per [roomId]). This happens
 * **before** [newestFirst]'s `asReversed()`: runs are consecutive in the
 * chronological order [rows] already arrives in, so collapsing first and
 * reversing the (shorter) result keeps the newest-first contract.
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
    isPaginating: Boolean,
    canPaginate: Boolean,
    onPaginate: () -> Unit,
    onMarkRead: () -> Unit,
    liveSummary: TurnSummary? = null,
    liveSteps: List<TurnStep> = emptyList(),
    liveThought: String? = null,
    liveAnswer: String? = null,
    liveName: String = "Agent",
    /** The clock the card's running elapsed time reads; a preview pins it so its frame is the same every time. */
    liveNowMs: () -> Long = System::currentTimeMillis,
    onReact: (row: TimelineRowDto, key: String) -> Unit = { _, _ -> },
    onDecide: suspend (row: TimelineRowDto, answer: GateAnswer) -> Boolean = { _, _ -> false },
    onReply: (row: TimelineRowDto) -> Unit = {},
    onEdit: (row: TimelineRowDto) -> Unit = {},
    onDelete: suspend (row: TimelineRowDto) -> Boolean = { false },
    onSwipeReply: (row: TimelineRowDto) -> Unit = {},
    onReport: (row: TimelineRowDto) -> Unit = {},
    reportingRowId: String? = null,
    imageUri: (eventId: String) -> String? = { null },
    faceUri: (mxcUri: String) -> String? = { null },
    loadFace: suspend (mxcUri: String) -> Unit = {},
    roomId: String = "",
    /** Height the pane floats over the list's bottom (the dock and the pill): content padding, so nothing is covered. */
    bottomReserve: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    // Per list, so per room (the pane keys the list by room), and above the
    // lazy items so a recycled row cannot forget what this device answered.
    // An answer that landed also tells the widgets, which stop offering it.
    val decisionAnswers = rememberDecisionAnswers(
        onAnswered = { eventId, optionId -> AppCore.decisionAnswered(roomId, eventId, optionId) },
    )
    // One state for everything live: the core's turn record (and the answer
    // being written). `finished` is the core's `endedBy`, however it ended.
    val isLive = liveSummary != null || liveAnswer != null
    val liveFinished = liveSummary?.endedBy != null
    val streaming = isLive && !liveFinished

    // Faces for readers, from the messages they have sent here. A receipt
    // carries only a user id; the room has usually already shown that
    // person's face beside something they said.
    val facesBySender = remember(revision) { readerFaces(rows) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The menu and the delete confirmation are about one row, held by id so
    // they survive recomposition and die with the row: a redaction, or a
    // different room's rows, removes the id from `rows` and both close.
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf<String?>(null) }
    var failedDelete by remember { mutableStateOf<String?>(null) }
    // Focus goes back to the row a report was started from once its sheet
    // closes (a keyboard or TalkBack reader would otherwise land at the top).
    // A reported message is gone by then, and the request quietly finds no
    // one to focus.
    val reportReturn = remember { FocusRequester() }
    var reportOrigin by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reportingRowId) {
        if (reportingRowId != null) {
            reportOrigin = reportingRowId
        } else if (reportOrigin != null) {
            runCatching { reportReturn.requestFocus() }
            reportOrigin = null
        }
    }
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
    // Whether a pull is showing at all. Rows read this, not `reveal.offset`:
    // the offset changes on every frame of a drag and of the spring back, and
    // a row that read it in composition recomposed on each one. The offset
    // itself is read only in layout and draw lambdas below.
    val revealing by remember { derivedStateOf { reveal.offset > 0f } }
    val revealWidthPx = with(LocalDensity.current) { RevealWidthDp.dp.toPx() }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    // The stretches the reader has opened. Per room: another room's stretch
    // ids are not this room's, and the same stretch should survive rotation.
    var expanded by rememberSaveable(roomId, stateSaver = ExpandedStretches) {
        mutableStateOf(emptySet<String>())
    }

    // The core's grouping, walked in the chronological order `rows` already
    // arrives in, then reversed. Keyed on `revision` (a constant-time
    // comparison; see `TimelineStore.revision`) and on what the reader opened.
    val entries = remember(revision, expanded) { displayRows(rows, expanded) }
    val newestFirst = entries.asReversed()
    val idByEventId = remember(revision, expanded) {
        buildMap {
            for (d in entries) if (d is DisplayRow.Row) d.row.item.eventId?.let { put(it, d.id) }
        }
    }
    val anchorId = remember(revision) { recordAnchorId(rows) }

    // A turn finishing in the room on screen is a success haptic, once, on the
    // change to finished (`TimelineView.swift:143`, `!was && now`). A list that
    // opens onto an already-finished turn starts with `finished` remembered,
    // and a turn that goes away without finishing never flips it. A turn the
    // core gave up on (timed out) did not succeed: no success haptic.
    val succeeded = liveSummary?.endedBy.let { it == TurnEnding.DONE || it == TurnEnding.REPLY }
    var wasFinished by remember { mutableStateOf(succeeded) }
    LaunchedEffect(succeeded) {
        if (!wasFinished && succeeded) haptics.play(Haptic.Success)
        wasFinished = succeeded
    }
    fun jumpTo(id: String) {
        val position = newestFirst.indexOfFirst { it.id == id }
        if (position < 0) return
        val index = position + if (streaming) 1 else 0
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
    val arrivals = remember { Arrivals() }
    val animatesThisUpdate = remember(revision) {
        val before = previousRowsHolder.value
        val previousIds = before?.mapTo(HashSet(before.size)) { it.item.id }.orEmpty()
        // Every new item counts, whatever it is: a membership line is as much
        // an arrival as a message. The limit is on what arrived, never on what
        // is left once filtered to messages.
        val added = rows.withIndex().filter { it.value.item.id !in previousIds }
        // Where they landed. New items after the newest one already shown are
        // arrivals; any before it are history being prepended (pagination) or
        // a resync, and never animate however few there are.
        val lastKnown = rows.indexOfLast { it.item.id in previousIds }
        val atNewestEnd = added.filter { it.index > lastKnown }
        val animates = TimelineAnimation.animates(
            arrived = added.size,
            had = before?.size ?: 0,
            hasApplied = before != null,
            wasAway = !isAtNewest,
            prepended = atNewestEnd.size != added.size,
        )
        arrivals.pending = if (animates) atNewestEnd.mapTo(HashSet()) { it.value.item.id } else emptySet()
        animates
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

    // The history glide's state: the offset every item is drawn at, read only
    // in the draw phase (a layer block), never in composition or layout.
    val glideDriver = remember { GlideDriver() }
    LaunchedEffect(glideDriver) { glideDriver.run() }
    // A glide belongs to the room it started in: another room's history starts at rest.
    LaunchedEffect(roomId) { glideDriver.reset() }
    // No reset when the turn ends: a glide under way finishes, so the history
    // never snaps by what was left of it.
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
                // A quote the core hides (D6, `hidesQuote`) is not drawn, so it has no jump.
                val quoteJump: (() -> Unit)? = row.item.replyTo?.eventId?.takeIf { !row.hidesQuote }?.let { parent ->
                    idByEventId[parent]?.let { target -> { jumpTo(target) } }
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    TimelineRow(
                        row = row,
                        now = Instant.now(),
                        continuesRun = row.continuesRun,
                        endsRun = row.endsRun,
                        readers = row.item.readBy.map { facesBySender[it] ?: ReaderFace(it, null, null) },
                        highlighted = highlightedId == row.item.id,
                        onQuoteTap = quoteJump,
                        attribution = if (row.showsRuntime) row.senderName else row.senderShort,
                        prelude = liveSummary?.recordLabel?.takeIf { liveFinished && row.item.id == anchorId }?.let { label ->
                            { WhatIDid(label = label, thought = liveThought, steps = liveSteps) }
                        },
                        imageUri = imageUri,
                        faceUri = faceUri,
                        loadFace = loadFace,
                        onReact = { key -> onReact(row, key) },
                        onDecide = { answer -> onDecide(row, answer) },
                        decisionAnswers = decisionAnswers,
                        modifier = (
                            if (hasMenu) {
                                rowModifier
                                    .then(
                                        if (reportOrigin == row.item.id) Modifier.focusRequester(reportReturn) else Modifier,
                                    )
                                    .combinedClickable(
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
                    if (time != null && revealing) {
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            // The one failure text that was silent to TalkBack.
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("delete-failed"),
                    )
                }
            }

            is DisplayRow.MembershipRun ->
                if (display.isStretch) {
                    StretchLine(
                        text = display.text,
                        onOpen = { expanded = expanded + display.id },
                        modifier = itemModifier.then(rowModifier),
                    )
                } else {
                    SystemLine(text = display.text, modifier = itemModifier.then(rowModifier))
                }
        }
    }

    // What a row does as it arrives and as its neighbours move. While a turn
    // streams, history rows get none of it: their placement is the glide's,
    // a transform that follows the card's growth (below), and a placement
    // spring on top of it would be a second motion for the same move.
    @Composable
    fun androidx.compose.foundation.lazy.LazyItemScope.rowMotion(id: String): Modifier {
        if (!animatesThisUpdate || streaming) return Modifier.glided(glideDriver)
        return Modifier
            .animateItem(
                fadeInSpec = null,
                placementSpec = if (reduceMotion) null else spring<androidx.compose.ui.unit.IntOffset>(),
                fadeOutSpec = motion(reduceMotion, tween<Float>(150)),
            )
            .arrivalRise(arrivals, id, reduceMotion)
            .glided(glideDriver)
    }

    @Composable
    fun LiveTurnCell() {
        // Plain, not state: written while the card is measured.
        val lastHeight = remember { IntArray(1) }
        LiveTurnCard(
            summary = liveSummary,
            steps = liveSteps,
            thought = liveThought,
            answer = liveAnswer,
            name = liveName,
            nowMs = liveNowMs,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // Detected while the card is *measured*, not in `onSizeChanged`: that
                // callback fires after the list has already placed its rows, which
                // is a frame too late for the offset to reach them. Measure runs
                // before every row's placement, which reads the offset.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val grew = placeable.height - lastHeight[0]
                    val first = lastHeight[0] == 0
                    lastHeight[0] = placeable.height
                    // Layout must not subscribe to the list's scroll state.
                    val atNewest = Snapshot.withoutReadObservation {
                        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                    }
                    // The card grew: the list lays out without animation, so the
                    // history is put back where it was and glides. Not on first
                    // appearance, and only at the newest message: scrolled back the
                    // list keeps the reader's place.
                    if (!first && grew > 0.5f && streaming && !reduceMotion && atNewest) glideDriver.grew(grew.toFloat())
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .glided(glideDriver),
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
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + bottomReserve),
            ) {
                // A turn in progress is the newest thing in the room: index 0,
                // below every history row. A finished turn has no cell here;
                // its record is in the answer's row.
                if (streaming) {
                    item(key = "live-turn") { LiveTurnCell() }
                }
                items(newestFirst, key = { it.id }) { row ->
                    HistoryRow(row, itemModifier = rowMotion(row.id))
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
                onReport = { menuFor = null; onReport(target) },
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
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = bottomReserve),
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


/**
 * The rows that arrived in the latest update and have not yet played their
 * entrance, the Android counterpart of `TimelineCollectionView`'s `arriving`
 * set: a row takes its place in it once, when it is first composed, so
 * scrolling it out of view and back never replays it.
 */
internal class Arrivals {
    var pending: Set<String> = emptySet()
    private val taken = HashSet<String>()

    fun take(id: String): Boolean = id in pending && taken.add(id)
}

/** The arrival's spring: 0.38 s, damping 0.78 (`TimelineCollectionView.swift:1186-1189`). */
private val ArrivalSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 270f)
private val ArrivalRise = 16.dp

/** A new message rises 16dp and fades in on a spring; under Reduce Motion it is simply there. */
@Composable
internal fun Modifier.arrivalRise(arrivals: Arrivals, id: String, reduce: Boolean): Modifier {
    val arriving = remember(id) { !reduce && arrivals.take(id) }
    if (!arriving) return this
    val progress = remember(id) { Animatable(0f) }
    LaunchedEffect(id) { progress.animateTo(1f, ArrivalSpring) }
    val rise = with(LocalDensity.current) { ArrivalRise.toPx() }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * rise
    }
}

/** The opened stretch ids as a saveable list, so rotation keeps them open. */
private val ExpandedStretches = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)


/**
 * The row that carries a finished turn's record: the newest row that is not
 * the reader's own and whose view is a bubble (`TimelineCollectionView.swift:812-824`).
 */
internal fun recordAnchorId(rows: List<TimelineRowDto>): String? =
    rows.lastOrNull { !it.item.isOwn && it.view is ItemView.Bubble }?.item?.id

/**
 * Who the card is named for (D11, owner answer Q1): the turn's own agent
 * ([agentId], which the core takes from the sender of the turn's first live
 * event) when the room's [cast] knows it, by the cast's display name; the room's
 * agent ([agentName], the cast's counterpart) when it does not; else whoever
 * spoke last, else "Agent".
 */
internal fun liveWriterName(
    agentName: String?,
    rows: List<TimelineRowDto>,
    agentId: String? = null,
    cast: RoomCast? = null,
): String {
    if (agentId != null && cast != null) {
        val counterpart = cast.counterpart
        val named = when {
            counterpart != null && counterpart.userId == agentId -> counterpart.name
            agentId in cast.agentIds -> cast.people.firstOrNull { it.userId == agentId }?.name
            else -> null
        }
        if (named != null) return named
    }
    return agentName ?: rows.lastOrNull { !it.item.isOwn }?.senderName ?: "Agent"
}

/**
 * The history glide's frame loop. Every glided item is a [GlideNode]; the
 * driver holds the offset as a plain field and asks each node to **re-place**
 * (`invalidatePlacement`), which re-runs its layer block with the new offset.
 * [grew] runs while the card is measured, before the frame is laid out and
 * drawn, so the history is back where it was in that same frame; nothing here
 * is snapshot state, so no read in layout is tracked, nothing is written to
 * composition's state during layout, and no global apply is flushed
 * mid-measure. The frame loop is woken through a channel.
 */
internal class GlideDriver {
    private val glide = HistoryGlide()
    private var plainOffset = 0f
    private val nodes = LinkedHashSet<GlideNode>()
    private val growths = Channel<Unit>(Channel.CONFLATED)

    val offset: Float get() = plainOffset

    fun register(node: GlideNode) {
        nodes += node
    }

    fun unregister(node: GlideNode) {
        nodes -= node
    }

    private fun apply() {
        for (node in nodes) node.invalidatePlacement()
    }

    fun grew(deltaPx: Float) {
        glide.add(deltaPx)
        plainOffset += deltaPx
        apply()
        growths.trySend(Unit)
    }

    fun reset() {
        glide.clear()
        plainOffset = 0f
        apply()
        growths.trySend(Unit)
    }

    suspend fun run() {
        growths.receiveAsFlow().collectLatest {
            while (true) {
                val over = withFrameNanos { now ->
                    plainOffset = glide.offsetAt(now)
                    apply()
                    !glide.isActive
                }
                if (over) break
            }
        }
    }
}

internal class GlideNode(private val driver: GlideDriver) : Modifier.Node(), LayoutModifierNode {
    override fun onAttach() = driver.register(this)
    override fun onDetach() = driver.unregister(this)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // A fresh block each placement: it carries this frame's offset.
            val y = driver.offset
            placeable.placeWithLayer(0, 0) { translationY = y }
        }
    }
}

private data class GlideElement(val driver: GlideDriver) : ModifierNodeElement<GlideNode>() {
    override fun create() = GlideNode(driver)
    override fun update(node: GlideNode) = Unit
}

/** Draws this item at the [driver]'s offset; see [GlideDriver]. */
internal fun Modifier.glided(driver: GlideDriver): Modifier = this.then(GlideElement(driver))
