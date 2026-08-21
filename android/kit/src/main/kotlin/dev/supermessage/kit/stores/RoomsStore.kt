package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.GapSync
import dev.supermessage.kit.Snapshot
import dev.supermessage.kit.generic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_ffi.RoomDiffEnvelope

/**
 * The roster.
 *
 * Every row arrives with its name already split, its preview already
 * composed and its affordance already chosen — [RoomRow] carries all three,
 * decided by the core. **This store parses nothing**, and neither does
 * whatever draws it above it: that is what stops Android and the other
 * hosts disagreeing about what a room is called or whether it owes an
 * answer.
 *
 * Ported from `apple/SupermessageKit/Stores/RoomsStore.swift`. Swift's
 * `@MainActor @Observable` becomes [rooms] and [selectedId] exposed as
 * [StateFlow]s, matching the rest of `stores/` — see [DraftStore]'s doc
 * comment for why `@MainActor` becomes a documented, not checked, invariant
 * here. [scope] is the Kotlin addition that stands in for it: it is where
 * [GapSync] launches its resync, and it must be confined to a single thread
 * of execution the same way [StreamingText]'s is, since nothing here locks.
 *
 * Unlike Swift's `sync`, which is a `var GapSync<RoomRow>?` set inside
 * `init` because its closures capture `self` before `self` finishes
 * initializing, [sync] is a plain `val`: it only closes over [client] and
 * [_rooms], both already constructed above it, so Kotlin has nothing here
 * that needs the optional dance Swift's initialization order forces.
 */
class RoomsStore(
    private val client: CoreClient,
    scope: CoroutineScope,
    private val onSelect: (String) -> Unit = {},
) {
    private val _rooms = MutableStateFlow<List<RoomRow>>(emptyList())
    val rooms: StateFlow<List<RoomRow>> = _rooms.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /**
     * The name held across a roster that no longer contains the open room.
     *
     * A space switch re-emits the roster as a `Reset` that drops the room
     * the reader is looking at. The selection, its timeline and its title
     * all have to outlive that.
     */
    private var selectedNameFallback: String? = null

    private val sync: GapSync<RoomRow> =
        GapSync(
            scope = scope,
            resync = {
                val snapshot = client.roomsSnapshot()
                // The room list is a single-subject channel — every envelope
                // is by definition ours — so the subject is empty and the
                // `accepts` filter is left at its default.
                Snapshot(subject = "", seq = snapshot.seq, items = snapshot.rooms)
            },
            onUpdate = { _rooms.value = it },
        )

    fun handle(envelope: RoomDiffEnvelope) {
        sync.handle(subject = envelope.subject, seq = envelope.seq, ops = envelope.ops.map { it.generic })
    }

    /** Fetch the roster now, rather than waiting for something to change. */
    suspend fun seed() {
        sync.seed()
    }

    fun select(roomId: String) {
        _selectedId.value = roomId
        selectedNameFallback = row(roomId)?.room?.name
        onSelect(roomId)
    }

    /**
     * Close whatever room is open, leaving the roster alone.
     *
     * For a phone coming back from a conversation: there, the roster is the
     * previous screen rather than a column beside the room, so nothing is
     * selected once you have returned to it. Distinct from [clear], which
     * empties the roster too and belongs to signing out.
     */
    fun deselect() {
        _selectedId.value = null
        selectedNameFallback = null
    }

    fun row(roomId: String): RoomRow? = _rooms.value.firstOrNull { it.room.id == roomId }

    val selectedRow: RoomRow?
        get() = selectedId.value?.let { row(it) }

    /** The open room's title, surviving its disappearance from the roster. */
    val selectedName: String?
        get() = selectedRow?.identity?.name ?: selectedNameFallback

    fun clear() {
        _rooms.value = emptyList()
        _selectedId.value = null
        selectedNameFallback = null
        sync.stop()
    }

    /**
     * Undo [clear]'s [GapSync.stop], so a later sign-in's `seed` actually
     * does something rather than being silently swallowed by a latch that
     * never reset.
     *
     * Unlike [TimelineStore], which reaches the same recovery for free
     * because [GapSync.resetForNewSubscription] already calls
     * [GapSync.resume] and every `subscribeTo` — including the first one
     * after a sign-in — calls that, this store has no subscription context
     * to reset for: the room list is the single-subject channel
     * [GapSync]'s own KDoc describes, live continuously once signed in
     * rather than opened per room. So `Session` calls this directly instead,
     * and calls it *before* handing the pump back to the core (the same
     * spot `EventPump.reset` is called from) — not from inside `seed` — so
     * that a `RoomsDiff` racing in immediately after a fresh `signIn`, before
     * `seed` gets around to running, is not silently dropped by a latch
     * this store has not yet had the chance to clear.
     */
    fun resume() {
        sync.resume()
    }
}
