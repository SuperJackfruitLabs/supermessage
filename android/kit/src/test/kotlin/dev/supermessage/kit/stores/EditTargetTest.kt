package dev.supermessage.kit.stores

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow

/** Ported from `apple/SupermessageKitTests/EditTargetTests.swift`. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditTargetTest {

    private fun row(editable: Boolean, eventId: String? = "\$e1:x.org", body: String? = "hello"): TimelineRow {
        val item = TimelineItemDto(
            id = "unique-1",
            eventId = eventId,
            kind = "message",
            msgtype = "m.text",
            detail = null,
            sender = "@me:x.org",
            senderDisplayName = "Me",
            senderAvatar = null,
            body = body,
            formattedBody = null,
            media = null,
            customPayload = null,
            timestampMs = 1_700_000_000_000u,
            isOwn = true,
            sendState = null,
            replyTo = null,
            edited = false,
            reactions = emptyList(),
            readBy = emptyList(),
            editable = editable,
        )
        return TimelineRow(
            item = item,
            view = ItemView.Bubble(muted = false, blocks = emptyList()),
            senderName = "Me",
            senderShort = "Me",
            membershipVerb = null,
            replyQuote = null,
            canReplyOrReact = eventId != null,
            replyPreview = null,
        )
    }

    /** "an edit starts from what the message actually says" */
    @Test
    fun `an edit starts from what the message actually says`() {
        val edits = EditTarget()
        assertEquals("hello", edits.start(row(editable = true), roomId = "!r:x"))
        assertEquals("\$e1:x.org", edits.pending(roomId = "!r:x")?.eventId)
    }

    /** "a message the SDK will not let this account rewrite starts nothing" */
    @Test
    fun `a message the SDK will not let this account rewrite starts nothing`() {
        // Not `isOwn`: "mine" and "editable" are different sets, and offering
        // an Edit the homeserver then refuses is worse than not offering it.
        val edits = EditTarget()
        assertNull(edits.start(row(editable = false), roomId = "!r:x"))
        assertNull("an edit was staged against an uneditable row", edits.pending(roomId = "!r:x"))
    }

    /** "a message the homeserver has not acknowledged has no address to edit" */
    @Test
    fun `a message the homeserver has not acknowledged has no address to edit`() {
        val edits = EditTarget()
        assertNull(edits.start(row(editable = true, eventId = null), roomId = "!r:x"))
        assertNull(edits.pending(roomId = "!r:x"))
    }

    /** "edits are kept per room, and cancelling one leaves the other" */
    @Test
    fun `edits are kept per room, and cancelling one leaves the other`() {
        val edits = EditTarget()
        edits.start(row(editable = true), roomId = "!a:x")
        edits.start(row(editable = true), roomId = "!b:x")
        edits.cancel("!a:x")
        assertNull(edits.pending(roomId = "!a:x"))
        assertNotNull("cancelling one room's edit cleared another's", edits.pending(roomId = "!b:x"))
    }

    /**
     * Not from Swift: `StateFlow` conflates equal values in a way an
     * `@Observable` property does not need pinning for. A view driven by
     * [EditTarget.targets] depends on this, so it is asserted rather than
     * assumed.
     */
    @Test
    fun `starting the same edit twice emits only once`() = runTest {
        val edits = EditTarget()
        val seen = mutableListOf<Map<String, EditTarget.Pending>>()
        val job = launch { edits.targets.collect { seen.add(it) } }
        runCurrent() // let the collector attach and receive the initial value first

        val theRow = row(editable = true)
        edits.start(theRow, roomId = "!a:x")
        edits.start(theRow, roomId = "!a:x")
        advanceUntilIdle()

        assertEquals(2, seen.size)
        job.cancel()
    }

    /** "cancelling an edit emits null for that room" */
    @Test
    fun `cancelling an edit clears it, and clearing it emits null`() = runTest {
        val edits = EditTarget()
        val seen = mutableListOf<EditTarget.Pending?>()
        val job = launch { edits.targets.collect { seen.add(it["!a:x"]) } }
        runCurrent()

        edits.start(row(editable = true), roomId = "!a:x")
        edits.cancel("!a:x")
        advanceUntilIdle()

        assertNull(seen.last())
        assertNull(edits.pending(roomId = "!a:x"))
        job.cancel()
    }
}
