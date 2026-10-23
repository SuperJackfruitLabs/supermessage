package dev.supermessage.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import dev.supermessage.AppCore
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch

/** The widgets that exist, so a redraw reaches each. The Needs you and Agents widgets register theirs. */
object WidgetRegistry {
    private val registered = CopyOnWriteArrayList<GlanceAppWidget>()

    fun register(widget: GlanceAppWidget) {
        if (registered.none { it::class == widget::class }) registered += widget
    }

    val all: List<GlanceAppWidget> get() = registered.toList()
}

/**
 * When the widgets are drawn again, with no write to prompt it.
 *
 * WidgetKit draws future entries by itself; Glance does not, so the app asks.
 * The snapshot is a recap, not live (`core::widget`, "the Home Screen is a
 * recap"): what changes between writes is the **frame** (an agent "active"
 * at the last write is "idle" fifteen minutes later with nothing written), so
 * the core says when the next frame begins (`WidgetView.nextChangeMs`) and the
 * widget is drawn again then. Two inexact alarms, neither needing the
 * exact-alarm permission:
 *
 * - **the frame:** at `nextChangeMs`, or none when there is no next frame;
 * - **the backstop:** every [RELOAD_EVERY_MS] (`widget::RELOAD_EVERY_MS`, iOS's
 *   timeline `.after(reloadEvery)`), so a skipped redraw is picked up.
 *
 * A fire redraws every registered widget and arms both again, so the chain
 * continues for as long as the process is alive; a write that the core says
 * is worth a redraw (a decision arriving or leaving) redraws at once.
 * Decisions about *what* to draw stay in the core: this only asks.
 *
 * @param nextChangeMs the core's `nextChangeMs` for now, from the snapshot.
 */
open class WidgetRedraw(
    context: Context,
    private val nextChangeMs: (nowMs: Long) -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
    private val widgets: () -> List<GlanceAppWidget> = { WidgetRegistry.all },
    private val update: suspend (GlanceAppWidget, Context) -> Unit = { widget, c -> widget.updateAll(c) },
) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)

    /** After a write: redraw now when the core said to, and keep the alarms right either way. */
    open suspend fun after(reload: Boolean) {
        if (reload) redraw() else arm()
    }

    /** Every registered widget is drawn from the snapshot as it stands, and both alarms are set again. */
    suspend fun redraw() {
        try {
            for (widget in widgets()) update(widget, context)
        } finally {
            arm()
        }
    }

    /** Sets the frame alarm for the snapshot's next frame (or cancels it) and the backstop for [RELOAD_EVERY_MS] out. */
    fun arm() {
        val at = now()
        val frame = nextChangeMs(at)
        if (frame == null) alarms.cancel(intent(FRAME)) else alarms.setAndAllowWhileIdle(AlarmManager.RTC, frame, intent(FRAME))
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, at + RELOAD_EVERY_MS, intent(BACKSTOP))
    }

    private fun intent(action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetRedrawReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** `core::widget::RELOAD_EVERY_MS`: 15 minutes. */
        const val RELOAD_EVERY_MS = 15 * 60 * 1000L

        const val FRAME = "dev.supermessage.widgets.REDRAW_AT_NEXT_FRAME"
        const val BACKSTOP = "dev.supermessage.widgets.REDRAW_BACKSTOP"
    }
}

/** The alarms' target. Not exported: only this app's own PendingIntents reach it. */
class WidgetRedrawReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        AppCore.scope.launch {
            try {
                AppCore.widgetRedraw(app).redraw()
            } finally {
                pending.finish()
            }
        }
    }
}
