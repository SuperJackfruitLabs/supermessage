package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.supermessage.kit.MentionCast
import dev.supermessage.kit.ReportSubject
import dev.supermessage.kit.RosterArrangement
import dev.supermessage.kit.SendResult
import dev.supermessage.kit.Session
import dev.supermessage.kit.VoicePlayback
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomRow

/**
 * The detail pane: the header, the timeline and the composer for the room the
 * session has open, or the invitation in their place. Moved out of
 * `MainActivity.kt` so that later tasks stop contending for one file; the
 * body is the pane as it was, plus [RoomHeader].
 *
 * [onOpenRoomInfo] is how the header reaches room info (iOS
 * `RoomScreen.swift:102-115`): the caller decides which panel the Extra pane
 * shows and opens it.
 */
@Composable
internal fun RoomPane(
    session: Session,
    rooms: List<RoomRow>,
    voice: VoicePlayback,
    now: Instant,
    onOpenRoomInfo: (roomId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // The room this pane is showing, reactively:
    // `TimelineStore.roomId` is set the moment
    // `Session.open` subscribes, before the first
    // diff for it has even arrived.
    val roomId by session.timeline.roomId.collectAsStateWithLifecycle()

    // What the report sheet is open for, if it is. Keyed by the room: a sheet
    // about one room's message must not follow the reader into the next.
    // `reportOrigin` is the row it was started from, for returning focus.
    var reporting by remember(roomId) { mutableStateOf<ReportSubject?>(null) }
    var reportOrigin by remember(roomId) { mutableStateOf<String?>(null) }

    // What the core already decided about this room — never
    // re-derived here. An invited room shows InvitationView in
    // place of the composer (and InvitationEmptyTimeline in place
    // of history) purely because its own row already carries
    // `RoomAffordance.RESPOND_TO_INVITATION`; this file adds no
    // second opinion about which rooms are invitations.
    val currentRow = roomId?.let { id -> rooms.firstOrNull { it.room.id == id } }
    val isInvitation = currentRow?.affordance == RoomAffordance.RESPOND_TO_INVITATION

    // Trigger 1 of spec §6's two: on room change,
    // mark it read. Trigger 2 — on any history
    // change while at the newest end — is Timeline's
    // own job (see its class doc): it can see `rows`
    // and `isAtNewest` together, neither of which
    // this call site has reason to duplicate.
    //
    // Not for an invitation: membership is `invite`, so there is no
    // history here for "read" to mean anything about yet (see
    // InvitationEmptyTimeline's own KDoc).
    LaunchedEffect(roomId, isInvitation) {
        if (roomId != null && !isInvitation) session.timeline.markRead()
    }

    // Only a room of people says how many are in it; an agent's room, and an
    // invitation, say nothing (iOS `RoomScreen.swift:116-123`). Reset on every
    // room change, so a count never outlives the room it was fetched for
    // (`RoomScreen.swift:35-39`).
    val describesAgent = remember(roomId, rooms, now) {
        roomId?.let { RosterArrangement.rosterRow(it, rooms, now)?.describesAgent } ?: false
    }
    // Keyed by the room, so a new room starts with no count on its very first
    // frame instead of borrowing the last room's until an effect runs.
    var memberCount by remember(roomId) { mutableStateOf<ULong?>(null) }
    LaunchedEffect(roomId, describesAgent, isInvitation) {
        val id = roomId
        if (id != null && !describesAgent && !isInvitation) {
            memberCount = try {
                session.roomInfo(id).activeMemberCount
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    val currentRoomId = roomId
    if (currentRoomId == null) {
        Box(
            modifier.fillMaxSize().testTag("pane-timeline"),
            contentAlignment = Alignment.Center,
        ) {
            Text("Select a room")
        }
    } else if (isInvitation) {
        Column(modifier = modifier.fillMaxSize().testTag("pane-timeline")) {
            RoomHeader(currentRow, memberCount = null, onOpenInfo = { onOpenRoomInfo(currentRoomId) })
            InvitationEmptyTimeline(modifier = Modifier.weight(1f).fillMaxWidth())
            InvitationView(
                roomId = currentRoomId,
                roomName = currentRow?.identity?.name ?: currentRoomId,
                inviter = session::inviter,
                joinRoom = session::joinRoom,
                leaveRoom = session::leaveRoom,
                modifier = Modifier.fillMaxWidth(),
                onReport = {
                    reporting = ReportSubject.Room(currentRoomId, currentRow?.identity?.name ?: currentRoomId)
                },
                reporting = reporting != null,
            )
        }
    } else {
        val items by session.timeline.items.collectAsStateWithLifecycle()
        // `Timeline` keys its grouping and its mark-read effect off
        // this rather than off `items` itself — see `Timeline.kt`'s
        // own note on why a revision bump, not a structural diff of
        // the whole list, is what "the history changed" means.
        val revision by session.timeline.revision.collectAsStateWithLifecycle()
        val isPaginating by session.timeline.isPaginating.collectAsStateWithLifecycle()
        val canPaginate by session.timeline.canPaginate.collectAsStateWithLifecycle()
        // Collected so a fetch that lands recomposes the rows that
        // asked for it; the lambdas below read from these.
        val mediaCache by session.media.cache.collectAsStateWithLifecycle()
        val faceCache by session.faces.cache.collectAsStateWithLifecycle()

        // `TypingStore.line` is a computed property,
        // not a StateFlow — `typers` is what is
        // actually observable, so that is what is
        // collected; `line` is re-read from it every
        // time `typers` changes.
        val typers by session.typing.typers.collectAsStateWithLifecycle()
        val typingLine = remember(typers) { session.typing.line }

        val liveAnswer by session.live.answer.collectAsStateWithLifecycle()
        val liveThought by session.live.thought.collectAsStateWithLifecycle()
        val liveTools by session.live.tools.collectAsStateWithLifecycle()
        val liveFinished by session.live.finished.collectAsStateWithLifecycle()

        // Reply/edit/attachment: reactive so a long
        // press elsewhere (Timeline's message menu,
        // below) or a picked photo shows up here the
        // moment the store records it.
        val replyTargets by session.replies.targets.collectAsStateWithLifecycle()
        val editTargets by session.edits.targets.collectAsStateWithLifecycle()
        val stagedFile by session.staged.file.collectAsStateWithLifecycle()
        val stagedPath by session.staged.path.collectAsStateWithLifecycle()
        val stagedFailure by session.staged.failure.collectAsStateWithLifecycle()
        val stagedRoom by session.staged.roomId.collectAsStateWithLifecycle()
        // The chip follows the room it was staged for, and shows a file, a
        // failure, or both (iOS `session.staged.isPresent(in:)`). The four
        // collected above are what recompose this; `isPresent` reads the same.
        val attachment = if (stagedRoom == currentRoomId && (stagedFile != null || stagedFailure != null)) {
            ChipState(file = stagedFile, path = stagedPath, failure = stagedFailure)
        } else null
        val haptics = LocalHapticFeedback.current
        val editing = editTargets[currentRoomId]

        // The composer's own text, held here rather
        // than mirrored live off `DraftStore` — the
        // same shape `ComposerView.swift`'s `@State
        // private var text` takes, seeded once per
        // room (`remember(currentRoomId)` standing in
        // for that file's `.task(id: roomId)`) rather
        // than re-derived from the store on every
        // recomposition. That distinction is what
        // makes editing work at all: see
        // `onTextChange` below for why a live mirror
        // would fight it.
        var text by remember(currentRoomId) {
            mutableStateOf(session.drafts.draft(currentRoomId))
        }
        // Who an `@` here can name, from the core's cast for this room
        // (iOS `.task(id: roomId) { loadCast() }`). Keyed by the room so a new
        // room never starts with the last one's people, and `load` drops an
        // answer for a room already left.
        val mentionCast = remember(currentRoomId) { MentionCast() }
        LaunchedEffect(currentRoomId) { mentionCast.load(currentRoomId, session::roomCast) }
        val castPeople by mentionCast.cast.collectAsStateWithLifecycle()
        var sending by remember(currentRoomId) { mutableStateOf(false) }
        var composerFailure by remember(currentRoomId) { mutableStateOf<String?>(null) }

        Column(modifier = modifier.fillMaxSize().testTag("pane-timeline")) {
            currentRow?.let {
                RoomHeader(it, memberCount = if (describesAgent || isInvitation) null else memberCount, onOpenInfo = { onOpenRoomInfo(currentRoomId) })
            }
            // The note that is playing belongs to this room and
            // this pane: leaving either stops it (the key is the
            // room; disposal is the pane going away).
            DisposableEffect(currentRoomId) { onDispose { voice.stop() } }
            // Keyed by the room: everything the list holds in memory (an open
            // message menu, a who-reacted popover, a swipe, the scroll position)
            // belongs to one room and must not be lent to the next
            // (`RoomScreen.swift:35-39` resets on `roomId` too).
            key(currentRoomId) {
                CompositionLocalProvider(LocalVoicePlayback provides voice) {
                    Timeline(
                        rows = items,
                        revision = revision,
                        typingLine = typingLine,
                        isPaginating = isPaginating,
                        canPaginate = canPaginate,
                        onPaginate = { scope.launch { session.timeline.paginateBack() } },
                        imageUri = { id -> mediaCache[id] ?: session.media.image(id) },
                        faceUri = { mxc -> faceCache[mxc] },
                        loadFace = session.faces::load,
                        roomId = currentRoomId,
                        onMarkRead = { scope.launch { session.timeline.markRead() } },
                        liveAnswer = liveAnswer,
                        liveThought = liveThought,
                        liveTools = liveTools,
                        liveFinished = liveFinished,
                        onReact = { row, key ->
                            scope.launch {
                                session.toggleReaction(row.item.eventId, key, currentRoomId)
                            }
                        },
                        // The event id is the gate's own, which the decision
                        // references; the card supplies what it resolves.
                        // Both are needed and neither side has both.
                        // Returns whether the answer landed, so the card can
                        // stop offering the choice — and keep offering it when
                        // the send failed.
                        onDecide = { row, answer ->
                            val gateId = answer.subject
                            if (gateId == null) {
                                // A permission request has no gate; route it
                                // to the permission sender (iOS
                                // `TimelineCollectionView.swift:1022-1031`).
                                session.answerPermission(optionId = answer.optionId, roomId = currentRoomId)
                            } else session.answerGate(
                                eventId = row.item.eventId,
                                gateId = gateId,
                                optionId = answer.optionId,
                                comment = answer.comment,
                                prompt = answer.prompt,
                                roomId = currentRoomId,
                            )
                        },
                        onReply = { row -> session.replies.start(row, currentRoomId) },
                        onEdit = { row -> session.edits.start(row, currentRoomId) },
                        onDelete = { row -> session.delete(row.item.eventId, currentRoomId) },
                        onSwipeReply = { row -> session.replies.start(row, currentRoomId) },
                        reportingRowId = reportOrigin,
                        // The subject needs the sender's kind, which the room is
                        // asked for; the sheet opens once it has the answer.
                        onReport = { row ->
                            reportOrigin = row.item.id
                            scope.launch {
                                val subject = session.reportSubjectFor(row, currentRoomId)
                                if (subject == null) reportOrigin = null else reporting = subject
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }

            Composer(
                text = text,
                onTextChange = { next ->
                    text = next
                    // Not while editing: the composer
                    // is holding an existing message,
                    // and writing that over the draft
                    // would destroy whatever was
                    // being written before the edit
                    // began — the exact guard
                    // `ComposerView.swift`'s own
                    // `onChange(of: text)` carries.
                    if (editing == null) {
                        session.drafts.set(next, currentRoomId)
                    }
                    scope.launch { session.setTyping(next.isNotBlank(), currentRoomId) }
                },
                onSend = {
                    scope.launch {
                        sending = true
                        try {
                            val currentEdit = editing
                            if (currentEdit != null) {
                                // The reader's text stays in the
                                // composer when this fails — an
                                // edit that vanished into an error
                                // would have silently discarded
                                // what they wrote.
                                val ok = session.edit(
                                    eventId = currentEdit.eventId,
                                    body = text,
                                    roomId = currentRoomId,
                                )
                                if (ok) {
                                    composerFailure = null
                                    session.edits.cancel(currentRoomId)
                                    text = session.drafts.draft(currentRoomId)
                                } else {
                                    composerFailure = "Could not save the edit."
                                }
                            } else {
                                when (val result = session.send(text, currentRoomId, mentionCast.mentionables)) {
                                    SendResult.Sent, SendResult.NothingToSend -> {
                                        composerFailure = null
                                        text = ""
                                        session.drafts.clear(currentRoomId)
                                        // Light, and only for a send that reached the
                                        // room: a buzz on a failure would contradict
                                        // the screen (iOS `ComposerView.swift:110-113`).
                                        if (result.delivered) haptics.play(Haptic.Light)
                                    }
                                    // The reason is on the chip, and the draft stays.
                                    // Unless the chip does not say this reason (a newer
                                    // pick took its place mid-send, with its own state),
                                    // so say it here.
                                    is SendResult.AttachmentFailed ->
                                        composerFailure =
                                            if (session.staged.failure.value?.message == result.message) null
                                            else result.message
                                    is SendResult.TextFailed -> composerFailure = result.message
                                }
                            }
                        } finally {
                            sending = false
                        }
                    }
                },
                // Reads the collected cast, so the lambda changes (and the composer
                // recomposes) when the cast arrives.
                mentionCandidates = { query -> castPeople?.let { mentionCast.candidates(query) }.orEmpty() },
                sending = sending,
                failure = composerFailure,
                replyTo = replyTargets[currentRoomId],
                onCancelReply = { session.replies.cancel(currentRoomId) },
                editing = editing,
                onCancelEdit = { session.edits.cancel(currentRoomId) },
                attachment = attachment,
                // A refusal is kept by the tracker and drawn on the chip, not here.
                onAttach = { path ->
                    composerFailure = null
                    scope.launch { session.staged.stage(path, currentRoomId) }
                },
                onRefuse = { filename, message ->
                    composerFailure = null
                    scope.launch { session.staged.refuse(filename, message, currentRoomId) }
                },
                onDiscardAttachment = {
                    composerFailure = null
                    scope.launch { session.staged.discard() }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    reporting?.let { subject ->
        ReportSheet(
            subject = subject,
            isBlocked = session.safety::isBlocked,
            onSend = { reason, block -> session.report(subject, reason, block) },
            onClose = {
                reporting = null
                reportOrigin = null
            },
        )
    }
}
