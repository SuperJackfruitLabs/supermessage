package dev.supermessage

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.SendState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import uniffi.supermessage_ffi.peopleLabel
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.MediaFileLabel
import uniffi.supermessage_core.ReactionDto
import uniffi.supermessage_core.ReplyQuoteView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

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
 * No `onReply`/`onReact` in this phase: existing reactions and read receipts
 * are drawn, but changing them belongs to the composer Phase B adds.
 */
@Composable
fun TimelineRow(
    row: TimelineRowDto,
    now: Instant,
    continuesRun: Boolean = false,
    attribution: String = "",
    avatarUri: (userId: String) -> String? = { null },
    modifier: Modifier = Modifier,
) {
    // "Who to name, already chosen ... Chosen by the list, which can see
    // every row; a single row cannot." Falls back to the full attribution so
    // a row built without an opinion still names its sender.
    val named = attribution.ifEmpty { row.senderName }

    when (val view = row.view) {
        is ItemView.Bubble ->
            MessageBlock(
                row = row,
                named = named,
                muted = view.muted,
                blocks = view.blocks,
                continuesRun = continuesRun,
                avatarUri = avatarUri,
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

        is ItemView.Placeholder -> SystemLine(view.text, modifier)

        // Carries no text: formatted here, from the item's own timestamp,
        // because formatting reads a clock and a locale and both belong
        // where the rendering is.
        ItemView.DateDivider ->
            DateDividerLine(row.item.timestampMs, now, modifier)

        // No label. The divider says it, and a caption repeated at every
        // scroll position would be chrome pretending to be content.
        ItemView.UnreadMarker ->
            HorizontalDivider(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .testTag("unread-marker"),
                color = MaterialTheme.colorScheme.primary,
                thickness = 2.dp,
            )

        is ItemView.Image ->
            ImageRow(named = named, alt = view.alt, width = view.width, height = view.height, modifier = modifier)

        is ItemView.MediaFile ->
            MediaFileRow(label = view.label, filename = view.filename, size = view.size, modifier = modifier)

        // A suite event — a Kaambaan card or run, a permission request,
        // station status. `DecisionCard` renders the whole fallback-chain
        // decision; see its own doc for the three states it handles.
        is ItemView.CustomEvent ->
            DecisionCard(
                view = view.view,
                label = view.label,
                eventType = view.eventType,
                modifier = modifier,
            )

        // Deliberately nothing. A row for this would still occupy layout
        // space — "deliberately silent should mean absent, not empty" is
        // `TimelineGrouping`'s reason for filtering `None` out upstream, and
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

private fun clockLabel(timestampMs: ULong, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
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
    continuesRun: Boolean,
    avatarUri: (userId: String) -> String?,
    modifier: Modifier = Modifier,
) {
    val isOwn = row.item.isOwn
    val sendState = SendState(row.item.sendState)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (continuesRun) 2.dp else 8.dp, bottom = 2.dp),
        horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
    ) {
        if (!isOwn && !continuesRun) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SenderFace(userId = row.item.sender, initial = named, avatarUri = avatarUri)
                Text(named, style = MaterialTheme.typography.labelLarge)
                row.item.timestampMs?.let {
                    Text(clockLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        row.replyQuote?.let { ReplyQuoteBlock(it) }

        // `m.notice` de-emphasised but never suppressed: the colour dims,
        // the block tree still renders in full.
        val textColor =
            if (muted && !isOwn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        CompositionLocalProvider(LocalContentColor provides textColor) {
            Box(
                modifier = Modifier
                    .testTag("bubble")
                    // Bounded so wide content (a table, a code block) scrolls
                    // inside itself rather than stretching this row — those
                    // shapes already carry `horizontalScroll` in `RichText`,
                    // but only a bounded container makes that scroll rather
                    // than merely grow.
                    .widthIn(max = 320.dp)
                    .let {
                        if (isOwn) {
                            it.clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))
                                .padding(10.dp)
                        } else {
                            it
                        }
                    },
            ) {
                RichText(blocks = blocks)
            }
        }

        if (isOwn) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val failed = sendState == SendState.FAILED
                val color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                sendState.label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = color) }
                row.item.timestampMs?.let {
                    Text(clockLabel(it), style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }

        // Only under your own messages, and only where a receipt actually
        // points: under someone else's message this would tell a reader
        // what they already know.
        if (isOwn && row.item.readBy.isNotEmpty()) {
            Text(
                "Read by ${peopleLabel(row.item.readBy)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (row.item.reactions.isNotEmpty()) {
            ReactionsRow(row.item.reactions)
        }
    }
}

@Composable
private fun ReplyQuoteBlock(quote: ReplyQuoteView, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 2.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
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
                    Text(quote.sender, style = MaterialTheme.typography.labelSmall)
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
private fun ReactionsRow(reactions: List<ReactionDto>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { reaction ->
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
        }
    }
}

/**
 * A sender's face beside their name, or the initial of the name itself.
 *
 * `avatarUri` is keyed by user id, not by an `mxc:` URI already resolved to a
 * cache — the list (Task 6) owns the cache and hands this row only what it
 * needs to draw one face.
 */
@Composable
private fun SenderFace(userId: String?, initial: String, avatarUri: (userId: String) -> String?) {
    val uri = userId?.let(avatarUri)
    val bitmap = remember(uri) { uri?.decodeDataUri() }
    Box(
        modifier = Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                initial.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * A thumbnail, reserving its box from [width]/[height] before any bytes are
 * asked for, so a lazy list never reflows once they land. No media loading
 * happens here — a future task wires that in; until then, and whenever it
 * fails, the alt text is what a reader sees rather than a blank box.
 */
@Composable
private fun ImageRow(named: String, alt: String, width: ULong?, height: ULong?, modifier: Modifier = Modifier) {
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
            Text(
                alt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** An informative row naming what an `m.file`/`m.audio`/`m.video` message is. */
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
 * Decode the `data:` URI a cache hands back. No network here — the bytes
 * already crossed the boundary. Returns `null` on anything malformed rather
 * than throwing, mirroring `RoomRow`'s `decodeDataUri()`.
 */
private fun String.decodeDataUri(): ImageBitmap? = try {
    val comma = indexOf(',')
    if (comma < 0) {
        null
    } else {
        val bytes = Base64.decode(substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
} catch (e: Exception) {
    null
}
