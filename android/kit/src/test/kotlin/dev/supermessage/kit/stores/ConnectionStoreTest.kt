package dev.supermessage.kit.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_ffi.ConnectionState

/**
 * Ported from `apple/SupermessageKitTests/ConnectionStoreTests.swift`.
 */
class ConnectionStoreTest {

    /** "the core's vocabulary maps to a state, including the error one" */
    @Test
    fun mapsEveryState() {
        // "error" was missing and fell through to `.unknown`, which put the
        // bare word "error" on screen with no explanation beside it.
        val store = ConnectionStore()
        val cases = listOf(
            "live" to ConnectionStore.Connection.Live,
            "connecting" to ConnectionStore.Connection.Connecting,
            "offline" to ConnectionStore.Connection.Offline,
            "error" to ConnectionStore.Connection.Error,
        )
        for ((raw, expected) in cases) {
            store.apply(ConnectionState(state = raw, message = null))
            assertEquals("for $raw", expected, store.state.value)
        }
    }

    /** "a word this build has not been taught is carried, not crashed on" */
    @Test
    fun unknownIsCarried() {
        // The vocabulary is the core's, so it can gain a value without this
        // app failing to build. One branch is the price.
        val store = ConnectionStore()
        store.apply(ConnectionState(state = "hibernating", message = null))
        assertEquals(ConnectionStore.Connection.Unknown("hibernating"), store.state.value)
    }

    /** "live is the quiet case and shows no bar" */
    @Test
    fun liveIsQuiet() {
        val store = ConnectionStore()
        store.apply(ConnectionState(state = "live", message = null))
        assertFalse(store.isWorthShowing)
        store.apply(ConnectionState(state = "error", message = "error sending request for url"))
        assertTrue(store.isWorthShowing)
    }

    /** "recovering clears the message as well as the state" */
    @Test
    fun recoveryClearsTheMessage() {
        // The bug the reader hit: an error that never went away. Half of that
        // was the core never retrying; this is the other half — the store
        // must not keep the old message when a healthy state finally arrives.
        val store = ConnectionStore()
        store.apply(ConnectionState(state = "error", message = "error sending request for url"))
        assertNotNull(store.message.value)

        store.apply(ConnectionState(state = "live", message = null))
        assertNull("a stale error message outlived the recovery", store.message.value)
        assertFalse(store.isWorthShowing)
    }
}
