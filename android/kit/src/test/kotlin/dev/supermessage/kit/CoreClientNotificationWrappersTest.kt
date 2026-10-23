package dev.supermessage.kit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.PushRegistration

class CoreClientNotificationWrappersTest {
    private class Recording : StubCore() {
        val calls = mutableListOf<List<Any?>>()
        val dto = NotificationDto(
            roomId = "!r:x", eventId = "\$e", title = "t", subtitle = null, body = "b",
            category = NotificationCategory.MESSAGE, permission = null, gate = null,
            threadId = "!r:x", suppress = null, fallbackTitle = null, fallbackBody = null, activity = null,
        )

        override fun restoreSessionQuietly(): Boolean {
            calls += listOf("restoreSessionQuietly")
            return true
        }

        override fun notificationFor(roomId: String, eventId: String): NotificationDto {
            calls += listOf("notificationFor", roomId, eventId)
            return dto
        }

        override fun registerPusher(registration: PushRegistration) {
            calls += listOf("registerPusher", registration)
        }

        override fun unregisterPusher() {
            calls += listOf("unregisterPusher")
        }

        override fun sendGateDecisionTo(
            roomId: String, gateId: String, optionId: String, comment: String?, inReplyTo: String, prompt: String,
        ) {
            calls += listOf("sendGateDecisionTo", roomId, gateId, optionId, comment, inReplyTo, prompt)
        }

        override fun attachmentMarkVoice(roomId: String, token: String, durationMs: ULong, waveform: List<Float>) {
            calls += listOf("attachmentMarkVoice", roomId, token, durationMs, waveform)
        }
    }

    @Test
    fun eachWrapperForwardsItsArgumentsUnchanged() = runTest {
        val core = Recording()
        val client = CoreClient(core)
        val registration = PushRegistration("key", "app", "App", "Pixel", "en", "https://gw/_matrix/push/v1/notify")

        assertTrue(client.restoreSessionQuietly())
        assertSame(core.dto, client.notificationFor("!r:x", "\$e"))
        client.registerPusher(registration)
        client.unregisterPusher()
        client.sendGateDecisionTo("!r:x", "gate-1", "approve", null, "\$reply", "Deploy?")
        client.sendGateDecisionTo("!r:x", "gate-1", "changes", "tighten it", "\$reply", "Deploy?")
        client.attachmentMarkVoice("!r:x", "tok", 4_250uL, listOf(0.1f, 0.9f))

        assertEquals(listOf("restoreSessionQuietly"), core.calls[0])
        assertEquals(listOf("notificationFor", "!r:x", "\$e"), core.calls[1])
        assertSame(registration, core.calls[2][1])
        assertEquals(listOf("unregisterPusher"), core.calls[3])
        assertEquals(listOf("sendGateDecisionTo", "!r:x", "gate-1", "approve", null, "\$reply", "Deploy?"), core.calls[4])
        assertNull(core.calls[4][4])
        assertEquals("tighten it", core.calls[5][4])
        assertEquals(listOf("attachmentMarkVoice", "!r:x", "tok", 4_250uL, listOf(0.1f, 0.9f)), core.calls[6])
        assertEquals(7, core.calls.size)
    }
}
