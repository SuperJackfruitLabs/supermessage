import Foundation
import Observation
import SupermessageFFI

/// Everything the app has: the core, the pump, and the stores the screens read.
///
/// ## The one drain task
///
/// `start` and `signIn` each end by calling `beginDraining`, which spawns
/// **exactly one** task that consumes `pump.events` with `for await` and hands
/// each event to a store. That single consumer is the whole ordering
/// guarantee: `DiffEnvelope` carries a `seq`, and applying diffs out of order
/// corrupts the reader's view in a way that presents as a rendering bug. A
/// second consumer, or a task per event, breaks it.
///
/// The task lives as long as the stream. `EventPump.finish()` on logout ends
/// the `for await`, and the task with it.
@MainActor
@Observable
public final class Session {
    public enum Phase: Equatable {
        /// Before `start()` has answered.
        case starting
        case signedOut
        case signedIn
    }

    public private(set) var phase: Phase = .starting
    /// The last thing worth telling the reader about, or `nil`.
    public private(set) var failure: String?

    public let connection = ConnectionStore()
    public let rooms: RoomsStore
    public let spaces: SpacesStore
    public let avatars: AvatarCache
    public let timeline: TimelineStore

    private let client: CoreClient
    private let pump = EventPump()
    private var drainTask: Task<Void, Never>?

    public init(client: CoreClient) {
        self.client = client
        rooms = RoomsStore(client: client)
        spaces = SpacesStore(client: client)
        avatars = AvatarCache(client: client)
        timeline = TimelineStore(client: client, sink: pump)
    }

    public convenience init() {
        self.init(client: CoreClient(dataDirectory: CoreClient.dataDirectory()))
    }

    /// Restore a stored session, if there is one.
    ///
    /// Credentials live in the iOS Data Protection keychain, which the core
    /// configures — this app never sees them.
    @discardableResult
    public func start() async -> Bool {
        do {
            let restored = try await client.restoreSession(sink: pump)
            phase = restored ? .signedIn : .signedOut
            if restored {
                beginDraining()
                await load()
            }
            return restored
        } catch {
            // A failure to *restore* is not a failure to sign in: there may
            // simply be nothing stored. Either way the answer is the login
            // screen, and saying more than that would be guessing.
            phase = .signedOut
            return false
        }
    }

    public func signIn(homeserver: String, username: String, password: String) async {
        failure = nil
        do {
            try await client.login(
                homeserver: homeserver, username: username, password: password, sink: pump)
            phase = .signedIn
            beginDraining()
            await load()
        } catch let error as FfiError {
            failure = ErrorPresenter.message(for: error)
        } catch {
            failure = "Couldn't sign in."
        }
    }

    /// Ask for the state the channels will not volunteer.
    ///
    /// The diff channels only speak when something *changes*, so a store built
    /// after the core has already emitted its opening state would sit empty
    /// until the next message — minutes, in a quiet account. Seeding is how it
    /// asks. See `GapSync.seed()`.
    private func load() async {
        await rooms.seed()
        await spaces.refresh()
    }

    public func signOut() async {
        try? await client.logout()
        pump.finish()
        drainTask?.cancel()
        drainTask = nil
        rooms.clear()
        timeline.clear()
        spaces.clear()
        avatars.clear()
        phase = .signedOut
    }

    /// The single consumer. See this type's note on ordering.
    private func beginDraining() {
        guard drainTask == nil else { return }
        drainTask = Task { [pump] in
            for await event in pump.events {
                handle(event)
            }
        }
    }

    /// Route one event to the store that owns it.
    ///
    /// An exhaustive switch with no `default`: a new variant on the boundary
    /// should break this build rather than be dropped on the floor, which is
    /// the same reason the Rust side's `CoreEvent` is a closed enum.
    private func handle(_ event: FfiEvent) {
        switch event {
        case let .connection(state):
            connection.apply(state)
        case let .roomsDiff(envelope):
            rooms.handle(envelope)
        case let .timelineDiff(envelope):
            timeline.handle(envelope)
        case .typing, .live, .thought, .tool, .attachmentStaged:
            // Wired to their stores as each lands. Listed explicitly rather
            // than swept into a `default` so the compiler keeps naming what is
            // still outstanding.
            break
        }
    }
}
