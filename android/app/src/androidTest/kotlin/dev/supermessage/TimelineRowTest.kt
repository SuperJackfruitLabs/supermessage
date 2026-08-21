package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.CustomEventView
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.MediaFileLabel
import uniffi.supermessage_core.ReactionDto
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.RichTableCell
import uniffi.supermessage_core.RichTableRow
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * One timeline row, drawn from the core's [ItemView] decision — see
 * `TimelineRow.kt`'s own KDoc and `apple/Supermessage/Timeline/TimelineRowView.swift`.
 *
 * `everyVariantIsHandled` is the important one: a `when` that silently misses
 * a variant renders an empty row, which looks like data loss and is invisible
 * to a test that only checks bubbles. It is not "renders something visible"
 * — `None` renders deliberately nothing (iOS returns `EmptyView()`), and
 * `UnreadMarker` renders a rule with no label on purpose. The property under
 * test is that no variant falls through unhandled, which with no `else`
 * branch is largely the compiler's job — so this asserts the nine visible
 * variants render their distinguishing content, and that `None` renders
 * nothing at all.
 */
class TimelineRowTest {
    @get:Rule val compose = createComposeRule()

    /** A fixed instant, so "Today" never depends on when this test runs. */
    private val now: Instant = Instant.parse("2024-01-15T12:00:00Z")

    private fun paragraph(text: String): List<RichBlock> =
        listOf(RichBlock.Paragraph(inlines = listOf(RichInline.Text(text))))

    private fun row(
        view: ItemView,
        senderName: String = "Sender",
        sender: String? = "@sender:example.org",
        isOwn: Boolean = false,
        body: String? = "hi",
        timestampMs: ULong? = now.toEpochMilli().toULong(),
        sendState: String? = null,
        reactions: List<ReactionDto> = emptyList(),
        readBy: List<String> = emptyList(),
    ): TimelineRowDto {
        val item = TimelineItemDto(
            id = "\$1",
            eventId = "\$1",
            kind = "message",
            msgtype = "m.text",
            detail = null,
            sender = sender,
            senderDisplayName = null,
            senderAvatar = null,
            body = body,
            formattedBody = null,
            media = null,
            customPayload = null,
            timestampMs = timestampMs,
            isOwn = isOwn,
            sendState = sendState,
            replyTo = null,
            edited = false,
            reactions = reactions,
            readBy = readBy,
            editable = true,
        )
        return TimelineRowDto(
            item = item,
            view = view,
            senderName = senderName,
            senderShort = senderName,
            membershipVerb = null,
            replyQuote = null,
            canReplyOrReact = true,
            replyPreview = null,
        )
    }

    @Test
    fun everyVariantIsHandled() {
        compose.setContent {
            Column {
                TimelineRow(row = row(view = ItemView.Bubble(muted = false, blocks = paragraph("bubble text"))), now = now)
                TimelineRow(row = row(view = ItemView.Emote, body = "waved"), now = now)
                TimelineRow(row = row(view = ItemView.System(text = "system text")), now = now)
                TimelineRow(row = row(view = ItemView.Placeholder(text = "placeholder text")), now = now)
                TimelineRow(row = row(view = ItemView.DateDivider), now = now)
                TimelineRow(row = row(view = ItemView.UnreadMarker), now = now)
                TimelineRow(row = row(view = ItemView.Image(alt = "a sunset", width = 100uL, height = 50uL)), now = now)
                TimelineRow(
                    row = row(
                        view = ItemView.MediaFile(
                            label = MediaFileLabel.FILE,
                            filename = "report.pdf",
                            size = 2048uL,
                            mimetype = null,
                        ),
                    ),
                    now = now,
                )
                TimelineRow(
                    row = row(
                        view = ItemView.CustomEvent(
                            view = CustomEventView.Placeholder(text = "nothing usable"),
                            label = "Turn",
                            eventType = "dev.agentpod.turn.v1",
                        ),
                    ),
                    now = now,
                )
                Box(Modifier.testTag("none-holder")) {
                    TimelineRow(row = row(view = ItemView.None), now = now)
                }
            }
        }

        compose.onNodeWithText("bubble text").assertIsDisplayed()
        compose.onNodeWithText("Sender waved").assertIsDisplayed()
        compose.onNodeWithText("system text").assertIsDisplayed()
        compose.onNodeWithText("placeholder text").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithTag("unread-marker").assertIsDisplayed()
        compose.onNodeWithText("a sunset").assertIsDisplayed()
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        compose.onNodeWithText("Turn").assertIsDisplayed()
        // `None` occupies no layout space at all — not an empty box, nothing.
        compose.onNodeWithTag("none-holder").onChildren().assertCountEquals(0)
    }

