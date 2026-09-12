import Foundation

/// The seam that lets a store be built without the Rust core behind it.
///
/// **Narrow on purpose, one protocol per store.** A single protocol over all
/// of `CoreClient`'s surface was the obvious shape and is the wrong one:
/// `CoreClient.swift`'s own comment is that *"nothing above this file holds a
/// `Core` reference. That is the point — a view that could reach one could
/// freeze the app from inside `body`."* Every call in it goes to a
/// `DispatchQueue` rather than the cooperative pool, because they all block,
/// and the failure mode of getting that wrong is a hang under load rather
/// than a test failure. A wide protocol is an invitation to supply an
/// implementation that forgets. A two-method protocol used by one cache is
/// not.
///
/// This is the shape the web side already has: `AvatarCacheDeps` and
/// `RoomsStoreDeps` in `src/lib/stores/` are exactly this, which is what lets
/// the frontend suite stub them. iOS never got the same treatment; this is
/// that treatment.
///
/// **Every requirement is `async`, and that is load-bearing rather than
/// stylistic.** `CoreClient` is an actor, so its members are actor-isolated;
/// a synchronous protocol requirement cannot be satisfied by an isolated
/// method, and making one `nonisolated` to fit would put a blocking call back
/// on whatever thread called it. `async` is the only signature that both
/// conformers can honestly meet.
public protocol AvatarFetching: Sendable {
    func roomAvatarFull(roomId: String) async throws -> String?
}

extension CoreClient: AvatarFetching {}
