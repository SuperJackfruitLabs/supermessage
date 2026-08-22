package dev.supermessage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.SearchResultDto

/**
 * [SearchPanel], the port of `apple/Supermessage/Panels/SearchPanel.swift`.
 *
 * Every test drives the composable directly with a fake suspend lambda
 * standing in for `Session.search` — the same shape `RoomInfoTest` already
 * exercises `RoomInfoPanel` with, since this panel has no store either (see
 * [SearchPanel]'s own KDoc on why).
 */
class SearchTest {
    @get:Rule val compose = createComposeRule()

    private fun hit(id: String, roomId: String = "!room:example.org", body: String, timestampMs: ULong) =
        SearchResultDto(eventId = id, roomId = roomId, sender = "@alice:example.org", body = body, timestampMs = timestampMs)

    /**
     * `searchMessages` returns hits already ordered; this panel renders that
     * order and computes none of its own.
     *
     * The three hits are handed back **not** sorted by timestamp in either
     * direction (3000, then 1000, then 2000) — deliberately, so that a
     * `SearchPanel` which secretly sorted by time (ascending or descending)
     * would render a different order than the core gave it, failing this
     * assertion. This is the test the brief's mandated mutation targets:
     * see this task's report for the actual failure output from sorting the
     * results client-side.
     */
    @Test
    fun resultsRenderInCoreOrder() {
        val results = listOf(
            hit("1", body = "third one chronologically", timestampMs = 3_000uL),
            hit("2", body = "first one chronologically", timestampMs = 1_000uL),
            hit("3", body = "second one chronologically", timestampMs = 2_000uL),
        )

        compose.setContent {
            SearchPanel(
                scope = null,
                onOpen = {},
                onClose = {},
                search = { _, _ -> results },
            )
        }

        compose.onNodeWithTag("search-field").performTextInput("pager")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.waitForIdle()

        val first = compose.onNodeWithText("third one chronologically").fetchSemanticsNode().boundsInRoot.top
        val second = compose.onNodeWithText("first one chronologically").fetchSemanticsNode().boundsInRoot.top
        val third = compose.onNodeWithText("second one chronologically").fetchSemanticsNode().boundsInRoot.top

        assertTrue(
            "expected the core's own order (third, first, second chronologically), not a re-sort: " +
                "top positions were $first, $second, $third",
            first < second && second < third,
        )
    }

    /** A query that ran and found nothing says so, rather than showing a blank pane. */
    @Test
    fun anEmptyResultSaysSo() {
        compose.setContent {
            SearchPanel(scope = null, onOpen = {}, onClose = {}, search = { _, _ -> emptyList() })
        }

        compose.onNodeWithTag("search-field").performTextInput("nothing to find")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.waitForIdle()

        compose.onNodeWithTag("search-empty").assertIsDisplayed()
        compose.onNodeWithText("Nothing found for \"nothing to find\".").assertIsDisplayed()
        compose.onNodeWithTag("search-loading").assertDoesNotExist()
        compose.onNodeWithTag("search-results").assertDoesNotExist()
    }

    /**
     * A query in flight is distinguishable from one that returned nothing —
     * both look identical to a query that has yet to run unless the loading
     * state is its own, separate thing.
     */
    @Test
    fun aSearchInFlightIsDistinguishableFromEmpty() {
        val gate = CompletableDeferred<List<SearchResultDto>>()

        compose.setContent {
            SearchPanel(scope = null, onOpen = {}, onClose = {}, search = { _, _ -> gate.await() })
        }

        compose.onNodeWithTag("search-field").performTextInput("pager")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.waitForIdle()

        compose.onNodeWithTag("search-loading").assertIsDisplayed()
        compose.onNodeWithText("Searching…").assertIsDisplayed()
        compose.onNodeWithTag("search-empty").assertDoesNotExist()
        compose.onNodeWithTag("search-results").assertDoesNotExist()

        gate.complete(emptyList())
        compose.waitForIdle()

        compose.onNodeWithTag("search-empty").assertIsDisplayed()
        compose.onNodeWithTag("search-loading").assertDoesNotExist()
    }

    /** Before anything is typed, the pane invites a search rather than showing nothing. */
    @Test
    fun idleInvitesASearch() {
        compose.setContent {
            SearchPanel(scope = null, onOpen = {}, onClose = {}, search = { _, _ -> emptyList() })
        }
        compose.waitForIdle()

        compose.onNodeWithTag("search-idle").assertIsDisplayed()
        compose.onNodeWithText("Find a message across your rooms.").assertIsDisplayed()
    }

    /** Typed but not yet submitted is its own state, not the empty-results state. */
    @Test
    fun typedButNotSubmittedIsReadyNotEmpty() {
        compose.setContent {
            SearchPanel(scope = null, onOpen = {}, onClose = {}, search = { _, _ -> emptyList() })
        }

        compose.onNodeWithTag("search-field").performTextInput("pager")
        compose.waitForIdle()

        compose.onNodeWithTag("search-ready").assertIsDisplayed()
        compose.onNodeWithText("Search for pager").assertIsDisplayed()
        compose.onNodeWithTag("search-empty").assertDoesNotExist()
    }

    /** Tapping a result opens its room, and closes the search pane behind it. */
    @Test
    fun tappingAResultOpensItsRoomAndCloses() {
        var opened: String? = null
        var closed = false
        val results = listOf(hit("1", roomId = "!target:example.org", body = "found it", timestampMs = 1_000uL))

        compose.setContent {
            SearchPanel(
                scope = null,
                onOpen = { opened = it },
                onClose = { closed = true },
                search = { _, _ -> results },
            )
        }

        compose.onNodeWithTag("search-field").performTextInput("pager")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.waitForIdle()

        compose.onNodeWithTag("search-result-1").performClick()
        compose.waitForIdle()

        assertEquals("!target:example.org", opened)
        assertEquals(true, closed)
    }

    /**
     * Opened from inside a room, a search starts narrowed to it — and
     * narrowing again re-asks rather than leaving the wide search's results
     * under the narrow label, or vice versa.
     */
    @Test
    fun narrowingToARoomReRunsWithItsId() {
        var lastRoomId = "unset"
        compose.setContent {
            SearchPanel(
                scope = SearchPanelScope(roomId = "!ops:example.org", name = "Ops Room"),
                onOpen = {},
                onClose = {},
                search = { _, roomId -> lastRoomId = roomId ?: "null"; emptyList() },
            )
        }

        compose.onNodeWithTag("search-field").performTextInput("pager")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.waitForIdle()

        assertEquals("started narrowed to the room search was opened from", "!ops:example.org", lastRoomId)

        compose.onNodeWithTag("search-scope-all").performClick()
        compose.waitForIdle()

        assertEquals("switching to All rooms re-ran unscoped", "null", lastRoomId)
    }
}
