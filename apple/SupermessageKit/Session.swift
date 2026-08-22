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
    /// Senders' faces, keyed by `mxc:` URI — see `AvatarCache.forMembers`.
    public let faces: AvatarCache
    public let media: MediaCache
    public let timeline: TimelineStore
    public let live = LiveStore()
    public let typing = TypingStore()
    public let drafts = DraftStore()
    public let replies = ReplyTarget()
    public let edits = EditTarget()
    public let staged: StagedAttachment

    private let client: CoreClient
    private let pump = EventPump()
    private var drainTask: Task<Void, Never>?

    public init(client: CoreClient) {
        self.client = client
        rooms = RoomsStore(client: client)
        spaces = SpacesStore(client: client)
        avatars = AvatarCache(client: client)
        faces = AvatarCache.forMembers(client: client)
        media = MediaCache(client: client)
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

    /// Add or remove one of this account's reactions.
    ///
    /// Takes the **event** id: a reaction is an `m.annotation` pointing at an
    /// event, and a message the server has not acknowledged has none. The
    /// affordance is hidden in that state (`canReplyOrReact`), so `nil` here
    /// means something raced, and doing nothing is the honest answer rather
    /// than sending against an id the homeserver never issued.
    public func toggleReaction(_ eventId: String?, key: String, in roomId: String) async {
        guard let eventId else { return }
        _ = try? await client.toggleReaction(roomId: roomId, eventId: eventId, key: key)
    }

    /// Rewrite a message this account sent.
    ///
    /// Takes the **event** id for the same reason `toggleReaction` does: an
    /// edit is a relation pointing at an event, and a message the homeserver
    /// has not acknowledged has none.
    ///
    /// Returns whether it landed, so a caller can leave the reader's text in
    /// the composer rather than discarding it into a failure.
    @discardableResult
    public func edit(_ eventId: String?, body: String, in roomId: String) async -> Bool {
        guard let eventId else { return false }
        do {
            try await client.editMessage(roomId: roomId, eventId: eventId, body: body)
            return true
        } catch {
            return false
        }
    }

    /// Delete a message. A Matrix redaction: permanent, and visible to
    /// everyone in the room.
    @discardableResult
    public func delete(_ eventId: String?, in roomId: String) async -> Bool {
        guard let eventId else { return false }
        do {
            try await client.deleteMessage(roomId: roomId, eventId: eventId)
            return true
        } catch {
            return false
        }
    }

    /// Set how loudly a room may interrupt.
    ///
    /// `.default` unsets this room's own rule rather than writing today's
    /// account default into it — see `Session::set_room_notification_mode`.
    /// Returns whether it landed, so a control can put itself back rather
    /// than showing a setting the homeserver never accepted.
    @discardableResult
    public func setNotifications(_ mode: NotificationMode, in roomId: String) async -> Bool {
        do {
            try await client.setRoomNotifications(roomId: roomId, mode: mode)
            return true
        } catch {
            return false
        }
    }

    /// Pin or unpin a room. The `m.favourite` tag, so it travels to other
    /// clients rather than living only on this phone.
    @discardableResult
    public func setPinned(_ pinned: Bool, in roomId: String) async -> Bool {
        do {
            try await client.setRoomPinned(roomId: roomId, pinned: pinned)
            return true
        } catch {
            return false
        }
    }

    /// Who invited this account to `roomId`, or `nil`.
    public func inviter(of roomId: String) async -> String? {
        (try? await client.roomInviter(roomId: roomId)) ?? nil
    }

    /// Who this app is signed in as, and where.
    public func account() async -> AccountDto? {
        try? await client.account()
    }

    public func roomInfo(_ roomId: String) async throws -> RoomInfoDto {
        try await client.roomInfo(roomId: roomId)
    }

    /// Search for `term`, in `roomId` when one is given and across every room
    /// this account can see otherwise.
    ///
    /// Unlike most of this file's read paths, a failure here is not
    /// swallowed — the same contract `roomInfo` above already keeps. A
    /// homeserver error, an expired token or a dropped connection must not
    /// render as "no results": it is `SearchPanel`, not this function, that
    /// can tell a reader apart from an empty list, and it does, by catching
    /// this and mapping it through `ErrorPresenter` into `SearchState.failed`.
    public func search(_ term: String, in roomId: String? = nil) async throws -> [SearchResultDto] {
        try await client.searchMessages(term: term, roomId: roomId)
    }

    public enum Outcome {
        case success(String)
        case failure(String)
    }

    /// A room's avatar at its original size, for looking at the picture.
    ///
    /// Not the roster's cache: that holds a 96px thumbnail, which is right
    /// for a circle in a list and four times too small the moment someone
    /// opens it. Fetched on demand, and the SDK's media store means opening
    /// the same picture twice hits the network once.
    public func fullAvatar(of roomId: String) async -> String? {
        (try? await client.roomAvatarFull(roomId: roomId)) ?? nil
    }

    /// Everyone this account shares a room with, agents first.
    public func people() async -> [PersonDto] {
        (try? await client.knownPeople()) ?? []
    }

    /// Open the conversation with `person`, creating it only if there is not
    /// one already.
    ///
    /// Reusing an existing one-to-one is the whole point: tapping an agent's
    /// name twice should return the reader to the conversation they had, not
    /// leave a roster of identically named rooms with the history scattered
    /// between them.
    public func openConversation(with person: PersonDto) async -> Outcome {
        if let roomId = try? await client.directRoomWith(userId: person.userId) {
            return .success(roomId)
        }
        return await createRoom(name: person.name, invite: [person.userId])
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
        faces.clear()
        edits.clearAll()
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
            // A message from someone is better evidence that they stopped
            // typing than the server-side timeout on the notice — see
            // `TypingStore.messagesArrived`. Own messages are excluded: this
            // reader's own send says nothing about who else is writing.
            // **Ids, not names.** `senderName` is the composed attribution
            // — "Super Chotu (Hermes on Guild)" — and the typing store holds
            // whatever the profile said. Matching those two strings is how
            // the indicator got stuck for minutes after the reply landed.
            let spoke = envelope.ops
                .map(\.generic)
                .flatMap(opValues)
                .filter { !$0.item.isOwn }
                .compactMap(\.item.sender)
            if !spoke.isEmpty { typing.messagesArrived(from: spoke) }
        case let .typing(roomId, users):
            typing.handle(roomId: roomId, users: users)
        case let .live(roomId, seq, text, done):
            live.handleLive(roomId: roomId, seq: seq, text: text, done: done)
        case let .thought(roomId, seq, text, done):
            live.handleThought(roomId: roomId, seq: seq, text: text, done: done)
        case let .tool(roomId, seq, toolCallId, title, kind, status, locations, input, output):
            live.handleTool(
                roomId: roomId, seq: seq, toolCallId: toolCallId, title: title, kind: kind,
                status: status, locations: locations, input: input, output: output)
        case .attachmentStaged:
            // Handled by the composer, which owns the staged strip. Listed
            // rather than swept into a `default` so a new variant on the
            // boundary still breaks this build.
            break
        }
    }
}
