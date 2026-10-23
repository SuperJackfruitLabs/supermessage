package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.StubCore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.RoomDiffEnvelope
import uniffi.supermessage_ffi.RoomDiffOp
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.spaceDisplayName

/**
 * Two rules the destinations depend on (parity M5, desktop's whole-branch review):
 * an empty roster is not the same as one not yet read, and an answer to a question
 * asked for a previous account or space never lands.
 *
 * The reads are held open on real threads behind latches, the way
 * `AvatarCacheTest.a cancelled load releases its in-flight guard` does.
 */
class FirstReadAndStaleReadsTest {

    private fun row(id: String) = RoomRow(
        room = RoomSummary(
            id = id, name = id, avatarUrl = null, unread = 0uL, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = null, runtime = null, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = id, role = null, initial = "X"),
        preview = null,
        affordance = RoomAffordance.COMPOSE,
    )

    private fun space(id: String) = SpaceSummary(
        id = id, name = id, avatarUrl = null, childCount = 0uL, membership = Membership.JOINED,
        identity = RoomIdentity(glyph = null, name = id, role = null, initial = "X"),
        displayName = spaceDisplayName(id),
    )

    /** A core whose reads wait for [release] after saying they began. */
    private class HeldCore(
        val rooms: List<RoomRow> = emptyList(),
        val spaces: List<SpaceSummary> = emptyList(),
        val avatar: String? = null,
    ) : StubCore() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private fun hold() {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        override fun roomsSnapshot(): RoomsSnapshot { hold(); return RoomsSnapshot(seq = 1uL, rooms = rooms) }
        override fun spacesList(): List<SpaceSummary> { hold(); return spaces }
        override fun roomAvatar(roomId: String): String? { hold(); return avatar }
    }

    private fun <T> withScope(body: suspend (CoroutineScope) -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        return try { runBlocking { body(scope) } } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    // MARK: the first read

    @Test
    fun `the roster is not loaded until the first read lands, and an empty one then is`() = withScope { scope ->
        val rooms = RoomsStore(client = CoreClient(core = HeldCore()), scope = scope)
        assertFalse("nothing has been read", rooms.loaded.value)

        rooms.handle(
            RoomDiffEnvelope("sm://rooms/diff", "", 1uL, listOf(RoomDiffOp.Reset(values = emptyList()))),
        )

        assertTrue(rooms.rooms.value.isEmpty())
        assertTrue("an empty roster that was read is loaded", rooms.loaded.value)
    }

    @Test
    fun `a seed counts as the first read`() = withScope { scope ->
        val core = HeldCore(rooms = listOf(row("!a:x")))
        core.release.countDown()
        val rooms = RoomsStore(client = CoreClient(core = core), scope = scope)
        rooms.seed()
        assertTrue(rooms.loaded.value)
        assertEquals(listOf("!a:x"), rooms.rooms.value.map { it.room.id })
    }

    @Test
    fun `signing out makes the roster unread again`() = withScope { scope ->
        val rooms = RoomsStore(client = CoreClient(core = HeldCore()), scope = scope)
        rooms.handle(
            RoomDiffEnvelope("sm://rooms/diff", "", 1uL, listOf(RoomDiffOp.Reset(values = listOf(row("!a:x"))))),
        )
        assertTrue(rooms.loaded.value)
        rooms.clear()
        assertFalse(rooms.loaded.value)
    }

    // MARK: stale reads

    @Test
    fun `a roster read from before a sign-out and back in does not land`() = withScope { scope ->
        val core = HeldCore(rooms = listOf(row("!old:x")))
        val rooms = RoomsStore(client = CoreClient(core = core), scope = scope)
        val job = scope.launch { rooms.seed() }
        assertTrue(core.entered.await(5, TimeUnit.SECONDS))
        rooms.clear()
        rooms.resume()
        core.release.countDown()
        job.join()
        assertTrue("the previous account's rooms landed", rooms.rooms.value.isEmpty())
        assertFalse("and counted as the first read", rooms.loaded.value)
    }

    @Test
    fun `a roster read from before a space switch does not land`() = withScope { scope ->
        val core = HeldCore(rooms = listOf(row("!old-space:x")))
        val rooms = RoomsStore(client = CoreClient(core = core), scope = scope)
        val job = scope.launch { rooms.seed() }
        assertTrue(core.entered.await(5, TimeUnit.SECONDS))
        rooms.supersede()
        core.release.countDown()
        job.join()
        assertTrue("the previous space's rooms landed", rooms.rooms.value.isEmpty())
        assertFalse(rooms.loaded.value)
    }

    @Test
    fun `a spaces read from before a sign-out does not land`() = withScope { scope ->
        val core = HeldCore(spaces = listOf(space("!old:x")))
        val spaces = SpacesStore(CoreClient(core = core))
        val job = scope.launch { spaces.refresh() }
        assertTrue(core.entered.await(5, TimeUnit.SECONDS))
        spaces.clear()
        core.release.countDown()
        job.join()
        assertTrue("the previous account's spaces landed", spaces.spaces.value.isEmpty())
    }

    @Test
    fun `an avatar fetched before a sign-out does not land`() = withScope { scope ->
        val core = HeldCore(avatar = "data:image/png;base64,AAAA")
        val avatars = AvatarCache(client = CoreClient(core = core))
        val job = scope.launch { avatars.load("!a:x") }
        assertTrue(core.entered.await(5, TimeUnit.SECONDS))
        avatars.clear()
        core.release.countDown()
        job.join()
        assertTrue("the previous account's avatar landed", avatars.cache.value.isEmpty())
    }
}
