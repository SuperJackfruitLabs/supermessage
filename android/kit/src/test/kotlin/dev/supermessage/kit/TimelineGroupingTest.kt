package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow

class TimelineGroupingTest {

    companion object {
        fun row(
            id: String,
            sender: String,
            at: ULong,
            isOwn: Boolean = false,
            system: Boolean = false,
        ): TimelineRow {
            val item = TimelineItemDto(
                id = id,
                eventId = id,
                kind = if (system) "state" else "message",
                msgtype = if (system) null else "m.text",
                detail = null,
                sender = sender,
                senderDisplayName = null,
                senderAvatar = null,
                body = "hi",
                formattedBody = null,
                media = null,
                customPayload = null,
                timestampMs = at,
                isOwn = isOwn,
                sendState = null,
                replyTo = null,
                edited = false,
                reactions = emptyList(),
                readBy = emptyList(),
                editable = false,
            )
            return TimelineRow(
                item = item,
                view = if (system) {
                    ItemView.System(text = "something happened")
                } else {
                    ItemView.Bubble(muted = false, blocks = emptyList())
                },
                senderName = sender,
                senderShort = sender,
                membershipVerb = null,
                replyQuote = null,
                canReplyOrReact = true,
                replyPreview = null,
            )
        }
    }

    /** "a second message from the same sender, moments later, continues the run" */
    @Test
    fun continuesForSameSender() {
        val first = row(id = "\$1", sender = "@a:x", at = 1_000u)
        val second = row(id = "\$2", sender = "@a:x", at = 60_000u)
        assertTrue(TimelineGrouping.continuesRun(second, previous = first))
    }

    /** "a different sender starts a new run" */
    @Test
    fun breaksOnSender() {
        val first = row(id = "\$1", sender = "@a:x", at = 1_000u)
        val second = row(id = "\$2", sender = "@b:x", at = 2_000u)
        assertFalse(TimelineGrouping.continuesRun(second, previous = first))
    }

    /** "a long gap starts a new run, even from the same sender" */
    @Test
    fun breaksOnTime() {
        // Two messages an hour apart are two turns, whoever sent them.
        val first = row(id = "\$1", sender = "@a:x", at = 0u)
        val second = row(id = "\$2", sender = "@a:x", at = TimelineGrouping.runWindowMs + 1u)
        assertFalse(TimelineGrouping.continuesRun(second, previous = first))
        val inside = row(id = "\$3", sender = "@a:x", at = TimelineGrouping.runWindowMs)
        assertTrue(TimelineGrouping.continuesRun(inside, previous = first))
    }

    /** "anything that is not an ordinary message ends the run" */
    @Test
    fun breaksOnNonMessage() {
        // Otherwise a message after a card reads as though the card's author
        // said it.
        val card = row(id = "\$1", sender = "@a:x", at = 1_000u, system = true)
        val message = row(id = "\$2", sender = "@a:x", at = 2_000u)
        assertFalse(TimelineGrouping.continuesRun(message, previous = card))
        assertFalse(TimelineGrouping.continuesRun(card, previous = message))
    }

    /** "the first row never continues anything" */
    @Test
    fun firstRowStandsAlone() {
        assertFalse(TimelineGrouping.continuesRun(row(id = "\$1", sender = "@a:x", at = 1u), previous = null))
    }

    /** "an own message does not join a peer's run" */
    @Test
    fun ownDoesNotJoinPeer() {
        // They are laid out on opposite sides; joining them would put a
        // trailing bubble under a leading header.
        val peer = row(id = "\$1", sender = "@a:x", at = 1_000u)
        val own = row(id = "\$2", sender = "@a:x", at = 2_000u, isOwn = true)
        assertFalse(TimelineGrouping.continuesRun(own, previous = peer))
    }

    /** "a room where one agent speaks does not repeat its runtime" */
    @Test
    fun singleSpeaker() {
        // The suffix is the same words under every message there, and the
        // header already says the name.
        assertTrue(
            TimelineGrouping.hasSingleSpeaker(
                listOf(
                    row(id = "1", sender = "@a:x", at = 1u),
                    row(id = "2", sender = "@a:x", at = 2u),
                )
            )
        )
    }

    /** "a room where several speak keeps it" */
    @Test
    fun severalSpeakers() {
        assertFalse(
            TimelineGrouping.hasSingleSpeaker(
                listOf(
                    row(id = "1", sender = "@a:x", at = 1u),
                    row(id = "2", sender = "@b:x", at = 2u),
                )
            )
        )
    }

