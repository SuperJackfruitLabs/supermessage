package dev.supermessage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiEvent
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.TimelineDiffEnvelope
import uniffi.supermessage_ffi.TimelineDiffOp
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * On a phone, Back from a room returns to the roster. ListDetailPaneScaffold
 * keeps the detail pane composed at zero size, so a room that was only
 * "deselected" went on marking itself read for new messages (a receipt to the
 * live account) and buzzing the finished-turn haptic on the roster, and a
 * short list went on paginating. Leaving must end all of it (iOS pops the
 * view). Driven through [AppRoot] with a fake core that records the writes.
 */
class LeavingARoomTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private val haptics = RecordingHaptics()
    private val alphaId = "!a:example.org"

    private class FakeCore : StubCore() {
        /** The session pushes the hidden set at sign-in; these tests hide nothing. */
        override fun setHiddenEvents(eventIds: List<String>) {}

        val receipts = mutableListOf<String>()
        val paginated = mutableListOf<String>()
        val typing = mutableListOf<Pair<String, Boolean>>()
        private var sink: EventSink? = null
        private var seq = 0uL

        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot(): RoomsSnapshot = RoomsSnapshot(seq = 0uL, rooms = listOf(joinedRow("!a:example.org", "Alpha")))
        override fun spacesList() = emptyList<uniffi.supermessage_core.SpaceSummary>()
        override fun account(): AccountDto = AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        override fun knownPeople(): List<PersonDto> = emptyList()
        override fun roomAvatar(roomId: String): String? = null
        override fun turn(roomId: String): uniffi.supermessage_core.TurnSnapshot? = null

        override fun roomCast(roomId: String) = uniffi.supermessage_core.RoomCast(
            roomId = roomId, people = emptyList(), counterpart = null,
            agentIds = emptyList(), addressee = null, mentionables = emptyList(),
        )
        override fun roomInfo(roomId: String) = throw IllegalStateException("offline")
        override fun markRoomRead(roomId: String) {
            receipts += roomId
        }
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean {
            paginated += roomId
            return true
        }
        override fun setTyping(roomId: String, typing: Boolean) {
            this.typing += roomId to typing
        }
        override fun timelineResync() = TimelineSnapshot(roomId = "", seq = 0uL, items = emptyList())
        override fun timelineSubscribe(roomId: String, sink: EventSink) {
            this.sink = sink
            seq = 0uL
            append(roomId, message("\$one", "first"))
        }

        /** An existing row changes (a reaction, an edit): the list keeps its shape. */
        fun replaceFirst(roomId: String, row: TimelineRow) {
            seq += 1uL
            sink!!.onEvent(
                FfiEvent.TimelineDiff(
                    TimelineDiffEnvelope(
                        channel = "timeline", subject = roomId, seq = seq,
                        ops = listOf(TimelineDiffOp.Set(0u, row)),
                    ),
                ),
            )
        }

        /** The room says something new, as the core would push it. */
        fun append(roomId: String, row: TimelineRow) {
            seq += 1uL
            sink!!.onEvent(
                FfiEvent.TimelineDiff(
                    TimelineDiffEnvelope(
                        channel = "timeline", subject = roomId, seq = seq,
                        ops = listOf(TimelineDiffOp.Append(listOf(row))),
                    ),
                ),
            )
        }
    }

    private val core = FakeCore()
    private lateinit var session: Session

    private fun openAlphaThenGoBack(goBack: Boolean) {
        session = Session(
            client = CoreClient(core = core, dispatcher = Dispatchers.Unconfined),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val file = File.createTempFile("leaving-a-room", ".preferences_pb")
        val prefs = RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalReduceMotion provides true) {
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                    AppRoot(session = session, prefs = prefs)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Alpha").performScrollTo().performClickAndSettle()
        compose.onNodeWithTag("row-\$one").assertIsDisplayed()
        if (goBack) {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNodeWithTag("roster-row").assertIsDisplayed()
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickAndSettle() {
        performClick()
        compose.waitForIdle()
    }

    private fun finishATurn() {
        // The core's own record of a turn that starts and then ends, as the
        // book would send it (the mirror takes `endedBy` from the summary).
        val thought = uniffi.supermessage_core.TurnInput.Thought(1uL, "hmm", false, 500uL)
        val started = uniffi.supermessage_ffi.foldTurn(alphaId, listOf(thought))!!
        val ended = uniffi.supermessage_ffi.foldTurn(
            alphaId,
            listOf(thought, uniffi.supermessage_core.TurnInput.Answer(1uL, true, 1_000uL)),
        )!!
        compose.runOnUiThread {
            session.live.apply(uniffi.supermessage_core.TurnUpdate(started.summary, null))
            session.live.apply(uniffi.supermessage_core.TurnUpdate(ended.summary, null))
        }
        compose.waitForIdle()
    }

    // ── Positive controls: the room on screen does both ─────────────────

    @Test
    fun aNewMessageInTheOpenRoomIsMarkedRead() {
        openAlphaThenGoBack(goBack = false)
        val before = core.receipts.size
        core.append(alphaId, message("\$two", "second"))
        compose.waitForIdle()
        assertEquals(before + 1, core.receipts.size)
    }

    @Test
    fun aTurnFinishingInTheOpenRoomBuzzes() {
        openAlphaThenGoBack(goBack = false)
        finishATurn()
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
    }

    // ── After Back ──────────────────────────────────────────────────────

    @Test
    fun aNewMessageInTheRoomYouLeftSendsNoReadReceipt() {
        openAlphaThenGoBack(goBack = true)
        val before = core.receipts.toList()
        core.append(alphaId, message("\$two", "second"))
        compose.waitForIdle()
        assertEquals(before, core.receipts)
    }

    /** The list keeps its shape here, so a list left at the newest end stays there. */
    @Test
    fun aChangeToARowInTheRoomYouLeftSendsNoReadReceipt() {
        openAlphaThenGoBack(goBack = true)
        val before = core.receipts.toList()
        core.replaceFirst(alphaId, message("\$one", "first, edited"))
        compose.waitForIdle()
        assertEquals(before, core.receipts)
    }

    @Test
    fun aChangeToARowInTheOpenRoomIsMarkedRead() {
        openAlphaThenGoBack(goBack = false)
        val before = core.receipts.size
        core.replaceFirst(alphaId, message("\$one", "first, edited"))
        compose.waitForIdle()
        assertEquals(before + 1, core.receipts.size)
    }

    @Test
    fun aTurnFinishingInTheRoomYouLeftPlaysNoHaptic() {
        openAlphaThenGoBack(goBack = true)
        finishATurn()
        assertEquals(emptyList<HapticFeedbackType>(), haptics.played)
    }

    @Test
    fun theRoomYouLeftDoesNotGoOnPaginatingOrTyping() {
        openAlphaThenGoBack(goBack = true)
        val paginated = core.paginated.toList()
        val typing = core.typing.toList()
        core.append(alphaId, message("\$two", "second"))
        compose.waitForIdle()
        assertEquals(paginated, core.paginated)
        assertEquals(typing, core.typing)
    }

    @Test
    fun openingTheSameRoomAgainShowsItAndMarksItRead() {
        openAlphaThenGoBack(goBack = true)
        val before = core.receipts.size
        compose.onNodeWithText("Alpha").performScrollTo().performClickAndSettle()
        compose.onNodeWithTag("row-\$one").assertIsDisplayed()
        // Opening a room is the first of the two triggers (spec section 6).
        assertTrue(core.receipts.size > before)
    }
}

private fun joinedRow(roomId: String, name: String) = RoomRow(
    room = RoomSummary(
        id = roomId, name = name, avatarUrl = null, unread = 0uL, lastMessage = null,
        lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
        lastActivityMs = null, runtime = null, membership = Membership.JOINED,
    ),
    identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
    preview = null,
    affordance = RoomAffordance.COMPOSE,
)

private fun message(id: String, body: String) = TimelineRow(
    item = uniffi.supermessage_core.TimelineItemDto(
        id = id, eventId = id, kind = "message", msgtype = "m.text", detail = null,
        sender = "@priya:example.org", senderDisplayName = null, senderAvatar = null, body = body,
        formattedBody = null, media = null, customPayload = null, timestampMs = 1_000uL,
        isOwn = false, sendState = null, replyTo = null, edited = false,
        reactions = emptyList(), readBy = emptyList(), editable = false, membershipSubject = null,
    ),
    view = ItemView.Bubble(
        muted = false,
        blocks = listOf(RichBlock.Paragraph(listOf(RichInline.Text(body)))),
        voice = null,
    ),
    senderName = "Priya", senderShort = "Priya", senderInitial = "P", membershipVerb = null,
    replyQuote = null, canReplyOrReact = true, replyPreview = null,
)
