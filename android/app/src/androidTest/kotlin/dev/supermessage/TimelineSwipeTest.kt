package dev.supermessage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.up
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
 * The horizontal gestures of the timeline (T3 reply, T4 times), with Reduce
 * Motion provided explicitly so nothing here depends on how the emulator was
 * started.
 */
class TimelineSwipeTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: Int, canReplyOrReact: Boolean = true): TimelineRowDto {
        val body = "message $id"
        val item = TimelineItemDto(
            id = "$id", eventId = "$id", kind = "message", msgtype = "m.text", detail = null,
            sender = "@a:x", senderDisplayName = null, senderAvatar = null, body = body,
            formattedBody = null, media = null, customPayload = null, timestampMs = 1_000uL * id.toULong(),
            isOwn = false, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = false, membershipSubject = null,
        )
        return TimelineRowDto(
            item = item,
            view = ItemView.Bubble(
                muted = false,
                blocks = listOf(RichBlock.Paragraph(inlines = listOf(RichInline.Text(body)))),
                voice = null,
            ),
            senderName = "Sender", senderShort = "Sender", senderInitial = "S", membershipVerb = null,
            replyQuote = null, canReplyOrReact = canReplyOrReact, replyPreview = null,
        )
    }

    private val replied = mutableListOf<String>()

    private fun show(rows: List<TimelineRowDto>) {
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Timeline(
                    rows = rows,
                    revision = 0uL,
                    isPaginating = false,
                    canPaginate = false,
                    onPaginate = {},
                    onMarkRead = {},
                    onSwipeReply = { replied += it.item.id },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private val five = (1..5).map { row(it) }

    @Test
    fun swipingRightStartsAReply() {
        show(five)
        compose.onNodeWithTag("row-1").performTouchInput { swipeRight(endX = left + 200f) }
        compose.waitForIdle()
        assertEquals(listOf("1"), replied)
    }

    @Test
    fun swipingAnUnrepliableRowDoesNothing() {
        show(listOf(row(1, canReplyOrReact = false)) + five.drop(1))
        compose.onNodeWithTag("row-1").performTouchInput { swipeRight(endX = left + 200f) }
        compose.waitForIdle()
        assertTrue("no reply for a row that cannot be replied to", replied.isEmpty())
    }

    @Test
    fun swipingLeftRevealsEveryTime() {
        show(five)
        compose.onNodeWithTag("row-3").performTouchInput {
            down(center)
            moveBy(Offset(-120f, 0f))
        }
        compose.onAllNodesWithTag("revealed-time").assertCountEquals(5)
        compose.onNodeWithTag("row-3").performTouchInput { up() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("revealed-time").assertCountEquals(0)
        assertTrue(replied.isEmpty())
    }

    @Test
    fun aVerticalDragRevealsNothing() {
        // Forty rows, so the list can scroll. Dragging down moves toward older rows.
        show((1..40).map { row(it) })
        repeat(3) {
            // Mostly vertical.
            compose.onNodeWithTag("timeline-list").performTouchInput {
                swipe(start = center, end = center + Offset(-30f, 400f))
            }
            compose.waitForIdle()
            compose.onAllNodesWithTag("revealed-time").assertCountEquals(0)

            // Diagonal, held: more across than down, but not twice as much. It
            // scrolls and must not reveal times while the finger is still on it.
            compose.onNodeWithTag("timeline-list").performTouchInput {
                down(center)
                moveBy(Offset(-300f, 200f))
            }
            compose.onAllNodesWithTag("revealed-time").assertCountEquals(0)
            compose.onNodeWithTag("timeline-list").performTouchInput { up() }
            compose.waitForIdle()
        }
        assertTrue("a mostly vertical drag never replies", replied.isEmpty())
        // It scrolled: the list is no longer at its newest end.
        compose.onNodeWithTag("jump-to-newest").assertExists()
    }
}
