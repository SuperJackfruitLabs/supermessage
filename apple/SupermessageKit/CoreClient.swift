import Foundation
import SupermessageFFI

/// A sink the core may be handed across a thread boundary.
///
/// `EventSink` as generated is `AnyObject` and says nothing about concurrency,
/// but a sink is handed to the core from inside `Task.detached` and then
/// called back on whatever thread the core likes. Requiring `Sendable` here is
/// how that fact becomes the compiler's problem rather than a comment's.
public protocol CoreEventSink: EventSink, Sendable {}

/// The only thing in this app that holds a `Core`.
///
/// **Every method on `Core` blocks the calling thread.** They are synchronous
/// Rust functions that `block_on` a tokio runtime, so a call takes as long as
/// the homeserver does and does nothing else while it waits.
///
/// ## Why a dispatch queue and not `Task.detached`
///
/// The obvious wrapper is `try await Task.detached { try body(core) }.value`,
/// and it is wrong for a reason that does not show up until the app is busy.
/// A detached task still runs on Swift's **cooperative** thread pool, which is
/// sized to the core count and built on the assumption that a task never
/// blocks — it yields. A handful of concurrent blocking calls therefore
/// occupy the whole pool, and everything else in the app, including work with
/// no interest in the network, stops. The failure mode is a hang, not a stall,
/// and it arrives under load rather than in a test.
///
/// So the blocking call goes to a `DispatchQueue`, which is a real thread pool
/// that expects to be blocked, and the result comes back through a
/// continuation. Being an actor is what serialises access to the object;
/// the queue is what keeps the blocking off every thread that matters.
///
/// Nothing above this file holds a `Core` reference. That is the point — a
/// view that could reach one could freeze the app from inside `body`.
public actor CoreClient {
    private let core: Core
    private let queue: DispatchQueue

    /// The queue's label, so a test can prove a call actually landed on it.
    static let queueLabel = "dev.supermessage.core"

    public init(dataDirectory: String) {
        core = Core(dataDir: dataDirectory)
        // Concurrent, not serial: the core is `Send + Sync` and serialising
        // here would make a slow media fetch block a keystroke's typing
        // notification. `.userInitiated` because everything behind this is
        // something a person is waiting to see.
        queue = DispatchQueue(
            label: Self.queueLabel, qos: .userInitiated, attributes: .concurrent)
    }

    /// Where the core keeps its SQLite stores.
    ///
    /// Inside the app container, so it inherits the sandbox and the backup
    /// rules rather than choosing its own — the same reasoning that puts the
    /// session in the Data Protection keychain.
    public static func dataDirectory() -> String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directory = base.appendingPathComponent("supermessage", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.path
    }

    /// Run one blocking call on the dedicated queue.
    private func run<T: Sendable>(_ body: @escaping @Sendable (Core) throws -> T) async throws -> T {
        let core = self.core
        let queue = self.queue
        return try await withCheckedThrowingContinuation { continuation in
            queue.async {
                continuation.resume(with: Result { try body(core) })
            }
        }
    }

    /// The non-throwing form, for the `Core` methods that cannot fail.
    private func run<T: Sendable>(_ body: @escaping @Sendable (Core) -> T) async -> T {
        let core = self.core
        let queue = self.queue
        return await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: body(core)) }
        }
    }

    /// Where a wrapped call actually runs, for the test that pins it.
    ///
    /// The queue label rather than `Thread.isMainThread`: an actor is already
    /// off the main thread, so "not main" is true of every plausible
    /// implementation and proves nothing. The label distinguishes *this*
    /// queue from the cooperative pool, which is the distinction that matters.
    func probeQueueLabel() async -> String {
        await run { _ in
            String(cString: __dispatch_queue_get_label(nil))
        }
    }

    // MARK: - Session

    public func login(
        homeserver: String, username: String, password: String, sink: any CoreEventSink
    ) async throws {
        try await run { try $0.login(homeserver: homeserver, username: username, password: password, sink: sink) }
    }

    public func restoreSession(sink: any CoreEventSink) async throws -> Bool {
        try await run { try $0.restoreSession(sink: sink) }
    }

    public func logout() async throws {
        try await run { try $0.logout() }
    }

    public func connectionState() async -> ConnectionState {
        await run { $0.connectionState() }
    }

    // MARK: - Rooms

    public func roomsSnapshot() async throws -> RoomsSnapshot {
        try await run { try $0.roomsSnapshot() }
    }

    public func joinRoom(roomId: String) async throws {
        try await run { try $0.joinRoom(roomId: roomId) }
    }

    public func joinRoomByAlias(aliasOrId: String) async throws -> String {
        try await run { try $0.joinRoomByAlias(aliasOrId: aliasOrId) }
    }

    public func leaveRoom(roomId: String) async throws {
        try await run { try $0.leaveRoom(roomId: roomId) }
    }

    public func createRoom(name: String, invite: [String], isDirect: Bool) async throws -> String {
        try await run { try $0.createRoom(name: name, invite: invite, isDirect: isDirect) }
    }

    public func inviteUser(roomId: String, userId: String) async throws {
        try await run { try $0.inviteUser(roomId: roomId, userId: userId) }
    }

    public func roomInviter(roomId: String) async throws -> String? {
        try await run { try $0.roomInviter(roomId: roomId) }
    }

    public func account() async throws -> AccountDto {
        try await run { try $0.account() }
    }

    public func roomInfo(roomId: String) async throws -> RoomInfoDto {
        try await run { try $0.roomInfo(roomId: roomId) }
    }

    public func markRoomRead(roomId: String) async throws {
        try await run { try $0.markRoomRead(roomId: roomId) }
    }

    // MARK: - Spaces

    public func spacesList() async throws -> [SpaceSummary] {
        try await run { try $0.spacesList() }
    }

    public func spaceSelect(spaceId: String?) async throws {
        try await run { try $0.spaceSelect(spaceId: spaceId) }
    }

    // MARK: - Timeline

    public func timelineSubscribe(roomId: String, sink: any CoreEventSink) async throws {
        try await run { try $0.timelineSubscribe(roomId: roomId, sink: sink) }
    }

    public func timelineResync() async throws -> TimelineSnapshot {
        try await run { try $0.timelineResync() }
    }

    public func timelinePaginateBack(roomId: String, count: UInt16) async throws -> Bool {
        try await run { try $0.timelinePaginateBack(roomId: roomId, count: count) }
    }

    // MARK: - Sending

    public func sendMessage(roomId: String, body: String, mentions: [String]) async throws {
        try await run { try $0.sendMessage(roomId: roomId, body: body, mentions: mentions) }
    }

    public func sendReply(roomId: String, body: String, inReplyTo: String) async throws {
        try await run { try $0.sendReply(roomId: roomId, body: body, inReplyTo: inReplyTo) }
    }

    public func setRoomNotifications(roomId: String, mode: NotificationMode) async throws {
        try await run { try $0.setRoomNotifications(roomId: roomId, mode: mode) }
    }

    public func setRoomPinned(roomId: String, pinned: Bool) async throws {
        try await run { try $0.setRoomPinned(roomId: roomId, pinned: pinned) }
    }

    public func editMessage(roomId: String, eventId: String, body: String) async throws {
        try await run { try $0.editMessage(roomId: roomId, eventId: eventId, body: body) }
    }

    public func deleteMessage(roomId: String, eventId: String) async throws {
        try await run { try $0.deleteMessage(roomId: roomId, eventId: eventId) }
    }

    public func toggleReaction(roomId: String, eventId: String, key: String) async throws -> Bool {
        try await run { try $0.toggleReaction(roomId: roomId, eventId: eventId, key: key) }
    }

    public func setTyping(roomId: String, typing: Bool) async throws {
        try await run { try $0.setTyping(roomId: roomId, typing: typing) }
    }

    // MARK: - Media and attachments

    public func roomAvatar(roomId: String) async throws -> String? {
        try await run { try $0.roomAvatar(roomId: roomId) }
    }

    public func memberAvatar(mxcUri: String) async throws -> String? {
        try await run { try $0.memberAvatar(mxcUri: mxcUri) }
    }

    public func mediaFetch(eventId: String) async throws -> String? {
        try await run { try $0.mediaFetch(eventId: eventId) }
    }

    public func attachmentStagePath(roomId: String, path: String) async throws -> StagedFile {
        try await run { try $0.attachmentStagePath(roomId: roomId, path: path) }
    }

    public func attachmentSend(roomId: String, token: String) async throws {
        try await run { try $0.attachmentSend(roomId: roomId, token: token) }
    }

    public func attachmentDiscard(token: String) async {
        await run { $0.attachmentDiscard(token: token) }
    }

    // MARK: - Search

    public func searchMessages(term: String) async throws -> [SearchResultDto] {
        try await run { try $0.searchMessages(term: term) }
    }
}
