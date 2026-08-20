import Foundation
import Observation
import SupermessageFFI
import UIKit

/// Media bytes for the timeline, fetched once and remembered.
///
/// Ported from `src/lib/stores/mediaCache.svelte.ts`, including the
/// distinction that file's doc comment argues for and which is easy to lose:
/// **"still loading" and "there is nothing to show" are different states**, and
/// a cache that conflates them either renders a spinner forever or a broken
/// image immediately. `uri` is `nil` in both cases; `hasFailed` is what tells
/// them apart.
///
/// Addressed by **event id**, not by row identity: media lives on the
/// homeserver against an event, and a local echo has no event yet. See
/// `TimelineItemDto`'s field docs for the distinction.
@MainActor
@Observable
public final class MediaCache {
    /// Decoded once, on arrival, and evictable.
    ///
    /// The core hands back a `data:` URI, and turning that into pixels is not
    /// free — doing it in the view's body would decode the same image on every
    /// pass a scrolling list makes over the row.
    ///
    /// A dictionary bounded by hand, for the reason `AvatarCache`'s doc
    /// comment gives at length: `@Observable` cannot see through an `NSCache`,
    /// so a picture landing in one invalidates nothing and the row keeps its
    /// placeholder. Bounded by **bytes** rather than count, because these are
    /// message-sized images and one of them can be worth a hundred avatars.
    private var cache: [String: UIImage] = [:]
    private var order: [String] = []
    private var bytesHeld = 0
    private var cost: [String: Int] = [:]
    /// Events that resolved to nothing renderable. Permanent, unlike the
    /// cache: an absence cannot be evicted into a presence.
    private var failed: Set<String> = []
    /// Fetches in flight, so a row drawn repeatedly before the first answer
    /// lands asks once.
    private var fetching: Set<String> = []

    private let client: CoreClient

    private let byteLimit: Int

    public init(client: CoreClient, byteLimit: Int = 64 * 1024 * 1024) {
        self.client = client
        self.byteLimit = byteLimit
    }

    /// The decoded image, starting a fetch the first time an event is seen.
    /// `nil` both before the fetch resolves and once it has resolved with
    /// nothing renderable — ask `hasFailed` to tell those apart.
    public func image(for eventId: String) -> UIImage? {
        if let held = cache[eventId] { return held }
        // Not held, and worth asking for: `failed` is the permanent absence
        // and `fetching` is the one already in flight. Anything else — a first
        // sighting, or an image the cache has since evicted — is fetched.
        // Keying this on what is held rather than on what was ever asked is
        // what keeps an evicted image reachable; see `AvatarCache.shouldFetch`,
        // where getting it wrong showed as avatars randomly not loading.
        if !failed.contains(eventId), !fetching.contains(eventId) {
            fetching.insert(eventId)
            Task { await load(eventId) }
        }
        return nil
    }

    /// Whether this event has definitively resolved to nothing renderable.
    ///
    /// `false` while a fetch is still in flight, which is why a caller showing
    /// a placeholder has to check both this and `uri`.
    public func hasFailed(_ eventId: String) -> Bool {
        failed.contains(eventId)
    }

    /// For the failure only the renderer can see: a `data:` URI the core
    /// produced that the image decoder then refused. The last line of the
    /// never-show-a-broken-image guarantee.
    public func markFailed(_ eventId: String) {
        evict(eventId)
        failed.insert(eventId)
        fetching.remove(eventId)
    }

    /// Hold a decoded image, evicting oldest-first to stay under the limit.
    func remember(_ image: UIImage, for eventId: String) {
        // The decoded backing store is what actually occupies memory, not the
        // encoded bytes the core sent.
        let bytes = image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0
        if cache[eventId] == nil { order.append(eventId) }
        evictCost(eventId)
        cache[eventId] = image
        cost[eventId] = bytes
        bytesHeld += bytes

        // Never evict the image just stored — a single picture larger than the
        // whole limit would otherwise be fetched and dropped forever.
        while bytesHeld > byteLimit, order.count > 1 {
            evict(order[0])
        }
    }

    private func evict(_ eventId: String) {
        evictCost(eventId)
        cache.removeValue(forKey: eventId)
        order.removeAll { $0 == eventId }
    }

    private func evictCost(_ eventId: String) {
        bytesHeld -= cost.removeValue(forKey: eventId) ?? 0
    }

    private func load(_ eventId: String) async {
        defer { fetching.remove(eventId) }
        let uri = try? await client.mediaFetch(eventId: eventId)
        guard let uri, let image = Self.decode(uri) else {
            // "The core found nothing fetchable", "the fetch failed", and "the
            // bytes would not decode" all land here. None is worth telling a
            // reader about in a timeline; all three mean the same thing on
            // screen, which is why `markFailed` exists as one state.
            failed.insert(eventId)
            return
        }
        remember(image, for: eventId)
    }

    /// A `data:` URI to pixels, off the main actor.
    ///
    /// `nonisolated` so the decode does not run on the main thread — a large
    /// image decoded there is a visible hitch in a scrolling list.
    private nonisolated static func decode(_ uri: String) -> UIImage? {
        guard let url = URL(string: uri), let data = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: data)
    }
}
