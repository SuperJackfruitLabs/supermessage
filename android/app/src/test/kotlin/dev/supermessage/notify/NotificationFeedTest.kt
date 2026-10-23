package dev.supermessage.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.supermessage.AppCore
import dev.supermessage.SessionViewModel
import dev.supermessage.kit.StubCore
import dev.supermessage.kit.notify.NotificationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot

/**
 * The feed's wiring that the device test does not reach: the account must be
 * on the poster before the first note goes up (Task 3's concern; Task 2
 * review, minor 3), or that note's buttons refuse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationFeedTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Answers who it is slowly, as a homeserver can, so a note can go up first. */
    private class SlowAccount : StubCore() {
        @Volatile var rows: List<RoomRow> = emptyList()
        private var seq = 0uL
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot(): RoomsSnapshot = RoomsSnapshot(seq = ++seq, rooms = rows)
        override fun spacesList() = emptyList<SpaceSummary>()
        override fun account(): AccountDto {
            Thread.sleep(1_500)
            return AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        }
        override fun knownPeople(): List<PersonDto> = emptyList()
        override fun roomInfo(roomId: String) = throw IllegalStateException("offline")
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { what }
            Thread.sleep(20)
        }
    }

    @Test(timeout = 30_000)
    fun theFirstNoteCarriesTheAccount() {
        val core = SlowAccount()
        core.rows = listOf(room(unread = 0, text = "quiet"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = SessionViewModel.forTest(core, scope).session
        val poster = AndroidPoster(app)
        val feed = NotificationFeed(
            session, NotificationCoordinator(poster, { null }, System::currentTimeMillis), poster, app, scope,
        )
        try {
            runBlocking { session.start() }
            feed.start()
            Thread.sleep(400) // the first roster, a baseline, has been seen
            core.rows = listOf(room(unread = 2, text = "ship it"))
            runBlocking { session.rooms.seed() }

            waitUntil("one notification posted") { shadowOf(manager).allNotifications.size == 1 }
            val shown = shadowOf(manager).allNotifications.single()
            assertEquals("ship it", shown.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            assertEquals(
                "the note's account, which its buttons check",
                "@me:example.org",
                shadowOf(shown.contentIntent).savedIntent.getStringExtra(AndroidPoster.EXTRA_ACCOUNT),
            )
        } finally {
            feed.stop()
            scope.cancel()
        }
    }

    /** Signs in at once and out cleanly. */
    private class SignsOut : StubCore() {
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot(): RoomsSnapshot = RoomsSnapshot(seq = 1uL, rooms = emptyList())
        override fun spacesList() = emptyList<SpaceSummary>()
        override fun account() = AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        override fun knownPeople(): List<PersonDto> = emptyList()
        override fun roomInfo(roomId: String) = throw IllegalStateException("offline")
        override fun logout() {}
        override fun restoreSessionQuietly(): Boolean = false
        override fun unregisterPusher() {}
    }

    /**
     * Signs in, starts the feed the way `SessionViewModel` does (no Activity,
     * no `PlatformCoordinator`), runs [body] with the session signed in, then
     * signs out.
     */
    private fun signedInFeed(body: (() -> Unit) -> Unit) {
        val core = SignsOut()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = SessionViewModel.forTest(core, scope).session
        val poster = AndroidPoster(app)
        val feed = NotificationFeed(
            session, NotificationCoordinator(poster, { null }, System::currentTimeMillis), poster, app, scope,
        )
        try {
            runBlocking { session.start() }
            feed.start()
            body { runBlocking { session.signOut() } }
        } finally {
            feed.stop()
            scope.cancel()
        }
    }

    private val owner = AppCore.pushOwner(app)

    @After
    fun forgetPush() {
        AppCore.installRegistrarForTest(null)
        owner.account = null
    }

    /**
     * I1(2), RF5: the session's own sign-out unregisters from the distributor
     * and clears the push owner, with no Activity to see it (one recreated by
     * a configuration change used to miss it, leaving the endpoint live).
     */
    @Test(timeout = 30_000)
    fun signOutUnregistersFromTheDistributorWithNoActivity() {
        var unregistered = 0
        AppCore.installRegistrarForTest(
            PushRegistrar(core = { SignsOut() }, unregisterDistributor = { unregistered++ }, owner = owner),
        )
        owner.account = "@me:example.org"
        signedInFeed { signOut ->
            assertEquals(0, unregistered)
            signOut()
            assertEquals("the distributor is told, once", 1, unregistered)
            assertNull("a push still on its way is no one's now", owner.account)
        }
    }

    /** I1(1): a push queued in WorkManager before sign-out never runs after it. */
    @Test(timeout = 30_000)
    fun signOutCancelsEveryQueuedPush() {
        val held = java.util.ArrayDeque<Runnable>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor { held.add(it) }.setTaskExecutor(SynchronousExecutor()).build(),
        )
        AppCore.installRegistrarForTest(PushRegistrar(core = { SignsOut() }, unregisterDistributor = {}, owner = owner))
        owner.account = "@me:example.org"
        signedInFeed { signOut ->
            enqueuePush(app, """{"notification":{"event_id":"${'$'}q","room_id":"!r:x"}}""".toByteArray())
            val work = WorkManager.getInstance(app)
            // Handed over, not done: the worker's executor is held.
            assertTrue(
                work.getWorkInfosForUniqueWork("push:!r:x:${'$'}q").get().single().state in
                    setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING),
            )
            signOut()
            assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfosForUniqueWork("push:!r:x:${'$'}q").get().single().state)
        }
    }

    /** Signs in and out like [SignsOut]; the room's cast is held until [castGiven], so a sign-out can land meanwhile. */
    private class SlowCast : StubCore() {
        @Volatile var sink: EventSink? = null
        val asked = java.util.concurrent.CountDownLatch(1)
        private val castGiven = java.util.concurrent.CountDownLatch(1)
        fun giveCast() = castGiven.countDown()
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun restoreSession(sink: EventSink): Boolean = true.also { this.sink = sink }
        override fun roomsSnapshot(): RoomsSnapshot = RoomsSnapshot(seq = 1uL, rooms = emptyList())
        override fun spacesList() = emptyList<SpaceSummary>()
        override fun account() = AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        override fun knownPeople(): List<PersonDto> = emptyList()
        override fun roomInfo(roomId: String) = throw IllegalStateException("offline")
        override fun logout() {}
        override fun restoreSessionQuietly(): Boolean = false
        override fun unregisterPusher() {}
        override fun roomCast(roomId: String): uniffi.supermessage_core.RoomCast {
            asked.countDown()
            castGiven.await()
            return uniffi.supermessage_core.RoomCast(
                roomId = roomId,
                people = listOf(PersonDto("@lyra:x", "Lyra", "L", null, null)),
                counterpart = null,
                agentIds = listOf("@lyra:x"),
                addressee = null,
                mentionables = emptyList(),
            )
        }
    }

    private fun running(room: String = "!a:x") = uniffi.supermessage_core.TurnUpdate(
        uniffi.supermessage_core.TurnSummary(
            roomId = room, turnId = 1uL, revision = 1uL, inProgress = true, endedBy = null,
            startedAtMs = (System.currentTimeMillis() - 60_000).toULong(), endedAtMs = null,
            lastActivityMs = System.currentTimeMillis().toULong(), answering = false, hasThought = false,
            totalSteps = 4u, completedSteps = 2u, failedStep = null, currentStepTitle = "Reading the logs",
            stepIsTool = false, recordLabel = null, agentId = "@lyra:x",
        ),
        null,
    )

    private fun turnCards() = manager.activeNotifications.filter { it.tag?.startsWith("turn:") == true }

    /**
     * I3: the first update of a turn waits on the room's cast; a sign-out in that gap withdrew
     * everything, and the resumed update must not put the last account's running card back.
     */
    @Test(timeout = 30_000)
    fun aTurnUpdateUnderWayAtSignOutPostsNoCardAfterIt() {
        val core = SlowCast()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = SessionViewModel.forTest(core, scope).session
        val poster = AndroidPoster(app)
        val feed = NotificationFeed(
            session, NotificationCoordinator(poster, { null }, System::currentTimeMillis), poster, app, scope,
            pushSignedOut = {},
        )
        try {
            runBlocking { session.start() }
            feed.start()
            // The feed subscribes on another thread and the flow does not replay: send until it is heard.
            val deadline = System.currentTimeMillis() + 15_000
            while (!core.asked.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                check(System.currentTimeMillis() < deadline) { "the feed never asked for the cast" }
                core.sink!!.onEvent(uniffi.supermessage_ffi.FfiEvent.Turn(running()))
            }
            runBlocking { session.signOut() }
            core.giveCast()
            Thread.sleep(500)
            assertEquals("no running card for the account that left", emptyList<String>(), turnCards().map { it.tag })
        } finally {
            core.giveCast()
            feed.stop()
            scope.cancel()
        }
    }

    /** The same turn with no sign-out does put its card up (the test above is not passing for want of one). */
    @Test(timeout = 30_000)
    fun aTurnUpdateWithNoSignOutPostsItsCard() {
        val core = SlowCast()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = SessionViewModel.forTest(core, scope).session
        val poster = AndroidPoster(app)
        val feed = NotificationFeed(
            session, NotificationCoordinator(poster, { null }, System::currentTimeMillis), poster, app, scope,
            pushSignedOut = {},
        )
        try {
            runBlocking { session.start() }
            feed.start()
            val deadline = System.currentTimeMillis() + 15_000
            while (!core.asked.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                check(System.currentTimeMillis() < deadline) { "the feed never asked for the cast" }
                core.sink!!.onEvent(uniffi.supermessage_ffi.FfiEvent.Turn(running()))
            }
            core.giveCast()
            waitUntil("the running card") { turnCards().size == 1 }
        } finally {
            core.giveCast()
            feed.stop()
            scope.cancel()
            manager.cancelAll()
        }
    }

    private fun room(unread: Long, text: String) = RoomRow(
        room = RoomSummary(
            id = "!a:x", name = "Atlas", avatarUrl = null, unread = unread.toULong(), lastMessage = text,
            lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
            lastActivityMs = 5uL + unread.toULong(), runtime = null, membership = Membership.JOINED,
        ),
        identity = RoomIdentity(glyph = null, name = "Atlas", role = null, initial = "A"),
        preview = RoomPreview(text = text, pending = false),
        affordance = RoomAffordance.COMPOSE,
    )
}
