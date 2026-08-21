package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one place a chat app must not be ambiguous. */
class SendStateTest {

    /** "the core's vocabulary reads across" */
    @Test
    fun readsTheWire() {
        assertEquals(SendState.SENDING, SendState("notSentYet"))
        assertEquals(SendState.FAILED, SendState("sendingFailed"))
        assertEquals(SendState.SENT, SendState("sent"))
    }

    /** "a message with no send state has arrived" */
    @Test
    fun nilIsSent() {
        // Every message a peer sent carries `null` here — it is on the server
        // by definition. Reading that as "unknown" would put a marker under
        // every incoming message in the room.
        assertEquals(SendState.SENT, SendState(null))
    }

    /** "a state this build has not been taught is not guessed at" */
    @Test
    fun unknownStaysUnknown() {
        assertEquals(SendState.UNKNOWN, SendState("somethingNew"))
        assertFalse(SendState("somethingNew").isWorthShowing)
    }

    /** "a failed message always says so" */
    @Test
    fun failureShows() {
        // The whole point. A message sitting on this phone looks exactly like
        // one that landed unless something says otherwise.
        assertTrue(SendState.FAILED.isWorthShowing)
        assertEquals("Not sent", SendState.FAILED.label)
    }

    /** "an ordinary sent message says nothing" */
    @Test
    fun successIsQuiet() {
        // A tick under every bubble is chrome on the unremarkable case.
        assertFalse(SendState.SENT.isWorthShowing)
        assertEquals(null, SendState.SENT.label)
    }
}
