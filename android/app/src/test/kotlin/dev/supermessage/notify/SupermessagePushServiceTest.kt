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
        val userId: String = ME,
    ) : StubCore() {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun restoreSessionQuietly(): Boolean {
            calls += "restore"
            return restores
        }
        override fun account(): AccountDto = AccountDto(userId = userId, homeserver = "https://x")
        override fun notificationFor(roomId: String, eventId: String): NotificationDto {
            calls += "notificationFor $roomId $eventId"
            return decided ?: throw IllegalStateException("could not fetch")
        }
    }

    /** What the widgets' writer was asked to merge; the real one writes a file. */
    private class Widgets(val fails: Boolean = false) : dev.supermessage.kit.widgets.WidgetWriting {
        val notes: MutableList<NotificationDto> = Collections.synchronizedList(mutableListOf())
        override suspend fun apply(note: NotificationDto, stillFor: suspend () -> Boolean): Boolean {
            if (fails) throw java.io.IOException("disk full")
            if (!stillFor()) return false
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

    /** What every push decided was merged into: no test writes the real snapshot file unless it means to. */
    private val merged = Widgets()

    @Before
    fun noRealWidgets() {
        installWidgets(merged)
    }

    /** The pusher's account, on disk, as the registrar stamps it: what each push is checked against. */
    private val owner = AppCore.pushOwner(app)

    @Before
    fun signedInAsMe() {
        owner.account = ME
    }

    @After
    fun forget() {
        AppCore.installForTest(null)
        AppCore.installWidgetsForTest(null)
        AppCore.coordinator = null
        owner.account = null
        manager.cancelAll()
    }

    private fun push(room: String = "!r:x", event: String = "\$e1") =
        """{"notification":{"event_id":"$event","room_id":"$room","counts":{"unread":2}}}""".toByteArray()

    private fun parsed(room: String = "!r:x", event: String = "\$e1") = MatrixPush(room, event, unread = null)

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
     * Delivers a push the way the job does: the worker's own `doWork`, built by
     * WorkManager's test helper (no scheduler involved, so nothing can wait on
     * one), with the account it was stamped with when it came in. The
     * service's hand-off is tested separately below.
     */
    private fun deliver(core: Recording, push: MatrixPush = parsed(), stampedFor: String? = ME) {
        AppCore.installForTest(core)
        val worker = TestListenableWorkerBuilder<PushWorker>(app)
            .setInputData(PushWorker.input(push, stampedFor))
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
        assertTrue("tagged, so a sign-out cancels it", PushWorker.TAG in infos.single().tags)
    }

    /**
     * A body over WorkManager's 10 KB `Data` limit used to throw out of
     * `onMessage` and take the app down. Only the ids go to the job now, so a
     * padded body is still a push; an id longer than Matrix allows is none.
     */
    @Test
    fun aBodyOverTenKilobytesNeverCrashesAndStillReachesTheJob() {
        val held = java.util.ArrayDeque<Runnable>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor { held.add(it) }.setTaskExecutor(SynchronousExecutor()).build(),
        )
        AppCore.installForTest(Recording(decided()))
        val service = Robolectric.buildService(SupermessagePushService::class.java).create().get()
        val padding = "x".repeat(12_000)
        val padded = """{"notification":{"event_id":"${'$'}big","room_id":"!r:x","pad":"$padding"}}""".toByteArray()

        service.onMessage(PushMessage(padded, false), INSTANCE_DEFAULT)

        val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork("push:!r:x:\$big").get()
        assertEquals("the push is queued, not lost", 1, infos.size)

        val longRoom = "!" + "r".repeat(MatrixPush.MAX_ID_LENGTH) + ":x"
        val tooLong = """{"notification":{"event_id":"${'$'}long","room_id":"$longRoom"}}""".toByteArray()
        service.onMessage(PushMessage(tooLong, false), INSTANCE_DEFAULT)
        assertEquals(
            "an id longer than Matrix allows is no push",
            0,
            WorkManager.getInstance(app).getWorkInfosForUniqueWork("push:$longRoom:\$long").get().size,
        )
        assertEquals(null, shown("\$long"))
    }

    @Test
    fun theWorkerDoesTheFetchAndThePost() {
        val core = Recording(decided())
        AppCore.installForTest(core)
        val worker = TestListenableWorkerBuilder<PushWorker>(app)
            .setInputData(PushWorker.input(parsed(), ME))
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
        assertEquals("the widgets merge what the core decided", listOf(decided()), merged.notes.toList())
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
        assertEquals("still decided, for the widgets", 1, merged.notes.size)
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
    }

    /**
     * I1: a push queued for this account that runs after sign-out (WorkManager
     * re-runs work after process death, and out-of-quota work can wait out
     * Doze) posts nothing, not even "New message": no one is signed in to tell.
     */
    @Test
    fun aQueuedPushThatRunsAfterSignOutPostsNothing() {
        owner.account = null // the sign-out cleared it
        val signedOut = Recording(decided(), restores = false)
        deliver(signedOut, stampedFor = ME)
        assertEquals("no session: nothing to fetch with", listOf("restore"), signedOut.calls.toList())
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)

        // A session that ended with no sign-out this process saw (revoked
        // while the app was dead): the owner is still stamped, but nothing
        // restores, so there is still no one to tell.
        owner.account = ME
        val revoked = Recording(decided(), restores = false)
        deliver(revoked, stampedFor = ME)
        assertEquals(listOf("restore"), revoked.calls.toList())
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
    }

    /** I1: A's push, run once B has signed in, posts nothing: not B's note, not "New message" under B. */
    @Test
    fun aPushForAccountAPostsNothingOnceBIsSignedIn() {
        owner.account = "@b:x" // B registered its own pusher
        val asB = Recording(decided(), userId = "@b:x")
        deliver(asB, stampedFor = "@a:x")
        assertEquals("A's push is not fetched as B", listOf("restore"), asB.calls.toList())
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
        assertEquals("nor handed to the widgets", 0, merged.notes.size)

        // Nor when what was asked for cannot be had: that was B's generic note before.
        val asBUnfetchable = Recording(decided = null, userId = "@b:x")
        deliver(asBUnfetchable, stampedFor = "@a:x")
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
    }

    /** A push that came when no account had a pusher is no one's. */
    @Test
    fun aPushStampedForNoAccountPostsNothingAndTouchesNoCore() {
        val core = Recording(decided())
        deliver(core, stampedFor = null)
        assertEquals(emptyList<String>(), core.calls.toList())
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
    }

    /** I1: signed out while the push was being worked: the sign-out withdrew everything, and this stays down. */
    @Test(timeout = 15_000)
    fun aPushUnderWayWhenSignOutLandsPostsNothing() {
        val gate = CountDownLatch(1)
        val asking = CountDownLatch(1)
        val core = object : Recording(decided()) {
            override fun notificationFor(roomId: String, eventId: String): NotificationDto {
                asking.countDown()
                gate.await()
                return super.notificationFor(roomId, eventId)
            }
        }
        AppCore.installForTest(core)
        try {
            val job = deliverPush(app, parsed(), owner = ME)
            assertTrue("the core was asked", asking.await(5, java.util.concurrent.TimeUnit.SECONDS))
            owner.account = null // sign-out
            gate.countDown()
            runBlocking { job.join() }
            assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
        } finally {
            gate.countDown()
        }
    }

    /**
     * Holds `notificationFor` until [release]d, so a sign-out can land while the push is fetched;
     * [who] is the session's account as the core reports it, which a sign-out ends.
     */
    private class Held(decided: NotificationDto) : Recording(decided) {
        val asking = CountDownLatch(1)
        private val gate = CountDownLatch(1)
        @Volatile var who: String? = ME
        override fun account(): AccountDto = who?.let { AccountDto(userId = it, homeserver = "https://x") }
            ?: throw IllegalStateException("signed out")
        override fun notificationFor(roomId: String, eventId: String): NotificationDto {
            asking.countDown()
            gate.await()
            return super.notificationFor(roomId, eventId)
        }
        fun release() = gate.countDown()
    }

    /** The real feed over memory, so the snapshot itself is what is checked. */
    private fun realWidgets(): dev.supermessage.kit.widgets.WidgetFeed {
        val feed = dev.supermessage.kit.widgets.WidgetFeed(dev.supermessage.kit.widgets.WidgetFixtures.Memory())
        AppCore.installWidgetsForTest(
            feed,
            object : dev.supermessage.widgets.WidgetRedraw(app, nextChangeMs = { null }, widgets = { emptyList() }) {
                override suspend fun after(reload: Boolean) {}
            },
        )
        return feed
    }

    /**
     * I2: signed out while the push was fetched. The sign-out reset the snapshot; the push's
     * merge (which marks the snapshot signed in) must not put the last account's decision back.
     */
    @Test(timeout = 15_000)
    fun aPushDecidedAcrossASignOutLeavesTheResetSnapshotAlone() {
        val feed = realWidgets()
        val core = Held(dev.supermessage.kit.widgets.WidgetFixtures.gate())
        AppCore.installForTest(core)
        try {
            val job = deliverPush(app, parsed(), owner = ME)
            assertTrue("the core was asked", core.asking.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // The sign-out: the session ends, its hook clears the owner, the publisher resets the snapshot.
            core.who = null
            owner.account = null
            runBlocking { feed.signedOut() }
            core.release()
            runBlocking { job.join() }
            assertEquals(uniffi.supermessage_core.WidgetContent.SIGNED_OUT, feed.view().content)
            assertTrue("no decision of the last account's", feed.view().decisions.isEmpty())
        } finally {
            core.release()
        }
    }

    /** I2: the next account signed in meanwhile: A's decision is not merged into B's snapshot. */
    @Test(timeout = 15_000)
    fun aPushDecidedWhileTheNextAccountSignedInIsNotMerged() {
        val feed = realWidgets()
        val core = Held(dev.supermessage.kit.widgets.WidgetFixtures.gate())
        AppCore.installForTest(core)
        try {
            val job = deliverPush(app, parsed(), owner = ME)
            assertTrue(core.asking.await(5, java.util.concurrent.TimeUnit.SECONDS))
            core.who = "@b:x"
            owner.account = "@b:x"
            runBlocking { feed.signedOut() }
            core.release()
            runBlocking { job.join() }
            assertTrue(feed.view().decisions.isEmpty())
        } finally {
            core.release()
        }
    }

    /**
     * I2: the session ended before its hook cleared the owner (the phase changes first, the
     * hook runs after it): the session itself is asked too, under the same lock.
     */
    @Test(timeout = 15_000)
    fun aPushWhoseSessionEndedBeforeTheOwnerWasClearedIsNotMerged() {
        val feed = realWidgets()
        val core = Held(dev.supermessage.kit.widgets.WidgetFixtures.gate())
        AppCore.installForTest(core)
        try {
            val job = deliverPush(app, parsed(), owner = ME)
            assertTrue(core.asking.await(5, java.util.concurrent.TimeUnit.SECONDS))
            core.who = null // logged out; the owner is still stamped
            runBlocking { feed.signedOut() }
            core.release()
            runBlocking { job.join() }
            assertEquals(uniffi.supermessage_core.WidgetContent.SIGNED_OUT, feed.view().content)
        } finally {
            core.release()
        }
    }

    /** Still signed in as the push's account: the decision is merged, as before. */
    @Test(timeout = 15_000)
    fun aPushForTheAccountStillSignedInIsMerged() {
        val feed = realWidgets()
        val core = Held(dev.supermessage.kit.widgets.WidgetFixtures.gate())
        AppCore.installForTest(core)
        core.release()
        runBlocking { deliverPush(app, parsed(), owner = ME).join() }
        assertEquals(1, feed.view().decisions.size)
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
        try {
            val began = System.currentTimeMillis()
            runBlocking { deliverPush(app, parsed(), owner = ME, budgetMs = 300).join() }
            assertTrue("gave up inside the budget", System.currentTimeMillis() - began < 5_000)
            assertEquals("New message", text(shown()!!, Notification.EXTRA_TEXT))
        } finally {
            gate.countDown()
        }
    }

    /** Past the budget before the account was known: there is no account to say "New message" for. */
    @Test(timeout = 15_000)
    fun aPushThatTakesTooLongBeforeItsAccountIsKnownPostsNothing() {
        val gate = CountDownLatch(1)
        val core = object : Recording(decided()) {
            override fun restoreSessionQuietly(): Boolean {
                gate.await()
                return super.restoreSessionQuietly()
            }
        }
        AppCore.installForTest(core)
        try {
            runBlocking { deliverPush(app, parsed(), owner = ME, budgetMs = 300).join() }
            assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun anythingButAMatrixPushPostsNothing() {
        AppCore.installForTest(Recording(decided()))
        enqueuePush(app, "hello".toByteArray())
        assertEquals(emptyList<Notification>(), shadowOf(manager).allNotifications)
    }


    private companion object {
        const val ME = "@me:x"
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
