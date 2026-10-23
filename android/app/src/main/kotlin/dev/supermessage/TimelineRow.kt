package dev.supermessage

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.supermessage.kit.SendState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.MediaFileLabel
import uniffi.supermessage_core.PlaceholderKind
import uniffi.supermessage_core.ReactionDto
import uniffi.supermessage_core.ReplyQuoteView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.TimelineRow as TimelineRowDto
import uniffi.supermessage_core.VoiceReplyPlayer
import uniffi.supermessage_ffi.peopleLabel

/**
 * The reactions a message's menu and its "+" offer to add, verbatim from
 * `TimelineRowView.swift:22`'s `quickReactions` — not a set invented here.
 * That file's own comment is why this is not extended or reordered: "two
 * different quick reactions is two different apps."
 */
val quickReactions = listOf("✅", "👍", "❌", "👀")

/** Two points inside a run, twelve between turns (`TimelineRowView.swift:375`, T2). */
private val RunGap = 2.dp
private val TurnGap = 12.dp

/**
 * One timeline row, drawn from the decision the core made about it.
 *
 * The `when` below is over [ItemView] — `core::item_view`'s classification of
 * a Matrix event — and has **no `else` branch**. Kotlin enforces exhaustiveness
 * over a sealed class, so a variant this build has never seen is a compile
 * error here rather than a blank row. That is deliberate:
 * `ItemView.DateDivider` exists as a variant *because* a host missed it while
 * it was only a comment — iOS rendered "Unsupported event (dateDivider)" in
 * the middle of a conversation (`item_view.rs:81`). An `else` branch would
 * reintroduce exactly that failure mode.
 *
 * This composable makes no decisions of its own — not the block tree
 * ([RichText] draws that), not who to name ([attribution]/[continuesRun],
 * chosen by the list that can see every row), not the clock ([now], injected
 * so a test can pin a fixed instant instead of restating "today" itself).
 * Mirrors `apple/Supermessage/Timeline/TimelineRowView.swift`.
 *
 * No `onReply` yet: unlike [onReact], nothing in this phase calls it, matching
 * `TimelineRowView.swift`'s own unused parameter of the same name.
 *
 * [onReact] toggles an existing reaction chip and also fires from the "add
 * reaction" affordance this row draws beside them ([AddReactionAffordance]),
 * whose tap opens exactly [quickReactions] and nothing else. The long-press
 * menu that also carries that strip lives on the list ([Timeline]), as iOS's
 * does on its collection view. `canReplyOrReact` gates whether the "+" renders
 * at all, the same guard iOS's own menu entry reads. A long press on a chip
 * says who reacted.
 *
 * [endsRun] is the other half of [continuesRun]: whether the row *below*
 * this one starts a new run. An own run shows its time once, under its last
 * message, instead of under every bubble in it — see [MessageBlock].
 */
