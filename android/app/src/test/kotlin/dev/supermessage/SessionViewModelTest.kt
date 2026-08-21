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

    /** Clearing the ViewModel signs the session out. */
    @Test
    fun clearingSignsOut() = runTest {
        val core = FakeCore()
        val vm = SessionViewModel.forTest(core)
        vm.clearForTest()
        assertEquals(1, core.logoutCalls)
    }

    /**
     * A [CoreInterface] that never touches Rust, following the house
     * pattern `:kit`'s `CoreClientTest` establishes: `private`, nested
     * inside the test class it belongs to, and every method this test does
     * not configure throws rather than returning a default that happens to
     * work. [logout] is the exception — the one call `Session.signOut`
     * actually makes on the path these tests exercise — and it counts
     * rather than throwing, deliberately: a no-op here would let
     * `clearingSignsOut` pass whether or not `signOut` was ever wired up to
     * call it, which is exactly the trap `:kit`'s `TimelineStoreTest`
     * documents about `NotImplementedError` being an `Error`, not an
     * `Exception` — silence would look identical to success. Everything
     * else `Session.signOut` reaches on this path (`RoomsStore.clear`,
     * `TimelineStore.clear`, `SpacesStore.clear`, `AvatarCache.clear`,
     * `EventPump.finish`, `StagedAttachment.discard`'s early return with
     * nothing staged) is confirmed, by reading each one, to touch no
     * `CoreInterface` method at all — so nothing else here needs a body.
     */
    private class FakeCore : CoreInterface {
        var logoutCalls: Int = 0
            private set

        override fun logout() {
            logoutCalls++
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
