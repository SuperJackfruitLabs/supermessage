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
        // "No results" alone leaves a reader wondering which query it means,
        // which matters when the field still holds a half-typed correction.
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

    /**
     * "a search that fails is not a search that found nothing" — the bug
     * this task fixes. `Failed` has to be distinguishable from `Empty`,
     * not just a message bolted onto it, or a reader is right back to not
     * being able to tell a refusal from zero hits.
     */
    @Test
    fun failedIsNotEmpty() {
        assertNotEquals(
            SearchState.Empty("hello"),
            SearchState.Failed("hello", "Can't reach the homeserver."),
        )
    }

    /** "a failure still names what was searched for" — same reason `Empty` does. */
    @Test
    fun failedNamesTheQueryAndCarriesTheMessage() {
        val failed = SearchState.Failed("hello", "Can't reach the homeserver.")
        assertEquals("hello", failed.query)
        assertEquals("Can't reach the homeserver.", failed.message)
    }

    /** "editing after a failure still leaves the field to correct" — a failure is not `Found`, so it does not stick. */
    @Test
    fun typingAfterAFailureMovesOnToReady() {
        val failed = SearchState.Failed("hello", "Can't reach the homeserver.")
        assertEquals(SearchState.Ready("hell"), failed.typed("hell"))
    }
}