@Composable
fun TimelineRow(
    row: TimelineRowDto,
    now: Instant,
    continuesRun: Boolean = false,
    endsRun: Boolean = true,
    readers: List<ReaderFace> = emptyList(),
    highlighted: Boolean = false,
    onQuoteTap: (() -> Unit)? = null,
    attribution: String = "",
    imageUri: (eventId: String) -> String? = { null },
    faceUri: (mxcUri: String) -> String? = { null },
    loadFace: suspend (mxcUri: String) -> Unit = {},
    onReact: ((String) -> Unit)? = null,
    onDecide: (suspend (GateAnswer) -> Boolean)? = null,
    decisionAnswers: DecisionAnswers? = null,
    prelude: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // "Who to name, already chosen ... Chosen by the list, which can see
    // every row; a single row cannot." Falls back to the full attribution so
    // a row built without an opinion still names its sender.
    val named = attribution.ifEmpty { row.senderName }

    when (val view = row.view) {
        // `voice` is an agent's answer read aloud: its player above the text,
        // one message. The voice message's own row is `None` (core::voice_reply).
        is ItemView.Bubble ->
            MessageBlock(
                row = row,
                named = named,
                muted = view.muted,
                blocks = view.blocks,
                spoken = view.voice,
                continuesRun = continuesRun,
                endsRun = endsRun,
                readers = readers,
                highlighted = highlighted,
                onQuoteTap = onQuoteTap,
                faceUri = faceUri,
                loadFace = loadFace,
                onReact = onReact,
                prelude = prelude,
                modifier = modifier,
            )

        // Centred italic prose *about* its sender, not something they said.
        ItemView.Emote ->
            Text(
                "$named ${row.item.body ?: ""}",
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
            )

        is ItemView.System -> SystemLine(view.text, modifier)

        // A message this device holds no key for is still a message: someone
        // sent it, at a time. A centred grey line read like "Krishna joined",
        // an event about the room rather than a gap in it
        // (`TimelineRowView.swift:153-158`). Every other placeholder stays a
        // quiet line.
        is ItemView.Placeholder ->
            if (view.kind == PlaceholderKind.UnableToDecrypt) {
                UndecryptableRow(
                    row = row,
                    named = named,
                    text = view.text,
                    continuesRun = continuesRun,
                    faceUri = faceUri,
                    loadFace = loadFace,
                    modifier = modifier,
                )
            } else {
                SystemLine(view.text, modifier)
            }

        // Carries no text: formatted here, from the item's own timestamp,
        // because formatting reads a clock and a locale and both belong
        // where the rendering is.
        ItemView.DateDivider ->
            DateDividerLine(row.item.timestampMs, now, modifier)

        // A word at the trailing end names the line: bare, it read as a
        // stray divider (`TimelineRowView.swift:136-151`). Accent, never amber.
        ItemView.UnreadMarker -> UnreadLine(modifier)

        is ItemView.Image ->
            ImageRow(
                named = named,
                alt = view.alt,
                width = view.width,
                height = view.height,
                caption = view.caption,
                picture = row.item.eventId?.let(imageUri)?.let { uri -> remember(uri) { uri.decodeDataUri() } },
                modifier = modifier,
            )

        is ItemView.MediaFile ->
            MediaFileRow(
                label = view.label,
                filename = view.filename,
                size = view.size,
                // Your own file on your side, as your bubbles are — which is
                // where a voice note's transcript is drawn under it.
                modifier = modifier
                    .fillMaxWidth()
                    .wrapContentWidth(if (row.item.isOwn) Alignment.End else Alignment.Start),
            )

        // `m.audio`: a player, not a file row. Voice note or audio file is
        // the core's call (`audio.isVoice`); see `AudioNote`. `transcript` is
        // what the note said, drawn under it in this row: the core folded the
        // transcript notice in, however late it landed, and hid the notice's
        // own row (`core::voice_transcript::reconcile`).
        is ItemView.Audio ->
            AudioRow(
                row = row,
                audio = view.audio,
                named = named,
                continuesRun = continuesRun,
                faceUri = faceUri,
                loadFace = loadFace,
                transcript = view.transcript,
                modifier = modifier,
            )

        // A suite event — a Superpipeline card or run, a permission request,
        // station status. `DecisionCard` renders the whole fallback-chain
        // decision; see its own doc for the three states it handles.
        is ItemView.CustomEvent ->
            DecisionCard(
                view = view.view,
                label = view.label,
                eventType = view.eventType,
                senderName = named,
                answers = decisionAnswers,
                answerKey = row.item.eventId ?: row.item.id,
                modifier = modifier,
                onDecide = onDecide,
            )

        // An agent's failed turn, as the card the core parsed off the hub's
        // message. See `TurnErrorCardView`.
        is ItemView.TurnError ->
            TurnErrorRow(
                row = row, named = named, card = view.card,
                faceUri = faceUri, loadFace = loadFace, onReact = onReact, modifier = modifier,
            )

        // What a voice note said, when its note is not loaded to carry it:
        // on the note's side, with no header — it belongs to the note, not
        // to the agent that posted it.
        is ItemView.VoiceTranscript ->
            VoiceTranscriptView(transcript = view.transcript, onOwnNote = view.onOwnNote, modifier = modifier)

        // Deliberately nothing. A row for this would still occupy layout
        // space — "deliberately silent should mean absent, not empty" is
        // `displayRows`'s reason for filtering `None` out upstream, and
        // this composable honours the same rule for whatever the filter
        // still hands it.
        ItemView.None -> Unit
    }
}

/**
 * A quiet line about the room rather than in it.
 *
 * Not `private`: [Timeline] reuses this to draw a collapsed
 * [dev.supermessage.kit.DisplayRow.MembershipRun] with the same visual
 * treatment as any other system line, rather than inventing a second one.
 */
@Composable
internal fun SystemLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

