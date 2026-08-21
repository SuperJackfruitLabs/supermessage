package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.GapSync
import dev.supermessage.kit.Snapshot
import dev.supermessage.kit.generic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.TimelineDiffEnvelope

/**
 * The focused room's timeline.
 *
 * Only one room is ever subscribed. Switching rooms restarts the core's
 * sequence counter at 1, so [subscribeTo] resets local tracking to a fresh
 * generation **before** issuing the subscribe — and the `accepts` filter
 * below rejects anything belonging to a room this store is no longer
 * showing.
 *
 * Resetting alone was not enough on the desktop, and the sequence that broke
 * it is worth keeping in view:
 *
 *  1. `subscribeTo("!b")` resets tracking, then awaits the subscribe, which
 *     has to build the timeline — slow.
 *  2. Room A's subscription is still installed and still emitting. Its next
 *     envelope arrives at, say, seq 12 against a tracker expecting 1: a gap.
 *  3. The resync that gap triggers is a fast mutex read, so it beats the
 *     subscribe — and it is served out of room A's still-installed handle.
 *     The tracker now holds A's items at A's high seq.
 *  4. Room B's stream finally starts at seq 1, 2, 3 — all below what the
 *     tracker expects, so all discarded as duplicates.
 *
 * Room A's messages then sit under room B's header until the next switch.
 * Rejecting anything whose subject is not the focused room turns steps 2 and
 * 3 into no-ops.
 *
 * Ported from `apple/SupermessageKit/Stores/TimelineStore.swift`. Swift's
 * `@MainActor @Observable` becomes [items], [revision], [roomId],
 * [isPaginating] and [canPaginate] exposed as [StateFlow]s, matching the
 * rest of `stores/` — see [RoomsStore]'s doc comment for why `@MainActor`
 * becomes a documented, not checked, invariant here. [scope] is the Kotlin
 * addition that stands in for it: it is where [GapSync] launches its
 * resync, and it must be confined to a single thread of execution the same
 * way [RoomsStore]'s is.
 *
 * **Unlike [RoomsStore]'s `sync`, which leaves `accepts` at its default**
 * because the room list is a single-subject channel where every envelope is
 * by definition ours, this store's subject is the focused room id, and it
 * changes mid-subscribe (see the hazard above) — so `accepts` here is a
 * real predicate, checked against [roomId] at the time an envelope or resync
 * snapshot arrives.
 *
 * Every `CoreClient` call below swallows its failure the way Swift's `try?`
 * does, with one deliberate difference already documented on [SpacesStore]:
 * [CancellationException] is rethrown before the broad catch, so a
 * coroutine cancellation is never mistaken for an ordinary failure.
 */
class TimelineStore(
    private val client: CoreClient,
    private val sink: EventSink,
    scope: CoroutineScope,
) {
    private val _items = MutableStateFlow<List<TimelineRow>>(emptyList())
    val items: StateFlow<List<TimelineRow>> = _items.asStateFlow()

    /**
     * Bumped whenever [items] is replaced.
     *
     * A cheap way for a list to answer "did the history actually change" in
     * constant time. It matters because a streaming turn updates other
     * observable state many times a second, and a list that cannot tell
     * "new token" from "new message" rebuilds every row for both — which is
     * exactly what made the timeline jitter while an agent was writing.
     *
     * Swift's `UInt64` wrapping (`&+=`) becomes [ULong], which wraps on
     * overflow under plain `+=` by default — no explicit wrapping operator
     * needed on the JVM.
     */
    private val _revision = MutableStateFlow(0uL)
    val revision: StateFlow<ULong> = _revision.asStateFlow()

    private val _roomId = MutableStateFlow<String?>(null)
    val roomId: StateFlow<String?> = _roomId.asStateFlow()

    /**
     * Set while a back-pagination round trip is in flight, so the view can
     * show it and so two do not overlap.
     */
    private val _isPaginating = MutableStateFlow(false)
    val isPaginating: StateFlow<Boolean> = _isPaginating.asStateFlow()

    /** False once the core reports there is no more history to fetch. */
    private val _canPaginate = MutableStateFlow(true)
    val canPaginate: StateFlow<Boolean> = _canPaginate.asStateFlow()

    private val sync: GapSync<TimelineRow> =
        GapSync(
            scope = scope,
            resync = {
                val snapshot = client.timelineResync()
                Snapshot(subject = snapshot.roomId, seq = snapshot.seq, items = snapshot.items)
            },
            // The subject is the focused room id, and it changes under this
            // store while a subscribe round trip is in flight.
            accepts = { subject -> subject == _roomId.value },
            onUpdate = { replaceItems(it) },
        )

    /** The one place [items] is written, so [revision] cannot drift from it. */
    private fun replaceItems(next: List<TimelineRow>) {
        _items.value = next
        _revision.value += 1uL
    }

    fun handle(envelope: TimelineDiffEnvelope) {
        sync.handle(subject = envelope.subject, seq = envelope.seq, ops = envelope.ops.map { it.generic })
    }

    /** Focus a room. Safe to call for the room already open — it does nothing. */
    suspend fun subscribeTo(roomId: String) {
        if (roomId == _roomId.value) return
        // Order matters: reset and re-point *before* the round trip, so
        // anything the previous room emits while it is in flight is
        // rejected by `accepts` rather than mistaken for a gap.
        sync.resetForNewSubscription()
        _roomId.value = roomId
        replaceItems(emptyList())
        _canPaginate.value = true
        try {
            client.timelineSubscribe(roomId = roomId, sink = sink)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Mirrors Swift's `try?`.
        }
    }

    /** Fetch older messages. `false` when there are none left. */
    suspend fun paginateBack(count: UShort = 20u): Boolean {
        val roomId = _roomId.value
        if (roomId == null || _isPaginating.value || !_canPaginate.value) return false
        _isPaginating.value = true
        try {
            // **`paginate_backwards` returns whether it hit the *start* of
            // the timeline**, not whether more remains — the SDK documents
            // it as "Returns whether we hit the start of the timeline". Read
            // the wrong way round, the first successful page in any room
            // with real history (which does not reach the start) switched
            // pagination off for good, and nothing older than the opening
            // screen would ever load.
            //
            // A failed call defaults to `false`: a network error is not
            // evidence that a room has no more history, and treating it as
            // such would make one dropped request permanent.
            val reachedStart =
                try {
                    client.timelinePaginateBack(roomId = roomId, count = count)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            applyPaginationResult(reachedStart = reachedStart)
            return _canPaginate.value
        } finally {
            _isPaginating.value = false
        }
    }

    /**
     * Record what a pagination round trip reported.
     *
     * Separate from the call so the state transition can be tested without
     * a homeserver — the inversion above was invisible until this had a
     * name.
     */
    internal fun applyPaginationResult(reachedStart: Boolean) {
        _canPaginate.value = !reachedStart
    }

    suspend fun markRead() {
        val roomId = _roomId.value ?: return
        try {
            client.markRoomRead(roomId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Mirrors Swift's `try?`.
        }
    }

    /** Re-ask for the timeline, for a store that came back to a quiet room. */
    suspend fun seed() {
        sync.seed()
    }

    fun clear() {
        sync.stop()
        replaceItems(emptyList())
        _roomId.value = null
    }
}
