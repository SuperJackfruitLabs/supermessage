package dev.supermessage.kit

import uniffi.supermessage_core.SearchResultDto

/**
 * Where a search has got to.
 *
 * Modelled as states rather than a pair of booleans because the booleans
 * were what went wrong: `searched` only became true *after* a query ran, so
 * typing left the untouched empty state on screen — the magnifying glass and
 * "Find a message across your rooms" — and a reader could not tell whether
 * the app was thinking, had found nothing, or had ignored them.
 */
sealed class SearchState {
    /** Nothing typed. The only state that may show the invitation to
     * search. */
    data object Idle : SearchState()

    /** Something typed, not yet run. Says how to run it rather than
     * pretending nothing has happened. */
    data class Ready(val q: String) : SearchState()

    /** Running. **The state that did not exist**, and the reason a working
     * search looked broken. */
    data class Searching(val q: String) : SearchState()

    data class Found(val results: List<SearchResultDto>) : SearchState()

    /** Ran, and there is nothing. Names the query, because "no results"
     * alone leaves a reader wondering which query it means. */
    data class Empty(val q: String) : SearchState()

    /**
     * What typing does, from wherever we are.
     *
     * Deliberately keeps results on screen while the query is being edited:
     * a list that empties on the first keystroke of a correction is a list
     * that throws away what you were looking at.
     */
    fun typed(query: String): SearchState {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Idle
        if (this is Found && results.isNotEmpty()) return this
        return Ready(trimmed)
    }

    val query: String
        get() = when (this) {
            is Idle -> ""
            is Ready -> q
            is Searching -> q
            is Empty -> q
            is Found -> ""
        }
}