/**
 * A stretch of membership changes folded into one line the reader can open:
 * the same quiet look as [SystemLine], with a chevron saying it opens.
 *
 * The text wraps rather than clips, so it holds at large font scales on a
 * narrow screen; the row is at least a touch target tall.
 */
@Composable
internal fun StretchLine(text: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = "Shows each change",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A hairline with the day on it, formatted relative to [now]. */
@Composable
private fun DateDividerLine(timestampMs: ULong?, now: Instant, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            dayLabel(timestampMs, now),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * The day a divider names — "Today" and "Yesterday" where those apply,
 * because a date is harder to place than a word. Mirrors
 * `TimelineRowView.day(_:)` on iOS.
 */
private fun dayLabel(
    timestampMs: ULong?,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (timestampMs == null) return ""
    val then = Instant.ofEpochMilli(timestampMs.toLong()).atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (then) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(then)
    }
}

internal fun clockLabel(timestampMs: ULong, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(timestampMs.toLong()).atZone(zone))

/** A message, peer or own. */
@Composable
private fun MessageBlock(
    row: TimelineRowDto,
    named: String,
    muted: Boolean,
    blocks: List<RichBlock>,
    spoken: VoiceReplyPlayer?,
    continuesRun: Boolean,
    endsRun: Boolean,
    readers: List<ReaderFace>,
    highlighted: Boolean,
    onQuoteTap: (() -> Unit)?,
    faceUri: (mxcUri: String) -> String?,
    loadFace: suspend (mxcUri: String) -> Unit,
    onReact: ((String) -> Unit)? = null,
    prelude: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isOwn = row.item.isOwn
    val sendState = SendState(row.item.sendState)
    val edited = row.item.edited

    Box(modifier = modifier.fillMaxWidth().padding(top = if (continuesRun) RunGap else TurnGap)) {
    RowHighlight(highlighted)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
    ) {
        if (!isOwn && !continuesRun) {
            PeerHeader(row = row, named = named, faceUri = faceUri, loadFace = loadFace, showsEdited = true)
        } else if (!isOwn && edited) {
            // A continuation drops its sender line, but "edited" is real
            // information rather than a repeated header — the desktop keeps
            // the marker alone in that case (`Timeline.svelte`), and so does
            // this.
            EditedMarker()
        }

        // A finished turn's record, between the header and the answer
        // (`TimelineRowView.swift:308-314`).
        prelude?.let { it(); Spacer(Modifier.height(6.dp)) }

        if (!row.hidesQuote) row.replyQuote?.let { ReplyQuoteBlock(it, onTap = onQuoteTap) }

        // Listen first, then read: the spoken answer above the words it says.
        spoken?.let { SpokenReply(spoken = it, isOwn = isOwn, modifier = Modifier.padding(bottom = 6.dp)) }

        // `m.notice` de-emphasised but never suppressed: the colour dims,
        // the block tree still renders in full.
        val textColor =
            if (muted && !isOwn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        // Phase D's whole point (see Theme.kt's file doc): serif for what an
        // agent wrote, sans for what the operator wrote. `row.item.isOwn` is
        // already the sender distinction every other branch in this
        // function reads — RichText itself makes no such decision, which is
        // why it takes the resolved face as a parameter rather than reading
        // `isOwn` on its own.
        val bodyFontFamily = if (isOwn) SupermessageTheme.typography.own else SupermessageTheme.typography.body
        // The bubble, with its reactions pulled up over its bottom edge
        // (`TimelineRowView.swift:319-323`). Negative spacing rather than an
        // overlay, so the chips still take their height in the row and never
        // overlap the next message.
        val canAddReaction = onReact != null && row.canReplyOrReact
        val hasChips = row.item.reactions.isNotEmpty() || canAddReaction
        Column(
            verticalArrangement = Arrangement.spacedBy((-8).dp),
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
        ) {
            CompositionLocalProvider(LocalContentColor provides textColor) {
                // `peer-card` / `own-bubble` wrap the one `bubble`: the tag the
                // role gives, around the card that is measured.
                Box(Modifier.testTag(if (isOwn) "own-bubble" else "peer-card")) {
                    Box(
                        modifier = Modifier
                            .testTag("bubble")
                            // Bounded so wide content (a table, a code block) scrolls
                            // inside itself rather than stretching this row. Peer
                            // 600, own 520 (`WhatIDidFooter.swift:96-102`).
                            .widthIn(max = if (isOwn) OwnMeasure else CardMeasure)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isOwn) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                                else SupermessageTheme.colors.surfaceRaised,
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        // Never your own (the core says so); a long read is cut
                        // and offers the reading view.
                        if (row.isLongRead && !isOwn) {
                            LongReadBody(blocks = blocks, fontFamily = bodyFontFamily, title = named)
                        } else {
                            RichText(blocks = blocks, fontFamily = bodyFontFamily)
                        }
                    }
                }
            }
            // The add-reaction affordance sits in the same row as any existing
            // chips (never a row of its own) and needs `onReact` to have
            // anywhere to send a tap, plus `canReplyOrReact` — the SDK's own
            // guard for "there is an event to react to yet".
            if (row.item.reactions.isNotEmpty() || canAddReaction) {
                ReactionsRow(
                    row.item.reactions,
                    onReact = onReact,
                    showAddAffordance = canAddReaction,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }

        // The own message's trailing meta line. A run of own messages
        // carries its time once, under the last of them ([endsRun]) — but a
        // send that failed or is still in flight is not a timestamp, and
        // hiding it mid-run would bury a failed send, so those always show.
        // So does "edited", for the same reason the peer side keeps it.
        val showsTime = endsRun && row.item.timestampMs != null
        if (isOwn && (sendState.isWorthShowing || edited || showsTime || readers.isNotEmpty())) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                val failed = sendState == SendState.FAILED
                val color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                sendState.label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = color) }
                if (edited) EditedMarker()
                if (showsTime) {
                    row.item.timestampMs?.let {
                        Text(clockLabel(it), style = MaterialTheme.typography.labelSmall, color = color)
                    }
                }
                // Only under your own messages, and only where a receipt
                // actually points; the faces sit on this line (D7, T3).
                if (readers.isNotEmpty()) ReaderStack(readers, faceUri, loadFace)
            }
        }
    }
    }
}

