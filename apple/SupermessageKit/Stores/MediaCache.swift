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
    /// Decoded once, on arrival.
    ///
    /// The core hands back a `data:` URI, and turning that into pixels is not
    /// free. Doing it in the view's body would decode the same image on every
    /// pass a scrolling list makes over the row.
    private var resolved: [String: UIImage] = [:]
    private var failed: Set<String> = []
    /// Every event a fetch has been started for, resolved or not.
    ///
    /// Separate from `resolved` because it is what stops the same media being
    /// requested repeatedly: a cell is asked to draw many times before its
    /// first fetch lands, and each of those would otherwise start another.
    private var requested: Set<String> = []

    private let client: CoreClient

    public init(client: CoreClient) {
        self.client = client
    }

    /// The decoded image, starting a fetch the first time an event is seen.
    /// `nil` both before the fetch resolves and once it has resolved with
    /// nothing renderable — ask `hasFailed` to tell those apart.
    public func image(for eventId: String) -> UIImage? {
        if !requested.contains(eventId) {
            requested.insert(eventId)
            Task { await load(eventId) }
        }
        return resolved[eventId]
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
        resolved[eventId] = nil
        failed.insert(eventId)
    }

    private func load(_ eventId: String) async {
        let uri = try? await client.mediaFetch(eventId: eventId)
        guard let uri, let image = Self.decode(uri) else {
            // "The core found nothing fetchable", "the fetch failed", and "the
            // bytes would not decode" all land here. None is worth telling a
            // reader about in a timeline; all three mean the same thing on
            // screen, which is why `markFailed` exists as one state.
            failed.insert(eventId)
            return
        }
        resolved[eventId] = image
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
