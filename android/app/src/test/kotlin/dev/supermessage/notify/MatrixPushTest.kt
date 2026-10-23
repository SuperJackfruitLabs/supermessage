package dev.supermessage.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading the Matrix push gateway body the distributor hands over unchanged,
 * ported from `RemotePushTests.swift:13-31`. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MatrixPushTest {
    private fun parse(json: String) = MatrixPush.parse(json.toByteArray())

    @Test
    fun readsRoomEventAndUnread() {
        val push = parse(
            """{"notification":{"event_id":"${'$'}e","room_id":"!r:hs","counts":{"unread":3},""" +
                """"devices":[{"app_id":"dev.supermessage","pushkey":"https://ntfy.sh/upX"}]}}""",
        )
        assertEquals(MatrixPush(roomId = "!r:hs", eventId = "\$e", unread = 3), push)
    }

    @Test
    fun needsBothIds() {
        assertNull(parse("""{"notification":{"room_id":"!r:hs"}}"""))
        assertNull(parse("""{"notification":{"event_id":"${'$'}e"}}"""))
        assertNull(parse("""{"notification":{"room_id":"","event_id":"${'$'}e"}}"""))
        assertNull(parse("""{"notification":{"room_id":null,"event_id":"${'$'}e"}}"""))
        assertEquals(
            "no counts is no unread, and the push still stands",
            MatrixPush("!r", "\$e", null),
            parse("""{"notification":{"room_id":"!r","event_id":"${'$'}e"}}"""),
        )
    }

    @Test
    fun ignoresAnythingThatIsNotAMatrixNotification() {
        assertNull(MatrixPush.parse(ByteArray(0)))
        assertNull(MatrixPush.parse(byteArrayOf(0x00, 0x7f, 0x1b)))
        assertNull(parse("New message"))
        assertNull(parse("""{"room_id":"!r","event_id":"${'$'}e"}"""))
        assertNull(parse("""{"notification":"!r ${'$'}e"}"""))
        assertNull(parse("""{"notification":{"room_id":7,"event_id":["${'$'}e"]}}"""))
        assertNull(parse("""["notification"]"""))
        assertNull(
            "an unread that is not a count is no unread",
            parse("""{"notification":{"room_id":"!r","event_id":"${'$'}e","counts":{"unread":"three"}}}""")?.unread,
        )
    }
}
