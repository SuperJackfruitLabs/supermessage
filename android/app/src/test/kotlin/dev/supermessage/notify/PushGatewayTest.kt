package dev.supermessage.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gateway discovery (unifiedpush.org/users/gateway). Robolectric only for `org.json`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushGatewayTest {
    @Test
    fun discoveryReplacesThePathAndQuery() {
        assertEquals("https://ntfy.sh/_matrix/push/v1/notify", PushGateway.discoveryUrl("https://ntfy.sh/upAbC?up=1"))
        assertEquals(
            "a port is the server's, so it stays",
            "https://push.example.org:8443/_matrix/push/v1/notify",
            PushGateway.discoveryUrl("https://push.example.org:8443/up/AbC#frag"),
        )
        assertEquals("https://ntfy.sh/_matrix/push/v1/notify", PushGateway.discoveryUrl("https://ntfy.sh"))
    }

    /** `PushConfiguration.swift:27-34`: anything not an absolute `https` URL is no gateway. */
    @Test
    fun onlyHttpsIsAGateway() {
        assertNull(PushGateway.discoveryUrl("http://ntfy.sh/upAbC?up=1"))
        assertNull(PushGateway.discoveryUrl("ntfy.sh/upAbC"))
        assertNull(PushGateway.discoveryUrl("https:///upAbC"))
        assertNull(PushGateway.discoveryUrl(""))
        assertNull(PushGateway.discoveryUrl("not a url at all"))
        assertNull(PushGateway.discoveryUrl("content://org.unifiedpush/up"))
    }

    @Test
    fun recognisesAMatrixGateway() {
        assertTrue(PushGateway.isMatrixGateway("""{"unifiedpush":{"gateway":"matrix"}}"""))
        assertTrue(PushGateway.isMatrixGateway(""" { "unifiedpush" : { "gateway" : "matrix", "version": 1 } }"""))
    }

    @Test
    fun anythingElseIsNotAGateway() {
        // ntfy's answer at an endpoint that is not a gateway.
        assertFalse(PushGateway.isMatrixGateway("""{"unifiedpush":{"version":1}}"""))
        assertFalse(PushGateway.isMatrixGateway("""{"gateway":"matrix"}"""))
        assertFalse(PushGateway.isMatrixGateway("""{"unifiedpush":{"gateway":"webpush"}}"""))
        assertFalse(PushGateway.isMatrixGateway("""{"unifiedpush":"matrix"}"""))
        assertFalse(PushGateway.isMatrixGateway("<html>404</html>"))
        assertFalse(PushGateway.isMatrixGateway(""))
    }
}