private val CardMeasure = 600.dp
private val OwnMeasure = 520.dp

/** How long a jumped-to message stays lit, `TimelineCollectionView.swift:921-935`. */
const val QuoteHighlightMs = 1_400L

/**
 * The jumped-to message's wash: a radius-12 rounded rectangle in `accentSoft`,
 * inset -8 horizontally, in over 0.15 s and out over 0.8 s
 * (`TimelineRowView.swift:79-88`). Not in the tree once it has faded.
 */
@Composable
private fun BoxScope.RowHighlight(highlighted: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = motion(reducesMotion(), tween(if (highlighted) 150 else 800)),
        label = "row-highlight",
    )
    if (alpha > 0f) {
        val inset = with(LocalDensity.current) { 8.dp.roundToPx() }
        Box(
            Modifier
                .matchParentSize()
                .layout { measurable, constraints ->
                    val wider = measurable.measure(
                        Constraints.fixed(constraints.maxWidth + 2 * inset, constraints.maxHeight),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) { wider.place(-inset, 0) }
                }
                .graphicsLayer { this.alpha = alpha }
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                .testTag("row-highlight"),
        )
    }
}

/**
 * The quiet "edited" beside a message's time, as the desktop draws it
 * (`Timeline.svelte`'s sender and trailing meta lines). Words, not a pencil:
 * a glyph alone reads as an action to take rather than a thing that happened.
 */
