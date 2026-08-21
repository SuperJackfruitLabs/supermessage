package dev.supermessage.kit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.ConnectionState
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.StagedFile
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * The only thing in this app that holds a `Core`.
 *
 * **Every method on `Core` blocks the calling thread.** They are synchronous
 * Rust functions that `block_on` a tokio runtime, so a call takes as long as
 * the homeserver does and does nothing else while it waits.
 *
 * ## Why a dedicated dispatcher and not `Dispatchers.Default`
 *
 * The obvious wrapper is `withContext(Dispatchers.Default) { body(core) }`,
 * and it is wrong for a reason that does not show up until the app is busy.
 * `Dispatchers.Default` is Kotlin's cooperative pool — sized to the core
 * count and built on the assumption that a coroutine never blocks, only
 * suspends. A handful of concurrent blocking calls therefore occupy the
 * whole pool, and everything else dispatched to it, including work with no
 * interest in the network, stops. The failure mode is a hang, not a stall,
 * and it arrives under load rather than in a test — exactly the trap the
 * iOS port fell into with `Task.detached`, which runs on Swift's own
 * cooperative pool for the same reason.
 *
 * `GlobalScope` is the wrong answer too, and for a different reason:
 * launching there detaches a call from any lifecycle, so nothing cancels it
 * on logout. This class never launches its own scope — it only switches the
 * *calling* coroutine's dispatcher, so cancelling the caller cancels the
 * call.
 *
 * So the blocking call goes to [Dispatchers.IO], a real thread pool that
 * expects to be blocked, via `withContext` — which is what keeps the
 * blocking off every dispatcher that matters without detaching the work
 * from whatever scope invoked it.
 *
 * ## Where this differs from `apple/SupermessageKit/CoreClient.swift`
 *
 * Swift's `CoreClient` is an `actor` that owns a `Core` it constructs from a
 * `dataDirectory: String`, and it exposes a `static dataDirectory()` that
 * asks `FileManager` for the app's own container. Neither survives the port
 * unchanged:
 *
 * - **No actor.** A Kotlin class with no shared mutable state needs no
 *   serialising wrapper — every method here only reads its own `core` and
 *   `dispatcher`, both `val`, so there is nothing for an actor to protect.
 * - **Takes [CoreInterface], not a directory.** Deciding where the core's
 *   SQLite stores live means asking Android for its own files directory,
 *   which needs a `Context` — a concern this module deliberately does not
 *   take on (`:kit` has no Android framework dependency beyond `:core`).
 *   That decision, and constructing the real `Core`, belongs to whichever
 *   layer has a `Context` to ask; this class takes the already-built
 *   [CoreInterface] (or, in a test, a fake) instead.
 * - **The dispatcher is a constructor parameter**, not implied by being an
 *   actor. Injecting it — defaulting to [Dispatchers.IO] — is what lets a
 *   test supply a probe dispatcher instead of exercising a real thread pool,
 *   and it is what will let every later store built on this class be tested
 *   the same way, without a real `Core`.
 * - **No `CoreEventSink`-equivalent marker protocol.** Swift's `login`,
 *   `restoreSession` and `timelineSubscribe` take a `CoreEventSink`, a local
 *   protocol that adds nothing but `Sendable` to the generated `EventSink` —
 *   needed only because Swift 6 strict concurrency requires a value crossing
 *   into `Task.detached` to be provably safe to hand across threads. The
 *   JVM has no compile-time equivalent to enforce, so the wrappers below
 *   take the generated [EventSink] directly.
 *
 * Nothing above this class holds a [CoreInterface] reference. That is the
 * point — code that could reach one directly could block a thread it does
 * not own from inside a suspend function that looks safe to call anywhere.
 */
