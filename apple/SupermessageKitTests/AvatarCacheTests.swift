import Observation
import Testing

@testable import SupermessageKit

/// When an avatar is worth asking the core for.
@MainActor
struct AvatarCacheTests {
    func cache() -> AvatarCache {
        AvatarCache(client: CoreClient(dataDirectory: CoreClient.dataDirectory()))
    }

    @Test("a room nobody has asked about is worth fetching")
    func fetchesTheFirstTime() {
        #expect(cache().shouldFetch("!a:x"))
    }

    @Test("an avatar already held is not fetched again")
    func doesNotRefetchWhatItHas() {
        let avatars = cache()
        avatars.remember("data:image/png;base64,AAAA", for: "!a:x")
        #expect(!avatars.shouldFetch("!a:x"))
    }

    @Test("a room with no avatar is not asked about twice")
    func remembersAbsence() {
        // Otherwise every scroll past a room without a picture is another
        // round trip that can only come back empty.
        let avatars = cache()
        avatars.rememberAbsent("!a:x")
        #expect(!avatars.shouldFetch("!a:x"))
    }

    @Test("an evicted avatar is fetched again")
    func refetchesAfterEviction() {
        // The bug this pins. `NSCache` evicts — under memory pressure and at
        // its count limit — and the old guard was a separate set of every id
        // ever *asked about*. An evicted avatar was therefore never asked for
        // again, and the row showed an empty circle for the rest of the
        // session. Because eviction is invisible, it looked like avatars
        // randomly failing to load.
        //
        // A count limit of one makes the eviction happen rather than waiting
        // for memory pressure. Note this must *not* use `clear()`, which wipes
        // the guard as well and so passes whether the bug is present or not.
        let avatars = AvatarCache(
            client: CoreClient(dataDirectory: CoreClient.dataDirectory()), countLimit: 1)
        avatars.remember("data:image/png;base64,AAAA", for: "!a:x")
        avatars.remember("data:image/png;base64,BBBB", for: "!b:x")

        #expect(avatars.uri(for: "!a:x") == nil, "the count limit did not evict; test is inert")
        #expect(avatars.shouldFetch("!a:x"), "an avatar that is gone must be fetchable again")
    }

    @Test("an arriving avatar tells the view to redraw")
    func arrivalIsObservable() {
        // Reported: no pictures on the first scroll, pictures on the second,
        // gone again after visiting a room.
        //
        // The storage was an `NSCache`, and `@Observable` cannot see through a
        // reference type mutated behind its back. Bytes landed and nothing
        // invalidated, so a row only picked them up when something *else*
        // forced a redraw — which is exactly what scrolling does.
        let avatars = cache()
        // A box rather than a captured `var`: `onChange` is `@Sendable`, and
        // the compiler is right to refuse a mutable capture across it.
        final class Flag: @unchecked Sendable { var fired = false }
        let redrew = Flag()

        withObservationTracking {
            _ = avatars.uri(for: "!a:x")
        } onChange: {
            redrew.fired = true
        }

        avatars.remember("data:image/png;base64,AAAA", for: "!a:x")
        #expect(redrew.fired, "an avatar arrived and no view was told")
    }

    @Test("one fetch at a time for the same room")
    func doesNotStampede() {
        // Every visible row asks on appear, and they all ask before the first
        // answer lands.
        let avatars = cache()
        avatars.beginFetch("!a:x")
        #expect(!avatars.shouldFetch("!a:x"))
    }
}
