package dev.supermessage.kit.stores

import dev.supermessage.kit.CoreClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.supermessage_ffi.FfiException

/**
 * Media bytes for the timeline, fetched once and remembered.
 *
 * Ported from `apple/SupermessageKit/Stores/MediaCache.swift`, itself ported
 * from `src/lib/stores/mediaCache.svelte.ts`, including the distinction that
 * file's doc comment argues for and which is easy to lose: **"still loading"
 * and "there is nothing to show" are different states**, and a cache that
 * conflates them either renders a spinner forever or a broken image
 * immediately. [image] is `null` in both cases; [hasFailed] is what tells
 * them apart.
 *
 * Addressed by **event id**, not by row identity: media lives on the
 * homeserver against an event, and a local echo has no event yet.
 *
 * A [StateFlow] of an **immutable** map, bounded by hand, for the reason
 * [AvatarCache]'s doc comment gives at length: an observer cannot see through
 * a reference type mutated behind its back, so a picture landing in one
 * invalidates nothing and the row keeps its placeholder. [cache] is replaced
 * wholesale on every write (see [remember]) rather than mutated in place, the
 * same discipline as [AvatarCache]. Bounded by **bytes** rather than count,
 * because these are message-sized images and one of them can be worth a
 * hundred avatars.
 *
 * **Where this port differs from Swift.** Swift decodes the core's `data:`
 * URI into a `UIImage` right here and bounds the cache by the *decoded*
 * pixel byte count (`bytesPerRow * height`), so the view never repeats a
 * decode a scrolling list would otherwise redo on every pass. `:kit` declares
 * no dependency on Compose or on Android's bitmap-decoding APIs, and none was
 * added for this port — decoding stays the view layer's job, the same as the
 * network fetch always was, and the JVM unit tests here exercise it without
 * an emulator or Robolectric. This cache therefore holds the still-encoded
 * `data:` URI [String] and bounds it by that string's own UTF-8 byte length —
 * a proxy for size, not the decoded figure Swift uses. [markFailed] is
 * unaffected either way: it is how a caller reports "the core produced bytes
 * and they would not render." On iOS that caller is the decode step inside
 * `load`; here it is whichever view-layer image loader attempts the decode
 * instead — which is exactly how Swift's own app already uses it today:
 * nothing in `apple/Supermessage` calls `markFailed` from inside
 * `MediaCache` either, only from a test.
 */
class MediaCache(
    private val client: CoreClient,
    private val scope: CoroutineScope,
    private val byteLimit: Int = 64 * 1024 * 1024,
) {
    /**
     * Held once, on arrival, and evictable — see this type's doc comment for
     * what "held" means on this port.
     */
    private val _cache = MutableStateFlow<Map<String, String>>(emptyMap())
    val cache: StateFlow<Map<String, String>> = _cache.asStateFlow()

    private val order = mutableListOf<String>()
    private var bytesHeld = 0
    private val cost = mutableMapOf<String, Int>()

    /**
     * Events that resolved to nothing renderable. Permanent, unlike the
     * cache: an absence cannot be evicted into a presence.
     */
    private val failed = mutableSetOf<String>()

    /**
     * Fetches in flight, so a row drawn repeatedly before the first answer
     * lands asks once.
     */
    private val fetching = mutableSetOf<String>()

    /**
     * The held URI, starting a fetch the first time an event is seen. `null`
     * both before the fetch resolves and once it has resolved with nothing
     * renderable — ask [hasFailed] to tell those apart.
     */
    fun image(eventId: String): String? {
        val held = _cache.value[eventId]
        if (held != null) return held
        // Not held, and worth asking for: `failed` is the permanent absence
        // and `fetching` is the one already in flight. Anything else — a
        // first sighting, or a URI the cache has since evicted — is
        // fetched. Keying this on what is held rather than on what was ever
        // asked is what keeps an evicted entry reachable; see
        // [AvatarCache.shouldFetch], where getting it wrong showed as
        // avatars randomly not loading.
        if (eventId !in failed && eventId !in fetching) {
            fetching.add(eventId)
            scope.launch { load(eventId) }
        }
        return null
    }

    /**
     * Whether this event has definitively resolved to nothing renderable.
     *
     * `false` while a fetch is still in flight, which is why a caller
     * showing a placeholder has to check both this and [image].
     */
    fun hasFailed(eventId: String): Boolean = eventId in failed

    /**
     * For the failure only the renderer can see: a `data:` URI the core
     * produced that an image decoder then refused. The last line of the
     * never-show-a-broken-image guarantee.
     */
    fun markFailed(eventId: String) {
        evict(eventId)
        failed.add(eventId)
        fetching.remove(eventId)
    }

    /** Hold a URI, evicting oldest-first to stay under the limit. */
    internal fun remember(uri: String, eventId: String) {
        // The encoded string is what this port bounds by — see this type's
        // doc comment for why that is a proxy for the decoded size Swift
        // bounds by, not the same figure.
        val bytes = uri.toByteArray(Charsets.UTF_8).size
        if (_cache.value[eventId] == null) order.add(eventId)
        evictCost(eventId)
        _cache.value = _cache.value + (eventId to uri)
        cost[eventId] = bytes
        bytesHeld += bytes

        // Never evict the entry just stored — a single picture larger than
        // the whole limit would otherwise be fetched and dropped forever.
        while (bytesHeld > byteLimit && order.size > 1) {
            evict(order[0])
        }
    }

    private fun evict(eventId: String) {
        evictCost(eventId)
        _cache.value = _cache.value - eventId
        order.remove(eventId)
    }

    private fun evictCost(eventId: String) {
        bytesHeld -= cost.remove(eventId) ?: 0
    }

    private suspend fun load(eventId: String) {
        try {
            val uri = try {
                client.mediaFetch(eventId)
            } catch (error: FfiException) {
                null
            }
            if (uri == null) {
                // "The core found nothing fetchable" and "the fetch failed"
                // both land here. Neither is worth telling a reader about in
                // a timeline, which is why they share `failed` with the
                // decode refusal `markFailed` reports.
                failed.add(eventId)
            } else {
                remember(uri, eventId)
            }
        } finally {
            fetching.remove(eventId)
        }
    }
}
