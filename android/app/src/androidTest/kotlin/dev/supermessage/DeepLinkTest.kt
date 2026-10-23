package dev.supermessage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.NavigationRequests
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiException
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.TimelineSnapshot

/** `supermessage://` links (parity M5 Task 5): a request in `NavigationRequests`, AppRoot over a stub core. */
class DeepLinkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Core(
        var rows: List<RoomRow>,
        private val signedIn: Boolean = true,
        /** When set, the roster waits for it: the window in which a signed-in shell has no rooms yet. */
        @Volatile var rosterGate: CountDownLatch? = null,
    ) : StubCore() {
        val subscribed = mutableListOf<String>()
        private var sink: EventSink? = null

        override fun restoreSession(sink: EventSink): Boolean { this.sink = sink; return signedIn }
        override fun login(homeserver: String, username: String, password: String, sink: EventSink) { this.sink = sink }
        override fun logout() {}
        override fun roomsSnapshot(): RoomsSnapshot {
            rosterGate?.await(15, TimeUnit.SECONDS)
            return RoomsSnapshot(seq = 0uL, rooms = rows)
        }
        override fun spacesList(): List<SpaceSummary> = emptyList()
        override fun account(): AccountDto = AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        override fun ignoredUsers(): List<uniffi.supermessage_core.RoomMemberDto> = emptyList()
        override fun knownPeople(): List<uniffi.supermessage_core.PersonDto> = emptyList()
        override fun ensureRecovery(): String? = null
        override fun recoveryState(): String = "enabled"
        override fun roomAvatar(roomId: String): String? = null
        override fun roomInfo(roomId: String) = RoomInfoDto(
            roomId = roomId, name = "Room", identity = uniffi.supermessage_core.RoomIdentity(null, "Room", null, "R"),
            topic = null, runtime = null, canonicalAlias = null, altAliases = emptyList(),
            activeMemberCount = 4uL, members = emptyList(), notifications = NotificationMode.DEFAULT, pinned = false,
        )
        override fun markRoomRead(roomId: String) {}
        override fun timelineSubscribe(roomId: String, sink: EventSink) {
            subscribed += roomId
            if (roomId == "!nope:x") throw FfiException.RoomChanged(requested = roomId, focused = "")
        }
        override fun timelineResync(): TimelineSnapshot =
            TimelineSnapshot(roomId = subscribed.lastOrNull().orEmpty(), seq = 0uL, items = emptyList())
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = true
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun setTyping(roomId: String, typing: Boolean) {}
        override fun turn(roomId: String): uniffi.supermessage_core.TurnSnapshot? = null
        override fun acknowledgement(
            roomId: String, addressee: String?, agentIds: List<String>,
            typingAgents: List<String>, turnInProgress: Boolean,
        ): uniffi.supermessage_core.Acknowledgement? = null
        override fun roomCast(roomId: String) = uniffi.supermessage_core.RoomCast(
            roomId = roomId, people = emptyList(), counterpart = null,
            agentIds = emptyList(), addressee = null, mentionables = emptyList(),
        )
    }

    private val requests = NavigationRequests()

    private fun phone(): Modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()
    private fun tablet(width: Int): Modifier =
        Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(width.dp).fillMaxHeight()

    private fun show(core: Core, shell: Modifier = phone()) {
        val session = Session(
            client = CoreClient(core = core, dispatcher = Dispatchers.IO, resumeOn = Dispatchers.Main.immediate),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val file = File.createTempFile("deeplink", ".preferences_pb")
        val prefs = RosterPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file })
        compose.setContent {
            Box(shell.testTag("test-shell")) { AppRoot(session = session, prefs = prefs, requests = requests) }
        }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun select(destination: String) {
        compose.onNodeWithTag("destination-$destination").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun ask(roomId: String) {
        compose.runOnUiThread { requests.request(roomId) }
        compose.waitForIdle()
    }

    private fun waitForRoom() = compose.waitUntil(5_000) { exists("room-header") }

    @Test
    fun aLinkOpensTheRoomInChats() {
        val core = Core(NavRows.fleet())
        show(core)
        compose.waitUntil(5_000) { exists("destination-needs-you") }
        select("needs-you")
        compose.onNodeWithTag("destination-needs-you").assertIsSelected()

        ask("!a:x")
        waitForRoom()
        assertTrue("the core was asked to open it: ${core.subscribed}", core.subscribed.contains("!a:x"))
        assertTrue("the bar is hidden in a room", !exists("nav-bar"))
        back()
        compose.onNodeWithTag("destination-chats").assertIsSelected()
        assertTrue("Back lands on the Chats roster", exists("roster-list"))
        assertNull(requests.pending.value)
    }

    @Test
    fun aLinkToARunningActivityWorksAgain() {
        val core = Core(NavRows.fleet())
        show(core)
        compose.waitUntil(5_000) { exists("roster-list") }
        ask("!a:x")
        waitForRoom()
        back()
        ask("!q:x")
        waitForRoom()
        assertEquals("!q:x", core.subscribed.last())
    }

    @Test
    fun aLinkBeforeSignInOpensAfterIt() {
        val core = Core(NavRows.fleet(), signedIn = false)
        show(core)
        compose.waitUntil(5_000) { exists("username") }
        ask("!a:x")
        assertEquals("held while signed out", "!a:x", requests.pending.value)
        assertTrue(core.subscribed.isEmpty())

        compose.onNodeWithTag("username").performTextInput("me")
        compose.onNodeWithTag("password").performTextInput("pw")
        compose.onNodeWithTag("sign-in").performSemanticsAction(SemanticsActions.OnClick)
        waitForRoom()
        assertTrue(core.subscribed.contains("!a:x"))
    }

    @Test
    fun aLinkOnATabletSelectsTheRoom() {
        val core = Core(NavRows.fleet())
        show(core, tablet(840))
        compose.waitUntil(5_000) { exists("nav-rail") && exists("roster-list") }
        select("agents")
        ask("!a:x")
        waitForRoom()
        compose.onNodeWithTag("destination-chats").assertIsSelected()
        assertTrue("the rail stays", exists("nav-rail"))
        assertTrue(core.subscribed.contains("!a:x"))
    }

    @Test
    fun signingOutDropsAPendingLink() {
        val gate = CountDownLatch(1)
        val core = Core(NavRows.fleet(), rosterGate = gate)
        show(core, tablet(840))
        // Signed in, roster not yet answered: the link has nothing to open and stays pending.
        compose.waitUntil(5_000) { exists("toolbar-account") }
        ask("!a:x")
        assertEquals("!a:x", requests.pending.value)

        compose.onNodeWithTag("toolbar-account").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("account-sign-out").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("account-sign-out-confirm-button").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { exists("username") }
        compose.waitForIdle()
        assertNull("the last account's link is gone", requests.pending.value)

        gate.countDown()
        compose.onNodeWithTag("username").performTextInput("zed")
        compose.onNodeWithTag("password").performTextInput("pw")
        compose.onNodeWithTag("sign-in").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { exists("destination-chats") && exists("roster-list") }
        compose.waitForIdle()
        assertTrue("no room opened", !exists("room-header"))
        assertTrue(core.subscribed.isEmpty())
    }

    @Test
    fun aRoomTheRosterDoesNotHaveIsStillAsked() {
        val core = Core(NavRows.fleet())
        show(core)
        compose.waitUntil(5_000) { exists("roster-list") }
        ask("!nope:x")
        compose.waitUntil(5_000) { core.subscribed.contains("!nope:x") }
        compose.waitForIdle()
        compose.onNodeWithTag("destination-chats").assertIsSelected()
        assertTrue("no detail pane", !exists("room-header"))
        assertTrue("the roster is still there", exists("roster-list"))
    }
}
