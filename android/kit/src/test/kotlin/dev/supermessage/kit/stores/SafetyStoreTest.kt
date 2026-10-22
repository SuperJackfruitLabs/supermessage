package dev.supermessage.kit.stores

import dev.supermessage.kit.ReportSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow

/** Ported from `SupermessageKitTests/SafetyStoreTests.swift`. */
class SafetyStoreTest {

    private fun row(id: String, eventId: String? = id): TimelineRow = TimelineRow(
        item = TimelineItemDto(
            id = id, eventId = eventId, kind = "message", msgtype = "m.text", detail = null,
            sender = "@a:x", senderDisplayName = null, senderAvatar = null, body = "hi",
            formattedBody = null, media = null, customPayload = null, timestampMs = 0uL,
            isOwn = false, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = false, membershipSubject = null,
        ),
        view = ItemView.Bubble(muted = false, blocks = emptyList(), voice = null),
        senderName = "A", senderShort = "A", senderInitial = "A", membershipVerb = null,
        replyQuote = null, canReplyOrReact = true, replyPreview = null,
    )

    private class SharedStorage : HiddenEventStorage {
        var ids: Set<String> = emptySet()
        var cleared = 0
        override fun load() = ids
        override fun save(ids: Set<String>) { this.ids = ids }
        override fun clear() { ids = emptySet(); cleared++ }
    }

    @Test
    fun hidesWhatWasReported() {
        val store = SafetyStore()
        val a = row("\$a"); val b = row("\$b"); val c = row("\$c")
        store.hide("\$b")
        assertEquals(listOf(a, c), store.visible(listOf(a, b, c)))
    }

    @Test
    fun aLocalEchoPasses() {
        val store = SafetyStore()
        store.hide("local-1")
        val echo = row(id = "local-1", eventId = null)
        assertEquals(listOf(echo), store.visible(listOf(echo)))
    }

    @Test
    fun theCoresListWins() {
        val store = SafetyStore()
        store.noteBlocked("@x")
        store.apply(listOf("@y"))
        assertFalse(store.isBlocked("@x"))
        assertTrue(store.isBlocked("@y"))
    }

    @Test
    fun persistsUntilSignOut() {
        val storage = SharedStorage()
        SafetyStore(storage).hide("\$b")
        val second = SafetyStore(storage)
        assertEquals(setOf("\$b"), second.hidden.value)
        second.clear()
        assertTrue(SafetyStore(storage).hidden.value.isEmpty())
        assertEquals(1, storage.cleared)
        assertTrue(storage.ids.isEmpty())
    }

    @Test
    fun whoCanBeBlocked() {
        val message = ReportSubject.Message("!r", "\$e", "@s:x", "Sam", isAgent = true)
        assertEquals("@s:x", message.blockable.userId)
        assertEquals("Sam", message.blockable.name)
        assertTrue(message.blockable.isAgent)
        assertNull(ReportSubject.Room("!r", "Lounge").blockable)
        assertEquals("@p:x", ReportSubject.User("@p:x", "Pat", false).blockable.userId)
        assertEquals("message:!r:\$e", message.id)
        assertEquals("room:!r", ReportSubject.Room("!r", "Lounge").id)
        assertEquals("user:@p:x", ReportSubject.User("@p:x", "Pat", false).id)
    }

    /** A write that began before a clear must not refill the store afterwards. */
    @Test
    fun aWriteFromBeforeAClearIsDropped() {
        val storage = SharedStorage()
        val store = SafetyStore(storage)
        val before = store.generation
        store.clear()
        store.hide("\$late", before)
        store.noteBlocked("@late", before)
        store.apply(listOf("@late"), before)
        assertTrue(store.hidden.value.isEmpty())
        assertTrue(store.blocked.value.isEmpty())
        assertTrue(storage.ids.isEmpty())
    }
}
