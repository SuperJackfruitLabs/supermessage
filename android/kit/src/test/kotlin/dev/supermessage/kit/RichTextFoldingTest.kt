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
        val folded = RichTextFolding.attributed(listOf(
            RichInline.Link("", listOf(RichInline.Text("still readable"))),
        ))
        assertEquals("still readable", folded.text)
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
