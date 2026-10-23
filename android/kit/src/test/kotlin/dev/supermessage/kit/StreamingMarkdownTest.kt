package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTest {
    @Test fun noNewlineIsAllTail() = assertEquals("" to "Hello wor", StreamingMarkdown.split("Hello wor", 3))

    @Test fun splitsBeforeTheArrivingChunk() =
        assertEquals("First line." to "Second li", StreamingMarkdown.split("First line.\nSecond li", 2))

    @Test fun aLineEndingInTheChunkStillFades() = assertEquals("" to "Done.", StreamingMarkdown.split("Done.\n", 6))

    @Test fun headingSettlesWhenWhole() = assertEquals("# Title" to "Body", StreamingMarkdown.split("# Title\nBody", 4))

    @Test fun listItemSettlesWhenWhole() =
        assertEquals("Intro\n- one" to "- tw", StreamingMarkdown.split("Intro\n- one\n- tw", 2))

    @Test fun blockMarkupRecognisesOpeners() {
        assertTrue(StreamingMarkdown.blockMarkup("12. step"))
        assertFalse(StreamingMarkdown.blockMarkup("12 apples"))
        assertTrue(StreamingMarkdown.blockMarkup("  > quote"))
    }

    @Test fun anArrivingCountPastTheTextIsClamped() = assertEquals("" to "ab", StreamingMarkdown.split("ab", 99))
}
