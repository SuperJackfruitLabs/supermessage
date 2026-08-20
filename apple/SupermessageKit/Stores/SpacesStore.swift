import Foundation
import Observation
import SupermessageFFI

/// The spaces the account belongs to, and which one filters the roster.
///
/// Not diff-driven — `spaces_list` is a plain call, refreshed when the roster
/// changes shape. A space is named by the same convention a room is, so it
/// arrives with its `identity` already split.
@MainActor
@Observable
public final class SpacesStore {
    public private(set) var spaces: [SpaceSummary] = []
    /// `nil` is "All rooms", which is a real choice rather than an absent one.
    public private(set) var selectedId: String?
    public private(set) var failure: String?

    private let client: CoreClient

    public init(client: CoreClient) {
        self.client = client
    }

    public func refresh() async {
        do {
            spaces = try await client.spacesList()
        } catch let error as FfiError {
            // A rail that cannot load is not worth an alert: the roster still
            // works unfiltered, which is the state it was already in.
            failure = ErrorPresenter.isWorthSurfacing(error)
                ? ErrorPresenter.message(for: error) : nil
        } catch {
            failure = nil
        }
    }

    /// Filter the roster to `spaceId`, or clear the filter with `nil`.
    public func select(_ spaceId: String?) async {
        do {
            try await client.spaceSelect(spaceId: spaceId)
            selectedId = spaceId
        } catch let error as FfiError {
            failure = ErrorPresenter.message(for: error)
        } catch {
            failure = "Couldn't switch space."
        }
    }

    /// An invitation is not a filter: tapping one has to offer Accept rather
    /// than pretending to scope a roster the account cannot see into.
    public func isInvitation(_ space: SpaceSummary) -> Bool {
        space.membership == .invited
    }

    public var selectedName: String? {
        guard let selectedId else { return nil }
        return spaces.first { $0.id == selectedId }?.identity.name
    }

    public func clear() {
        spaces = []
        selectedId = nil
        failure = nil
    }
}
