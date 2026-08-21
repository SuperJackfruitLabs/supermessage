package dev.supermessage.kit

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink

/**
 * How this host proves a `Core` call actually left the calling coroutine's
 * thread.
 *
 * **The rules themselves, restated from `apple/SupermessageKitTests/CoreClientTests.swift`:**
 * every `Core` method blocks the calling thread — it is a synchronous Rust
 * call that `block_on`s a tokio runtime — so `CoreClient` must run each one
 * on a dispatcher built to be blocked (`Dispatchers.IO`), never on the
 * cooperative one (`Dispatchers.Default`) that every other coroutine in the
 * app shares and assumes will yield.
 *
 * Swift's suite asserts on the dispatch queue's *label*, deliberately: "not
 * main" is true of every plausible implementation (an actor is already off
 * the main thread) and proves nothing. The label is what tells the
 * dedicated queue apart from the cooperative pool.
 *
 * A Kotlin coroutine dispatcher has no equivalent label to read back, so the
 * port asserts the stronger thing directly: the dispatcher is injected, and
 * the test proves the client actually used the one it was given, via a probe
 * that records whether it was asked to dispatch at all. That is testable
 * without any real threading and pins exactly the rule Swift's label check
 * pinned — that the call runs on *this* dispatcher, not some other one the
 * implementation could have reached for instead.
 */
class CoreClientTest {

