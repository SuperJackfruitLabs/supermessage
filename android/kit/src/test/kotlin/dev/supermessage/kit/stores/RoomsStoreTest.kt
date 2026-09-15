package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.StubCore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomDiffEnvelope
import uniffi.supermessage_ffi.RoomDiffOp
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.StagedFile
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * Ported from `apple/SupermessageKitTests/RoomsStoreTests.swift`.
 *
 * What is specific to the roster store. The gap/resync machinery underneath
 * it is `GapSync`'s, and is tested there rather than again through a second
 * front door.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomsStoreTest {

    private fun row(id: String, name: String, membership: Membership = Membership.JOINED): RoomRow =
        RoomRow(
            room = RoomSummary(
                id = id, name = name, avatarUrl = null, unread = 0uL, lastMessage = null,
                lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
                lastActivityMs = null, runtime = null, membership = membership,
            ),
            identity = RoomIdentity(glyph = null, name = name, role = null, initial = "X"),
            preview = null,
            affordance = if (membership == Membership.JOINED) {
                RoomAffordance.COMPOSE
            } else {
                RoomAffordance.RESPOND_TO_INVITATION
            },
        )

    /** "the open room's name survives it being filtered out of the roster" */
    @Test
    fun `selection outlives a reset`() = runTest {
        // Exactly what a space switch does: the core re-emits the roster as a
        // Reset that no longer contains the open room. The selection, its
        // timeline and its title all have to outlive that, or switching space
        // with a room open blanks the header.
        val rooms = RoomsStore(client = CoreClient(core = FakeCore()), scope = this)
        rooms.handle(
            RoomDiffEnvelope(
                channel = "sm://rooms/diff", subject = "", seq = 1uL,
                ops = listOf(RoomDiffOp.Reset(values = listOf(row("!a:x", "Ganesha")))),
            ),
        )
        rooms.select("!a:x")
        assertEquals("Ganesha", rooms.selectedName)

        rooms.handle(
            RoomDiffEnvelope(
                channel = "sm://rooms/diff", subject = "", seq = 2uL,
                ops = listOf(RoomDiffOp.Reset(values = listOf(row("!b:x", "Ops")))),
            ),
        )

        assertEquals("the selection was dropped", "!a:x", rooms.selectedId.value)
        assertEquals("the header lost its title", "Ganesha", rooms.selectedName)
    }

    /** "a rename lands immediately, because the row is the live one" */
    @Test
    fun `rename lands`() = runTest {
        val rooms = RoomsStore(client = CoreClient(core = FakeCore()), scope = this)
        rooms.handle(
            RoomDiffEnvelope(
                channel = "sm://rooms/diff", subject = "", seq = 1uL,
                ops = listOf(RoomDiffOp.Reset(values = listOf(row("!a:x", "Ganesha")))),
            ),
        )
        rooms.select("!a:x")

        rooms.handle(
            RoomDiffEnvelope(
                channel = "sm://rooms/diff", subject = "", seq = 2uL,
                ops = listOf(RoomDiffOp.Set(index = 0u, value = row("!a:x", "Ganesha Prime"))),
            ),
        )

        assertEquals("Ganesha Prime", rooms.selectedName)
    }

    /** "clearing drops everything, so a second account starts empty" */
    @Test
    fun `clear is thorough`() = runTest {
        val rooms = RoomsStore(client = CoreClient(core = FakeCore()), scope = this)
        rooms.handle(
            RoomDiffEnvelope(
                channel = "sm://rooms/diff", subject = "", seq = 1uL,
                ops = listOf(RoomDiffOp.Reset(values = listOf(row("!a:x", "Ganesha")))),
            ),
        )
        rooms.select("!a:x")

        rooms.clear()

        assertEquals(emptyList<RoomRow>(), rooms.rooms.value)
        assertEquals(null, rooms.selectedId.value)
        assertEquals("a stale title outlived the sign-out", null, rooms.selectedName)
    }

    /**
     * Not from Swift: `handle` before any `select` must not crash reading a
     * roster that does not yet contain anything, and `selectedRow`/`row`
     * must agree on "not found" being `null` rather than throwing.
     */
    @Test
    fun `selecting a room id absent from the roster leaves selectedRow null`() = runTest {
        val rooms = RoomsStore(client = CoreClient(core = FakeCore()), scope = this)

        rooms.select("!missing:x")

        assertEquals("!missing:x", rooms.selectedId.value)
        assertEquals(null, rooms.selectedRow)
        assertEquals(null, rooms.selectedName)
    }

    /**
     * Not from Swift: [RoomsStore.onSelect] is a side channel — Session
     * (Task 15) uses it to drive the timeline — and it must fire with the
     * id that was actually selected, and only on `select`, never on
     * `deselect` or `clear`.
     */
    @Test
    fun `selecting a room notifies onSelect with that room's id`() = runTest {
        val selected = mutableListOf<String>()
        val rooms = RoomsStore(client = CoreClient(core = FakeCore()), scope = this, onSelect = { selected.add(it) })

        rooms.select("!a:x")
        rooms.deselect()
        rooms.clear()

        assertEquals(listOf("!a:x"), selected)
    }

    /**
     * "resume undoes clear's latch, so a later sign-in's diffs are not
     * silently dropped" — not from Swift, which has no `RoomsStore.resume`
     * at all. `clear()` calls `GapSync.stop()`, and without `resume()`
     * undoing it, the *first* sign-out in a process's life would leave
     * every later sign-in's roster permanently empty: `handle`'s own
     * `sync.handle` is gated on `GapSync`'s `stopped` flag, which `stop()`
     * never had a way back from before this fix. See `RoomsStore.resume`'s
     * own KDoc for why `Session` calls this directly rather than relying on
     * `seed`.
     */
    @Test
    fun `resume after clear lets handle publish again`() = runTest {
        val rooms = RoomsStore(client = CoreClient(core = FakeCore()), scope = this)
        rooms.handle(
            RoomDiffEnvelope(
                channel = "sm://rooms/diff", subject = "", seq = 1uL,
                ops = listOf(RoomDiffOp.Reset(values = listOf(row("!a:x", "Ganesha")))),
            ),
        )
        assertEquals(listOf("!a:x"), rooms.rooms.value.map { it.room.id })

        rooms.clear()
        assertEquals(emptyList<RoomRow>(), rooms.rooms.value)

        // seq 2 — genuinely the next envelope in sequence, so a rejection
        // here can only be `stopped`'s doing, not `DiffTracker` treating it
        // as an already-seen duplicate. Without resume(), this would be
        // silently swallowed by GapSync's still-tripped `stopped` latch —
        // the same failure mode a later sign-in's roster would show,
        // permanently, after the first sign-out in a process's life.
        rooms.handle(
            RoomDiffEnvelope(
                channel = "sm://rooms/diff", subject = "", seq = 2uL,
                ops = listOf(RoomDiffOp.Reset(values = listOf(row("!b:x", "Ops")))),
            ),
        )
        assertEquals(
            "handle before resume must have done nothing, silently, without this",
            emptyList<RoomRow>(),
            rooms.rooms.value,
        )

        rooms.resume()
        // The same envelope, replayed: `stop()`/`resume()` never touched the
        // tracker's own sequence, so seq 2 is still the next one expected.
        rooms.handle(
            RoomDiffEnvelope(
                channel = "sm://rooms/diff", subject = "", seq = 2uL,
                ops = listOf(RoomDiffOp.Reset(values = listOf(row("!b:x", "Ops")))),
            ),
        )

        assertEquals(
            "resume must let a later sign-in's diffs publish again",
            listOf("!b:x"),
            rooms.rooms.value.map { it.room.id },
        )
    }

    private class FakeCore : StubCore() {
    }
}
