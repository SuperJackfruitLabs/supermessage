package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.RichListItem
import uniffi.supermessage_core.RichTableCell
import uniffi.supermessage_core.RichTableRow

/**
 * A message body, drawn from blocks the core already parsed.
 *
 * **This composable parses nothing.** Both rendering paths — the sanitised
 * `formatted_body` a human's client sent, and the raw markdown an agent
 * wrote — were turned into one block tree by `core::rich`, so the rule that
 * raw HTML is dropped rather than escaped is made once in Rust and inherited
 * here rather than re-argued. See `apple/Supermessage/Timeline/RichTextView.swift`,
 * which this mirrors block for block.
 *
 * **No syntax highlighting**, deliberately. The whole palette runs on one
 * accent, and a code block lit in six competing hues would be the loudest
 * thing on screen.
 *
 * The `when` below over [RichBlock] (and the inline one over [RichInline])
 * has no `else` branch. Kotlin enforces exhaustiveness over a sealed class,
 * so a future core variant is a compile error here rather than a blank
 * render — the failure mode `ItemView::DateDivider` exists to prevent, after
 * a host missed it while it was only a comment.
 *
 * [fontFamily] is the face prose renders in — [SupermessageTheme.typography.body]
 * (serif) by default, because most of what a `RichText` draws is what an
 * agent wrote. [TimelineRow.kt]'s `MessageBlock` overrides it to
 * [SupermessageTheme.typography.own] (sans) for the reader's own messages —
 * see its own comment for why `row.item.isOwn` is what decides, not this
 * file. Threaded through every recursive call ([BlockQuoteView],
 * [ListBlockView]) so a quoted or nested block keeps the same face as its
 * parent rather than reverting to the default; [TableView] and inline code
 * spans are the two exceptions ([TableView] threads it too, in fact — the
 * one true exception is `RichInline.Code`, which always takes
 * [SupermessageTheme.typography.code] regardless of [fontFamily], because
 * mono marks data, not authorship).
 */
@Composable
fun RichText(
    blocks: List<RichBlock>,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily = SupermessageTheme.typography.body,
) {
    val codeFontFamily = SupermessageTheme.typography.code
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> RichBlockView(block, fontFamily, codeFontFamily) }
    }
}

@Composable
private fun RichBlockView(block: RichBlock, fontFamily: FontFamily, codeFontFamily: FontFamily) {
    when (block) {
        is RichBlock.Paragraph ->
            Text(annotated(block.inlines, codeFontFamily), fontFamily = fontFamily)

        is RichBlock.Heading ->
            Text(annotated(block.inlines, codeFontFamily), style = headingStyle(block.level), fontFamily = fontFamily)

        is RichBlock.CodeBlock ->
            CodeBlockView(block.text)

        is RichBlock.BlockQuote ->
            BlockQuoteView(block.blocks, fontFamily)

        is RichBlock.ListBlock ->
            ListBlockView(ordered = block.ordered, start = block.start, items = block.items, fontFamily = fontFamily)

        RichBlock.ThematicBreak ->
            HorizontalDivider()

        is RichBlock.Table ->
            TableView(header = block.header, rows = block.rows, fontFamily = fontFamily, codeFontFamily = codeFontFamily)
    }
}

/**
 * Explicit ladder rather than arithmetic on a text style: a level that
 * somehow arrived out of range degrades to body text instead of producing
 * something the type ramp has no rung for. Mirrors
 * `RichTextView.headingStyle(for:)` on iOS.
 */
@Composable
private fun headingStyle(level: UByte) = when (level.toInt()) {
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    4, 5, 6 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.bodyLarge
}

/**
 * Scrolls inside its own container, so a long line never makes the timeline
 * scroll sideways.
 */
@Composable
private fun CodeBlockView(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text,
            fontFamily = SupermessageTheme.typography.code,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun BlockQuoteView(blocks: List<RichBlock>, fontFamily: FontFamily) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        RichText(blocks = blocks, fontFamily = fontFamily)
    }
}

@Composable
private fun ListBlockView(ordered: Boolean, start: UInt, items: List<RichListItem>, fontFamily: FontFamily) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ordered) "${start + index.toUInt()}." else "•", fontFamily = fontFamily)
                RichText(blocks = item.blocks, fontFamily = fontFamily)
            }
        }
    }
}

/**
 * Also scrolls inside itself. A wide table is the classic way a reading
 * column ends up horizontally scrollable as a whole.
 */
@Composable
private fun TableView(
    header: List<RichTableCell>,
    rows: List<RichTableRow>,
    fontFamily: FontFamily,
    codeFontFamily: FontFamily,
) {
    Column(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (header.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                header.forEach { cell ->
                    Text(annotated(cell.inlines, codeFontFamily), fontWeight = FontWeight.SemiBold, fontFamily = fontFamily)
                }
            }
            HorizontalDivider()
        }
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.cells.forEach { cell -> Text(annotated(cell.inlines, codeFontFamily), fontFamily = fontFamily) }
            }
        }
    }
}

/** Inline runs, folded into an [AnnotatedString]. Mirrors `RichTextFolding` on iOS. */
private fun annotated(inlines: List<RichInline>, codeFontFamily: FontFamily): AnnotatedString = buildAnnotatedString {
    appendInlines(inlines, codeFontFamily)
}

private fun AnnotatedString.Builder.appendInlines(inlines: List<RichInline>, codeFontFamily: FontFamily) {
    inlines.forEach { inline -> appendInline(inline, codeFontFamily) }
}

/**
 * Recurses into every nested-inline variant (`Emphasis`, `Strong`, `Link`)
 * rather than rendering only their first child. `RichTextFolding` on iOS was
 * found by this project's own mutation testing to be untested *and wrong*
 * for exactly this — a fold that stops at the first inline loses every word
 * after it.
 */
private fun AnnotatedString.Builder.appendInline(inline: RichInline, codeFontFamily: FontFamily) {
    when (inline) {
        is RichInline.Text ->
            append(inline.text)

        is RichInline.Emphasis ->
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(inline.inlines, codeFontFamily) }

        is RichInline.Strong ->
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(inline.inlines, codeFontFamily) }

        is RichInline.Code ->
            withStyle(SpanStyle(fontFamily = codeFontFamily)) { append(inline.text) }

        is RichInline.Link ->
            withLink(LinkAnnotation.Url(inline.href)) {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    appendInlines(inline.inlines, codeFontFamily)
                }
            }

        RichInline.Break ->
            append("\n")
    }
}
