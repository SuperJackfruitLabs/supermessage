import Foundation
import Observation
import SupermessageFFI

/// Room avatars, fetched once and kept.
///
/// **`NSCache`, not a dictionary.** The desktop keeps these in an unbounded
/// map, which is fine for a session on a workstation and is not fine on a
/// phone: an account with hundreds of rooms would hold every avatar it ever
/// scrolled past. A count limit plus eviction under memory pressure is what
/// the platform already offers, and it costs nothing to use.
///
/// The value is a `data:` URI the core produced, so nothing here fetches from
/// the network or decodes an image itself.
@MainActor
@Observable
public final class AvatarCache {
    private let cache = NSCache<NSString, NSString>()
    /// Rooms the core has said have no avatar at all.
    ///
    /// Permanent, unlike the cache: an absence cannot be evicted into a
    /// presence, and re-asking on every scroll past a room without a picture
    /// is a round trip that can only come back empty.
    private var withoutAvatar: Set<String> = []
    /// Fetches in flight, so the many rows that appear at once ask once.
    private var fetching: Set<String> = []
    private let client: CoreClient

    public init(client: CoreClient, countLimit: Int = 200) {
        self.client = client
        cache.countLimit = countLimit
    }

    public func uri(for roomId: String) -> String? {
        cache.object(forKey: roomId as NSString) as String?
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
        cache.setObject(uri as NSString, forKey: roomId as NSString)
        fetching.remove(roomId)
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
        guard let uri = try? await client.roomAvatar(roomId: roomId), !uri.isEmpty else {
            rememberAbsent(roomId)
            return
        }
        remember(uri, for: roomId)
    }

    public func clear() {
        cache.removeAllObjects()
        withoutAvatar.removeAll()
        fetching.removeAll()
    }
}
