package dev.supermessage

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import dev.supermessage.previews.PreviewFixtures
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import uniffi.supermessage_core.Acknowledgement
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.Counterpart
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RuntimeDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiEvent
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.TimelineDiffEnvelope
import uniffi.supermessage_ffi.TimelineDiffOp
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * One agent room behind the real [AppRoot] and [Session]: a fake core that serves the
 * room's rows, its cast and turn events, and echoes what the reader sends as their own
 * newest row, the way the real core's local echo does. Shared by [KeyboardGapTest] and
 * [StreamingFollowTest].
 */
internal object RoomHarness {
    const val ROOM = "!atlas:x"
    const val NAME = "Atlas"

    fun room(): RoomRow = RoomRow(
        room = RoomSummary(
            id = ROOM, name = NAME, avatarUrl = null, unread = 0uL, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = System.currentTimeMillis().toULong() - 60_000uL,
            runtime = RuntimeDto("claude-code", "Ashram"), membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = NAME, role = null, initial = "A"),
        preview = null,
        affordance = RoomAffordance.COMPOSE,
    )

    fun row(id: String, body: String, own: Boolean): TimelineRow = PreviewFixtures.row(
        PreviewFixtures.item(id, sender = if (own) "@me:x" else "@atlas:x", body = body, isOwn = own),
        ItemView.Bubble(muted = false, blocks = listOf(RichBlock.Paragraph(listOf(RichInline.Text(body)))), voice = null),
        senderName = if (own) "You" else NAME, senderShort = NAME, senderInitial = "A",
    )

    fun history(count: Int): List<TimelineRow> =
        (0 until count).map { row("\$h$it", "older message $it", own = it % 3 == 0) }

    class FakeCore(initial: List<TimelineRow>) : StubCore() {
        val rows = initial.toMutableList()
        private var sink: EventSink? = null
        private var seq = 0uL
        private var sent = 0

        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot() = RoomsSnapshot(seq = 0uL, rooms = listOf(room()))
        override fun spacesList() = emptyList<SpaceSummary>()
        override fun account() = AccountDto(userId = "@me:x", homeserver = "https://x")
        override fun knownPeople() = emptyList<PersonDto>()
        override fun roomAvatar(roomId: String): String? = null
        override fun turn(roomId: String): uniffi.supermessage_core.TurnSnapshot? = null
        override fun roomCast(roomId: String) = RoomCast(
            roomId = roomId,
            people = listOf(PersonDto("@atlas:x", NAME, "A", null, null), PersonDto("@k:x", "Krishna", "K", null, null)),
            counterpart = Counterpart("@atlas:x", NAME),
            agentIds = listOf("@atlas:x"), addressee = "@atlas:x", mentionables = emptyList(),
        )
        override fun markRoomRead(roomId: String) {}
        override fun timelinePaginateBack(roomId: String, count: UShort) = false
        override fun setTyping(roomId: String, typing: Boolean) {}
        override fun timelineResync() = TimelineSnapshot(roomId = ROOM, seq = 0uL, items = emptyList())
        override fun logout() {}
        override fun acknowledgement(
            roomId: String, addressee: String?, agentIds: List<String>,
            typingAgents: List<String>, turnInProgress: Boolean,
        ): Acknowledgement? = null

        override fun timelineSubscribe(roomId: String, sink: EventSink) {
            this.sink = sink
            seq = 0uL
            push(TimelineDiffOp.Append(rows.toList()))
        }

        /** The local echo of a send: the reader's words, as their own newest row. */
        override fun sendMessage(roomId: String, body: String, mentions: List<String>) {
            sent += 1
            append(row("\$sent$sent", body, own = true))
        }

        private fun push(op: TimelineDiffOp) {
            seq += 1uL
            sink!!.onEvent(FfiEvent.TimelineDiff(TimelineDiffEnvelope(channel = "timeline", subject = ROOM, seq = seq, ops = listOf(op))))
        }

        fun append(row: TimelineRow) {
            rows += row
            push(TimelineDiffOp.Append(listOf(row)))
        }

        fun event(e: FfiEvent) = sink!!.onEvent(e)
    }

    /** A session over [core], its calls answered on the calling thread. */
    fun session(core: FakeCore): Session = Session(
        client = CoreClient(core = core, dispatcher = Dispatchers.Unconfined, resumeOn = null),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    fun prefs(): RosterPreferences {
        val file = File.createTempFile("room-harness", ".preferences_pb")
        return RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
    }

    fun mainActivitySoftInputMode(context: Context): Int =
        context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0).softInputMode

    fun AndroidComposeTestRule<*, *>.count(tag: String): Int =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
}
