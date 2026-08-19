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
    public let live = LiveStore()
    public let typing = TypingStore()
    public let drafts = DraftStore()
    public let replies = ReplyTarget()
    public let staged: StagedAttachment

    private let client: CoreClient
    private let pump = EventPump()
    private var drainTask: Task<Void, Never>?

    public init(client: CoreClient) {
        self.client = client
        rooms = RoomsStore(client: client)
        spaces = SpacesStore(client: client)
        avatars = AvatarCache(client: client)
        timeline = TimelineStore(client: client, sink: pump)
        staged = StagedAttachment(client: client)
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

    /// Send what is in the composer: the text, the attachment, or both.
    ///
    /// Returns a message when the core refuses, or `nil`. Mentions are the
    /// core's — `collectMentions` produces the `m.mentions` an agent reads to
    /// decide a message in a room full of agents was addressed to it, and this
    /// app must not have a second opinion about that.
    public func send(text: String, in roomId: String) async -> String? {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)

        if staged.file != nil, let failure = await staged.send(in: roomId) {
            return failure
        }
        guard !body.isEmpty else { return nil }

        do {
            if let reply = replies.pending(for: roomId) {
                try await client.sendReply(roomId: roomId, body: body, inReplyTo: reply.eventId)
                replies.cancel(roomId)
            } else {
                let mentions = SupermessageFFI.collectMentions(text: body, members: [])
                try await client.sendMessage(roomId: roomId, body: body, mentions: mentions)
            }
            await setTyping(false, in: roomId)
            return nil
        } catch let error as FfiError {
            return ErrorPresenter.message(for: error)
        } catch {
            return "Couldn't send that."
        }
    }

    /// Tell the room whether this account is typing.
    ///
    /// Failures are swallowed: a typing notice nobody saw is not worth an
    /// alert, and the composer is the last place to interrupt someone.
    public func setTyping(_ typing: Bool, in roomId: String) async {
        try? await client.setTyping(roomId: roomId, typing: typing)
    }

    /// Foreground and background.
    ///
    /// **The one thing iOS needs that desktop never did.** A suspended app
    /// loses its sockets, and the `sm://` channels only speak when something
    /// *changes* — so a store that came back to a quiet account would sit
    /// empty until the next message, which in these rooms can be hours. This
    /// is exactly what `seed()` was written for, after a webview reload left
    /// the desktop roster empty with a perfectly healthy core behind it.
    public func scenePhaseChanged(to active: Bool) async {
        guard phase == .signedIn else { return }
        if active {
            await rooms.seed()
            await timeline.seed()
            await spaces.refresh()
        } else if let roomId = timeline.roomId {
            // Leaving a typing notice on when the app goes away tells the room
            // someone is writing who is not even looking at it.
            await setTyping(false, in: roomId)
        }
    }

    /// The commands the panels drive.
    ///
    /// Each returns a message on refusal rather than throwing, because a panel
    /// shows the failure inline rather than propagating it — the reader is in
    /// the middle of something and an alert would take the room away.

    public func joinRoom(_ roomId: String) async -> String? {
        await refusal { try await client.joinRoom(roomId: roomId) }
    }

    public func leaveRoom(_ roomId: String) async -> String? {
        await refusal { try await client.leaveRoom(roomId: roomId) }
    }

    public func roomInfo(_ roomId: String) async throws -> RoomInfoDto {
        try await client.roomInfo(roomId: roomId)
    }

    public func search(_ term: String) async -> [SearchResultDto] {
        (try? await client.searchMessages(term: term)) ?? []
    }

    public enum Outcome {
        case success(String)
        case failure(String)
    }

    public func createRoom(name: String, invite: [String]) async -> Outcome {
        do {
            let roomId = try await client.createRoom(
                name: name, invite: invite, isDirect: !invite.isEmpty)
            await load()
            return .success(roomId)
        } catch let error as FfiError {
            return .failure(ErrorPresenter.message(for: error))
        } catch {
            return .failure("Couldn't create that room.")
        }
    }

    public func joinByAlias(_ aliasOrId: String) async -> Outcome {
        do {
            let roomId = try await client.joinRoomByAlias(aliasOrId: aliasOrId)
            await load()
            return .success(roomId)
        } catch let error as FfiError {
            return .failure(ErrorPresenter.message(for: error))
        } catch {
            return .failure("Couldn't join that room.")
        }
    }

    private func refusal(_ body: () async throws -> Void) async -> String? {
        do {
            try await body()
            await load()
            return nil
        } catch let error as FfiError {
            return ErrorPresenter.message(for: error)
        } catch {
            return "That didn't work."
        }
    }

    /// Open a room: the timeline subscribes, and the transient stores are
    /// re-pointed so nothing from the last room survives the switch.
    public func open(roomId: String) async {
        live.focus(roomId)
        typing.focus(roomId)
        await timeline.subscribeTo(roomId)
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
        live.clear()
        typing.focus(nil)
        drafts.clearAll()
        replies.clearAll()
        await staged.discard()
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
        case let .typing(roomId, users):
            typing.handle(roomId: roomId, users: users)
        case let .live(roomId, seq, text, done):
            live.handleLive(roomId: roomId, seq: seq, text: text, done: done)
        case let .thought(roomId, seq, text, done):
            live.handleThought(roomId: roomId, seq: seq, text: text, done: done)
        case let .tool(roomId, seq, toolCallId, title, status):
            live.handleTool(
                roomId: roomId, seq: seq, toolCallId: toolCallId, title: title, status: status)
        case .attachmentStaged:
            // Handled by the composer, which owns the staged strip. Listed
            // rather than swept into a `default` so a new variant on the
            // boundary still breaks this build.
            break
        }
    }
}
