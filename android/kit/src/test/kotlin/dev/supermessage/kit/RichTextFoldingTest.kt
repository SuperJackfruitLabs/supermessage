package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.RichInline

class RichTextFoldingTest {

    /** "nested emphasis keeps its text and its trait" */
    @Test
    fun nestedInlinesFold() {
        val folded = RichTextFolding.attributed(listOf(
            RichInline.Text("a "),
            RichInline.Strong(listOf(
                RichInline.Text("b"),
                RichInline.Emphasis(listOf(RichInline.Text("c"))),
            )),
        ))
        assertEquals("a bc", folded.text)
        // The text surviving is the half that matters — a fold that dropped
        // the nesting would lose the words inside it, which is exactly the
        // bug the Rust side had in a tight list.
        assertTrue(folded.runs.any { it.strong })
        // The inner run ("c") is inside both the strong and the emphasis —
        // the core's tree says the text is both, and union is the correct
        // reading of it. (Swift's `AttributedString` assigns rather than
        // unions here and keeps only the outer trait; that is a Swift-side
        // gap this port deliberately does not carry over. See the KDoc on
        // RichTextFolding.)
        val inner = folded.runs.first { it.text == "c" }
        assertTrue("expected \"c\" to be both strong and emphasised, was $inner",
            inner.strong && inner.emphasis)
    }

    /** "a link keeps its destination" */
    @Test
    fun linkKeepsItsHref() {
        val folded = RichTextFolding.attributed(listOf(
            RichInline.Link("https://e.org/x", listOf(RichInline.Text("go"))),
        ))
        assertEquals("go", folded.text)
        assertTrue(folded.runs.any { it.link == "https://e.org/x" })
    }

    /** "a link with an unusable destination keeps its words" */
    @Test
    fun brokenLinkKeepsText() {
        // Losing the target is a degradation; losing the sentence is a bug.
        // A blank href, which never reaches `URI(...)` at all — `isBlank()`
        // short-circuits it first — and a genuinely unparseable one, which
        // does: `URI(...)` throws a `URISyntaxException` on the space, so
        // this is the case that actually exercises `runCatching`'s catch arm
        // rather than `isUsableHref`'s earlier, cheaper check.
        val blank = RichTextFolding.attributed(listOf(
            RichInline.Link("", listOf(RichInline.Text("still readable"))),
        ))
        assertEquals("still readable", blank.text)
        assertTrue("a blank href must not be kept as a link", blank.runs.none { it.link != null })

        val unparseable = RichTextFolding.attributed(listOf(
            RichInline.Link("http://exa mple.com", listOf(RichInline.Text("still readable too"))),
        ))
        assertEquals("still readable too", unparseable.text)
        assertTrue(
            "an unparseable href must not be kept as a link",
            unparseable.runs.none { it.link != null },
        )
    }

    /** "inline code is marked as code, not as plain text" */
    @Test
    fun codeIsMarked() {
        val folded = RichTextFolding.attributed(listOf(RichInline.Code("cargo test")))
        assertEquals("cargo test", folded.text)
        assertTrue(folded.runs.any { it.code })
    }

    /** "a break is a newline, so a hard-wrapped message stays wrapped" */
    @Test
    fun breakIsANewline() {
        val folded = RichTextFolding.attributed(listOf(
            RichInline.Text("one"), RichInline.Break, RichInline.Text("two"),
        ))
        assertEquals("one\ntwo", folded.text)
    }

    /** "an empty run folds to nothing rather than to a space" */
    @Test
    fun emptyIsEmpty() {
        assertTrue(RichTextFolding.attributed(emptyList()).text.isEmpty())
    }
}
