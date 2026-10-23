package dev.supermessage.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.supermessage.AppCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import uniffi.supermessage_core.WidgetView

/** What a widget draws: the core's view of the snapshot, and the moment it was read for. */
internal data class DrawnSnapshot(val view: WidgetView, val nowMs: Long)

/**
 * The widgets' snapshot as it changes, for a widget whose Glance session is running.
 *
 * Glance runs `provideGlance` once per session, and a session lives at least 45 s
 * (`TimeoutOptions`). An `updateAll` on a running session only reloads Glance's own state
 * (`AppWidgetSession.UpdateGlanceState`), so a snapshot read in `provideGlance` stays what the
 * widget shows for the whole session: a decision marked sent, a new one, a sign-out would not
 * be drawn. So the widgets read the snapshot **in composition**, from [follow]: every
 * [WidgetRedraw.redraw] calls [changed] first, the running session reads the snapshot again
 * (off the main thread) and recomposes, and a session that is not running is started by the
 * `updateAll` that follows, which reads it fresh. Each read takes its own `now`.
 */
internal object WidgetSnapshots {
    private val revision = MutableStateFlow(0L)

    /** The snapshot changed: every widget drawing it reads it again. */
    fun changed() = revision.update { it + 1 }

    /** The current revision; [follow] from it misses no change made after it was read. */
    val current: Long get() = revision.value

    /** The snapshot as a widget draws it now. A file read and a core call: not on the main thread. */
    fun read(context: Context): DrawnSnapshot {
        val now = System.currentTimeMillis()
        return DrawnSnapshot(AppCore.widgets(context).view(now), now)
    }

    /** The snapshot again after each change since [seen] (conflated: the latest change wins). */
    fun follow(context: Context, seen: Long): Flow<DrawnSnapshot> =
        revision.filter { it > seen }.map { withContext(Dispatchers.IO) { read(context) } }
}

/**
 * The snapshot a running widget session draws: [first] (read in `provideGlance`, at revision
 * [seen]), then each change after it.
 */
@Composable
internal fun followSnapshot(context: Context, first: DrawnSnapshot, seen: Long): DrawnSnapshot {
    val changes = remember(context, seen) { WidgetSnapshots.follow(context, seen) }
    return changes.collectAsState(first).value
}
