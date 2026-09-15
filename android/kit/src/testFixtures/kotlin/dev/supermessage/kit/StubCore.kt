package dev.supermessage.kit

import uniffi.supermessage_core.*
import uniffi.supermessage_ffi.*

/**
 * A `CoreInterface` where every method refuses.
 *
 * Twelve test classes across three source sets each need a fake core, and
 * each needs three or four methods of it. Before this they carried the other
 * thirty-odd as hand-written `throw NotImplementedError()` lines — the same
 * wall of text, twelve times, alphabetised by hand.
 *
 * **The cost was not the typing.** Adding one method to `CoreInterface` broke
 * all twelve at once, and only the Linux runner compiles all three source
 * sets, so the breakage arrived one source set per CI round trip. Three
 * commits went into a single interface change that way. Extending this class
 * instead means a new method is inherited, and a fake that genuinely needs it
 * still says so.
 *
 * Refusing rather than returning empty values is deliberate and worth keeping:
 * a fake that quietly answers `emptyList()` to a call the test did not expect
 * turns a wrong call into a passing test. The stack trace from here names the
 * method nobody meant to call.
 *
 * Lives in `testFixtures` so all three source sets can see it and the release
 * variant cannot — the same reason `PREVIEW_FIXTURE_MARKER` exists on iOS.
 */
open class StubCore : CoreInterface {
    override fun account(): AccountDto = throw NotImplementedError()
    override fun attachmentDiscard(token: kotlin.String): Unit = throw NotImplementedError()
    override fun attachmentSend(roomId: kotlin.String, token: kotlin.String): Unit = throw NotImplementedError()
    override fun attachmentStagePath(roomId: kotlin.String, path: kotlin.String): StagedFile = throw NotImplementedError()
    override fun connectionState(): ConnectionState = throw NotImplementedError()
    override fun createRoom(name: kotlin.String, invite: List<kotlin.String>, isDirect: kotlin.Boolean): kotlin.String = throw NotImplementedError()
    override fun deleteMessage(roomId: kotlin.String, eventId: kotlin.String): Unit = throw NotImplementedError()
    override fun directRoomWith(userId: kotlin.String): kotlin.String? = throw NotImplementedError()
    override fun editMessage(roomId: kotlin.String, eventId: kotlin.String, body: kotlin.String): Unit = throw NotImplementedError()
    override fun enableRecovery(): kotlin.String = throw NotImplementedError()
    override fun inviteUser(roomId: kotlin.String, userId: kotlin.String): Unit = throw NotImplementedError()
    override fun joinRoom(roomId: kotlin.String): Unit = throw NotImplementedError()
    override fun joinRoomByAlias(aliasOrId: kotlin.String): kotlin.String = throw NotImplementedError()
    override fun knownPeople(): List<PersonDto> = throw NotImplementedError()
    override fun leaveRoom(roomId: kotlin.String): Unit = throw NotImplementedError()
    override fun login(homeserver: kotlin.String, username: kotlin.String, password: kotlin.String, sink: EventSink): Unit = throw NotImplementedError()
    override fun logout(): Unit = throw NotImplementedError()
    override fun markRoomRead(roomId: kotlin.String): Unit = throw NotImplementedError()
    override fun mediaFetch(eventId: kotlin.String): kotlin.String? = throw NotImplementedError()
    override fun memberAvatar(mxcUri: kotlin.String): kotlin.String? = throw NotImplementedError()
    override fun recoverWithKey(recoveryKey: kotlin.String): Unit = throw NotImplementedError()
    override fun recoveryState(): kotlin.String = throw NotImplementedError()
    override fun restoreSession(sink: EventSink): kotlin.Boolean = throw NotImplementedError()
    override fun roomAvatar(roomId: kotlin.String): kotlin.String? = throw NotImplementedError()
    override fun roomAvatarFull(roomId: kotlin.String): kotlin.String? = throw NotImplementedError()
    override fun roomInfo(roomId: kotlin.String): RoomInfoDto = throw NotImplementedError()
    override fun roomInviter(roomId: kotlin.String): kotlin.String? = throw NotImplementedError()
    override fun roomsSnapshot(): RoomsSnapshot = throw NotImplementedError()
    override fun searchMessages(term: kotlin.String, roomId: kotlin.String?): List<SearchResultDto> = throw NotImplementedError()
    override fun sendGateDecision(roomId: kotlin.String, gateId: kotlin.String, optionId: kotlin.String, comment: kotlin.String?, inReplyTo: kotlin.String, prompt: kotlin.String): Unit = throw NotImplementedError()
    override fun sendMessage(roomId: kotlin.String, body: kotlin.String, mentions: List<kotlin.String>): Unit = throw NotImplementedError()
    override fun sendReply(roomId: kotlin.String, body: kotlin.String, inReplyTo: kotlin.String): Unit = throw NotImplementedError()
    override fun setRoomNotifications(roomId: kotlin.String, mode: NotificationMode): Unit = throw NotImplementedError()
    override fun setRoomPinned(roomId: kotlin.String, pinned: kotlin.Boolean): Unit = throw NotImplementedError()
    override fun setTyping(roomId: kotlin.String, typing: kotlin.Boolean): Unit = throw NotImplementedError()
    override fun spaceSelect(spaceId: kotlin.String?): Unit = throw NotImplementedError()
    override fun spacesList(): List<SpaceSummary> = throw NotImplementedError()
    override fun timelinePaginateBack(roomId: kotlin.String, count: kotlin.UShort): kotlin.Boolean = throw NotImplementedError()
    override fun timelineResync(): TimelineSnapshot = throw NotImplementedError()
    override fun timelineSubscribe(roomId: kotlin.String, sink: EventSink): Unit = throw NotImplementedError()
    override fun toggleReaction(roomId: kotlin.String, eventId: kotlin.String, key: kotlin.String): kotlin.Boolean = throw NotImplementedError()}
