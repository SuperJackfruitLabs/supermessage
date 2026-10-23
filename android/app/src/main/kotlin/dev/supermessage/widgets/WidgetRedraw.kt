package dev.supermessage.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import androidx.glance.appwidget.updateAll
import dev.supermessage.AppCore
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch

/** The widgets that exist, so a redraw reaches each. */
object WidgetRegistry {
    // A redraw can start a cold process (an alarm, the periodic work) in which no provider has
    // run, so the widgets this app has are known here, not only registered by their receivers.
    private val registered = CopyOnWriteArrayList<GlanceAppWidget>(listOf(NeedsYouWidget(), AgentsWidget()))

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
 * widget is drawn again then. Two things, neither needing the exact-alarm permission:
 *
 * - **the frame:** one inexact alarm at `nextChangeMs`, or none when there is no next frame;
 * - **the backstop:** unique periodic WorkManager work every [RELOAD_EVERY_MS]
 *   (`widget::RELOAD_EVERY_MS`, iOS's timeline `.after(reloadEvery)`), so a
 *   skipped redraw is picked up. WorkManager keeps it across a reboot and a
 *   force-stop, which an alarm does not.
 *
 * **Only while a widget is placed.** With none, [arm] cancels both, so the
 * process is never woken for nothing; the provider arms on `onEnabled` and
 * `onUpdate` (the system sends an update after boot, which re-sets the frame
 * alarm the reboot dropped) and cancels on `onDisabled`.
 *
 * A fire redraws every registered widget and arms again, so the frame chain
 * continues; a write that the core says is worth a redraw (a decision arriving
 * or leaving) redraws at once. Decisions about *what* to draw stay in the core:
 * this only asks.
 *
 * @param nextChangeMs the core's `nextChangeMs` for now, from the snapshot.
 * @param placed whether any of this app's widgets is on a home screen.
 */
open class WidgetRedraw(
    context: Context,
    private val nextChangeMs: (nowMs: Long) -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
    private val widgets: () -> List<GlanceAppWidget> = { WidgetRegistry.all },
    private val update: suspend (GlanceAppWidget, Context) -> Unit = { widget, c -> widget.updateAll(c) },
    private val placed: () -> Boolean = { anyWidgetPlaced(context.applicationContext) },
) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)

    /** After a write: redraw now when the core said to, and keep the schedule right either way. */
    open suspend fun after(reload: Boolean) {
        if (reload) redraw() else arm()
    }

    /**
     * Every registered widget is drawn from the snapshot as it stands, and the schedule is set
     * again. A widget whose session is running reads the snapshot again ([WidgetSnapshots]);
     * `updateAll` starts a session for one that has none.
     */
    suspend fun redraw() {
        WidgetSnapshots.changed()
        try {
            for (widget in widgets()) update(widget, context)
        } finally {
            arm()
        }
    }

    /**
     * Sets the frame alarm for the snapshot's next frame (or cancels it) and makes sure the
     * periodic backstop is queued; with no widget placed, cancels both.
     */
    fun arm() {
        if (!placed()) return cancelAll()
        val frame = nextChangeMs(now())
        if (frame == null) alarms.cancel(frameIntent()) else alarms.setAndAllowWhileIdle(AlarmManager.RTC, frame, frameIntent())
        // KEEP: the queued work (and its phase) stands; re-enqueuing would push the next run out.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BACKSTOP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(RELOAD_EVERY_MS, TimeUnit.MILLISECONDS)
                // Whoever armed has just drawn; the first backstop is a period away.
                .setInitialDelay(RELOAD_EVERY_MS, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    /** No widget remains: nothing is left to wake the process for. */
    fun cancelAll() {
        alarms.cancel(frameIntent())
        WorkManager.getInstance(context).cancelUniqueWork(BACKSTOP_WORK)
    }

    private fun frameIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetRedrawReceiver::class.java).setAction(FRAME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** `core::widget::RELOAD_EVERY_MS`: 15 minutes, which is also WorkManager's shortest period. */
        const val RELOAD_EVERY_MS = 15 * 60 * 1000L

        const val FRAME = "dev.supermessage.widgets.REDRAW_AT_NEXT_FRAME"
        const val BACKSTOP_WORK = "widgets-backstop"

        /** Whether the launcher holds any widget of this app. */
        fun anyWidgetPlaced(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.getInstalledProvidersForPackage(context.packageName, null)
                .any { manager.getAppWidgetIds(it.provider).isNotEmpty() }
        }
    }
}

/** The periodic backstop: draw every widget from the snapshot, and arm the next frame. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppCore.widgetRedraw(applicationContext).redraw()
        return Result.success()
    }
}

/** The frame alarm's target. Not exported: only this app's own PendingIntents reach it. */
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

/**
 * Arms [WidgetRedraw] from a provider callback without touching the disk or the core on the
 * main thread (the callbacks run there): `arm` reads the snapshot and asks the core when the
 * next frame is. The broadcast is held open ([BroadcastReceiver.goAsync]) until it is done,
 * so the process is not stopped half way. `goAsync` is null outside a real broadcast (a test
 * calling the callback directly) and after Glance took it: the WorkManager job holds the work then.
 */
/** The WorkManager job that holds an arm no broadcast holds; replaced by a test. */
internal var armHold: (Context) -> Unit = { app ->
    runCatching {
        androidx.work.WorkManager.getInstance(app).enqueueUniqueWork(
            ARM_WORK,
            androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build(),
        )
    }
}

internal const val ARM_WORK = "widget-arm"

internal fun BroadcastReceiver.armOffMain(context: Context) {
    // Glance's own `onReceive` may already have taken the broadcast's PendingResult
    // (`goAsync` hands it out once, then returns null). Then nothing of ours holds the process,
    // so the arm also goes to WorkManager (unique, one at a time), whose job redraws and arms.
    val pending = goAsync()
    val app = context.applicationContext
    if (pending == null) armHold(app)
    AppCore.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            AppCore.widgetRedraw(app).arm()
        } finally {
            pending?.finish()
        }
    }
}
