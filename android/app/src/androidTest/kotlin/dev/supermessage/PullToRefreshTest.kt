package dev.supermessage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot

/**
 * Pull to refresh on Chats, Needs you and Agents re-reads the roster (`session.rooms.seed()`,
 * iOS `RoomListView.swift:175-178`), once per pull, and the indicator stays until it returns.
 */
class PullToRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Core(private val rows: List<RoomRow>) : StubCore() {
        val reads = AtomicInteger()

        /** When set, the next roster read waits for it. */
        @Volatile var hold: CountDownLatch? = null
        override fun restoreSession(sink: EventSink): Boolean = true
        override fun logout() {}
        override fun roomsSnapshot(): RoomsSnapshot {
            reads.incrementAndGet()
            hold?.await(15, TimeUnit.SECONDS)
            return RoomsSnapshot(seq = 0uL, rooms = rows)
        }
        override fun spacesList(): List<SpaceSummary> = emptyList()
        override fun account(): AccountDto = AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        override fun ignoredUsers(): List<uniffi.supermessage_core.RoomMemberDto> = emptyList()
        override fun knownPeople(): List<uniffi.supermessage_core.PersonDto> = emptyList()
        override fun ensureRecovery(): String? = null
        override fun recoveryState(): String = "enabled"
        override fun roomAvatar(roomId: String): String? = null
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun markRoomRead(roomId: String) {}
        override fun setTyping(roomId: String, typing: Boolean) {}
        override fun turn(roomId: String): uniffi.supermessage_core.TurnSnapshot? = null
        override fun timelineSubscribe(roomId: String, sink: EventSink) {}
        override fun timelineResync() = uniffi.supermessage_ffi.TimelineSnapshot(roomId = "", seq = 0uL, items = emptyList())
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = true
    }

    private fun show(core: Core) {
        val session = Session(
            client = CoreClient(core = core, dispatcher = Dispatchers.IO),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val file = File.createTempFile("pull", ".preferences_pb")
        val prefs = RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
        compose.setContent {
            Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) { AppRoot(session = session, prefs = prefs) }
        }
        compose.waitForIdle()
    }

    private fun indicator() = compose.onAllNodesWithTag("refresh-indicator").fetchSemanticsNodes().isNotEmpty()

    private fun pull(list: String, core: Core) {
        val before = core.reads.get()
        val latch = CountDownLatch(1)
        core.hold = latch
        compose.onNodeWithTag(list).performTouchInput { swipe(Offset(centerX, 60f), Offset(centerX, height * 0.8f), durationMillis = 600) }
        compose.waitUntil(5_000) { core.reads.get() > before }
        compose.waitUntil(5_000) { indicator() }
        assertTrue("the indicator stays while the read is out", indicator())
        assertEquals("one pull is one read", before + 1, core.reads.get())
        core.hold = null
        latch.countDown()
        compose.waitUntil(5_000) { !indicator() }
        assertEquals("and still one when it has returned", before + 1, core.reads.get())
    }

    @Test
    fun pullingChatsReseedsTheRoster() {
        val core = Core(NavRows.fleet())
        show(core)
        compose.waitUntil(5_000) { core.reads.get() >= 1 }
        pull("roster-list", core)
    }

    @Test
    fun pullingNeedsYouAndAgentsReseedsToo() {
        val core = Core(NavRows.fleet())
        show(core)
        compose.onNodeWithTag("destination-needs-you").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("needs-you-list").fetchSemanticsNodes().isNotEmpty() }
        pull("needs-you-list", core)
        compose.onNodeWithTag("destination-agents").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("agents-list").fetchSemanticsNodes().isNotEmpty() }
        pull("agents-list", core)
    }
}
