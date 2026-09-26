import Foundation
import SupermessageFFI

// The seam that lets a store be built without the Rust core behind it.
//
// **Narrow on purpose, one protocol per consumer.** A single protocol over
// all of `CoreClient`'s surface was the obvious shape and is the wrong one.
// `CoreClient.swift`'s own comment is that *"nothing above this file holds a
// `Core` reference. That is the point — a view that could reach one could
// freeze the app from inside `body`."* Every call in it goes to a
// `DispatchQueue` rather than the cooperative pool, because they all block,
// and the failure mode of getting that wrong is a hang under load rather than
// a test failure. A wide protocol is an invitation to supply an
// implementation that forgets; a two-method protocol used by one cache is
// not.
//
// This is the shape the web side already has: `AvatarCacheDeps` and
// `RoomsStoreDeps` in `src/lib/stores/` are exactly this, which is what lets
// the frontend suite stub them. iOS never got the same treatment; this is
// that treatment.
//
// **Every requirement is `async`, and that is load-bearing rather than
// stylistic.** `CoreClient` is an actor, so its members are actor-isolated; a
// synchronous protocol requirement cannot be satisfied by an isolated method,
// and marking one `nonisolated` to fit would put a blocking call back on
// whatever thread called it. `async` is the only signature both a real client
// and a stub can honestly meet — a store wanting a synchronous accessor
// cannot have one through this seam.
//
// **Each protocol carries exactly what its consumer calls.** The grep that
// produced these lists is worth keeping runnable:
//
//     grep -oE "client\.[a-zA-Z]+" SupermessageKit/Stores/*.swift
//
// Two of `CoreClient`'s 38 public methods appear in no protocol below, and it
// is not an oversight: `connectionState()` and `inviteUser(roomId:userId:)`
// have no callers anywhere in the Kit, the app, or the tests. 36 + 2 = 38,
// which is the arithmetic that says nothing was missed by accident.

// MARK: - What the stores call

/// `AvatarCache`, both of them — the room one and `forMembers`.
public protocol AvatarFetching: Sendable {
    func roomAvatar(roomId: String) async throws -> String?
    func memberAvatar(mxcUri: String) async throws -> String?
}

/// `MediaCache`.
public protocol MediaFetching: Sendable {
    func mediaFetch(eventId: String) async throws -> String?
}

/// `RoomsStore`.
public protocol RoomsSnapshotting: Sendable {
    func roomsSnapshot() async throws -> RoomsSnapshot
}

/// `SpacesStore`.
public protocol SpaceSelecting: Sendable {
    func spacesList() async throws -> [SpaceSummary]
    func spaceSelect(spaceId: String?) async throws
}

/// `StagedAttachment`.
public protocol AttachmentStaging: Sendable {
    func attachmentStagePath(roomId: String, path: String) async throws -> StagedFile
    /// `caption` travels in the same event as the file (MSC2530).
    func attachmentSend(roomId: String, token: String, caption: String?) async throws
    func attachmentDiscard(token: String) async
    /// Mark a staged recording as a voice message: its length and waveform.
    func attachmentMarkVoice(roomId: String, token: String, durationMs: UInt64, waveform: [Float]) async throws
}

extension AttachmentStaging {
    /// A seam that does not know voice messages sends recordings as plain
    /// audio files — what every seam did before voice messages existed.
    public func attachmentMarkVoice(roomId: String, token: String, durationMs: UInt64, waveform: [Float]) async throws {}
}

/// `TimelineStore`.
public protocol TimelineSubscribing: Sendable {
    func timelineSubscribe(roomId: String, sink: any CoreEventSink) async throws
    func timelineResync() async throws -> TimelineSnapshot
    func timelinePaginateBack(roomId: String, count: UInt16) async throws -> Bool
    func markRoomRead(roomId: String) async throws
}

// MARK: - What Session itself calls

