package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationRequestsTest {
    @Test
    fun consumeReturnsThePendingRoomOnce() {
        val requests = NavigationRequests()
        assertNull(requests.consume())
        requests.request("!a:x")
        assertEquals("!a:x", requests.pending.value)
        assertEquals("!a:x", requests.consume())
        assertNull(requests.consume())
        assertNull(requests.pending.value)
    }

    @Test
    fun aLaterRequestWins() {
        val requests = NavigationRequests()
        requests.request("!a:x")
        requests.request("!b:x")
        assertEquals("!b:x", requests.consume())
    }

    @Test
    fun clearDropsIt() {
        val requests = NavigationRequests()
        requests.request("!a:x")
        requests.clear()
        assertNull(requests.consume())
    }
}
