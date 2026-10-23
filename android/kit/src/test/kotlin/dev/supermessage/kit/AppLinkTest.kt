package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinkTest {
    @Test
    fun roundTrip() {
        assertEquals("!a:x", AppLink.roomId(AppLink.room("!a:x")))
        assertEquals("!a:x", AppLink.roomId(AppLink.decision("!a:x", "\$e")))
        assertEquals("!a b+c:x", AppLink.roomId(AppLink.room("!a b+c:x")))
    }

    @Test
    fun theLinkShapeIsTheOneIosWrites() {
        assertEquals("supermessage://open?room=%21a%3Ax", AppLink.room("!a:x"))
        assertEquals("supermessage://open?room=%21a%3Ax&event=%24e", AppLink.decision("!a:x", "\$e"))
    }

    @Test
    fun percentEncodingIsDecoded() {
        assertEquals("!a:x", AppLink.roomId("supermessage://open?room=%21a%3Ax"))
        assertEquals("!a:x", AppLink.roomId("supermessage://open?room=!a:x"))
    }

    @Test
    fun theEventDoesNotChangeWhereItOpens() {
        assertEquals("!a:x", AppLink.roomId("supermessage://open?event=%24e&room=%21a%3Ax"))
    }

    @Test
    fun notOursIsNull() {
        listOf(
            "https://open?room=%21a%3Ax",
            "supermessage://rooms?room=%21a%3Ax",
            "supermessage://open",
            "supermessage://open?event=%24e",
            "supermessage://open?room=",
            "supermessage://open?room=%zz",
            "https://matrix.to/#/!a:x",
            "not a url at all",
            "",
        ).forEach { assertNull(it, AppLink.roomId(it)) }
    }
}
