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
        val storage = InMemoryHiddenEvents()
        SafetyStore(storage).apply { bind("@a:x", adoptLegacy = false); hide("\$b") }
        val second = SafetyStore(storage).apply { bind("@a:x", adoptLegacy = false) }
        assertEquals(setOf("\$b"), second.hidden.value)
        second.clear()
        assertTrue(SafetyStore(storage).apply { bind("@a:x", adoptLegacy = false) }.hidden.value.isEmpty())
        assertTrue(storage.load("@a:x").isEmpty())
    }

    @Test
    fun anotherAccountSeesNothingOfTheFirstsHiddenMessages() {
        val storage = InMemoryHiddenEvents()
        SafetyStore(storage).apply { bind("@a:x", adoptLegacy = false); hide("\$b") }
        val other = SafetyStore(storage).apply { bind("@b:x", adoptLegacy = false) }
        assertTrue(other.hidden.value.isEmpty())
        other.hide("\$c")
        other.clear()
        assertEquals(setOf("\$b"), storage.load("@a:x"))
    }

    @Test
    fun theOldDeviceWideSetGoesToTheRestoredAccountOnce() {
        val storage = InMemoryHiddenEvents().apply { seedLegacy(setOf("\$old")) }
        val store = SafetyStore(storage)
        store.bind("@a:x", adoptLegacy = true)
        assertEquals(setOf("\$old"), store.hidden.value)
        assertEquals(setOf("\$old"), storage.load("@a:x"))
        assertTrue(storage.takeLegacy().isEmpty())
        assertTrue(SafetyStore(storage).apply { bind("@b:x", adoptLegacy = true) }.hidden.value.isEmpty())
    }

    @Test
    fun theOldDeviceWideSetIsDroppedAfterAFreshSignIn() {
        val storage = InMemoryHiddenEvents().apply { seedLegacy(setOf("\$old")) }
        val store = SafetyStore(storage)
        store.bind("@a:x", adoptLegacy = false)
        assertTrue(store.hidden.value.isEmpty())
        assertTrue(storage.takeLegacy().isEmpty())
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
        val storage = InMemoryHiddenEvents()
        val store = SafetyStore(storage).apply { bind("@a:x", adoptLegacy = false) }
        val before = store.generation
        store.clear()
        store.hide("\$late", before)
        store.noteBlocked("@late", before)
        store.apply(listOf("@late"), before)
        assertTrue(store.hidden.value.isEmpty())
        assertTrue(store.blocked.value.isEmpty())
        assertTrue(storage.load("@a:x").isEmpty())
    }
}
