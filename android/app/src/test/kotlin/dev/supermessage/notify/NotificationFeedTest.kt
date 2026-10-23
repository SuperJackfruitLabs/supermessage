package dev.supermessage.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.SessionViewModel
import dev.supermessage.kit.StubCore
import dev.supermessage.kit.notify.NotificationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
