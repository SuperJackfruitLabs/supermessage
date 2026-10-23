package dev.supermessage.widgets

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.supermessage.AppCore
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Active" turns into "idle" with nothing written, so the widgets redraw at
 * the core's next frame, and every 15 minutes whatever happens
 * (`widget::RELOAD_EVERY_MS`; iOS's timeline `.after(reloadEvery)`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION") // ScheduledAlarm exposes its fields only
class WidgetRedrawTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val now = 1_800_000_000_000L

    private val work get() = WorkManager.getInstance(app)

    @Before
    fun startWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun forget() = AppCore.installWidgetsForTest(null)

    private fun redraw(
        next: Long?,
        widgets: List<GlanceAppWidget> = emptyList(),
        updated: MutableList<GlanceAppWidget> = mutableListOf(),
        placed: Boolean = true,
    ) = WidgetRedraw(
        app, nextChangeMs = { next }, now = { now }, widgets = { widgets },
        update = { w, _ -> updated += w }, placed = { placed },
    )

    private fun backstop(): List<WorkInfo> =
        work.getWorkInfosForUniqueWork(WidgetRedraw.BACKSTOP_WORK).get().filter { !it.state.isFinished }

    private fun alarmFor(action: String) =
        alarms.scheduledAlarms.singleOrNull { shadowOf(it.operation).savedIntent.action == action }

    @Test
    fun theNextRedrawIsScheduledAtTheNextFrame() {
        val frame = now + 4 * 60_000 + 17
        redraw(next = frame).arm()
        val alarm = alarmFor(WidgetRedraw.FRAME) ?: error("no alarm for the frame")
        assertEquals("exactly the core's nextChangeMs", frame, alarm.triggerAtMs)
        assertEquals(AlarmManager.RTC, alarm.type)
    }

    @Test
    fun noNextFrameSchedulesNothing() {
        redraw(next = now + 60_000).arm()
        redraw(next = null).arm()
        assertNull("the frame alarm is withdrawn", alarmFor(WidgetRedraw.FRAME))
    }

    @Test
    fun aRedrawIsAlsoDueEveryFifteenMinutesAsPeriodicWork() {
        redraw(next = now + 60_000).arm()
        val info = backstop().single()
        assertEquals(15 * 60_000L, info.periodicityInfo!!.repeatIntervalMillis)
        redraw(next = null).arm()
        assertEquals("even with no next frame, and not queued twice", 1, backstop().size)
    }

    @Test
    fun withNoWidgetPlacedNothingIsArmedAndWhatWasIsCancelled() {
        redraw(next = now + 60_000).arm()
        assertNotNull(alarmFor(WidgetRedraw.FRAME))
        assertEquals(1, backstop().size)
        redraw(next = now + 60_000, placed = false).arm()
        assertNull("the frame alarm is withdrawn", alarmFor(WidgetRedraw.FRAME))
        assertEquals("the backstop is cancelled", 0, backstop().size)
        redraw(next = now + 60_000, placed = false).arm()
        assertNull("and nothing is armed afterwards", alarmFor(WidgetRedraw.FRAME))
        assertEquals(0, backstop().size)
    }

    @Test
    fun cancellingEverythingWhenTheLastWidgetGoes() {
        val subject = redraw(next = now + 60_000)
        subject.arm()
        subject.cancelAll()
        assertNull(alarmFor(WidgetRedraw.FRAME))
        assertEquals(0, backstop().size)
    }

    @Test
    fun armingAgainAfterARebootRestoresTheFrameAlarmAndKeepsTheQueuedWork() {
        val subject = redraw(next = now + 60_000)
        subject.arm()
        // A reboot drops the alarm (here: the same cancel a missing frame makes); the work stays queued.
        redraw(next = null).arm()
        assertNull(alarmFor(WidgetRedraw.FRAME))
        val before = backstop().single().id
        subject.arm()
        assertNotNull("the update after boot sets the frame alarm again", alarmFor(WidgetRedraw.FRAME))
        assertEquals("KEEP: the same work, not a new one", before, backstop().single().id)
    }

    @Test
    fun theRefreshWorkerRedrawsEveryWidgetThenArmsAgain() {
        val updated = mutableListOf<GlanceAppWidget>()
        val stub = Stub()
        AppCore.installWidgetsForTest(null, redraw(next = now + 60_000, widgets = listOf(stub), updated = updated))
        try {
            val result = runBlocking { TestListenableWorkerBuilder<WidgetRefreshWorker>(app).build().doWork() }
            assertEquals(androidx.work.ListenableWorker.Result.success(), result)
            assertTrue("the worker redrew the widget", stub in updated)
        } finally {
            AppCore.installWidgetsForTest(null)
        }
    }

    private class Stub : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) = Unit
    }

    @Test
    fun aRedrawDrawsEveryWidgetThenArmsAgain() = runBlocking {
        val first = Stub()
        val second = Stub()
        val updated = mutableListOf<GlanceAppWidget>()
        redraw(next = now + 60_000, widgets = listOf(first, second), updated = updated).redraw()
        assertEquals(listOf(first, second), updated)
        assertEquals(now + 60_000, alarmFor(WidgetRedraw.FRAME)!!.triggerAtMs)
    }

    @Test
    fun aWriteThatIsNotWorthADrawOnlyKeepsTheAlarmsRight() = runBlocking {
        val updated = mutableListOf<GlanceAppWidget>()
        val subject = redraw(next = now + 60_000, widgets = listOf(Stub()), updated = updated)
        subject.after(reload = false)
        assertEquals(emptyList<GlanceAppWidget>(), updated)
        assertNotNull(alarmFor(WidgetRedraw.FRAME))
        subject.after(reload = true)
        assertEquals(1, updated.size)
    }
}
