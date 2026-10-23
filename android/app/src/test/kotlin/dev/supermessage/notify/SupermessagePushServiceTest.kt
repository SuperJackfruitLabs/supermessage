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
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
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

    private val decidedSeen = Collections.synchronizedList(mutableListOf<NotificationDto>())

    @After
    fun forget() {
        AppCore.installForTest(null)
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

    /**
     * Delivers [bytes] the way the job does: the worker's own `doWork`, built by
     * WorkManager's test helper (no scheduler involved, so nothing can wait on
     * one). The service's hand-off is tested separately below.
     */
    private fun deliver(core: Recording, bytes: ByteArray = push()) {
        AppCore.installForTest(core)
        SupermessagePushService.onDecided = { decidedSeen += it }
        val worker = TestListenableWorkerBuilder<PushWorker>(app)
            .setInputData(androidx.work.Data.Builder().putByteArray(PushWorker.KEY_PUSH, bytes).build())
            .build()
        runBlocking { worker.doWork() }
    }

    /** The connector binds the service for ~5 s: onMessage must hand off and return. */
    @Test
    fun theServiceOnlyEnqueuesExpeditedWorkAndReturnsAtOnce() {
        AppCore.installForTest(Recording(decided()))
        val held = java.util.ArrayDeque<Runnable>()
        val deferred = java.util.concurrent.Executor { held.add(it) }
        // Queries go through the task executor, so it must run; only the worker's own executor is held.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor(deferred).setTaskExecutor(SynchronousExecutor()).build(),
        )
        val core = Recording(decided())
        AppCore.installForTest(core)
        val service = Robolectric.buildService(SupermessagePushService::class.java).create().get()

        service.onMessage(PushMessage(push(), false), INSTANCE_DEFAULT)

        assertEquals("the core was not touched inside onMessage", emptyList<String>(), core.calls.toList())
        assertEquals(null, shown())
        val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork("push:!r:x:\$e1").get()
        assertEquals(1, infos.size)
        // Handed over, not done: the worker's executor is held, so it has not run.
        assertTrue(infos.single().state in setOf(androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.RUNNING))
    }

    @Test
    fun theWorkerDoesTheFetchAndThePost() {
        val core = Recording(decided())
        AppCore.installForTest(core)
        val worker = TestListenableWorkerBuilder<PushWorker>(app)
            .setInputData(androidx.work.Data.Builder().putByteArray(PushWorker.KEY_PUSH, push()).build())
            .build()

        val result = runBlocking { worker.doWork() }

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals(listOf("restore", "notificationFor !r:x \$e1"), core.calls.toList())
        assertEquals("Lyra", text(checkNotNull(shown()), Notification.EXTRA_TITLE))
    }

    @Test
    fun aWorkerWithNoPushFails() {
        val worker = TestListenableWorkerBuilder<PushWorker>(app).build()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), runBlocking { worker.doWork() })
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
