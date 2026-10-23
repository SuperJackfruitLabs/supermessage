package dev.supermessage.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.AppCore
import dev.supermessage.kit.StubCore
import dev.supermessage.kit.notify.NotificationCoordinator
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.data.PushMessage
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.GateAnswers
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.NotificationCategory
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.NotificationSuppression
import uniffi.supermessage_core.TimelineItemDto
import uniffi.supermessage_core.TimelineRow

/**
 * A push into a process with no Activity: fetched, decrypted and worded by the
 * core (`notificationFor`), then posted, dropped, or said generically
 * (`NotificationService.swift:104-116, 150-181`). `onMessage` is called
 * directly on a Robolectric-built service; the core is a recording fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SupermessagePushServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private open class Recording(
        val decided: NotificationDto? = null,
        val restores: Boolean = true,
    ) : StubCore() {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun restoreSessionQuietly(): Boolean {
            calls += "restore"
            return restores
        }
        override fun account(): AccountDto = AccountDto(userId = "@me:x", homeserver = "https://x")
        override fun notificationFor(roomId: String, eventId: String): NotificationDto {
            calls += "notificationFor $roomId $eventId"
            return decided ?: throw IllegalStateException("could not fetch")
        }
    }

    /** What the widgets' writer was asked to merge; the real one writes a file. */
    private class Widgets(val fails: Boolean = false) : dev.supermessage.kit.widgets.WidgetWriting {
        val notes: MutableList<NotificationDto> = Collections.synchronizedList(mutableListOf())
        override suspend fun apply(note: NotificationDto): Boolean {
            if (fails) throw java.io.IOException("disk full")
            notes += note
            return true
        }
        override suspend fun apply(rows: List<uniffi.supermessage_core.RoomRow>, live: List<uniffi.supermessage_core.WidgetLiveTurn>, asOfMs: Long) = false
        override suspend fun apply(roomId: String, rows: List<TimelineRow>) = false
        override suspend fun markAnswered(roomId: String, eventId: String, optionId: String) = false
        override suspend fun clearAnswer(roomId: String, eventId: String) = false
        override suspend fun opened() = false
        override suspend fun signedOut() = false
        override fun holdsDecision(roomId: String) = false
    }

    private fun installWidgets(widgets: Widgets): MutableList<Boolean> {
        val redraws = Collections.synchronizedList(mutableListOf<Boolean>())
        AppCore.installWidgetsForTest(
            widgets,
            object : dev.supermessage.widgets.WidgetRedraw(app, nextChangeMs = { null }, widgets = { emptyList() }) {
                override suspend fun after(reload: Boolean) { redraws += reload }
            },
        )
        return redraws
    }

    private val decidedSeen = Collections.synchronizedList(mutableListOf<NotificationDto>())

    /** No test writes the real snapshot file unless it means to. */
    @Before
    fun noRealWidgets() {
        installWidgets(Widgets())
    }

    @After
    fun forget() {
        AppCore.installForTest(null)
        AppCore.installWidgetsForTest(null)
        AppCore.coordinator = null
        SupermessagePushService.onDecided = {}
        manager.cancelAll()
    }

    private fun push(room: String = "!r:x", event: String = "\$e1") =
        """{"notification":{"event_id":"$event","room_id":"$room","counts":{"unread":2}}}""".toByteArray()

    private fun decided(
        category: NotificationCategory = NotificationCategory.MESSAGE,
        suppress: NotificationSuppression? = null,
    ) = NotificationDto(
        roomId = "!r:x", eventId = "\$e1", title = "Lyra", subtitle = "Ops", body = "ship it",
        category = category, permission = null,
        gate = if (category == NotificationCategory.GATE) GateAnswers("g1", "Ship it?", listOf("approve", "request_changes", "reject")) else null,
        threadId = "!r:x", suppress = suppress,
        fallbackTitle = if (suppress != null) "Ops" else null,
        fallbackBody = if (suppress != null) "Krishna reacted ✅ to a message" else null,
        activity = null,
    )

    /** Builds the service, delivers [bytes] through `onMessage`, and waits for its work. */
    private fun deliver(core: Recording, bytes: ByteArray = push()) {
        AppCore.installForTest(core)
        SupermessagePushService.onDecided = { decidedSeen += it }
        val service = Robolectric.buildService(SupermessagePushService::class.java).create().get()
        service.onMessage(PushMessage(bytes, false), INSTANCE_DEFAULT)
        runBlocking { checkNotNull(service.lastWork) { "onMessage started no work" }.join() }
    }

    private fun shown(tag: String = "\$e1"): Notification? = shadowOf(manager).getNotification(tag, 1)

    private fun text(n: Notification, key: String) = n.extras.getCharSequence(key)?.toString()

    @Test
    fun aPushIsFetchedDecidedAndPosted() {
        deliver(Recording(decided()))
        val note = checkNotNull(shown()) { "posted under the event id, the local note's tag too" }
        assertEquals("Lyra", text(note, Notification.EXTRA_TITLE))
        assertEquals("ship it", text(note, Notification.EXTRA_TEXT))
        assertEquals("Ops", text(note, Notification.EXTRA_SUB_TEXT))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("the hook sees what the core decided", listOf(decided()), decidedSeen.toList())
    }

    /** `NotificationService.swift:118-125`: each decided push is merged, and the core's `reload` decides the redraw. */
    @Test
    fun aDecidedPushIsMergedIntoTheWidgets() {
        val widgets = Widgets()
        val redraws = installWidgets(widgets)
        deliver(Recording(decided(NotificationCategory.GATE)))
        assertEquals(listOf(decided(NotificationCategory.GATE)), widgets.notes.toList())
        assertEquals("the redraw follows the merge's reload", listOf(true), redraws.toList())
    }

    @Test
    fun aSuppressedPushIsStillMergedIntoTheWidgets() {
        val widgets = Widgets()
        installWidgets(widgets)
        deliver(Recording(decided(suppress = NotificationSuppression.REACTION)))
        assertEquals(1, widgets.notes.size)
    }

    @Test
    fun aFailedWidgetWriteNeverStopsThePushBeingShown() {
        installWidgets(Widgets(fails = true))
        deliver(Recording(decided()))
        assertEquals("Lyra", text(checkNotNull(shown()), Notification.EXTRA_TITLE))
    }

    @Test
    fun aPushCarriesTheAccountItWasDecidedFor() {
        deliver(Recording(decided(NotificationCategory.GATE)))
        val note = shown()!!
        assertEquals(listOf("Approve", "Request changes", "Reject"), note.actions.map { it.title.toString() })
        for (action in note.actions) {
            assertEquals("@me:x", shadowOf(action.actionIntent).savedIntent.getStringExtra(AndroidPoster.EXTRA_ACCOUNT))
        }
    }

    /** Android may always post nothing: iOS's `canFilter` branch (`NotificationComposer.swift:344-347`). */
    @Test
    fun aSuppressedPushPostsNothing() {
        deliver(Recording(decided(suppress = NotificationSuppression.REACTION)))
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
        assertEquals("still decided, for the widgets", 1, decidedSeen.size)
    }

    /** `AppDelegate.swift:95-108`: not for the room on screen in the foreground. */
    @Test
    fun aPushForTheOpenRoomInFrontPostsNothing() {
        AppCore.coordinator = NotificationCoordinator(poster = {}, modeOf = { null }, now = { 0L }).apply {
            activeChanged(true)
            openRoomChanged("!r:x")
        }
        deliver(Recording(decided()))
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
    }

    /** RF focus 4: an event the app already posted locally is not shown again. */
    @Test
    fun aPushAlreadyPostedLocallyIsNotShownAgain() {
        val local = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = NotificationCoordinator(poster = { local += it.id }, modeOf = { null }, now = { 0L })
        AppCore.coordinator = coordinator
        runBlocking { coordinator.timelineChanged("!r:x", "Ops", listOf(message("\$e1", at = 5))) }
        assertEquals("posted locally first", listOf("\$e1"), local.toList())
        assertTrue("\$e1" in coordinator.notified)

        deliver(Recording(decided()))
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
    }

    @Test
    fun aPushThatCannotBeFetchedSaysNewMessageAndOpensItsRoom() {
        deliver(Recording(decided = null))
        val note = checkNotNull(shown()) { "a generic note is posted" }
        assertEquals("supermessage", text(note, Notification.EXTRA_TITLE))
        assertEquals("New message", text(note, Notification.EXTRA_TEXT))
        assertEquals("supermessage://open?room=%21r%3Ax", shadowOf(note.contentIntent).savedIntent.data.toString())
        assertEquals("nothing to answer, nothing offered", 0, note.actions?.size ?: 0)
    }

    @Test
    fun aPushRestoresQuietlyFirst() {
        val core = Recording(decided())
        deliver(core)
        assertEquals(listOf("restore", "notificationFor !r:x \$e1"), core.calls.toList())

        manager.cancelAll()
        val signedOut = Recording(decided(), restores = false)
        deliver(signedOut)
        assertEquals("no session: nothing to fetch with", listOf("restore"), signedOut.calls.toList())
        assertEquals("New message", text(shown()!!, Notification.EXTRA_TEXT))
    }

    @Test(timeout = 15_000)
    fun aPushThatTakesTooLongSaysNewMessage() {
        val gate = CountDownLatch(1)
        val core = object : Recording(decided()) {
            override fun notificationFor(roomId: String, eventId: String): NotificationDto {
                gate.await()
                return super.notificationFor(roomId, eventId)
            }
        }
        AppCore.installForTest(core)
        val began = System.currentTimeMillis()
        runBlocking { deliverPush(app, push(), budgetMs = 300).join() }
        assertTrue("gave up inside the budget", System.currentTimeMillis() - began < 5_000)
        assertEquals("New message", text(shown()!!, Notification.EXTRA_TEXT))
        gate.countDown()
    }

    @Test
    fun anythingButAMatrixPushPostsNothing() {
        deliver(Recording(decided()), "hello".toByteArray())
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
    }

    private fun message(eventId: String, at: Long) = TimelineRow(
        item = TimelineItemDto(
            id = eventId, eventId = eventId, kind = "message", msgtype = "m.text", detail = null,
            sender = "@lyra:x", senderDisplayName = "Lyra", senderAvatar = null, body = "ship it",
            formattedBody = null, media = null, customPayload = null, timestampMs = at.toULong(),
            isOwn = false, sendState = null, replyTo = null, edited = false,
            reactions = emptyList(), readBy = emptyList(), editable = false, membershipSubject = null,
        ),
        view = ItemView.Bubble(muted = false, blocks = emptyList(), voice = null),
        senderName = "Lyra", senderShort = "Lyra", senderInitial = "L", membershipVerb = null,
        replyQuote = null, canReplyOrReact = true, replyPreview = "ship it",
    )
}
