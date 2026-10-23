package dev.supermessage.kit.widgets

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.ActivityKind
import uniffi.supermessage_core.GateAnswers
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.NotificationActivity
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.WidgetContent

/**
 * The feed over the real core (`widget_apply_*`), an in-memory store and a
 * fake clock. The merge rules are pinned in Rust (`widget/tests.rs`); this
 * pins that the host runs them under one lock, writes what changed, and
 * reports whether to redraw. Needs the host library like `CorePrereqsBindingTest`.
 */
class WidgetFeedTest {
    private val now = AtomicLong(1_800_000_000_000)

    private class Memory : SnapshotStore {
        @Volatile var json: String? = null
        val writes = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun read(): String? {
            // A pause between read and write is where an unserialised pair would interleave.
            Thread.sleep(1)
            return json
        }

        override fun write(json: String) {
            Thread.sleep(1)
            this.json = json
            writes += json
        }
    }

    private val store = Memory()
    private val feed = WidgetFeed(store, now = now::get)

    private fun gate(room: String = "!r:hs", event: String = "\$g1") = NotificationDto(
        roomId = room, eventId = event, title = "Hermes", subtitle = null, body = "Approve \"Ship v2\"?",
        category = NotificationCategory.GATE, permission = null,
        gate = GateAnswers("gate-1", "Ship v2", listOf("approve", "request_changes", "reject")),
        threadId = room, suppress = null, fallbackTitle = null, fallbackBody = null,
        activity = NotificationActivity(
            kind = ActivityKind.DECISION, roomName = "Hermes", roomIsAgent = true,
            sender = "@agent_hermes:hs", atMs = now.get().toULong(), line = null, gateId = "gate-1",
            references = null, optionId = null,
        ),
    )

    private fun room(id: String = "!a:hs") = RoomRow(
        room = RoomSummary(
            id = id, name = "Atlas", avatarUrl = null, unread = 0uL, lastMessage = "hello",
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = now.get().toULong(), runtime = null, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = "Atlas", role = null, initial = "A"),
        preview = RoomPreview(text = "hello", pending = false),
        affordance = RoomAffordance.COMPOSE,
    )

    private fun revision(json: String): Long =
        Regex("\"revision\"\\s*:\\s*(\\d+)").find(json)!!.groupValues[1].toLong()

    @Test(timeout = 60_000)
    fun aDecidedPushBecomesADecisionAndReloadsAtOnce() = runBlocking {
        assertTrue("a decision arriving reloads at once", feed.apply(gate()))
        val view = feed.view()
        assertEquals(WidgetContent.SNAPSHOT, view.content)
        assertEquals(listOf("\$g1"), view.decisions.map { it.decision.eventId })
        assertEquals(1, store.writes.size)
    }

    @Test(timeout = 60_000)
    fun anUnchangedRosterIsNotRewritten() = runBlocking {
        val asOf = now.get()
        feed.apply(listOf(room()), emptyList(), asOf)
        val written = store.writes.size
        assertEquals("the first roster is a write", 1, written)
        assertFalse("nothing changed, nothing to redraw", feed.apply(listOf(room()), emptyList(), asOf))
        assertEquals("the core said unchanged, so nothing is written", written, store.writes.size)
    }

    @Test(timeout = 60_000)
    fun answerReadsWithoutWriting() = runBlocking {
        feed.apply(gate())
        val writes = store.writes.size
        val answer = feed.answer("!r:hs", "\$g1", "approve")!!
        assertEquals("gate-1", answer.gateId)
        assertNull("not an option this decision offers", feed.answer("!r:hs", "\$g1", "nonsense"))
        assertNull("not a decision it holds", feed.answer("!r:hs", "\$other", "approve"))
        assertEquals(writes, store.writes.size)
    }

    @Test(timeout = 60_000)
    fun signingOutLeavesNothingToShow() = runBlocking {
        feed.apply(gate())
        feed.signedOut()
        val view = feed.view()
        assertEquals(WidgetContent.SIGNED_OUT, view.content)
        assertTrue(view.decisions.isEmpty())
        assertTrue(view.agents.isEmpty())
    }

    /**
     * 50 writers, each seeing the previous one's result: no write is lost and
     * the revision only grows. `opened()` takes the clock's time, and the clock
     * ticks on every read, so each of the 50 really changes the snapshot.
     */
    @Test(timeout = 120_000)
    fun concurrentWritesAreSerialised() = runBlocking {
        val ticking = AtomicLong(now.get())
        val feed = WidgetFeed(store, now = { ticking.incrementAndGet() })
        (1..50).map { async(Dispatchers.Default) { feed.opened() } }.awaitAll()
        val revisions = store.writes.map(::revision)
        assertEquals("every call changed the snapshot and was written", 50, revisions.size)
        assertEquals("each saw the one before it", (1L..50L).toList(), revisions)
    }
}
