package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import uniffi.supermessage_core.SearchResultDto

/** A search that looks broken while working is the worst way to be wrong. */
class SearchStateTest {

    private fun hit(id: String) = SearchResultDto(
        eventId = id, roomId = "!r:x", sender = "@a:x", body = "hello", timestampMs = 1u,
    )

    /** "typing leaves the untouched empty state behind" */
    @Test
    fun typingLeavesIdle() {
        // The bug. `searched` only became true after a query *ran*, so typing
        // left "Find a message across your rooms" on screen and a reader
        // could not tell thinking from ignoring.
        assertEquals(SearchState.Ready("hello"), SearchState.Idle.typed("hello"))
    }

    /** "clearing the field goes back to the invitation" */
    @Test
    fun clearingReturnsToIdle() {
        assertEquals(SearchState.Idle, SearchState.Ready("hello").typed(""))
        assertEquals(SearchState.Idle, SearchState.Ready("hello").typed("   "))
    }

    /** "editing a query does not throw away what you were reading" */
    @Test
    fun resultsSurviveEditing() {
        // A list that empties on the first keystroke of a correction is a
        // list that discards the thing you were looking at.
        val found = SearchState.Found(listOf(hit("1")))
        assertEquals(found, found.typed("hell"))
    }

    /** "a search with nothing in it still names what was searched for" */
    @Test
    fun emptyNamesTheQuery() {
        assertEquals("hello", SearchState.Empty("hello").query)
    }

    /** "running is a state of its own" */
    @Test
    fun searchingExists() {
        // It did not exist, and its absence is what made a working search
        // look like a broken one.
        assertEquals("hello", SearchState.Searching("hello").query)
        assertNotEquals(SearchState.Searching("hello"), SearchState.Ready("hello"))
    }
}
