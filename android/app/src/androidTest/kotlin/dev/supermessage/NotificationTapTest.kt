package dev.supermessage

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.kit.StubCore
import dev.supermessage.notify.AndroidPoster
import dev.supermessage.notify.PushRegistrar
import org.unifiedpush.android.connector.UnifiedPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.PlannedNotification
import uniffi.supermessage_core.RoomInfoDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.TimelineSnapshot

/**
 * Tapping a note must reach the running Activity, not rebuild it and its Session. MainActivity is
 * singleTask, so a tap is delivered to the one instance's `onNewIntent`. The real Activity is
 * started in its own task, as `LinkIntentTest` does: `ActivityScenario` cannot close a singleTask
 * Activity that a NEW_TASK | CLEAR_TOP intent has reached (it stays PAUSED behind its EmptyActivity).
 */
@RunWith(AndroidJUnit4::class)
class NotificationTapTest {
    /** Finds the real MainActivity's composition; it launches nothing itself. */
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val started = mutableListOf<MainActivity>()

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { started.forEach { it.finishAndRemoveTask() } }
        instrumentation.waitForIdleSync()
        UnifiedPush.removeDistributor(context)
        AppCore.installRegistrarForTest(null)
        AppCore.installForTest(null)
        manager.cancelAll()
    }

    private fun grantAndClear() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    /** The real MainActivity, at the root of the app's own task. */
    private fun launch(): MainActivity =
        (instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity).also { started += it }

    private fun modelOf(activity: MainActivity): SessionViewModel {
        var model: SessionViewModel? = null
        instrumentation.runOnMainSync { model = androidx.lifecycle.ViewModelProvider(activity)[SessionViewModel::class.java] }
        return model!!
    }

    @Test
    fun tappingANoteKeepsTheSameActivityAndViewModel() {
        grantAndClear()
        val pending = postedTap("!fake:x", "\$tap")
        val first = launch()
        val model = modelOf(first)
        pending.send()
        Thread.sleep(2_000)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("the tap reached the running Activity", AndroidPoster.openRoomUri("!fake:x"), first.intent.data)
            assertFalse(first.isFinishing)
        }
        assertEquals("one MainActivity", listOf(first), liveMainActivities())
        assertSame(model, modelOf(first))
    }

    private fun liveMainActivities(): List<MainActivity> {
        var found = emptyList<MainActivity>()
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            found = androidx.test.runner.lifecycle.Stage.values()
                .filter { it != androidx.test.runner.lifecycle.Stage.DESTROYED }
                .flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>()
                .distinct()
        }
        return found
    }

    /** A signed-in account with [NavRows.fleet]'s rooms; records what the timeline is pointed at. */
    private class SignedIn : StubCore() {
        val subscribed: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        fun subscribedNow(): List<String> = synchronized(subscribed) { subscribed.toList() }

        override fun restoreSession(sink: EventSink): Boolean = true
        override fun restoreSessionQuietly(): Boolean = true
        override fun logout() {}
        override fun roomsSnapshot(): RoomsSnapshot = RoomsSnapshot(seq = 0uL, rooms = NavRows.fleet())
        override fun spacesList(): List<SpaceSummary> = emptyList()
        override fun account(): AccountDto = AccountDto(userId = "@me:x", homeserver = "https://x")
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
        }
        override fun timelineResync(): TimelineSnapshot =
            TimelineSnapshot(roomId = subscribedNow().lastOrNull().orEmpty(), seq = 0uL, items = emptyList())
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
        override fun registerPusher(registration: uniffi.supermessage_core.PushRegistration) {}
        override fun unregisterPusher() {}
        /** Signed in, the app asks for push; the test APK's FakeDistributor answers with one push. */
        override fun notificationFor(roomId: String, eventId: String) = NotificationDto(
            roomId = roomId, eventId = eventId, title = "Lyra", subtitle = null, body = "pushed",
            category = NotificationCategory.MESSAGE, permission = null, gate = null, threadId = roomId,
            suppress = null, fallbackTitle = null, fallbackBody = null, activity = null,
        )
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never: $what" }
            Thread.sleep(50)
        }
    }

    /** Posts a note for [roomId] as the signed-in account, and hands back its tap. */
    private fun postedTap(roomId: String, tag: String): android.app.PendingIntent {
        AndroidPoster(context, accountId = "@me:x").post(
            PlannedNotification(
                id = tag, roomId = roomId, eventId = tag, title = "Lyra", subtitle = null,
                body = "hello", category = NotificationCategory.MESSAGE, permission = null, gate = null,
            ),
        )
        // The system takes a post asynchronously.
        waitUntil("the note appears") { manager.activeNotifications.any { it.tag == tag } }
        return manager.activeNotifications.single { it.tag == tag }.notification.contentIntent
    }

    /** The real MainActivity over a signed-in fake core, with the roster read. */
    private fun signedInApp(): Triple<MainActivity, SessionViewModel, SignedIn> {
        grantAndClear()
        val core = SignedIn()
        AppCore.coordinator = null
        AppCore.installForTest(core)
        AppCore.installRegistrarForTest(PushRegistrar(core = { core }, unregisterDistributor = {}, probe = { null }))
        val activity = launch()
        val model = modelOf(activity)
        waitUntil("the roster is read") { model.session.rooms.loaded.value }
        return Triple(activity, model, core)
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * The tap reaches the one running MainActivity (singleTask, `onNewIntent`) and opens its room,
     * over a signed-in fake core: the whole path from the PendingIntent the poster builds.
     */
    @Test
    fun aTapDeliveredToTheRunningActivityOpensItsRoom() {
        val (first, vm, core) = signedInApp()
        assertNull("nothing open yet", vm.session.rooms.selectedId.value)

        postedTap("!q:x", "\$tapq").send()
        // Through the compose rule: it waits for the real Activity's composition to settle.
        compose.waitUntil(10_000) {
            vm.session.rooms.selectedId.value == "!q:x" && "!q:x" in core.subscribedNow()
        }
        compose.waitUntil(5_000) { exists("room-header") }
        assertEquals("one MainActivity, the first", listOf(first), liveMainActivities())
        instrumentation.runOnMainSync { assertEquals("!q:x", linkedRoom(first.intent)) }
        assertNull("the request was consumed", vm.requests.pending.value)
    }

    /** A tap for a room the roster does not list behaves as M5's link does: the open room stays. */
    @Test
    fun aTapForAnUnlistedRoomLeavesTheOpenRoomAlone() {
        val (_, vm, core) = signedInApp()
        postedTap("!q:x", "\$tapq").send()
        compose.waitUntil(10_000) { vm.session.rooms.selectedId.value == "!q:x" && "!q:x" in core.subscribedNow() }

        postedTap("!nope:x", "\$tapnope").send()
        compose.waitUntil(5_000) { exists("room-unavailable-ok") }
        compose.onNodeWithText("Room unavailable").assertIsDisplayed()
        Thread.sleep(300)
        assertEquals("still selected", "!q:x", vm.session.rooms.selectedId.value)
        assertEquals("still the timeline's room", "!q:x", vm.session.timeline.roomId.value)
        assertEquals("nothing else subscribed", listOf("!q:x"), core.subscribedNow())
    }
}
