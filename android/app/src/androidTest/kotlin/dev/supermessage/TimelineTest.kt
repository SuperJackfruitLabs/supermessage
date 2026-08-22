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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.ReactionDto
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

    private fun row(
        id: String,
        body: String,
        timestampMs: ULong,
        canReplyOrReact: Boolean = true,
        editable: Boolean = false,
        isOwn: Boolean = false,
        reactions: List<ReactionDto> = emptyList(),
    ): TimelineRowDto {
        val item = TimelineItemDto(
            id = id, eventId = id, kind = "message", msgtype = "m.text", detail = null,
            sender = "@a:x", senderDisplayName = null, senderAvatar = null, body = body,
            formattedBody = null, media = null, customPayload = null, timestampMs = timestampMs,
            isOwn = isOwn, sendState = null, replyTo = null, edited = false,
            reactions = reactions, readBy = emptyList(), editable = editable,
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
            canReplyOrReact = canReplyOrReact,
            replyPreview = null,
        )
    }

    /** `count` rows, ascending timestamp — the same order `TimelineStore.items` holds. */
    private fun ascendingRows(count: Int): List<TimelineRowDto> =
        (1..count).map { row(id = "$it", body = "row $it", timestampMs = it.toULong() * 1_000uL) }

    /** A membership row: kind and `detail` are what `collapseMembershipRuns` groups on. */
    private fun membershipRow(id: String, sender: String, verb: String, timestampMs: ULong): TimelineRowDto {
        val item = TimelineItemDto(
            id = id, eventId = id, kind = "membership", msgtype = null, detail = verb,
            sender = "@$sender:x", senderDisplayName = sender, senderAvatar = null, body = null,
            formattedBody = null, media = null, customPayload = null, timestampMs = timestampMs,
            isOwn = false, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = false,
        )
        return TimelineRowDto(
            item = item,
            view = ItemView.System(text = "$sender $verb"),
            senderName = sender,
            senderShort = sender,
            membershipVerb = verb,
            replyQuote = null,
            canReplyOrReact = false,
            replyPreview = null,
        )
    }

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

    /**
     * A run of identical membership changes collapses into one line.
     *
     * Found on a device: eight consecutive "Annapurna … updated their
     * membership" rows filled the screen. `:kit` has collapsed these since
     * the port; the container simply never called it.
     */
    @Test
    fun aRunOfMembershipChangesCollapses() {
        val senders = listOf("Ganesha", "Krishna", "Annapurna", "Surya", "A5", "A6", "A7", "A8")
        val fixed = senders.mapIndexed { index, sender ->
            membershipRow(
                id = "${index + 1}",
                sender = sender,
                verb = "updated their membership",
                timestampMs = (index + 1).toULong() * 1_000uL,
            )
        }

        compose.setContent {
            Timeline(
                rows = fixed,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
            )
        }

        // One collapsed line, not eight identical ones.
        compose.onNodeWithText("Ganesha, Krishna and 6 others updated their membership")
            .assertIsDisplayed()
        // The rows that fed the run are no longer drawn individually.
        compose.onNodeWithTag("row-2").assertDoesNotExist()
        compose.onNodeWithTag("row-8").assertDoesNotExist()
    }

    /**
     * Runs break on a different verb — "three joined" and "one left" stay two
     * sentences rather than becoming one that is true of neither.
     */
    @Test
    fun aDifferentVerbStartsANewRun() {
        val fixed = listOf(
            membershipRow(id = "1", sender = "Ganesha", verb = "joined the room", timestampMs = 1_000uL),
            membershipRow(id = "2", sender = "Krishna", verb = "left the room", timestampMs = 2_000uL),
        )

        compose.setContent {
            Timeline(
                rows = fixed,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
            )
        }

        compose.onNodeWithText("Ganesha joined the room").assertIsDisplayed()
        compose.onNodeWithText("Krishna left the room").assertIsDisplayed()
    }

    /**
     * A long press on a row with something to offer (`canReplyOrReact`)
     * reports that row — Task 6's wiring for starting a reply, the same
     * layer `TimelineCollectionView.swift`'s own long-press context menu and
     * swipe action live on, never `TimelineRowView`/`TimelineRow` itself.
     */
    @Test
    fun aLongPressOnAReplyableRowReportsIt() {
        var pressed: TimelineRowDto? = null
        val fixed = listOf(row(id = "1", body = "hello", timestampMs = 1_000uL, canReplyOrReact = true))

        compose.setContent {
            Timeline(
                rows = fixed,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
                onRowLongPress = { pressed = it },
            )
        }

        compose.onNodeWithTag("row-1").performTouchInput { longClick() }
        compose.waitForIdle()

        assertEquals("1", pressed?.item?.id)
    }

    /**
     * A row that cannot be replied to and cannot be edited gets no gesture
     * at all — see `Timeline`'s own class doc for why the gesture's very
     * *existence* is gated on those two flags, narrower than iOS's two-item
     * menu that is built unconditionally and merely omits an item.
     */
    @Test
    fun aRowWithNothingToOfferGetsNoLongPressGesture() {
        var pressed: TimelineRowDto? = null
        val fixed = listOf(
            row(id = "1", body = "hello", timestampMs = 1_000uL, canReplyOrReact = false, editable = false),
        )

        compose.setContent {
            Timeline(
                rows = fixed,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
                onRowLongPress = { pressed = it },
            )
        }

        compose.onNodeWithTag("row-1").performTouchInput { longClick() }
        compose.waitForIdle()

        assertNull(pressed)
    }

    /**
     * Tapping an existing reaction chip reports which row it belongs to
     * alongside the wire key — [Timeline] curries [TimelineRow]'s own
     * `onReact: ((String) -> Unit)?` with the row, since [TimelineRow] has
     * no notion of "which message is this from the caller's point of view."
     */
    @Test
    fun tappingAReactionChipReportsTheRowAndKey() {
        var reactedRow: TimelineRowDto? = null
        var reactedKey: String? = null
        val fixed = listOf(
            row(
                id = "1", body = "hello", timestampMs = 1_000uL,
                reactions = listOf(
                    ReactionDto(key = "👍", displayKey = "👍", count = 1u, byMe = false, senders = listOf("@a:x")),
                ),
            ),
        )

        compose.setContent {
            Timeline(
                rows = fixed,
                typingLine = null,
                isPaginating = false,
                canPaginate = false,
                onPaginate = {},
                onMarkRead = {},
                onReact = { row, key -> reactedRow = row; reactedKey = key },
            )
        }

        compose.onNodeWithTag("reaction-👍").performClick()
        compose.waitForIdle()

        assertEquals("1", reactedRow?.item?.id)
        assertEquals("👍", reactedKey)
    }
}