// `Session` is the wide consumer — 23 of the 36 — so these are split by the
// panel that drives them rather than lumped. That split is not cosmetic: it
// is the decomposition route D would need. If `RoomInfoPanel` is ever
// refactored to take what it uses instead of the whole `Session`, the thing
// it should take is `RoomAdministering`, and it already exists.

/// Sign-in, restore and sign-out. `LoginView` and `Session.start()`.
public protocol SessionAuthenticating: Sendable {
    func login(
        homeserver: String, username: String, password: String, sink: any CoreEventSink
    ) async throws
    func restoreSession(sink: any CoreEventSink) async throws -> Bool
    func logout() async throws
}

/// Everything that puts something in a room. `ComposerView`, `TimelineView`.
public protocol MessageSending: Sendable {
    func sendMessage(roomId: String, body: String, mentions: [String]) async throws
    func sendReply(roomId: String, body: String, inReplyTo: String) async throws
    func editMessage(roomId: String, eventId: String, body: String) async throws
    func deleteMessage(roomId: String, eventId: String) async throws
    func toggleReaction(roomId: String, eventId: String, key: String) async throws -> Bool
    func sendGateDecision(
        roomId: String, gateId: String, optionId: String, comment: String?,
        inReplyTo: String, prompt: String
    ) async throws
    func setTyping(roomId: String, typing: Bool) async throws
}

/// Getting into and out of rooms. `NewRoomPanel`, `InvitationView`.
public protocol RoomMembership: Sendable {
    func joinRoom(roomId: String) async throws
    func joinRoomByAlias(aliasOrId: String) async throws -> String
    func leaveRoom(roomId: String) async throws
    func createRoom(name: String, invite: [String], isDirect: Bool) async throws -> String
    /// "enabled", "disabled", "incomplete" or "unknown" — see `RecoveryView`.
    func recoveryState() async throws -> String
    /// Returns the recovery key, once. Never store or log it.
    func enableRecovery() async throws -> String
    func recoverWithKey(recoveryKey: String) async throws
    /// The key to show once at sign-in, or nil when there was nothing to do.
    func ensureRecovery() async throws -> String?
    /// Destructive: replaces the identity and returns a new key.
    func resetRecovery(password: String) async throws -> String
    func directRoomWith(userId: String) async throws -> String?
    func roomInviter(roomId: String) async throws -> String?
}

/// Per-room settings and detail. `RoomInfoPanel`.
public protocol RoomAdministering: Sendable {
    func setRoomNotifications(roomId: String, mode: NotificationMode) async throws
    func setRoomPinned(roomId: String, pinned: Bool) async throws
    func roomInfo(roomId: String) async throws -> RoomInfoDto
    func roomAvatarFull(roomId: String) async throws -> String?
}

/// Who exists and what was said. `AccountPanel`, `SearchPanel`.
public protocol AccountDirectory: Sendable {
    func account() async throws -> AccountDto
    func knownPeople() async throws -> [PersonDto]
    func searchMessages(term: String, roomId: String?) async throws -> [SearchResultDto]
}

// MARK: - The composition Session takes

/// Everything a `Session` needs, which is everything above.
///
/// `Session` builds all seven of its client-backed stores in its initialiser,
/// so it is the one place that has to hold the union. Stating it as a
/// composition rather than one flat protocol is what keeps each store's
/// dependency honest: `MediaCache` takes `any MediaFetching` and cannot
/// reach a room list through it.
public typealias SessionClient = AvatarFetching & MediaFetching & RoomsSnapshotting
    & SpaceSelecting & AttachmentStaging & TimelineSubscribing & SessionAuthenticating
    & MessageSending & RoomMembership & RoomAdministering & AccountDirectory

// MARK: - The one real conformer

// Every method is already declared on `CoreClient` with these exact
// signatures, so each conformance is empty. That is the point: the seam adds
// no code to the path that talks to the core, and no branch to it either.
extension CoreClient: SessionClient {}
