package dev.supermessage.kit

import java.net.URI
import uniffi.supermessage_core.RichInline

/**
 * One folded run of text: a contiguous span with the styling that applies
 * to all of it.
 *
 * Kotlin has no equivalent of Foundation's `AttributedString`, and `:kit` may
 * not depend on Compose's `AnnotatedString` — the state layer stays off any
 * view toolkit, which is exactly what `ModuleShapeTest` polices. This is the
 * plain-data replacement: a flat string plus the run boundaries and styling
 * a renderer needs, with no toolkit behind either. `:app` maps this to
 * whatever its view layer wants later.
 *
 * **Nested styles compose.** `Strong { inlines: [Emphasis { ... }] }` means
 * the enclosed text is bold *and* italic — that is what the core's tree
 * says, and `emphasis`/`strong`/`code` are independent flags precisely so a
 * doubly-nested run can carry more than one. This is a deliberate reading of
 * the DTO, and it differs from the Swift original on purpose: Swift assigns
 * `inlinePresentationIntent` outright over the whole appended range rather
 * than unioning it, so a nested `Emphasis` gets overwritten by an
 * enclosing `Strong` and only the outermost trait survives on iOS. That is a
 * latent Swift-side bug, not a rule to preserve — "the Swift source is the
 * specification" governs this port's *shape*, not its defects. See
 * `RichTextFoldingTest.nestedInlinesFold`, which pins the union behaviour.
 */
data class FoldedRun(
    val text: String,
    val emphasis: Boolean = false,
    val strong: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** A folded block: its runs, in order, and the flat text they spell out. */
data class FoldedText(val runs: List<FoldedRun>) {
    val text: String get() = runs.joinToString("") { it.text }
}

/**
 * Inline runs, folded into flat, styled text.
 *
 * In the Kit rather than beside the view, for the same reason
 * `TimelineFollow` is: it is the part with decisions in it, and a test can
 * reach it here. The block layout is a view's job and stays in the app.
 *
 * Everything the core hands over is already parsed — this never looks at
 * markdown or HTML, only at the tree it was given.
 */
object RichTextFolding {

    fun attributed(inlines: List<RichInline>): FoldedText =
        FoldedText(inlines.flatMap { fold(it).runs })

    private fun fold(inline: RichInline): FoldedText = when (inline) {
        is RichInline.Text -> FoldedText(listOf(FoldedRun(text = inline.text)))

        is RichInline.Emphasis -> withEmphasis(attributed(inline.inlines))

        is RichInline.Strong -> withStrong(attributed(inline.inlines))

        is RichInline.Code -> FoldedText(listOf(FoldedRun(text = inline.text, code = true)))

        is RichInline.Link -> {
            val inner = attributed(inline.inlines)
            // A link with an unparseable destination keeps its text and
            // loses its target, rather than disappearing. The core already
            // restricted the schemes it will emit.
            if (isUsableHref(inline.href)) withLink(inner, inline.href) else inner
        }

        RichInline.Break -> FoldedText(listOf(FoldedRun(text = "\n")))
    }

    private fun withEmphasis(folded: FoldedText) =
        FoldedText(folded.runs.map { it.copy(emphasis = true) })

    private fun withStrong(folded: FoldedText) =
        FoldedText(folded.runs.map { it.copy(strong = true) })

    private fun withLink(folded: FoldedText, href: String) =
        FoldedText(folded.runs.map { it.copy(link = href) })

    private fun isUsableHref(href: String): Boolean {
        if (href.isBlank()) return false
        return runCatching { URI(href) }.isSuccess
    }
}