class CoreClient(
    private val core: CoreInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Run one blocking call on [dispatcher]. */
    private suspend fun <T> run(body: (CoreInterface) -> T): T =
        withContext(dispatcher) { body(core) }

    // MARK: - Session

    suspend fun login(homeserver: String, username: String, password: String, sink: EventSink) {
        run { it.login(homeserver, username, password, sink) }
    }

    suspend fun restoreSession(sink: EventSink): Boolean = run { it.restoreSession(sink) }

    suspend fun logout() {
        run { it.logout() }
    }

    suspend fun connectionState(): ConnectionState = run { it.connectionState() }

    // MARK: - Rooms

    suspend fun roomsSnapshot(): RoomsSnapshot = run { it.roomsSnapshot() }

    suspend fun joinRoom(roomId: String) {
        run { it.joinRoom(roomId) }
    }

    suspend fun joinRoomByAlias(aliasOrId: String): String = run { it.joinRoomByAlias(aliasOrId) }

    suspend fun leaveRoom(roomId: String) {
        run { it.leaveRoom(roomId) }
    }

    suspend fun createRoom(name: String, invite: List<String>, isDirect: Boolean): String =
        run { it.createRoom(name, invite, isDirect) }

    suspend fun inviteUser(roomId: String, userId: String) {
        run { it.inviteUser(roomId, userId) }
    }

    suspend fun roomInviter(roomId: String): String? = run { it.roomInviter(roomId) }

    suspend fun account(): AccountDto = run { it.account() }

    suspend fun roomAvatarFull(roomId: String): String? = run { it.roomAvatarFull(roomId) }

    suspend fun knownPeople(): List<PersonDto> = run { it.knownPeople() }

    suspend fun directRoomWith(userId: String): String? = run { it.directRoomWith(userId) }

    suspend fun roomInfo(roomId: String): RoomInfoDto = run { it.roomInfo(roomId) }

    suspend fun markRoomRead(roomId: String) {
        run { it.markRoomRead(roomId) }
    }

    // MARK: - Spaces

    suspend fun spacesList(): List<SpaceSummary> = run { it.spacesList() }

    suspend fun spaceSelect(spaceId: String?) {
        run { it.spaceSelect(spaceId) }
    }

    // MARK: - Timeline

    suspend fun timelineSubscribe(roomId: String, sink: EventSink) {
        run { it.timelineSubscribe(roomId, sink) }
    }

    suspend fun timelineResync(): TimelineSnapshot = run { it.timelineResync() }

    suspend fun timelinePaginateBack(roomId: String, count: UShort): Boolean =
        run { it.timelinePaginateBack(roomId, count) }

    // MARK: - Sending

    suspend fun sendMessage(roomId: String, body: String, mentions: List<String>) {
        run { it.sendMessage(roomId, body, mentions) }
    }

    suspend fun sendReply(roomId: String, body: String, inReplyTo: String) {
        run { it.sendReply(roomId, body, inReplyTo) }
    }

    suspend fun setRoomNotifications(roomId: String, mode: NotificationMode) {
        run { it.setRoomNotifications(roomId, mode) }
    }

    suspend fun setRoomPinned(roomId: String, pinned: Boolean) {
        run { it.setRoomPinned(roomId, pinned) }
    }

    suspend fun editMessage(roomId: String, eventId: String, body: String) {
        run { it.editMessage(roomId, eventId, body) }
    }

    suspend fun deleteMessage(roomId: String, eventId: String) {
        run { it.deleteMessage(roomId, eventId) }
    }

    suspend fun toggleReaction(roomId: String, eventId: String, key: String): Boolean =
        run { it.toggleReaction(roomId, eventId, key) }

    suspend fun setTyping(roomId: String, typing: Boolean) {
        run { it.setTyping(roomId, typing) }
    }

    // MARK: - Media and attachments

    suspend fun roomAvatar(roomId: String): String? = run { it.roomAvatar(roomId) }

    suspend fun memberAvatar(mxcUri: String): String? = run { it.memberAvatar(mxcUri) }

    suspend fun mediaFetch(eventId: String): String? = run { it.mediaFetch(eventId) }

    suspend fun attachmentStagePath(roomId: String, path: String): StagedFile =
        run { it.attachmentStagePath(roomId, path) }

    suspend fun attachmentSend(roomId: String, token: String) {
        run { it.attachmentSend(roomId, token) }
    }

    suspend fun attachmentDiscard(token: String) {
        run { it.attachmentDiscard(token) }
    }

    // MARK: - Search

    suspend fun searchMessages(term: String, roomId: String?): List<SearchResultDto> =
        run { it.searchMessages(term, roomId) }
}
