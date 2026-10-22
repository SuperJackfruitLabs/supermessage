package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot

/**
 * R2: swipe a room to mark read, mute, pin or decline (`RoomListView.swift:260-327`).
 * Always against a fake core: the live account is never swiped.
 */
class RosterSwipeTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private fun roomRow(id: String, name: String, invited: Boolean = false) = RoomRow(
        room = RoomSummary(
            id = id, name = name, avatarUrl = null, unread = 0uL, lastMessage = null,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = null, runtime = null,
            membership = if (invited) Membership.INVITED else Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = name, role = null, initial = name.take(1)),
        preview = null,
        affordance = if (invited) RoomAffordance.RESPOND_TO_INVITATION else RoomAffordance.COMPOSE,
    )

    private fun info(notifications: NotificationMode, pinned: Boolean = false) = RoomInfoDto(
        roomId = "!a:example.org", name = "Alpha", identity = RoomIdentity(null, "Alpha", null, "A"),
        topic = null, runtime = null, canonicalAlias = null, altAliases = emptyList(),
        activeMemberCount = 2uL, members = emptyList(), notifications = notifications, pinned = pinned,
    )

    private class FakeCore(
        private val rooms: List<RoomRow>,
        private val infoResult: () -> RoomInfoDto = { throw IllegalStateException("offline") },
        private val markReadResult: () -> Unit = {},
    ) : StubCore() {
        val markedRead = mutableListOf<String>()
        val notificationsSet = mutableListOf<Pair<NotificationMode, String>>()
        val pinnedSet = mutableListOf<Pair<Boolean, String>>()
        val left = mutableListOf<String>()
        val infoAsked = mutableListOf<String>()

        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot(): RoomsSnapshot = RoomsSnapshot(seq = 0uL, rooms = rooms)
        override fun spacesList() = emptyList<uniffi.supermessage_core.SpaceSummary>()
        override fun account(): AccountDto = AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        override fun knownPeople(): List<PersonDto> = emptyList()
        override fun roomAvatar(roomId: String): String? = null
        override fun timelineResync() = uniffi.supermessage_ffi.TimelineSnapshot(roomId = "", seq = 0uL, items = emptyList())
        override fun setTyping(roomId: String, typing: Boolean): Unit = Unit
        override fun roomInfo(roomId: String): RoomInfoDto {
            infoAsked += roomId
            return infoResult()
        }
        override fun markRoomRead(roomId: String) {
            markReadResult()
            markedRead += roomId
        }
        override fun setRoomNotifications(roomId: String, mode: NotificationMode) {
            notificationsSet += mode to roomId
        }
        override fun setRoomPinned(roomId: String, pinned: Boolean) {
            pinnedSet += pinned to roomId
        }
        override fun leaveRoom(roomId: String) {
            left += roomId
        }
    }

    private val haptics = RecordingHaptics()

    private fun show(core: FakeCore) {
        val session = Session(
            client = CoreClient(core = core, dispatcher = Dispatchers.Unconfined),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val file = File.createTempFile("roster-swipe", ".preferences_pb")
        val prefs = RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalReduceMotion provides true) {
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                    AppRoot(session = session, prefs = prefs)
                }
            }
        }
        compose.waitForIdle()
    }

    private val alpha = listOf(roomRow("!a:example.org", "Alpha"))

    @Test
    fun swipingRightMarksRead() {
        val core = FakeCore(alpha)
        show(core)
        compose.onNodeWithTag("roster-row").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf("!a:example.org"), core.markedRead)
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
    }

    @Test
    fun aMarkReadTheCoreRefusesPlaysNoHaptic() {
        val core = FakeCore(alpha, markReadResult = { throw IllegalStateException("not the open room") })
        show(core)
        compose.onNodeWithTag("roster-row").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(emptyList<HapticFeedbackType>(), haptics.played)
    }

    @Test
    fun swipingLeftOffersMuteAndPin() {
        show(FakeCore(alpha))
        compose.onNodeWithTag("roster-row").performTouchInput { swipeLeft(endX = right - 160.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-Mute").assertIsDisplayed()
        compose.onNodeWithTag("swipe-Pin").assertIsDisplayed()
    }

    @Test
    fun muteAsksTheCoreFirstThenSaysUnmute() {
        val core = FakeCore(alpha, infoResult = { info(NotificationMode.DEFAULT) })
        show(core)
        compose.onNodeWithTag("roster-row").performTouchInput { swipeLeft(endX = right - 160.dp.toPx()) }
        compose.onNodeWithTag("swipe-Mute").performClick()
        compose.waitForIdle()
        assertEquals(listOf(NotificationMode.MUTED to "!a:example.org"), core.notificationsSet)
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)

        compose.onNodeWithTag("roster-row").performTouchInput { swipeLeft(endX = right - 160.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-Unmute").assertIsDisplayed()
    }

    @Test
    fun muteSwipeDoesNothingWhenRoomInfoFails() {
        val core = FakeCore(alpha) // roomInfo throws
        show(core)
        compose.onNodeWithTag("roster-row").performTouchInput { swipeLeft(endX = right - 160.dp.toPx()) }
        compose.onNodeWithTag("swipe-Mute").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Pair<NotificationMode, String>>(), core.notificationsSet)
        assertEquals(emptyList<HapticFeedbackType>(), haptics.played)

        compose.onNodeWithTag("roster-row").performTouchInput { swipeLeft(endX = right - 160.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-Mute").assertIsDisplayed()
    }

    @Test
    fun pinAsksTheCoreThenSaysUnpin() {
        val core = FakeCore(alpha, infoResult = { info(NotificationMode.DEFAULT, pinned = false) })
        show(core)
        compose.onNodeWithTag("roster-row").performTouchInput { swipeLeft(endX = right - 160.dp.toPx()) }
        compose.onNodeWithTag("swipe-Pin").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true to "!a:example.org"), core.pinnedSet)
        compose.onNodeWithTag("roster-row").performTouchInput { swipeLeft(endX = right - 160.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-Unpin").assertIsDisplayed()
    }

    @Test
    fun anInvitationSwipesToDecline() {
        val core = FakeCore(listOf(roomRow("!inv:x", "Lab Room", invited = true)))
        show(core)
        compose.onNodeWithTag("invitations-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("roster-row").performTouchInput { swipeLeft(endX = right - 100.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-Decline").assertIsDisplayed()
        compose.onAllNodesWithTag("swipe-Mute").assertCountEquals(0)
        compose.onNodeWithTag("swipe-Decline").performClick()
        compose.waitForIdle()
        assertEquals(listOf("!inv:x"), core.left)
    }

    @Test
    fun swipeActionsAreReachableWithoutAGesture() {
        val core = FakeCore(alpha, infoResult = { info(NotificationMode.DEFAULT) })
        show(core)
        val actions = compose.onNodeWithTag("roster-row").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(listOf("Mark read", "Mute", "Pin"), actions.map { it.label })
        compose.runOnUiThread { actions.first { it.label == "Pin" }.action() }
        compose.waitForIdle()
        assertEquals(listOf(true to "!a:example.org"), core.pinnedSet)
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
    }

    @Test
    fun anInvitationsCustomActionIsDecline() {
        val core = FakeCore(listOf(roomRow("!inv:x", "Lab Room", invited = true)))
        show(core)
        compose.onNodeWithTag("invitations-row").performClick()
        compose.waitForIdle()
        val actions = compose.onNodeWithTag("roster-row").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(listOf("Decline"), actions.map { it.label })
    }
}
