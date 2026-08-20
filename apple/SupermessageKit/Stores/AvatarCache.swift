import Foundation
import Observation
import SupermessageFFI

/// Avatars, fetched once and kept.
///
/// Keyed by whatever the caller fetches by — a room id for the roster, an
/// `mxc:` URI for a message sender. One type rather than two because the hard
/// part here is not the fetch (one line either way) but the observation and
/// eviction discipline below, and having that written twice is how one copy
/// quietly regresses.
///
/// **A dictionary, bounded by hand — not an `NSCache`.** An `NSCache` was the
/// obvious choice and was wrong for one decisive reason: `@Observable` cannot
/// see through a reference type mutated behind its back, so an avatar landing
/// in the cache invalidated nothing and no row redrew. It presented exactly as
/// reported — no pictures on the first scroll, pictures on the second, gone
/// again after visiting a room — because the only thing that ever showed them
/// was some *other* change forcing a redraw.
///
/// The bound still matters: the desktop keeps these in an unbounded map, which
/// is fine on a workstation and is not fine on a phone, where an account with
/// hundreds of rooms would hold every avatar it ever scrolled past. So the
/// eviction `NSCache` would have done is done here instead, in terms
/// observation can follow.
///
/// The value is a `data:` URI the core produced, so nothing here fetches from
/// the network or decodes an image itself.
@MainActor
@Observable
public final class AvatarCache {
    /// Observable, which is the whole point — see this type's doc comment.
    private var cache: [String: String] = [:]
    /// Insertion order, oldest first, for eviction. A plain array because the
    /// bound is a couple of hundred entries and the roster is walked far more
    /// often than it is evicted from.
    private var order: [String] = []
    /// Rooms the core has said have no avatar at all.
    ///
    /// Permanent, unlike the cache: an absence cannot be evicted into a
    /// presence, and re-asking on every scroll past a room without a picture
    /// is a round trip that can only come back empty.
    private var withoutAvatar: Set<String> = []
    /// Fetches in flight, so the many rows that appear at once ask once.
    private var fetching: Set<String> = []
    /// How a key becomes a `data:` URI. `nil` means this key has no avatar.
    private let fetch: (String) async -> String?

    private let countLimit: Int

    private init(countLimit: Int, fetch: @escaping (String) async -> String?) {
        self.countLimit = countLimit
        self.fetch = fetch
    }

    /// Room avatars, keyed by room id.
    public convenience init(client: CoreClient, countLimit: Int = 200) {
        self.init(countLimit: countLimit) { roomId in
            guard let uri = try? await client.roomAvatar(roomId: roomId), !uri.isEmpty else {
                return nil
            }
            return uri
        }
    }

    /// Message senders' faces, keyed by the `mxc:` URI their profile carries.
    ///
    /// Keyed by the URI rather than the user id on purpose: two members with
    /// the same picture share one entry, and a member who changes their
    /// picture gets a new key rather than a stale hit.
    public static func forMembers(client: CoreClient, countLimit: Int = 200) -> AvatarCache {
        AvatarCache(countLimit: countLimit) { mxcUri in
            guard let uri = try? await client.memberAvatar(mxcUri: mxcUri), !uri.isEmpty else {
                return nil
            }
            return uri
        }
    }

    public func uri(for roomId: String) -> String? {
        cache[roomId]
    }

    /// Whether this room's avatar is worth asking the core for.
    ///
    /// **Keyed on what is held now, not on what was ever asked.** The previous
    /// version kept a set of every id it had attempted, and an `NSCache`
    /// evicts — under memory pressure and at its count limit. An evicted
    /// avatar was therefore never fetched again, and the row showed an empty
    /// circle for the rest of the session. The eviction was invisible, so the
    /// bug looked like avatars randomly not loading.
    func shouldFetch(_ roomId: String) -> Bool {
        uri(for: roomId) == nil && !withoutAvatar.contains(roomId) && !fetching.contains(roomId)
    }

    func remember(_ uri: String, for roomId: String) {
        if cache[roomId] == nil { order.append(roomId) }
        cache[roomId] = uri
        fetching.remove(roomId)

        // Oldest first. Deliberately *not* least-recently-used: an LRU needs a
        // touch on every read, and a read here happens for every visible row
        // on every redraw.
        while order.count > countLimit {
            cache.removeValue(forKey: order.removeFirst())
        }
    }

    func rememberAbsent(_ roomId: String) {
        withoutAvatar.insert(roomId)
        fetching.remove(roomId)
    }

    func beginFetch(_ roomId: String) {
        fetching.insert(roomId)
    }

    /// Fetch unless it is held, known absent, or already in flight. Safe to
    /// call from a row's `task`, which is to say on every appearance.
    public func load(_ roomId: String) async {
        guard shouldFetch(roomId) else { return }
        beginFetch(roomId)
        guard let uri = await fetch(roomId) else {
            rememberAbsent(roomId)
            return
        }
        remember(uri, for: roomId)
    }

    public func clear() {
        cache.removeAll()
        order.removeAll()
        withoutAvatar.removeAll()
        fetching.removeAll()
    }
}