    /** "your own messages do not make a room multi-voiced" */
    @Test
    fun ownMessagesDoNotCount() {
        // Own messages are attributed by position, not by name, so they say
        // nothing about how many agents are talking.
        assertTrue(
            TimelineGrouping.hasSingleSpeaker(
                listOf(
                    row(id = "1", sender = "@a:x", at = 1u),
                    row(id = "2", sender = "@me:x", at = 2u, isOwn = true),
                )
            )
        )
    }
}

/** Collapsing membership churn, ported from the desktop. */
class MembershipRunTest {

    companion object {
        fun membership(id: String, sender: String, verb: String): TimelineRow {
            val item = TimelineItemDto(
                id = id,
                eventId = id,
                kind = "membership",
                msgtype = null,
                detail = verb,
                sender = sender,
                senderDisplayName = sender,
                senderAvatar = null,
                body = null,
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
                view = ItemView.System(text = "$sender $verb"),
                senderName = sender,
                senderShort = sender,
                membershipVerb = verb,
                replyQuote = null,
                canReplyOrReact = false,
                replyPreview = null,
            )
        }
    }

    /** "a run of the same change becomes one sentence" */
    @Test
    fun collapsesARun() {
        // Ten identical "updated their membership" lines is what this replaces.
        val rows = listOf(
            membership("1", "Ganesha", "joined the room"),
            membership("2", "Krishna", "joined the room"),
            membership("3", "Annapurna", "joined the room"),
            membership("4", "Surya", "joined the room"),
        )
        val out = TimelineGrouping.collapseMembershipRuns(rows)
        assertEquals(1, out.size)
        val run = out[0] as? DisplayRow.MembershipRun ?: run { fail("expected a run"); return }
        assertEquals("Ganesha, Krishna and 2 others joined the room", run.text)
    }

    /** "a run of one reads exactly like an ungrouped line" */
    @Test
    fun singleReadsNormally() {
        // Never "Ganesha and 0 others".
        val out = TimelineGrouping.collapseMembershipRuns(
            listOf(membership("1", "Ganesha", "joined the room"))
        )
        val run = out[0] as? DisplayRow.MembershipRun ?: run { fail("expected a run"); return }
        assertEquals("Ganesha joined the room", run.text)
    }

    /** "different verbs stay different sentences" */
    @Test
    fun differentVerbsSplit() {
        // One sentence covering both would be true of neither.
        val out = TimelineGrouping.collapseMembershipRuns(
            listOf(
                membership("1", "Ganesha", "joined the room"),
                membership("2", "Krishna", "left the room"),
            )
        )
        assertEquals(2, out.size)
    }

    /** "messages pass through untouched and break a run" */
    @Test
    fun messagesInterrupt() {
        val out = TimelineGrouping.collapseMembershipRuns(
            listOf(
                membership("1", "Ganesha", "joined the room"),
                TimelineGroupingTest.row(id = "m", sender = "@a:x", at = 2u),
                membership("2", "Krishna", "joined the room"),
            )
        )
        assertEquals(3, out.size)
        if (out[1] !is DisplayRow.Row) {
            fail("a message became part of a run")
        }
    }
}

/** Rows the core said to draw nothing for. */
class SilentRowTest {

    companion object {
        fun silent(id: String): TimelineRow {
            val item = TimelineItemDto(
                id = id,
                eventId = id,
                kind = "state",
                msgtype = null,
                detail = null,
                sender = "@a:x",
                senderDisplayName = null,
                senderAvatar = null,
                body = null,
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
                view = ItemView.None,
                senderName = "a",
                senderShort = "a",
                membershipVerb = null,
                replyQuote = null,
                canReplyOrReact = false,
                replyPreview = null,
            )
        }
    }

    /** "a row that draws nothing gets no row" */
    @Test
    fun silentRowsAreDropped() {
        // A cell with no content does not reliably collapse to no height —
        // one appeared as roughly three hundred points of blank in the middle
        // of two different rooms. Deliberately silent means absent.
        val out = TimelineGrouping.collapseMembershipRuns(
            listOf(
                TimelineGroupingTest.row(id = "m", sender = "@a:x", at = 1u),
                silent("s"),
                TimelineGroupingTest.row(id = "n", sender = "@a:x", at = 2u),
            )
        )
        assertEquals(2, out.size)
        assertEquals(listOf("m", "n"), out.map { it.id })
    }

    /** "a silent row does not break a membership run either" */
    @Test
    fun silentRowsDoNotSplitRuns() {
        val out = TimelineGrouping.collapseMembershipRuns(
            listOf(
                MembershipRunTest.membership("1", "Ganesha", "joined the room"),
                silent("s"),
                MembershipRunTest.membership("2", "Krishna", "joined the room"),
            )
        )
        assertTrue(
            "an invisible row split a run that a reader sees as one",
            out.size == 1,
        )
    }
}
