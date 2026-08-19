import Observation
import Testing
import UIKit

@testable import SupermessageKit

/// The distinction this cache exists to keep.
@MainActor
struct MediaCacheTests {
    func cache() -> MediaCache {
        MediaCache(client: CoreClient(dataDirectory: CoreClient.dataDirectory()))
    }

    @Test("nothing is known about an event nobody has asked about")
    func startsEmpty() {
        // Crucially `hasFailed` is false, not true: a caller reading it before
        // any fetch has started must not conclude there is nothing to show.
        let media = cache()
        #expect(!media.hasFailed("$e:x"))
    }

    @Test("loading and unrenderable are different answers")
    func loadingIsNotFailure() {
        // Both report `uri == nil`, and a renderer that cannot tell them apart
        // shows either a spinner that never stops or a broken image that was
        // never given a chance. `hasFailed` is the whole difference.
        let media = cache()
        _ = media.image(for: "$e:x")
        #expect(media.image(for: "$e:x") == nil)
        #expect(!media.hasFailed("$e:x"), "an in-flight fetch is not a failure")

        media.markFailed("$e:x")
        #expect(media.image(for: "$e:x") == nil)
        #expect(media.hasFailed("$e:x"))
    }

    @Test("an arriving picture tells the view to redraw")
    func arrivalIsObservable() {
        // The same fault the avatars had, and it would have shown the same
        // way: an `NSCache` is a reference type mutated behind `@Observable`'s
        // back, so bytes landing invalidate nothing and the row keeps its
        // placeholder until some unrelated change forces a redraw.
        let media = cache()
        final class Flag: @unchecked Sendable { var fired = false }
        let redrew = Flag()

        withObservationTracking {
            _ = media.image(for: "$e:x")
        } onChange: {
            redrew.fired = true
        }

        media.remember(UIImage(), for: "$e:x")
        #expect(redrew.fired, "a picture arrived and no view was told")
    }

    @Test("a decoder's refusal is remembered")
    func markFailedSticks() {
        // The one failure a fetch cannot catch: bytes arrived and the image
        // decoder refused them.
        let media = cache()
        media.markFailed("$e:x")
        #expect(media.hasFailed("$e:x"))
    }
}
