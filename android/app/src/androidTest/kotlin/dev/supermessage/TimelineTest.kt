package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * The scroll container — see `Timeline.kt`'s own KDoc and
 * `apple/Supermessage/Timeline/TimelineCollectionView.swift`'s header.
 *
 * **Geometry, not existence** governs [theNewestMessageIsVisibleOnOpen], the
 * same idiom `RootScaffoldTest.assertWithinShell` established: a row present
 * in the tree but scrolled to the wrong end of the screen is exactly the
 * fault that idiom exists to catch.
 */
class TimelineTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: String, body: String, timestampMs: ULong): TimelineRowDto {
        val item = TimelineItemDto(
            id = id, eventId = id, kind = "message", msgtype = "m.text", detail = null,
            sender = "@a:x", senderDisplayName = null, senderAvatar = null, body = body,
            formattedBody = null, media = null, customPayload = null, timestampMs = timestampMs,
            isOwn = false, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = false,
        )
        return TimelineRowDto(
            item = item,
            view = ItemView.Bubble(
                muted = false,
                blocks = listOf(RichBlock.Paragraph(inlines = listOf(RichInline.Text(body)))),
            ),
            senderName = "Sender",
            senderShort = "Sender",
            membershipVerb = null,
            replyQuote = null,
            canReplyOrReact = true,
            replyPreview = null,
        )
    }

    /** `count` rows, ascending timestamp — the same order `TimelineStore.items` holds. */
    private fun ascendingRows(count: Int): List<TimelineRowDto> =
        (1..count).map { row(id = "$it", body = "row $it", timestampMs = it.toULong() * 1_000uL) }

    /**
     * Rule 1: the room opens at its newest message, fully visible.
     *
     * All three rows fit comfortably in the shell, so this is not about
     * whether the newest row exists — it is about WHERE it sits. The newest
     * message (highest timestamp) must be lowest on screen, the reading
     * position every chat opens at, and its own bounds must sit above the
     * shell's bottom edge rather than merely be present in the tree.
     */
    @Test
    fun theNewestMessageIsVisibleOnOpen() {
        val fixed = listOf(
            row(id = "1", body = "oldest", timestampMs = 1_000uL),
            row(id = "2", body = "middle", timestampMs = 2_000uL),
            row(id = "3", body = "newest", timestampMs = 3_000uL),
        )

        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 640.dp).testTag("timeline-shell")) {
                Timeline(
                    rows = fixed,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = false,
                    onPaginate = {},
                    onMarkRead = {},
                )
            }
        }

        val shell = compose.onNodeWithTag("timeline-shell").fetchSemanticsNode().boundsInRoot
        val oldest = compose.onNodeWithTag("row-1").fetchSemanticsNode().boundsInRoot
        val newest = compose.onNodeWithTag("row-3").fetchSemanticsNode().boundsInRoot

        assertTrue("newest row was not laid out: $newest", newest.width > 0f)
        assertTrue(
            "newest row's bounds $newest fall below the shell's own bottom edge ${shell.bottom}",
            newest.bottom <= shell.bottom,
        )
        assertTrue(
            "the newest message must sit lowest on screen; newest.top=${newest.top} oldest.top=${oldest.top}",
            newest.top > oldest.top,
        )
    }

    /**
     * Rule 3, to the extent this composable's own signature can prove it: a
     * genuine change to `rows` (the container's stand-in for a revision
     * bump — `TimelineStore` only ever replaces `items` wholesale) is what
     * moves the list, and a recomposition entirely unrelated to `rows`
     * leaves what is already on screen untouched.
     */
    @Test
    fun theListFollowsRevisionRatherThanDiffingRows() {
        var rows by mutableStateOf(listOf(row(id = "1", body = "first", timestampMs = 1_000uL)))
        var unrelated by mutableStateOf(0)

        compose.setContent {
            Column {
                // Recomposes on its own; proves an unrelated recomposition
                // elsewhere in the tree does not disturb what Timeline
                // already drew.
                Text(
                    "marker:$unrelated",
                    modifier = Modifier.testTag("marker"),
                )
                Timeline(
                    rows = rows,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = false,
                    onPaginate = {},
                    onMarkRead = {},
                )
            }
        }

        compose.onNodeWithText("first").assertIsDisplayed()
        compose.onNodeWithTag("row-2").assertDoesNotExist()

        unrelated++
        compose.waitForIdle()
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.onNodeWithTag("row-2").assertDoesNotExist()

        // A revision bump: `TimelineStore` replaces `items` wholesale, never
        // patches it in place.
        rows = listOf(
            row(id = "1", body = "first", timestampMs = 1_000uL),
            row(id = "2", body = "second", timestampMs = 2_000uL),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("row-2").assertIsDisplayed()
    }

    /** Pagination fires when the reader reaches the older end, and not before. */
    @Test
    fun reachingTheOlderEndAsksForMore() {
        val fixed = ascendingRows(40)
        var paginateCalls = 0

        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 300.dp)) {
                Timeline(
                    rows = fixed,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = true,
                    onPaginate = { paginateCalls++ },
                    onMarkRead = {},
                )
            }
        }

        // Not before: freshly opened, at the newest end.
        compose.waitForIdle()
        assertEquals(0, paginateCalls)

        // The older end, inverted, is the tail of the list this container
        // was handed — `fixed.lastIndex` in `newestFirst`'s own index space.
        compose.onNodeWithTag("timeline-list").performScrollToIndex(fixed.lastIndex)
        compose.waitForIdle()

        assertTrue("reaching the older end should have asked for more", paginateCalls > 0)
    }

    /** Pagination stops asking once the store says there is no more. */
    @Test
    fun nothingIsAskedForWhenThereIsNoMore() {
        val fixed = ascendingRows(40)
        var paginateCalls = 0

        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 300.dp)) {
                Timeline(
                    rows = fixed,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = false,
                    onPaginate = { paginateCalls++ },
                    onMarkRead = {},
                )
            }
        }

        compose.onNodeWithTag("timeline-list").performScrollToIndex(fixed.lastIndex)
        compose.waitForIdle()

        assertEquals(0, paginateCalls)
    }

    /** The jump-to-newest affordance appears only when away from the newest end. */
    @Test
    fun theWayBackAppearsOnlyWhenAway() {
        val fixed = ascendingRows(40)

        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 300.dp)) {
                Timeline(
                    rows = fixed,
                    typingLine = null,
                    isPaginating = false,
                    canPaginate = false,
                    onPaginate = {},
                    onMarkRead = {},
                )
            }
        }

        compose.onNodeWithTag("jump-to-newest").assertDoesNotExist()

        compose.onNodeWithTag("timeline-list").performScrollToIndex(20)
        compose.waitForIdle()
        compose.onNodeWithTag("jump-to-newest").assertIsDisplayed()

        compose.onNodeWithTag("jump-to-newest").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("jump-to-newest").assertDoesNotExist()
    }

    /** The typing line shows what TypingStore said, and nothing when it is null. */
    @Test
    fun theTypingLineComesFromTheStore() {
        var typingLine by mutableStateOf<String?>("Ganesha is typing…")

        compose.setContent {
            Timeline(
                rows = emptyList(),
                typingLine = typingLine,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
            )
        }

        compose.onNodeWithText("Ganesha is typing…").assertIsDisplayed()

        typingLine = null
        compose.waitForIdle()
        compose.onNodeWithTag("typing-line").assertDoesNotExist()
    }
}
