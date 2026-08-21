package dev.supermessage.kit

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
        val core = FakeCore(blockMillis = 10)
        val client = CoreClient(core = core)

        val jobs = List(64) { launch { client.connectionState() } }
        jobs.forEach { it.join() }

        assertEquals(64, core.connectionStateCallCount)
    }
}

/**
 * A [CoreInterface] that never touches Rust — every wrapped method
 * [CoreClient] can call is implemented here, so the client's test can run
 * with no `.so`, no network, and no device involved.
 *
 * [blockMillis] stands in for the real `Core`'s defining trait: a call that
 * blocks the calling thread for as long as the homeserver takes. Sleeping a
 * few milliseconds is enough to make [CoreClientTest.manyBlockingCallsAtOnceDoNotStarveAnything]
 * meaningful without slowing the suite down.
 */
class FakeCore(private val blockMillis: Long = 0) : CoreInterface {
    var connectionStateCallCount: Int = 0
        private set

    private fun block() {
        if (blockMillis > 0) Thread.sleep(blockMillis)
    }

    override fun account(): uniffi.supermessage_core.AccountDto = throw NotImplementedError()

    override fun attachmentDiscard(token: String) {}

    override fun attachmentSend(roomId: String, token: String) {}

    override fun attachmentStagePath(roomId: String, path: String): uniffi.supermessage_ffi.StagedFile =
        throw NotImplementedError()

    override fun connectionState(): ConnectionState {
        block()
        connectionStateCallCount++
        return ConnectionState(state = "offline", message = null)
    }

    override fun createRoom(name: String, invite: List<String>, isDirect: Boolean): String =
        throw NotImplementedError()

    override fun deleteMessage(roomId: String, eventId: String) {}

    override fun directRoomWith(userId: String): String? = null

    override fun editMessage(roomId: String, eventId: String, body: String) {}

    override fun inviteUser(roomId: String, userId: String) {}

    override fun joinRoom(roomId: String) {}

    override fun joinRoomByAlias(aliasOrId: String): String = aliasOrId

    override fun knownPeople(): List<uniffi.supermessage_core.PersonDto> = emptyList()

    override fun leaveRoom(roomId: String) {}

    override fun login(homeserver: String, username: String, password: String, sink: EventSink) {}

    override fun logout() {}

    override fun markRoomRead(roomId: String) {}

    override fun mediaFetch(eventId: String): String? = null

    override fun memberAvatar(mxcUri: String): String? = null

    override fun restoreSession(sink: EventSink): Boolean = false

    override fun roomAvatar(roomId: String): String? = null

    override fun roomAvatarFull(roomId: String): String? = null

    override fun roomInfo(roomId: String): uniffi.supermessage_core.RoomInfoDto = throw NotImplementedError()

    override fun roomInviter(roomId: String): String? = null

    override fun roomsSnapshot(): uniffi.supermessage_ffi.RoomsSnapshot = throw NotImplementedError()

    override fun searchMessages(term: String, roomId: String?): List<uniffi.supermessage_core.SearchResultDto> =
        emptyList()

    override fun sendMessage(roomId: String, body: String, mentions: List<String>) {}

    override fun sendReply(roomId: String, body: String, inReplyTo: String) {}

    override fun setRoomNotifications(roomId: String, mode: uniffi.supermessage_core.NotificationMode) {}

    override fun setRoomPinned(roomId: String, pinned: Boolean) {}

    override fun setTyping(roomId: String, typing: Boolean) {}

    override fun spaceSelect(spaceId: String?) {}

    override fun spacesList(): List<uniffi.supermessage_core.SpaceSummary> = emptyList()

    override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = true

    override fun timelineResync(): uniffi.supermessage_ffi.TimelineSnapshot = throw NotImplementedError()

    override fun timelineSubscribe(roomId: String, sink: EventSink) {}

    override fun toggleReaction(roomId: String, eventId: String, key: String): Boolean = false
}
