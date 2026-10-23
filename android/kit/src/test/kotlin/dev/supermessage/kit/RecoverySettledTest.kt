package dev.supermessage.kit

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot

/** The first-run demo waits for the first sign-in's recovery key, so it must know when the core has answered. */
class RecoverySettledTest {
    private class Core(val gate: CountDownLatch, val key: String?) : StubCore() {
        override fun login(homeserver: String, username: String, password: String, sink: EventSink) {}
        override fun roomsSnapshot() = RoomsSnapshot(seq = 0uL, rooms = emptyList())
        override fun spacesList() = emptyList<uniffi.supermessage_core.SpaceSummary>()
        override fun account() = AccountDto(userId = "@me:x", homeserver = "https://x")
        override fun ignoredUsers() = emptyList<uniffi.supermessage_core.RoomMemberDto>()
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun ensureRecovery(): String? {
            gate.await(10, TimeUnit.SECONDS)
            return key
        }
    }

    @Test
    fun settledOnlyOnceTheCoreHasAnsweredAboutTheKey() = runBlocking {
        val gate = CountDownLatch(1)
        val session = Session(
            client = CoreClient(core = Core(gate, "EsTd key"), dispatcher = Dispatchers.IO),
            scope = CoroutineScope(Dispatchers.IO),
        )
        assertTrue("a session that has not signed in has nothing to wait for", session.recoverySettled.value)
        session.signIn("https://x", "u", "p")
        assertFalse("signed in, the key is not known yet", session.recoverySettled.value)
        assertEquals(null, session.newRecoveryKey.value)
        gate.countDown()
        withTimeoutOrNullPoll { session.recoverySettled.value }
        assertEquals("EsTd key", session.newRecoveryKey.value)
    }

    @Test
    fun settledWithNoKeyWhenTheAccountHasOne() = runBlocking {
        val gate = CountDownLatch(0)
        val session = Session(
            client = CoreClient(core = Core(gate, null), dispatcher = Dispatchers.IO),
            scope = CoroutineScope(Dispatchers.IO),
        )
        session.signIn("https://x", "u", "p")
        withTimeoutOrNullPoll { session.recoverySettled.value }
        assertTrue(session.recoverySettled.value)
        assertEquals(null, session.newRecoveryKey.value)
    }

    @Test
    fun aNewSignInIsUnsettledFromItsFirstMoment() = runBlocking {
        var seenAtLogin: Boolean? = null
        lateinit var session: Session
        val core = object : StubCore() {
            override fun login(homeserver: String, username: String, password: String, sink: EventSink) {
                seenAtLogin = session.recoverySettled.value
            }
            override fun roomsSnapshot() = RoomsSnapshot(seq = 0uL, rooms = emptyList())
            override fun spacesList() = emptyList<uniffi.supermessage_core.SpaceSummary>()
            override fun account() = AccountDto(userId = "@me:x", homeserver = "https://x")
            override fun ignoredUsers() = emptyList<uniffi.supermessage_core.RoomMemberDto>()
            override fun setHiddenEvents(eventIds: List<String>) {}
            override fun ensureRecovery(): String? = null
        }
        session = Session(client = CoreClient(core = core, dispatcher = Dispatchers.IO), scope = CoroutineScope(Dispatchers.IO))
        assertTrue(session.recoverySettled.value)
        session.signIn("https://x", "u", "p")
        assertEquals("not settled while the core logs in", false, seenAtLogin)
        withTimeoutOrNullPoll { session.recoverySettled.value }
    }

    @Test
    fun aFailedSignInLeavesNothingToWaitFor() = runBlocking {
        val core = object : StubCore() {
            override fun login(homeserver: String, username: String, password: String, sink: EventSink) {
                throw RuntimeException("no")
            }
        }
        val session = Session(client = CoreClient(core = core, dispatcher = Dispatchers.IO), scope = CoroutineScope(Dispatchers.IO))
        session.signIn("https://x", "u", "p")
        assertTrue(session.recoverySettled.value)
    }

    private suspend fun withTimeoutOrNullPoll(done: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!done() && System.nanoTime() < end) kotlinx.coroutines.delay(10)
        assertTrue("settled within 10 s", done())
    }
}
