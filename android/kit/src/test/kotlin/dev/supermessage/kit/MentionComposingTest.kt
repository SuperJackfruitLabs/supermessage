package dev.supermessage.kit

import dev.supermessage.kit.MentionComposing.Query
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.supermessage_core.PersonDto

class MentionComposingTest {
    private val atlas = PersonDto("@atlas:x", "Atlas", "A", null, null)

    @Test
    fun aBareAtOpensAnEmptyQuery() = assertEquals(Query("", 0), MentionComposing.activeQuery("@"))

    @Test
    fun theQueryIsWhatFollowsTheAt() = assertEquals(Query("at", 3), MentionComposing.activeQuery("hi @at"))

    @Test
    fun anAtInsideAnAddressIsNotAMention() = assertNull(MentionComposing.activeQuery("ana@example.org"))

    @Test
    fun aQueryEndsAtWhitespace() = assertNull(MentionComposing.activeQuery("@atlas "))

    @Test
    fun aQueryPastTheLongestIsASentence() {
        assertNull(MentionComposing.activeQuery("@" + "a".repeat(41)))
        assertEquals(Query("a".repeat(40), 0), MentionComposing.activeQuery("@" + "a".repeat(40)))
    }

    @Test
    fun insertingReplacesTheQueryAndLeavesASpace() =
        assertEquals("hi @Atlas ", MentionComposing.insert(atlas, Query("at", 3), "hi @at"))

    @Test
    fun anAstralCharacterBeforeTheAtKeepsTheUtf16Offset() {
        val query = MentionComposing.activeQuery("🧠 @a")
        assertEquals(3, query?.atOffset)
        assertEquals("🧠 @Atlas ", MentionComposing.insert(atlas, query!!, "🧠 @a"))
    }
}
