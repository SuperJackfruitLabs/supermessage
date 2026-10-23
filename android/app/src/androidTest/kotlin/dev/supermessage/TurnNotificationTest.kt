package dev.supermessage

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.kit.StubCore
import dev.supermessage.kit.notify.NotificationCoordinator
import dev.supermessage.notify.AndroidPoster
import dev.supermessage.notify.NotificationFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.Counterpart
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_core.TurnEnding
import uniffi.supermessage_core.TurnUpdate
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.FfiEvent
import uniffi.supermessage_ffi.RoomsSnapshot

/**
 * The running turn's ongoing notification in the real shade, fed by a [StubCore]-backed session's
 * turn events. **Nothing here touches a real account.**
 */
@RunWith(AndroidJUnit4::class)
class TurnNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private class FakeCore : StubCore() {
        @Volatile var sink: EventSink? = null
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun restoreSession(sink: EventSink): Boolean = true.also { this.sink = sink }
        override fun roomsSnapshot() = RoomsSnapshot(seq = 1uL, rooms = listOf(NavRows.row("!a:x", "Ops room", unread = 0, preview = "")))
        override fun spacesList() = emptyList<SpaceSummary>()
        override fun account() = AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        override fun knownPeople() = emptyList<PersonDto>()
        override fun roomInfo(roomId: String) = throw IllegalStateException("offline")
        override fun roomCast(roomId: String) = RoomCast(
            roomId = roomId,
            people = listOf(PersonDto("@lyra:x", "Lyra", "L", null, null), PersonDto("@atlas:x", "Atlas", "A", null, null)),
            counterpart = Counterpart("@atlas:x", "Atlas"),
            agentIds = listOf("@lyra:x", "@atlas:x"),
            addressee = null,
            mentionables = emptyList(),
        )
        fun event(e: FfiEvent) = sink!!.onEvent(e)
    }

    private val core = FakeCore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = SessionViewModel.forTest(core, scope).session
    private val poster = AndroidPoster(context)
    private val coordinator = NotificationCoordinator(poster, session::notificationMode, System::currentTimeMillis)
    private val feed = NotificationFeed(session, coordinator, poster, context, scope, pushSignedOut = {})

    @Before
    fun start() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        runBlocking { session.start() }
        feed.start()
        // The feed's collectors subscribe on another thread; an event sent before is not replayed.
        Thread.sleep(500)
    }

    @After
    fun stop() {
        feed.stop()
        scope.cancel()
        manager.cancelAll()
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { what }
            Thread.sleep(50)
        }
    }

    /** Recent: the system ignores a notification whose time is long past. */
    private val startedAt = System.currentTimeMillis() - 90_000

    private fun update(revision: ULong, running: Boolean, step: String = "Reading the logs", agent: String? = "@lyra:x") =
        TurnUpdate(
            uniffi.supermessage_core.TurnSummary(
                roomId = "!a:x", turnId = 1uL, revision = revision, inProgress = running,
                endedBy = if (running) null else TurnEnding.REPLY,
                startedAtMs = startedAt.toULong(), endedAtMs = if (running) null else (startedAt + 60_000).toULong(),
                lastActivityMs = startedAt.toULong(), answering = false, hasThought = false,
                totalSteps = 4u, completedSteps = 2u, failedStep = null, currentStepTitle = step,
                stepIsTool = false, recordLabel = null, agentId = agent,
            ),
            null,
        )

    private fun turnNotes() = manager.activeNotifications.filter { it.tag?.startsWith("turn:") == true }

    @Test
    fun aFakeTurnShowsAndEndsInTheShade() {
        core.event(FfiEvent.Turn(update(1uL, running = true)))
        waitUntil("an ongoing notification") { turnNotes().size == 1 }
        val shown = turnNotes().single().notification
        assertTrue("ongoing", shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Reading the logs", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Lyra · step 2 of 4", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("agent-activity", shown.channelId)
        assertEquals(startedAt, shown.`when`)
        assertTrue("a chronometer", shown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))

        // The same words again do not repost; a new step changes it in place.
        core.event(FfiEvent.Turn(update(2uL, running = true, step = "Writing the fix")))
        waitUntil("the step changed in place") {
            turnNotes().singleOrNull()?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Writing the fix"
        }

        // It ends: the ongoing notification goes, and no "finished" note takes its place.
        core.event(FfiEvent.Turn(update(3uL, running = false)))
        waitUntil("the notification left") { turnNotes().isEmpty() }
        Thread.sleep(500)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun theRoomInFrontHasNoNotificationUntilTheReaderLooksAway() {
        coordinator.openRoomChanged("!a:x")
        coordinator.activeChanged(true)
        core.event(FfiEvent.Turn(update(1uL, running = true)))
        Thread.sleep(800)
        assertEquals("the in-room card is the turn", 0, turnNotes().size)

        coordinator.activeChanged(false)
        waitUntil("it shows once the app is away") { turnNotes().size == 1 }
        coordinator.activeChanged(true)
        waitUntil("and goes again when the reader returns to that room") { turnNotes().isEmpty() }
    }
}
