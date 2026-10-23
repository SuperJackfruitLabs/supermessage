package dev.supermessage.widgets

import android.app.AlarmManager
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.supermessage.AppCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The provider owns the redraw schedule's lifetime: armed when a widget is placed and on
 * every system update (also sent after a reboot), cancelled when the last one goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class NeedsYouWidgetReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private var placed = true
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        AppCore.installWidgetsForTest(
            null,
            WidgetRedraw(app, nextChangeMs = { now + 60_000 }, now = { now }, widgets = { emptyList() }, placed = { placed }),
        )
    }

    @After
    fun forget() = AppCore.installWidgetsForTest(null)

    private fun frameAlarm() =
        alarms.scheduledAlarms.singleOrNull { shadowOf(it.operation).savedIntent.action == WidgetRedraw.FRAME }

    private fun backstop(): List<WorkInfo> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(WidgetRedraw.BACKSTOP_WORK).get().filter { !it.state.isFinished }

    private val receiver = NeedsYouWidgetReceiver()

    /**
     * Glance's `onUpdate` (and `onReceive`) call `goAsync()` and finish what it returns. A real broadcast
     * carries a PendingResult; a callback called straight from a test carries none, and Glance's
     * `finish()` then threw on a background thread, after the test, into whichever `runTest`
     * started next (`UncaughtExceptionsBeforeTest`). So each such call is handed one, as the system does.
     * (`goAsync` gives it out once, so our own `armOffMain` still finds none: the arm's WorkManager
     * hold. `onEnabled` and `onDisabled` take none from Glance, so those are called bare.)
     */
    private fun <R : android.content.BroadcastReceiver> R.inBroadcast(): R = apply {
        val create = org.robolectric.shadows.ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.javaPrimitiveType, String::class.java, android.os.Bundle::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        android.content.BroadcastReceiver::class.java
            .getDeclaredMethod("setPendingResult", android.content.BroadcastReceiver.PendingResult::class.java)
            .apply { isAccessible = true }
            .invoke(this, create.invoke(null, 0, null, null, false))
    }

    /** The receivers arm off the main thread: wait for what they set. */
    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "timed out waiting for: $what" }
            Thread.sleep(10)
        }
    }

    @Test(timeout = 30_000)
    fun placingTheFirstWidgetArms() {
        assertNull(frameAlarm())
        receiver.onEnabled(app)
        await("the frame alarm and the backstop") { frameAlarm() != null && backstop().size == 1 }
    }

    @Test(timeout = 30_000)
    fun theProvidersDoTheirReadsOffTheMainThread() {
        val realHold = armHold
        armHold = {}
        val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        AppCore.installWidgetsForTest(
            null,
            WidgetRedraw(app, nextChangeMs = { threads += Thread.currentThread(); now + 60_000 }, now = { now }, widgets = { emptyList() }, placed = { true }),
        )
        receiver.onEnabled(app)
        receiver.inBroadcast().onUpdate(app, AppWidgetManager.getInstance(app), intArrayOf(7))
        AgentsWidgetReceiver().inBroadcast().onUpdate(app, AppWidgetManager.getInstance(app), intArrayOf(8))
        await("three arms") { threads.size >= 3 }
        val main = android.os.Looper.getMainLooper().thread
        armHold = realHold
        assertTrue("arm read the core on the main thread", threads.none { it === main })
    }

    /** Glance may have taken the broadcast's PendingResult: then nothing holds the arm but WorkManager. */
    @Test(timeout = 30_000)
    fun anArmWithNoBroadcastToHoldGoesToWorkManager() {
        receiver.onEnabled(app)
        await("the arm job") {
            WorkManager.getInstance(app).getWorkInfosForUniqueWork(ARM_WORK).get().isNotEmpty()
        }
    }

    @Test(timeout = 30_000)
    fun removingOneProviderWhileTheOtherIsPlacedKeepsTheSchedule() {
        receiver.onEnabled(app)
        await("armed") { frameAlarm() != null && backstop().size == 1 }
        // The Agents widget is still on a home screen: `placed` stays true.
        AgentsWidgetReceiver().onDisabled(app)
        Thread.sleep(300)
        assertNotNull(frameAlarm())
        assertEquals(1, backstop().size)
    }

    @Test(timeout = 30_000)
    fun theUpdateAfterBootArmsAgain() {
        receiver.onEnabled(app)
        await("armed") { frameAlarm() != null }
        // A reboot dropped the alarm; the launcher's update brings it back.
        placed = true
        AppCore.installWidgetsForTest(
            null,
            WidgetRedraw(app, nextChangeMs = { null }, now = { now }, widgets = { emptyList() }, placed = { true }),
        )
        AppCore.widgetRedraw(app).arm()
        assertNull(frameAlarm())
        // Not armed again by the redraw: a stub that says there is no next frame cleared it.
        AppCore.installWidgetsForTest(
            null,
            WidgetRedraw(app, nextChangeMs = { now + 60_000 }, now = { now }, widgets = { emptyList() }, placed = { true }),
        )
        // The launcher's (and the system's, after boot) own broadcast, through the provider's
        // onReceive. A real one carries a PendingResult for goAsync(); Robolectric's delivered
        // broadcast carries none, and Glance's finish() then threw on a background thread, after
        // this test, into whichever runTest started next. So hand it one, as the system does.
        val update = NeedsYouWidgetReceiver().inBroadcast()
        update.onReceive(
            app,
            android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setClass(app, NeedsYouWidgetReceiver::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(7)),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        await("armed again by the update") { frameAlarm() != null }
        assertEquals("the queued work stands", 1, backstop().size)
    }

    @Test(timeout = 30_000)
    fun removingTheLastWidgetCancelsEverything() {
        receiver.onEnabled(app)
        await("armed") { frameAlarm() != null && backstop().isNotEmpty() }
        placed = false
        receiver.onDisabled(app)
        await("cancelled") { frameAlarm() == null && backstop().isEmpty() }
    }

    @Test(timeout = 30_000)
    fun theProviderIsDeclaredWithTheWidgetsLabelAndDescription() {
        val info = app.packageManager.getReceiverInfo(
            android.content.ComponentName(app, NeedsYouWidgetReceiver::class.java), android.content.pm.PackageManager.GET_META_DATA,
        )
        assertTrue(info.exported)
        assertEquals("Needs you", app.getString(dev.supermessage.R.string.needs_you_widget_label))
        assertEquals(WidgetCopy.NAME, app.getString(dev.supermessage.R.string.needs_you_widget_label))
        assertEquals(WidgetCopy.DESCRIPTION, app.getString(dev.supermessage.R.string.needs_you_widget_description))
        assertFalse(WidgetRedraw.anyWidgetPlaced(app))
    }

    @Test(timeout = 30_000)
    fun theAgentsProviderIsDeclaredWithItsLabelAndDescription() {
        val info = app.packageManager.getReceiverInfo(
            android.content.ComponentName(app, AgentsWidgetReceiver::class.java), android.content.pm.PackageManager.GET_META_DATA,
        )
        assertTrue(info.exported)
        assertEquals("Agents", app.getString(dev.supermessage.R.string.agents_widget_label))
        assertEquals(WidgetCopy.AGENTS, app.getString(dev.supermessage.R.string.agents_widget_label))
        assertEquals(WidgetCopy.AGENTS_DESCRIPTION, app.getString(dev.supermessage.R.string.agents_widget_description))
    }

    @Test(timeout = 30_000)
    fun bothWidgetsAreRedrawnByTheSharedSchedule() {
        assertEquals(
            setOf(NeedsYouWidget::class, AgentsWidget::class),
            WidgetRegistry.all.map { it::class }.toSet(),
        )
    }
}
