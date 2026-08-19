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
    /// Ids already asked about, so a room with no avatar is not re-fetched on
    /// every scroll. Bounded by the roster, which is small.
    private var attempted: Set<String> = []
    private let client: CoreClient

    public init(client: CoreClient, countLimit: Int = 200) {
        self.client = client
        cache.countLimit = countLimit
    }

    public func uri(for roomId: String) -> String? {
        cache.object(forKey: roomId as NSString) as String?
    }

    /// Fetch if it has never been asked for. Safe to call from a row's `task`.
    public func load(_ roomId: String) async {
        guard !attempted.contains(roomId) else { return }
        attempted.insert(roomId)
        guard let uri = try? await client.roomAvatar(roomId: roomId), !uri.isEmpty else { return }
        cache.setObject(uri as NSString, forKey: roomId as NSString)
    }

    public func clear() {
        cache.removeAllObjects()
        attempted.removeAll()
    }
}