    /** a call runs on the dispatcher the client was given, not the caller's own */
    @Test
    fun aCallRunsOnTheGivenDispatcher() = runTest {
        // The invariant, and the reason it is asserted this way.
        //
        // A probe dispatcher stands in for "the cooperative pool would have
        // been wrong here": it is a distinct `CoroutineDispatcher` instance,
        // so if `CoreClient` ever hardcoded `Dispatchers.Default` or ran the
        // body inline on the caller's dispatcher, this dispatcher would never
        // be asked to do anything and the flag below would stay false.
        var dispatched = false
        val probe = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched = true
                block.run()
            }
        }
        val client = CoreClient(core = FakeCore(), dispatcher = probe)

        client.connectionState()

        assertEquals(true, dispatched)
    }

    /** many blocking calls at once do not starve anything */
    @Test
    fun manyBlockingCallsAtOnceDoNotStarveAnything() = runBlocking {
        // The failure the dedicated dispatcher prevents, made observable:
        // far more simultaneous blocking calls than the cooperative pool
        // (`Dispatchers.Default`, sized to the core count) could serve
        // without making the rest wait. `Dispatchers.IO` — this client's
        // default — is built to be blocked, so all 64 finish rather than
        // queueing behind however many cores this machine has.
        //
        // What this does NOT prove: it does not discriminate between
        // `Dispatchers.IO` and `Dispatchers.Default`. On a machine with
        // enough cores, `Default`'s pool serves 64 ten-millisecond blocking
        // calls in a few queued batches well within any timeout this test
        // could reasonably assert, so a wrong default would still pass here.
        // This test only proves many concurrent blocking calls all
        // complete; [defaultDispatcherIsIO] below is the guard on which
        // dispatcher the default actually is.
        val core = FakeCore(blockMillis = 10)
        val client = CoreClient(core = core)

        val jobs = List(64) { launch { client.connectionState() } }
        jobs.forEach { it.join() }

        assertEquals(64, core.connectionStateCallCount)
    }

    /** a default-constructed client uses `Dispatchers.IO`, not the cooperative pool */
    @Test
    fun defaultDispatcherIsIO() {
        // Deterministic, not a timing test. `manyBlockingCallsAtOnceDoNotStarveAnything`
        // above proves many concurrent blocking calls all complete, but on a
        // machine with enough cores that is equally true of `Dispatchers.IO`
        // and the wrong choice, `Dispatchers.Default` — so it cannot catch
        // someone changing the default. This assertion can: it reads the
        // constructor's default value back directly, the same one
        // `Task.detached` on iOS and `Dispatchers.Default` on Android are
        // both wrong to be.
        val client = CoreClient(core = FakeCore())
        assertEquals(Dispatchers.IO, client.dispatcher)
    }

    /**
     * A [CoreInterface] that never touches Rust — every wrapped method
     * [CoreClient] can call is implemented here, so the client's test can run
     * with no `.so`, no network, and no device involved.
     *
     * `private`, nested inside the test class it belongs to, and every
     * method this test does not configure throws rather than returning a
     * default that happens to work — the same house pattern every other
     * fake in this module follows (`AvatarCacheTest`, `RoomsStoreTest`,
     * `SessionTest`, and the rest). A version of this fake once lived at the
     * top level, public, with benign defaults for methods no test here
     * calls; that let a test that accidentally reached an unconfigured path
     * return a value that happened to work instead of failing loudly, which
     * is exactly the trap the other fakes' throwing defaults exist to avoid.
     *
     * [blockMillis] stands in for the real `Core`'s defining trait: a call
     * that blocks the calling thread for as long as the homeserver takes.
     * Sleeping a few milliseconds is enough to make
     * [manyBlockingCallsAtOnceDoNotStarveAnything] meaningful without
     * slowing the suite down.
     */
    private class FakeCore(private val blockMillis: Long = 0) : CoreInterface {
        // `manyBlockingCallsAtOnceDoNotStarveAnything` drives this fake from
        // 64 real `Dispatchers.IO` threads at once, all incrementing this
        // counter concurrently. A plain `var Int` loses updates under that —
        // `count++` is read-modify-write, not atomic, so two threads can both
        // read the same value before either writes back, and one increment
        // vanishes. That is exactly the 63/64 flake this counter caused:
        // `AtomicInteger` is what makes `incrementAndGet()` a single
        // indivisible operation. Any future counter here that a concurrent
        // test can observe needs the same treatment.
        private val _connectionStateCallCount = AtomicInteger(0)
        val connectionStateCallCount: Int
            get() = _connectionStateCallCount.get()

        private fun block() {
            if (blockMillis > 0) Thread.sleep(blockMillis)
        }

        override fun connectionState(): ConnectionState {
            block()
            _connectionStateCallCount.incrementAndGet()
            return ConnectionState(state = "offline", message = null)
        }

        override fun account(): uniffi.supermessage_core.AccountDto = throw NotImplementedError()
        override fun attachmentDiscard(token: String): Unit = throw NotImplementedError()
        override fun attachmentSend(roomId: String, token: String): Unit = throw NotImplementedError()
        override fun attachmentStagePath(roomId: String, path: String): uniffi.supermessage_ffi.StagedFile =
            throw NotImplementedError()
        override fun createRoom(name: String, invite: List<String>, isDirect: Boolean): String =
            throw NotImplementedError()
        override fun deleteMessage(roomId: String, eventId: String): Unit = throw NotImplementedError()
        override fun directRoomWith(userId: String): String? = throw NotImplementedError()
        override fun editMessage(roomId: String, eventId: String, body: String): Unit =
            throw NotImplementedError()
        override fun inviteUser(roomId: String, userId: String): Unit = throw NotImplementedError()
        override fun joinRoom(roomId: String): Unit = throw NotImplementedError()
        override fun joinRoomByAlias(aliasOrId: String): String = throw NotImplementedError()
        override fun knownPeople(): List<uniffi.supermessage_core.PersonDto> = throw NotImplementedError()
        override fun leaveRoom(roomId: String): Unit = throw NotImplementedError()
        override fun login(homeserver: String, username: String, password: String, sink: EventSink): Unit =
            throw NotImplementedError()
        override fun logout(): Unit = throw NotImplementedError()
        override fun markRoomRead(roomId: String): Unit = throw NotImplementedError()
        override fun mediaFetch(eventId: String): String? = throw NotImplementedError()
        override fun memberAvatar(mxcUri: String): String? = throw NotImplementedError()
        override fun restoreSession(sink: EventSink): Boolean = throw NotImplementedError()
        override fun roomAvatar(roomId: String): String? = throw NotImplementedError()
        override fun roomAvatarFull(roomId: String): String? = throw NotImplementedError()
        override fun roomInfo(roomId: String): uniffi.supermessage_core.RoomInfoDto =
            throw NotImplementedError()
        override fun roomInviter(roomId: String): String? = throw NotImplementedError()
        override fun roomsSnapshot(): uniffi.supermessage_ffi.RoomsSnapshot = throw NotImplementedError()
        override fun searchMessages(term: String, roomId: String?): List<uniffi.supermessage_core.SearchResultDto> =
            throw NotImplementedError()
        override fun sendMessage(roomId: String, body: String, mentions: List<String>): Unit =
            throw NotImplementedError()
        override fun sendReply(roomId: String, body: String, inReplyTo: String): Unit =
            throw NotImplementedError()
        override fun setRoomNotifications(roomId: String, mode: uniffi.supermessage_core.NotificationMode): Unit =
            throw NotImplementedError()
        override fun setRoomPinned(roomId: String, pinned: Boolean): Unit = throw NotImplementedError()
        override fun setTyping(roomId: String, typing: Boolean): Unit = throw NotImplementedError()
        override fun spaceSelect(spaceId: String?): Unit = throw NotImplementedError()
        override fun spacesList(): List<uniffi.supermessage_core.SpaceSummary> = throw NotImplementedError()
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean =
            throw NotImplementedError()
        override fun timelineResync(): uniffi.supermessage_ffi.TimelineSnapshot = throw NotImplementedError()
        override fun timelineSubscribe(roomId: String, sink: EventSink): Unit = throw NotImplementedError()
        override fun toggleReaction(roomId: String, eventId: String, key: String): Boolean =
            throw NotImplementedError()
    }
}
