package dev.supermessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.SystemKind
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

class TimelineSwipeTest {
    private fun row(view: ItemView, timestampMs: ULong? = 1_000uL) = TimelineRowDto(
        item = TimelineItemDto(
            id = "1", eventId = "1", kind = "message", msgtype = "m.text", detail = null,
            sender = "@a:x", senderDisplayName = null, senderAvatar = null, body = "hi",
            formattedBody = null, media = null, customPayload = null, timestampMs = timestampMs,
            isOwn = false, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = false, membershipSubject = null,
        ),
        view = view,
        senderName = "Sender", senderShort = "Sender", senderInitial = "S", membershipVerb = null,
        replyQuote = null, canReplyOrReact = true, replyPreview = null,
    )

    private val bubble = ItemView.Bubble(
        muted = false,
        blocks = listOf(RichBlock.Paragraph(inlines = listOf(RichInline.Text("hi")))),
        voice = null,
    )

    @Test
    fun theRubberBandIsOneToOneUpToTheWidthAndAFifthBeyond() {
        assertEquals(30f, rubberBand(30f, 68f), 0f)
        assertEquals(68f, rubberBand(68f, 68f), 0f)
        assertEquals(88f, rubberBand(168f, 68f), 0.001f)
    }

    @Test
    fun rowsThatStayPutShowNoTime() {
        val clock = { ts: ULong -> "t$ts" }
        assertNull(revealedTime(row(ItemView.DateDivider), clock))
        assertNull(revealedTime(row(ItemView.UnreadMarker), clock))
        assertNull(revealedTime(row(ItemView.System(kind = SystemKind.EncryptionEnabled, text = "joined")), clock))
        assertNull(revealedTime(row(ItemView.None), clock))
    }

    @Test
    fun aBubbleShowsItsClock() {
        assertEquals("t1000", revealedTime(row(bubble), { ts: ULong -> "t$ts" }))
        assertNull(revealedTime(row(bubble, timestampMs = null), { "t$it" }))
    }
}