    /** A muted bubble (m.notice) is visually distinct but still legible. */
    @Test
    fun aMutedBubbleStillShowsItsText() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Bubble(muted = true, blocks = paragraph("automated notice text"))),
                now = now,
            )
        }
        compose.onNodeWithText("automated notice text").assertIsDisplayed()
    }

    /** Attribution comes from senderName; the row derives no names. */
    @Test
    fun attributionComesFromTheRow() {
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("hello")),
                    senderName = "Cleaner Cody",
                ),
                now = now,
                attribution = "",
            )
        }
        compose.onNodeWithText("Cleaner Cody").assertIsDisplayed()
    }

    /** An image with no loaded bytes shows its alt text rather than a blank box. */
    @Test
    fun anImageWithoutBytesShowsItsAlt() {
        compose.setContent {
            TimelineRow(
                row = row(view = ItemView.Image(alt = "a lighthouse", width = null, height = null)),
                now = now,
            )
        }
        compose.onNodeWithText("a lighthouse").assertIsDisplayed()
    }

    /**
     * Carried from Task 2's review: `RichText`'s `Table` scrolls inside
     * itself only if the container it sits in is bounded. A wide table in a
     * bubble must stay inside the bubble's own bounded width rather than
     * stretching the row.
     */
    @Test
    fun aWideTableStaysWithinTheBubblesWidth() {
        fun cell(text: String) = RichTableCell(inlines = listOf(RichInline.Text(text)))
        val blocks = listOf(
            RichBlock.Table(
                header = listOf(
                    cell("Column One Header"),
                    cell("Column Two Header"),
                    cell("Column Three Header"),
                    cell("Column Four Header"),
                ),
                rows = listOf(
                    RichTableRow(
                        cells = listOf(
                            cell("A very long cell value one"),
                            cell("A very long cell value two"),
                            cell("A very long cell value three"),
                            cell("A very long cell value four"),
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            Box(Modifier.width(1000.dp)) {
                TimelineRow(row = row(view = ItemView.Bubble(muted = false, blocks = blocks)), now = now)
            }
        }
        val width = compose.onNodeWithTag("bubble").getUnclippedBoundsInRoot().width
        assertTrue("bubble should stay within its bounded width, was $width", width <= 320.dp)
    }

    /** Existing reactions and read receipts are drawn, read-only, in this phase. */
    @Test
    fun reactionsAndReadReceiptsRenderReadOnly() {
        compose.setContent {
            TimelineRow(
                row = row(
                    view = ItemView.Bubble(muted = false, blocks = paragraph("done")),
                    isOwn = true,
                    readBy = listOf("@a:example.org", "@b:example.org"),
                    reactions = listOf(
                        ReactionDto(
                            key = "✅",
                            displayKey = "✅",
                            count = 2u,
                            byMe = true,
                            senders = listOf("@sender:example.org", "@a:example.org"),
                        ),
                    ),
                ),
                now = now,
            )
        }
        compose.onNodeWithText("✅", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Read by", substring = true).assertIsDisplayed()
    }
}
