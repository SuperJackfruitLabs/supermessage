package dev.supermessage.kit.notify

import dev.supermessage.kit.HostCore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PlannedNotification
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow

/** Real core functions (the host library), a recording poster and a fake clock. */
class NotificationCoordinatorTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun hostCore() = HostCore.ensureBuilt("NotificationCoordinatorTest")
    }

    private var clock = 1_000_000L
    private val posted = mutableListOf<PlannedNotification>()
    private val asked = mutableListOf<String>()
    private var mode: NotificationMode? = NotificationMode.DEFAULT

    private fun coordinator() = NotificationCoordinator(
        poster = { posted += it },
        modeOf = { asked += it; mode },
        now = { clock },
    )

    private fun room(id: String, unread: Long, text: String = "hello", pending: Boolean = false, activity: Long = 5) = RoomRow(
        room = RoomSummary(
            id = id, name = id, avatarUrl = null, unread = unread.toULong(), lastMessage = text,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = activity.toULong(), runtime = null, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = id, role = null, initial = "X"),
        preview = RoomPreview(text = text, pending = pending),
        affordance = RoomAffordance.COMPOSE,
    )

    private fun message(eventId: String, at: Long, sender: String = "@lyra:x", senderName: String = "Lyra") = TimelineRow(
        item = TimelineItemDto(
            id = eventId, eventId = eventId, kind = "message", msgtype = "m.text", detail = null,
            sender = sender, senderDisplayName = senderName, senderAvatar = null, body = "hi there",
            formattedBody = null, media = null, customPayload = null, timestampMs = at.toULong(),
            isOwn = false, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = false, membershipSubject = null,
        ),
        view = ItemView.Bubble(muted = false, blocks = emptyList(), voice = null),
        senderName = senderName, senderShort = senderName, senderInitial = "L", membershipVerb = null,
        replyQuote = null, canReplyOrReact = true, replyPreview = "hi there",
    )

    @Test
    fun theFirstRosterIsABaseline() = runTest {
        val c = coordinator()
        c.roomsChanged(listOf(room("!a:x", unread = 3)))
        assertTrue(posted.isEmpty())
    }

    @Test
    fun aNewMessageInAnotherRoomPostsTheCoresPreview() = runTest {
        val c = coordinator()
        c.roomsChanged(listOf(room("!a:x", unread = 0)))
        c.roomsChanged(listOf(room("!a:x", unread = 1, text = "ship it")))
        assertEquals(1, posted.size)
        assertEquals("ship it", posted[0].body)
        assertEquals(NotificationCategory.MESSAGE, posted[0].category)
    }

    @Test
    fun comingBackToTheForegroundIsNotABurst() = runTest {
        val c = coordinator()
        c.roomsChanged(listOf(room("!a:x", unread = 0)))
        c.activeChanged(true)
        c.roomsChanged(listOf(room("!a:x", unread = 1)))
        assertTrue("inside the catch-up window", posted.isEmpty())
        clock += CATCH_UP_MS
        c.roomsChanged(listOf(room("!a:x", unread = 2)))
        assertEquals(1, posted.size)
    }

    @Test
    fun aMutedRoomHoldsAMessageButNeverADecision() = runTest {
        mode = NotificationMode.MUTED
        val c = coordinator()
        c.roomsChanged(listOf(room("!a:x", unread = 0)))
        c.roomsChanged(listOf(room("!a:x", unread = 1)))
        assertTrue("a message is held", posted.isEmpty())
        c.roomsChanged(listOf(room("!a:x", unread = 2, text = "Allow?", pending = true)))
        assertEquals(1, posted.size)
        assertEquals(NotificationCategory.DECISION, posted[0].category)
    }

    @Test
    fun theModeIsAskedOncePerMinute() = runTest {
        val c = coordinator()
        c.roomsChanged(listOf(room("!a:x", unread = 0)))
        c.roomsChanged(listOf(room("!a:x", unread = 1, activity = 6)))
        clock += 30_000
        c.roomsChanged(listOf(room("!a:x", unread = 2, activity = 7)))
        assertEquals(2, posted.size)
        assertEquals(1, asked.size)
        clock += 31_000
        c.roomsChanged(listOf(room("!a:x", unread = 3, activity = 8)))
        assertEquals(3, posted.size)
        assertEquals(2, asked.size)
    }

    @Test
    fun aRowIsRecordedAsNotifiedEvenWhenHeldBack() = runTest {
        mode = NotificationMode.MUTED
        val c = coordinator()
        c.timelineChanged("!a:x", "Atlas", emptyList())
        c.timelineChanged("!a:x", "Atlas", listOf(message("\$e1", at = clock + 10)))
        assertTrue(posted.isEmpty())
        assertTrue(c.notified.contains("\$e1"))
    }

    @Test
    fun aRoomSwitchStartsANewSince() = runTest {
        val c = coordinator()
        val older = message("\$old", at = clock - 5_000)
        c.timelineChanged("!a:x", "Atlas", listOf(older))
        assertTrue(posted.isEmpty())
        clock += 1_000
        c.timelineChanged("!b:x", "Borealis", listOf(older, message("\$new", at = clock + 1)))
        assertEquals(listOf("\$new"), posted.mapNotNull { it.eventId })
    }

    @Test
    fun signingOutForgetsEverything() = runTest {
        val c = coordinator()
        c.roomsChanged(listOf(room("!a:x", unread = 0)))
        c.timelineChanged("!a:x", "Atlas", listOf(message("\$e1", at = clock + 1)))
        assertFalse(c.notified.isEmpty())
        c.signedOut()
        assertTrue(c.notified.isEmpty())
        posted.clear()
        c.roomsChanged(listOf(room("!a:x", unread = 4)))
        assertTrue("the next roster is a baseline", posted.isEmpty())
    }

    @Test
    fun leavingTheForegroundMovesSinceSoWhatWasSeenDoesNotPostLater() = runTest {
        val c = coordinator()
        c.activeChanged(true)
        clock += CATCH_UP_MS
        c.openRoomChanged("!a:x")
        c.timelineChanged("!a:x", "Atlas", emptyList())
        val seen = message("\$seen", at = clock + 10)
        clock += 20
        c.timelineChanged("!a:x", "Atlas", listOf(seen)) // on screen: the core suppresses it
        assertTrue(posted.isEmpty())
        clock += 20
        c.activeChanged(false)
        clock += 20
        c.timelineChanged("!a:x", "Atlas", listOf(seen, message("\$next", at = clock)))
        assertEquals(listOf("\$next"), posted.mapNotNull { it.eventId })
    }

    @Test
    fun aMessageIsTitledWithItsSenderNotTheRoom() = runTest {
        val c = coordinator()
        c.timelineChanged("!a:x", "Atlas", emptyList())
        c.timelineChanged("!a:x", "Atlas", listOf(message("\$e1", at = clock + 1)))
        assertEquals("Lyra", posted.single().title)
        assertEquals("Atlas", posted.single().subtitle)
    }
}
