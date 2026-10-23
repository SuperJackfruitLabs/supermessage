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

    /** The receivers arm off the main thread: wait for what they set. */
    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "timed out waiting for: $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun placingTheFirstWidgetArms() {
        assertNull(frameAlarm())
        receiver.onEnabled(app)
        await("the frame alarm and the backstop") { frameAlarm() != null && backstop().size == 1 }
    }

    @Test
    fun theProvidersDoTheirReadsOffTheMainThread() {
        val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        AppCore.installWidgetsForTest(
            null,
            WidgetRedraw(app, nextChangeMs = { threads += Thread.currentThread(); now + 60_000 }, now = { now }, widgets = { emptyList() }, placed = { true }),
        )
        receiver.onEnabled(app)
        receiver.onUpdate(app, AppWidgetManager.getInstance(app), intArrayOf(7))
        AgentsWidgetReceiver().onUpdate(app, AppWidgetManager.getInstance(app), intArrayOf(8))
        await("three arms") { threads.size == 3 }
        val main = android.os.Looper.getMainLooper().thread
        assertTrue("arm read the core on the main thread", threads.none { it === main })
    }

    @Test
    fun removingOneProviderWhileTheOtherIsPlacedKeepsTheSchedule() {
        receiver.onEnabled(app)
        await("armed") { frameAlarm() != null && backstop().size == 1 }
        // The Agents widget is still on a home screen: `placed` stays true.
        AgentsWidgetReceiver().onDisabled(app)
        Thread.sleep(300)
        assertNotNull(frameAlarm())
        assertEquals(1, backstop().size)
    }

    @Test
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
        // The launcher's (and the system's, after boot) own broadcast, through the manifest's receiver.
        app.sendBroadcast(
            android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setClass(app, NeedsYouWidgetReceiver::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(7)),
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        await("armed again by the update") { frameAlarm() != null }
        assertEquals("the queued work stands", 1, backstop().size)
    }

    @Test
    fun removingTheLastWidgetCancelsEverything() {
        receiver.onEnabled(app)
        await("armed") { frameAlarm() != null && backstop().isNotEmpty() }
        placed = false
        receiver.onDisabled(app)
        await("cancelled") { frameAlarm() == null && backstop().isEmpty() }
    }

    @Test
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

    @Test
    fun theAgentsProviderIsDeclaredWithItsLabelAndDescription() {
        val info = app.packageManager.getReceiverInfo(
            android.content.ComponentName(app, AgentsWidgetReceiver::class.java), android.content.pm.PackageManager.GET_META_DATA,
        )
        assertTrue(info.exported)
        assertEquals("Agents", app.getString(dev.supermessage.R.string.agents_widget_label))
        assertEquals(WidgetCopy.AGENTS, app.getString(dev.supermessage.R.string.agents_widget_label))
        assertEquals(WidgetCopy.AGENTS_DESCRIPTION, app.getString(dev.supermessage.R.string.agents_widget_description))
    }

    @Test
    fun bothWidgetsAreRedrawnByTheSharedSchedule() {
        assertEquals(
            setOf(NeedsYouWidget::class, AgentsWidget::class),
            WidgetRegistry.all.map { it::class }.toSet(),
        )
    }
}
