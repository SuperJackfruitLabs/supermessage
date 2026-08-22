package dev.supermessage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink

/**
 * `SessionViewModel` is the one place in the Android app that constructs a
 * real `Core` — which means opening SQLite against a real data directory,
 * something no plain JVM unit test can do and Robolectric is deliberately
 * not reached for here (see the Task 1 brief). So this suite does not touch
 * the public `SessionViewModel(app: Application)` constructor at all: it
 * drives the same `Session`-building wiring through
 * [SessionViewModel.Companion.forTest], the seam that exists so this file
 * can prove the wiring without an `Application`, a `.so`, or a device.
 */
class SessionViewModelTest {

    /** The session is built once and handed out, not rebuilt per read. */
    @Test
    fun theSessionIsStable() = runTest {
        val vm = SessionViewModel.forTest(FakeCore())
        assertNotNull(vm.session)
        assertEquals(vm.session, vm.session)
    }

    /**
     * [SessionViewModel.build] wires the `CoreInterface` it is handed all
     * the way through to the `Session` it returns — this is a `Session`
     * that actually talks to the core it was built with, not one that
     * looks real but reaches nothing.
     *
     * This replaces an earlier `clearingSignsOut` test that asserted
     * clearing the ViewModel calls the fake's `logout()`. That behaviour
     * does not exist in production — see [SessionViewModel.onCleared]'s own
     * KDoc for why it must not — so the old test's mutation could only ever
     * break the test's own harness, never a real code path. It has been
     * removed rather than kept as a decorative pass.
     */
    @Test
    fun theSessionReachesTheCoreItWasBuiltWith() = runTest {
        val core = FakeCore()
        val vm = SessionViewModel.forTest(core)

        val inviter = vm.session.inviter(roomId = "room-1")

        assertEquals("the-fake-core-answered", inviter)
        assertEquals(1, core.roomInviterCalls)
    }

    /**
     * A [CoreInterface] that never touches Rust, following the house
     * pattern `:kit`'s `CoreClientTest` establishes: `private`, nested
     * inside the test class it belongs to, and every method this test does
     * not configure throws rather than returning a default that happens to
     * work. [roomInviter] is the one exception — `Session.inviter` calls it
     * directly, with no `try`/`catch` swallowing a wrong answer into a
     * default, which is exactly why it is the call
     * [theSessionReachesTheCoreItWasBuiltWith] uses to prove the wiring:
     * a `NotImplementedError` here (an `Error`, not an `Exception` —
     * `:kit`'s `TimelineStoreTest` documents the same trap) would fail
     * loudly rather than being caught and hidden, and a wrong return value
     * would be visible directly in the assertion, not laundered through a
     * `catch (e: Exception) { null }` the way most of `Session`'s other
     * passthroughs are.
     */
    private class FakeCore : CoreInterface {
        var roomInviterCalls: Int = 0
            private set

        override fun roomInviter(roomId: String): String? {
            roomInviterCalls++
            return "the-fake-core-answered"
        }

        override fun account(): uniffi.supermessage_core.AccountDto = throw NotImplementedError()
        override fun attachmentDiscard(token: String): Unit = throw NotImplementedError()
        override fun attachmentSend(roomId: String, token: String): Unit = throw NotImplementedError()
        override fun attachmentStagePath(roomId: String, path: String): uniffi.supermessage_ffi.StagedFile =
            throw NotImplementedError()
        override fun connectionState(): ConnectionState = throw NotImplementedError()
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
