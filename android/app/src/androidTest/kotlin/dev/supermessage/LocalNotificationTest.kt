package dev.supermessage

import android.Manifest
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot

@RunWith(AndroidJUnit4::class)
class LocalNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    private class FakeCore : StubCore() {
        @Volatile var rows: List<RoomRow> = emptyList()
        private var seq = 0uL
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun restoreSession(sink: EventSink): Boolean = true
        override fun roomsSnapshot(): RoomsSnapshot = RoomsSnapshot(seq = ++seq, rooms = rows)
        override fun spacesList() = emptyList<SpaceSummary>()
        override fun account(): AccountDto = AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        override fun knownPeople(): List<PersonDto> = emptyList()
        override fun roomInfo(roomId: String) = throw IllegalStateException("offline")
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { what }
            Thread.sleep(50)
        }
    }

    @Test
    fun aRaisedUnreadInAnotherRoomPostsANotification() {
        val core = FakeCore()
        core.rows = listOf(NavRows.row("!a:x", "Atlas", unread = 0, preview = "quiet"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = SessionViewModel.forTest(core, scope).session
        val poster = AndroidPoster(context)
        val feed = NotificationFeed(
            session, NotificationCoordinator(poster, session::notificationMode, System::currentTimeMillis),
            poster, context, scope,
        )
        try {
            runBlocking { session.start() }
            feed.start()
            // The first roster is a baseline: nothing posts for it.
            Thread.sleep(800)
            assertEquals(0, manager.activeNotifications.size)

            core.rows = listOf(NavRows.row("!a:x", "Atlas", unread = 2, preview = "ship it"))
            runBlocking { session.rooms.seed() }

            waitUntil("one notification posted") { manager.activeNotifications.size == 1 }
            val shown = manager.activeNotifications.single().notification
            assertEquals("Atlas", shown.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            assertEquals("ship it", shown.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        } finally {
            feed.stop()
            scope.cancel()
        }
    }
}
