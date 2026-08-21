package dev.supermessage.kit.stores

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow

/**
 * Ported from `apple/SupermessageKitTests/ComposerStateTests.swift`'s
 * `ReplyTargetTests` — that file is misnamed (there is no Swift
 * `ComposerState`), but its rules are real and are carried across whole.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplyTargetTest {

    private fun row(id: String, eventId: String? = id, sender: String, preview: String?): TimelineRow {
        val item = TimelineItemDto(
            id = id,
            eventId = eventId,
            kind = "message",
            msgtype = "m.text",
            detail = null,
            sender = "@a:x",
            senderDisplayName = sender,
            senderAvatar = null,
            body = "body",
            formattedBody = null,
            media = null,
            customPayload = null,
            timestampMs = 1u,
            isOwn = false,
            sendState = null,
            replyTo = null,
            edited = false,
            reactions = emptyList(),
            readBy = emptyList(),
            editable = false,
        )
        return TimelineRow(
            item = item,
            view = ItemView.Bubble(muted = false, blocks = emptyList()),
            senderName = sender,
            senderShort = sender,
            membershipVerb = null,
            replyQuote = null,
            canReplyOrReact = true,
            replyPreview = preview,
        )
    }

    /** "a reply addresses the event, not the row's identity" */
    @Test
    fun `a reply addresses the event, not the row's identity`() {
        // `m.in_reply_to` takes an event id. `item.id` is the SDK's stable
        // identity, which holds still across the local-echo-to-confirmed
        // transition precisely *because* it is not the event id — so sending
        // it would address an event the homeserver has never heard of.
        //
        // They were one field until identity was split out, which is why
        // reading the wrong one was invisible.
        val target = ReplyTarget()
        target.start(row(id = "unique-9", eventId = "\$real:x", sender = "Ganesha", preview = null), roomId = "!a:x")
        assertEquals("\$real:x", target.pending(roomId = "!a:x")?.eventId)
    }

    /** "a reply target takes its name and preview from the row, not from the body" */
    @Test
    fun `a reply target takes its name and preview from the row, not from the body`() {
        // The attribution chain and the excerpt's bounding are the core's, so
        // the composer shows exactly what the timeline showed.
        val target = ReplyTarget()
        target.start(row(id = "\$1", sender = "Ganesha", preview = "the original"), roomId = "!a:x")

        val pending = target.pending(roomId = "!a:x")
        assertEquals("\$1", pending?.eventId)
        assertEquals("Ganesha", pending?.sender)
        assertEquals("the original", pending?.excerpt)
    }

    /** "a parent with nothing to preview still makes a valid target" */
    @Test
    fun `a parent with nothing to preview still makes a valid target`() {
        // A media message with no caption. Replying to it must still work.
        val target = ReplyTarget()
        target.start(row(id = "\$1", sender = "Ganesha", preview = null), roomId = "!a:x")
        assertEquals("\$1", target.pending(roomId = "!a:x")?.eventId)
        assertNull(target.pending(roomId = "!a:x")?.excerpt)
    }

    /** "a reply target is scoped to its room" */
    @Test
    fun `a reply target is scoped to its room`() {
        val target = ReplyTarget()
        target.start(row(id = "\$1", sender = "Ganesha", preview = "x"), roomId = "!a:x")
        assertNull(target.pending(roomId = "!b:x"))
    }

    /**
     * Not from Swift: `StateFlow` conflates equal values in a way an
     * `@Observable` property does not need pinning for. A view driven by
     * [ReplyTarget.targets] depends on this, so it is asserted rather than
     * assumed.
     */
    @Test
    fun `starting the same reply twice emits only once`() = runTest {
        val target = ReplyTarget()
        val seen = mutableListOf<Map<String, ReplyTarget.Pending>>()
        val job = launch { target.targets.collect { seen.add(it) } }
        runCurrent() // let the collector attach and receive the initial value first

        val theRow = row(id = "\$1", sender = "Ganesha", preview = "x")
        target.start(theRow, roomId = "!a:x")
        target.start(theRow, roomId = "!a:x")
        advanceUntilIdle()

        assertEquals(2, seen.size)
        job.cancel()
    }

    /** "cancelling a reply clears its room and leaves nothing behind" */
    @Test
    fun `cancelling a reply clears it, and clearing it emits null`() = runTest {
        val target = ReplyTarget()
        val seen = mutableListOf<ReplyTarget.Pending?>()
        val job = launch { target.targets.collect { seen.add(it["!a:x"]) } }
        runCurrent()

        target.start(row(id = "\$1", sender = "Ganesha", preview = "x"), roomId = "!a:x")
        target.cancel("!a:x")
        advanceUntilIdle()

        assertNull(seen.last())
        assertNull(target.pending(roomId = "!a:x"))
        job.cancel()
    }
}
