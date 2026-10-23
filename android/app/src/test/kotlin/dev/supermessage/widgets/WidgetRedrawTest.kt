package dev.supermessage.widgets

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private fun redraw(
        next: Long?,
        widgets: List<GlanceAppWidget> = emptyList(),
        updated: MutableList<GlanceAppWidget> = mutableListOf(),
    ) = WidgetRedraw(app, nextChangeMs = { next }, now = { now }, widgets = { widgets }, update = { w, _ -> updated += w })

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
    fun aRedrawIsAlsoDueEveryFifteenMinutes() {
        redraw(next = now + 60_000).arm()
        assertEquals(now + 15 * 60_000, alarmFor(WidgetRedraw.BACKSTOP)!!.triggerAtMs)
        redraw(next = null).arm()
        assertEquals("even with no next frame", now + 15 * 60_000, alarmFor(WidgetRedraw.BACKSTOP)!!.triggerAtMs)
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