@Composable
internal fun EditedMarker() {
    Text(
        "edited",
        modifier = Modifier.testTag("edited-marker"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun quoteRuleColor(quote: ReplyQuoteView): Color {
    val senderId = (quote as? ReplyQuoteView.Available)?.senderId
        ?: return MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    return SupermessageTheme.peer(senderId)
}

@Composable
private fun ReplyQuoteBlock(quote: ReplyQuoteView, onTap: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .testTag("reply-quote")
            .let {
                // Tappable only when its parent is loaded; the hint says what a
                // tap does (`TimelineRowView.swift:538`).
                if (onTap == null) it else it.quoteTap(onTap)
            }
            .padding(vertical = 2.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(quoteRuleColor(quote)),
        )
        when (quote) {
            // The core folds Unavailable/Pending/Error together, so this is
            // the one shape to handle.
            ReplyQuoteView.Unavailable ->
                Text(
                    "Original message unavailable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )

            is ReplyQuoteView.Available ->
                Column {
                    Text(
                        quote.sender,
                        style = MaterialTheme.typography.labelSmall,
                        color = quote.senderId?.let { SupermessageTheme.peer(it) } ?: Color.Unspecified,
                    )
                    val excerpt = quote.excerpt
                    val label = quote.label
                    if (excerpt != null) {
                        Text(excerpt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else if (label != null) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
        }
    }
}

@Composable
private fun ReactionsRow(
    reactions: List<ReactionDto>,
    onReact: ((String) -> Unit)? = null,
    showAddAffordance: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        reactions.forEach { reaction ->
            var asking by remember { mutableStateOf(false) }
            // A ring of the page around each chip, so where it overlaps the
            // bubble's corner the two stay separate shapes (`:612-614`).
            Box(Modifier.background(SupermessageTheme.colors.surface, CircleShape).padding(2.dp)) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (reaction.byMe) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        // `key`, not `displayKey`: the wire value is what the
                        // homeserver matches against what everyone else sent, and
                        // reacting with the display form would land a *different*
                        // reaction beside the one the reader meant to join.
                        // A `null` `onReact` (no composer in context) leaves a tap
                        // inert; a long press still says who reacted.
                        .combinedClickable(
                            onClickLabel = onReact?.let { "React with ${reaction.displayKey}" },
                            onLongClickLabel = "Who reacted",
                            onClick = {
                                if (onReact != null) {
                                    haptics.play(Haptic.Selection)
                                    onReact(reaction.key)
                                }
                            },
                            onLongClick = { asking = true },
                        )
                        .semantics {
                            contentDescription = "${reaction.displayKey}, ${reaction.count}" +
                                if (reaction.byMe) ", including yours" else ""
                        }
                        .testTag("reaction-${reaction.key}")
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // `displayKey`, never `key`: `key` is wire data compared
                    // byte-for-byte against what other clients sent; this one is
                    // bounded for display.
                    Text(reaction.displayKey, style = MaterialTheme.typography.labelSmall)
                    // One reaction is already fully described by its key;
                    // printing "1" beside it counts nothing anyone wondered about.
                    if (reaction.count > 1u) {
                        Text(
                            "${reaction.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (reaction.byMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                if (asking) {
                    // Who reacted, as iOS's popover does
                    // (`TimelineRowView.swift:575-593`): the key and the names.
                    Popup(onDismissRequest = { asking = false }) { ReactionWhoCard(reaction) }
                }
            }
        }
        if (showAddAffordance && onReact != null) {
            AddReactionAffordance(onReact = onReact)
        }
    }
}

/** The key and the people behind it: what the who-reacted popup shows. */
@Composable
internal fun ReactionWhoCard(reaction: ReactionDto, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 240.dp)
            .testTag("reaction-who"),
    ) {
        Text(reaction.displayKey, style = MaterialTheme.typography.titleMedium)
        Text(peopleLabel(reaction.senders), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The affordance Phase B left out: existing reaction chips toggle
 * ([ReactionsRow]), but nothing added a reaction that was not already on the
 * message. A tap here opens exactly [quickReactions] — the same set
 * `TimelineRowView.swift` and the desktop client already offer, chosen once
 * for the reason that file's own comment gives, not reinvented per host.
 * It was a long press until the message menu took that gesture's place on the
 * row; a tap is what a visible "+" promises.
 */
@Composable
private fun AddReactionAffordance(onReact: (String) -> Unit, modifier: Modifier = Modifier) {
    var pickerOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClickLabel = "Add a reaction") { pickerOpen = !pickerOpen }
                .testTag("add-reaction")
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("+", style = MaterialTheme.typography.labelSmall)
        }
        if (pickerOpen) {
            Row(
                modifier = Modifier.padding(top = 4.dp).testTag("reaction-picker"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickReactions.forEach { emoji ->
                    Text(
                        emoji,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClickLabel = "React with $emoji") {
                                onReact(emoji)
                                pickerOpen = false
                            }
                            .testTag("quick-reaction-$emoji")
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * A sender's face beside their name, or the initial of the name itself.
 *
 * Keyed by the sender's `mxc:` avatar, as on iOS: [faceUri] reads the face
 * cache for it and [loadFace] asks the core to fetch it when it is missing.
 */
@Composable
internal fun SenderFace(
    mxcUri: String?,
    initial: String?,
    faceUri: (mxcUri: String) -> String?,
    loadFace: suspend (mxcUri: String) -> Unit,
    size: Dp = SenderFaceSize,
) {
    LaunchedEffect(mxcUri) { mxcUri?.let { loadFace(it) } }
    val uri = mxcUri?.let(faceUri)
    val bitmap = remember(uri) { uri?.decodeDataUri() }
    Box(
        modifier = Modifier.size(size).testTag("sender-face").clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("sender-face-image"),
                contentScale = ContentScale.Crop,
            )
        } else if (initial == null) {
            // Someone this room has not shown a message from yet, so there is
            // no initial to hand: a person, not a guess made from their id.
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = SupermessageTheme.colors.contentFaint,
                modifier = Modifier.size(size * 0.6f),
            )
        } else {
            // Rendered whole, not `firstOrNull()`. A Kotlin `Char` is a
            // UTF-16 code unit, so for an astral glyph — 🧠, and this
            // deployment is full of them — taking the first one yields half a
            // surrogate pair and draws tofu. `room_identity.rs`'s own header
            // records Android doing exactly that for an account avatar.
            //
            // Nothing needs trimming here: `sender_initial` arrives from the
            // core as one glyph or one uppercased letter, and uppercasing it
            // again would be this host re-deciding something already decided.
            val style = MaterialTheme.typography.labelSmall
            Text(
                initial.ifEmpty { "?" },
                // The default disc keeps the type role; a smaller one scales
                // the initial with it (iOS: `size * 0.56`).
                style = if (size == SenderFaceSize) style else style.copy(fontSize = with(LocalDensity.current) { (size * 0.56f).toSp() }),
            )
        }
    }
}

private val SenderFaceSize = 18.dp

/**
 * Someone whose read receipt points at a message, with whatever face the room
 * has already shown for them. [initial] is `null` when they have sent nothing
 * this device has seen, so the core has never handed over an initial for them.
 */
data class ReaderFace(val userId: String, val mxcUri: String?, val initial: String?)

/**
 * The face of each person who has sent a message in [rows], keyed by sender:
 * the first `senderAvatar` and `senderInitial` seen for them. Only
 * `kind == "message"` rows count (`TimelineCollectionView.swift:747-779`); a
 * reader with no such row is absent, and is drawn as a person glyph.
 */
internal fun readerFaces(rows: List<TimelineRowDto>): Map<String, ReaderFace> {
    val faces = HashMap<String, ReaderFace>()
    for (row in rows) {
        val sender = row.item.sender ?: continue
        if (sender in faces || row.item.kind != "message") continue
        faces[sender] = ReaderFace(sender, row.item.senderAvatar, row.senderInitial)
    }
    return faces
}

/**
 * "Read by …" as a few small faces rather than a sentence (D7, T3): up to
 * three of 14dp overlapping by 4, each ringed 1.5 in the page colour, then
 * "+n" for the rest. The sentence is the accessibility label, named by the
 * core so it reads the same everywhere (`TimelineChrome.swift:142-175`).
 */
@Composable
internal fun ReaderStack(
    readers: List<ReaderFace>,
    faceUri: (String) -> String?,
    loadFace: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = "Read by ${peopleLabel(readers.map { it.userId })}"
    Row(
        modifier = modifier.clearAndSetSemantics {
            semanticsTestTag = "reader-stack"
            contentDescription = label
        },
        horizontalArrangement = Arrangement.spacedBy((-4).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        readers.take(ReaderFacesShown).forEach { reader ->
            Box(
                Modifier
                    .testTag("reader-face")
                    .background(SupermessageTheme.colors.surface, CircleShape)
                    .padding(1.5.dp),
            ) {
                SenderFace(reader.mxcUri, reader.initial, faceUri, loadFace, size = 14.dp)
            }
        }
        if (readers.size > ReaderFacesShown) {
            Text(
                "+${readers.size - ReaderFacesShown}",
                style = MaterialTheme.typography.labelSmall,
                color = SupermessageTheme.colors.contentFaint,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

private const val ReaderFacesShown = 3

/** The line before the first unread message, named so it is not a stray divider. */
@Composable
private fun UnreadLine(modifier: Modifier = Modifier) {
    val accent = SupermessageTheme.colors.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clearAndSetSemantics {
                semanticsTestTag = "unread-marker"
                contentDescription = "New messages"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = accent, thickness = 2.dp)
        Text("New", style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

/**
 * A message this device holds no key for (`TimelineRowView.swift:637-680`):
 * the sender header when it is someone else's and starts a turn, then a lock
 * with the core's text, and the one thing a reader can do about it.
 */
@Composable
private fun UndecryptableRow(
    row: TimelineRowDto,
    named: String,
    text: String,
    continuesRun: Boolean,
    faceUri: (mxcUri: String) -> String?,
    loadFace: suspend (mxcUri: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOwn = row.item.isOwn
    val colors = SupermessageTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (continuesRun) RunGap else TurnGap)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
    ) {
        if (!isOwn && !continuesRun) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SenderFace(row.item.senderAvatar, row.senderInitial, faceUri, loadFace, size = 22.dp)
                Text(
                    named,
                    style = MaterialTheme.typography.labelLarge,
                    color = row.item.sender?.let { SupermessageTheme.peer(it) } ?: Color.Unspecified,
                )
                row.item.timestampMs?.let {
                    Text(clockLabel(it), style = MaterialTheme.typography.labelSmall, color = colors.contentFaint)
                }
            }
        }
        Row(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceRaised)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.contentMuted, modifier = Modifier.size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = colors.contentMuted,
                )
                Text(
                    "Encryption recovery in your account can restore older messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.contentFaint,
                )
            }
        }
    }
}

/**
 * A thumbnail, reserving its box from [width]/[height] before any bytes are
 * asked for, so a lazy list never reflows once they land. [picture] is the
 * decoded image when the cache has one; until then, and whenever the fetch
 * fails, the alt text is what a reader sees rather than a blank box.
 */
@Composable
private fun ImageRow(named: String, alt: String, width: ULong?, height: ULong?, caption: String?, picture: ImageBitmap?, modifier: Modifier = Modifier) {
    val aspect = if (width != null && height != null && height > 0uL) {
        width.toFloat() / height.toFloat()
    } else {
        4f / 3f
    }
    Column(modifier = modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(named, style = MaterialTheme.typography.labelLarge)
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = alt },
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) {
                Image(
                    bitmap = picture,
                    contentDescription = alt,
                    modifier = Modifier.fillMaxSize().testTag("timeline-image"),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    alt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        // What the sender wrote with it (MSC2530), under the picture: one
        // message, the way it was sent.
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 320.dp))
        }
    }
}

/** An informative row naming what an `m.file`/`m.video` message is. `m.audio` is [AudioRow]. */
@Composable
private fun MediaFileRow(label: MediaFileLabel, filename: String, size: ULong?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(filename, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mediaFileCaption(label, size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * `label` is display text the core already chose — printed, not switched on
 * for wording, per the brief: `MediaFileLabel` is `FILE`/`AUDIO`/`VIDEO`,
 * already human-facing.
 */
private fun mediaFileCaption(label: MediaFileLabel, size: ULong?): String {
    val kind = label.name.lowercase().replaceFirstChar { it.uppercaseChar() }
    return if (size == null) kind else "$kind · ${formatBytes(size)}"
}

private fun formatBytes(size: ULong): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = size.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
}

/**
 * A tap that leaves the touch alone. A `clickable` here would take the whole
 * gesture and the row's long press (the menu) and swipes would die on the
 * quote; this watches the down and up without consuming either, and gives up
 * once the touch is held as long as a long press or another handler takes it.
 */
private fun Modifier.quoteTap(onTap: () -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onTap)
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                if (up != null) latest()
            }
        }
        // Keyboard and D-pad reach the quote, and Enter / centre activates it.
        .onKeyEvent { event ->
            val activates = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter
            if (activates && event.type == KeyEventType.KeyUp) {
                latest()
                true
            } else {
                activates
            }
        }
        .focusable()
        .semantics(mergeDescendants = false) {
            onClick(label = "Shows the original message") {
                latest()
                true
            }
        }
}

/**
 * An agent's failed turn under the agent's own header, with the reactions any
 * message has (`TimelineRowView.swift:213-248`). Always headed: a failure is
 * never the continuation of the answer above it.
 */
@Composable
private fun TurnErrorRow(
    row: TimelineRowDto,
    named: String,
    card: uniffi.supermessage_core.TurnErrorCard,
    faceUri: (mxcUri: String) -> String?,
    loadFace: suspend (mxcUri: String) -> Unit,
    onReact: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = TurnGap), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PeerHeader(row = row, named = named, faceUri = faceUri, loadFace = loadFace, showsEdited = false)
        Column(verticalArrangement = Arrangement.spacedBy((-8).dp)) {
            TurnErrorCardView(card = card)
            if (row.item.reactions.isNotEmpty()) {
                ReactionsRow(row.item.reactions, onReact = onReact, modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
    }
}
