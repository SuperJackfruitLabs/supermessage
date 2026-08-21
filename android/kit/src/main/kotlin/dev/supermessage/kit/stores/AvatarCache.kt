package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_ffi.FfiException

/**
 * Avatars, fetched once and kept.
 *
 * Keyed by whatever the caller fetches by — a room id for the roster, an
 * `mxc:` URI for a message sender. One type rather than two because the hard
 * part here is not the fetch (one line either way) but the observation and
 * eviction discipline below, and having that written twice is how one copy
 * quietly regresses.
 *
 * **A map, bounded by hand — not the platform's cache.** On iOS an `NSCache`
 * was the obvious choice and was wrong for one decisive reason: `@Observable`
 * cannot see through a reference type mutated behind its back, so an avatar
 * landing in the cache invalidated nothing and no row redrew. It presented
 * exactly as reported — no pictures on the first scroll, pictures on the
 * second, gone again after visiting a room — because the only thing that
 * ever showed them was some *other* change forcing a redraw. Compose has the
 * identical hazard: a `HashMap` mutated in place behind a `State` invalidates
 * nothing either. That is why [cache] is a [StateFlow] of an **immutable**
 * map, replaced wholesale on every write rather than mutated — the
 * replacement, not the fetch, is what a collector actually sees. See
 * [remember] for where that replacement happens.
 *
 * The bound still matters: an unbounded map is fine on a workstation and is
 * not fine on a phone, where an account with hundreds of rooms would hold
 * every avatar it ever scrolled past. So the eviction the platform cache
 * would have done is done here instead, in terms observation can follow.
 *
 * The value is a `data:` URI the core produced, so nothing here fetches from
 * the network or decodes an image itself.
 *
 * Ported from `apple/SupermessageKit/Stores/AvatarCache.swift`. Swift's
 * `@MainActor @Observable final class` becomes [cache] exposed as a
 * [StateFlow] — see `DraftStore`'s doc comment for why `@MainActor` becomes a
 * documented, not checked, invariant here rather than a compiler-enforced
 * one. [load] stays a plain `suspend` function, the same shape as Swift's
 * `async` one: it is always awaited from a caller's own coroutine (a row's
 * `LaunchedEffect`, matching Swift's `.task`), so — unlike `MediaCache`,
 * whose own [dev.supermessage.kit.stores.MediaCache.image] fires a fetch
 * without being awaited — this class needs no
 * [kotlinx.coroutines.CoroutineScope] of its own.
 */
class AvatarCache private constructor(
    private val countLimit: Int,
    private val fetch: suspend (String) -> String?,
) {
    /** Observable, which is the whole point — see this type's doc comment. */
    private val _cache = MutableStateFlow<Map<String, String>>(emptyMap())
    val cache: StateFlow<Map<String, String>> = _cache.asStateFlow()

    /**
     * Insertion order, oldest first, for eviction. A plain list because the
     * bound is a couple of hundred entries and the roster is walked far more
     * often than it is evicted from.
     */
    private val order = mutableListOf<String>()

    /**
     * Rooms the core has said have no avatar at all.
     *
     * Permanent, unlike the cache: an absence cannot be evicted into a
     * presence, and re-asking on every scroll past a room without a picture
     * is a round trip that can only come back empty.
     */
    private val withoutAvatar = mutableSetOf<String>()

    /** Fetches in flight, so the many rows that appear at once ask once. */
    private val fetching = mutableSetOf<String>()

    /** Room avatars, keyed by room id. */
    constructor(client: CoreClient, countLimit: Int = 200) : this(
        countLimit,
        fetch = { roomId ->
            try {
                client.roomAvatar(roomId)?.takeIf { it.isNotEmpty() }
            } catch (error: FfiException) {
                null
            }
        },
    )

    companion object {
        /**
         * Message senders' faces, keyed by the `mxc:` URI their profile
         * carries.
         *
         * Keyed by the URI rather than the user id on purpose: two members
         * with the same picture share one entry, and a member who changes
         * their picture gets a new key rather than a stale hit.
         */
        fun forMembers(client: CoreClient, countLimit: Int = 200): AvatarCache =
            AvatarCache(countLimit) { mxcUri ->
                try {
                    client.memberAvatar(mxcUri)?.takeIf { it.isNotEmpty() }
                } catch (error: FfiException) {
                    null
                }
            }
    }

    fun uri(roomId: String): String? = _cache.value[roomId]

    /**
     * Whether this room's avatar is worth asking the core for.
     *
     * **Keyed on what is held now, not on what was ever asked.** The
     * alternative keeps a set of every id it has ever attempted, and the
     * platform cache this replaced evicts — under memory pressure and at its
     * count limit. An evicted avatar would therefore never be fetched again,
     * and the row would show an empty circle for the rest of the session.
     * The eviction is invisible, so the bug looks like avatars randomly not
     * loading.
     */
    internal fun shouldFetch(roomId: String): Boolean =
        uri(roomId) == null && roomId !in withoutAvatar && roomId !in fetching

    internal fun remember(uri: String, roomId: String) {
        if (_cache.value[roomId] == null) order.add(roomId)
        _cache.value = _cache.value + (roomId to uri)
        fetching.remove(roomId)

        // Oldest first. Deliberately *not* least-recently-used: an LRU needs a
        // touch on every read, and a read here happens for every visible row
        // on every redraw.
        while (order.size > countLimit) {
            val evicted = order.removeAt(0)
            _cache.value = _cache.value - evicted
        }
    }

    internal fun rememberAbsent(roomId: String) {
        withoutAvatar.add(roomId)
        fetching.remove(roomId)
    }

    internal fun beginFetch(roomId: String) {
        fetching.add(roomId)
    }

    /**
     * Fetch unless it is held, known absent, or already in flight. Safe to
     * call from a row's `LaunchedEffect`, which is to say on every
     * appearance.
     */
    suspend fun load(roomId: String) {
        if (!shouldFetch(roomId)) return
        beginFetch(roomId)
        val fetched = fetch(roomId)
        if (fetched == null) {
            rememberAbsent(roomId)
        } else {
            remember(fetched, roomId)
        }
    }

    fun clear() {
        _cache.value = emptyMap()
        order.clear()
        withoutAvatar.clear()
        fetching.clear()
    }
}
