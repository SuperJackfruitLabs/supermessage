package dev.supermessage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSecretStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: AndroidSecretStore

    @Before fun setUp() {
        store = AndroidSecretStore(context)
        store.clearForTest()
    }

    @After fun tearDown() = store.clearForTest()

    @Test fun aValueSetIsReadBack() {
        store.set("passphrase", "hunter2")
        assertEquals("hunter2", store.get("passphrase"))
    }

    @Test fun anAbsentKeyIsNull() {
        assertNull(store.get("never-written"))
    }

    @Test fun aDeletedKeyIsNull() {
        store.set("passphrase", "hunter2")
        store.delete("passphrase")
        assertNull(store.get("passphrase"))
    }

    /**
     * Distinct keys do not collide — one key's value never reads back under
     * another's. A single shared IV or a single shared preference entry would
     * pass the round-trip test above and fail this one.
     */
    @Test fun distinctKeysDoNotCollide() {
        store.set("a", "first")
        store.set("b", "second")
        assertEquals("first", store.get("a"))
        assertEquals("second", store.get("b"))
    }

    /**
     * The ciphertext is genuinely different each time even for the same
     * plaintext — GCM with a fresh IV per write. Equal ciphertexts would mean
     * a reused IV, which is the classic GCM break.
     */
    @Test fun theSameValueEncryptsDifferentlyEachTime() {
        store.set("a", "same")
        val first = store.rawForTest("a")
        store.set("a", "same")
        val second = store.rawForTest("a")
        org.junit.Assert.assertNotEquals(first, second)
        assertEquals("same", store.get("a"))
    }

    /** Non-ASCII survives Base64 and GCM unchanged. */
    @Test fun nonAsciiRoundTrips() {
        store.set("k", "passphrase — with an em dash, 日本語, and 🔐")
        assertEquals("passphrase — with an em dash, 日本語, and 🔐", store.get("k"))
    }

    /**
     * A value survives a new store instance over the same preferences — the
     * difference between a store and a cache, and the whole reason a session
     * outlives the app. The nearest thing to a process restart a test can do.
     */
    @Test fun aValueSurvivesANewInstance() {
        store.set("passphrase", "hunter2")
        assertEquals("hunter2", AndroidSecretStore(context).get("passphrase"))
    }

    /**
     * What is actually on disk is ciphertext, never the plaintext. This is a
     * direct check on the stored bytes, independent of whatever `get` does
     * with them — it catches a `set` that skips encryption even if some
     * other test happens to notice the fallout indirectly.
     */
    @Test fun theStoredValueIsNotThePlaintext() {
        store.set("passphrase", "hunter2")
        val raw = store.rawForTest("passphrase")
        assertFalse(raw != null && raw.contains("hunter2"))
    }
}
