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

    @Test
    fun placingTheFirstWidgetArms() {
        assertNull(frameAlarm())
        receiver.onEnabled(app)
        assertNotNull(frameAlarm())
        assertEquals(1, backstop().size)
    }

    @Test
    fun theUpdateAfterBootArmsAgain() {
        receiver.onEnabled(app)
        // A reboot dropped the alarm; the launcher's update brings it back.
        placed = true
        AppCore.installWidgetsForTest(
            null,
            WidgetRedraw(app, nextChangeMs = { null }, now = { now }, widgets = { emptyList() }, placed = { true }),
        )
        AppCore.widgetRedraw(app).arm()
        assertNull(frameAlarm())
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
        assertNotNull(frameAlarm())
        assertEquals("the queued work stands", 1, backstop().size)
    }

    @Test
    fun removingTheLastWidgetCancelsEverything() {
        receiver.onEnabled(app)
        assertTrue(frameAlarm() != null && backstop().isNotEmpty())
        placed = false
        receiver.onDisabled(app)
        assertNull(frameAlarm())
        assertEquals(0, backstop().size)
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
}
