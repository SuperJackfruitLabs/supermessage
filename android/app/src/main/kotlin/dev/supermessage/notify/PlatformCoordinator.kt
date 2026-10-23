package dev.supermessage.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.supermessage.AppCore
import dev.supermessage.kit.Session
import dev.supermessage.kit.notify.shouldAskForNotifications
import dev.supermessage.kit.widgets.WidgetWriting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_core.WidgetLiveTurn

/**
 * Android's `PlatformCoordinator` (`Supermessage/Platform/PlatformCoordinator.swift`):
 * the host-side lifecycle of notifications. Later tasks extend it.
 *
 * - After a **fresh sign-in** it asks for notification permission, once
 *   (`:124-131`, `:161-173`); a restored session never prompts.
 * - On `SIGNED_IN → SIGNED_OUT` it withdraws every delivered notification
 *   (`:143-155`, `LocalNotifier.swift:101-107`), so the next account never
 *   sees the last one's, and unregisters from the UnifiedPush distributor
 *   (the core's logout already removed the pusher, `C/session.rs:297`).
 * - **Signed in, allowed to notify, and in front**, it asks the connector for
 *   background push (`:188-205`). [run] only runs while the Activity is
 *   started, so each return to the foreground asks again; the registrar
 *   never registers the same endpoint twice.
 * - **The widgets' snapshot** is written from here (M7 Task 1,
 *   `PlatformCoordinator.swift:96-99, 142, 152, 222-225, 271-311`): the recap
 *   starts again when the app comes forward and when it leaves; the roster and
 *   the turn the app is watching are merged in; the open room's timeline is
 *   merged **only while the snapshot holds a decision for that room** (a
 *   streaming answer re-emits it several times a second); a decision answered
 *   from its card is marked answered; sign-out resets the snapshot. Every
 *   merge is the core's (`WidgetFeed`); this decides only when to ask. The
 *   writes go through one queue on [widgetScope], so they land in the order
 *   they were asked.
 */
internal class PlatformCoordinator(
    context: Context,
    private val phases: StateFlow<Session.Phase>,
    private val permissionLauncher: () -> Unit,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val granted: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    },
    /** Asks the UnifiedPush connector for a distributor and registers ([askForPush]). */
    private val askForPush: () -> Unit = {},
    /** The registrar's sign-out: unregister from the distributor and forget. */
    private val pushSignedOut: () -> Unit = {},
    /** The widgets' one writer; `null` writes nothing. */
    private val widgets: WidgetWriting? = null,
    /** After each widget write, with whether the core says the widgets are worth drawing now. */
    private val widgetsWritten: suspend (reload: Boolean) -> Unit = {},
    private val widgetScope: CoroutineScope = AppCore.scope,
    /** The clock, in milliseconds since the epoch. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        session: Session,
        permissionLauncher: () -> Unit,
        askForPush: () -> Unit = {},
        pushSignedOut: () -> Unit = {},
        widgets: WidgetWriting? = null,
        widgetsWritten: suspend (reload: Boolean) -> Unit = {},
    ) : this(
        context, session.phase, permissionLauncher, askForPush = askForPush, pushSignedOut = pushSignedOut,
        widgets = widgets, widgetsWritten = widgetsWritten,
    )

    private val context = context.applicationContext

    // Kept across restarts of [run]: a sign-in that happened while the app was
    // stopped is still a transition from SIGNED_OUT when it comes back.
    @Volatile
    internal var lastPhase: Session.Phase? = null
        private set

    /** Collects until cancelled. The Activity runs it while STARTED. */
    suspend fun run() {
        ensureChannels(context)
        phases.collect { now ->
            val before = lastPhase
            lastPhase = now
            if (shouldAskForNotifications(before, now, sdkInt, granted())) {
                permissionLauncher()
            }
            if (now == Session.Phase.SIGNED_IN && allowed()) askForPush()
            if (before == Session.Phase.SIGNED_IN && now == Session.Phase.SIGNED_OUT) {
                NotificationManagerCompat.from(context).cancelAll()
                pushSignedOut()
            }
        }
    }

    /** The permission dialog's answer: a grant while signed in asks for push at once. */
    fun permissionAnswered(granted: Boolean) {
        if (granted && lastPhase == Session.Phase.SIGNED_IN) askForPush()
    }

    /** API 31–32 have no runtime permission to grant. */
    private fun allowed(): Boolean = sdkInt < 33 || granted()

    // -- The widgets' snapshot ------------------------------------------------

    /** Merges to make, in the order asked; each returns the core's `reload`. */
    private val writes = Channel<suspend (WidgetWriting) -> Boolean>(Channel.UNLIMITED)

    init {
        if (widgets != null) {
            widgetScope.launch {
                for (write in writes) {
                    try {
                        widgetsWritten(write(widgets))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A failed write is the next one's to repair: every merge starts from what is stored.
                    }
                }
            }
        }
    }

    private fun writeWidgets(write: suspend (WidgetWriting) -> Boolean) {
        if (widgets != null) writes.trySend(write)
    }

    /** Returns once every write asked so far has been made. */
    internal suspend fun widgetWritesDone() {
        if (widgets == null) return
        val done = CompletableDeferred<Unit>()
        writes.send {
            done.complete(Unit)
            false
        }
        done.await()
    }

    // Callers are on the main thread (the Activity) and on [widgetScope] (the
    // flows); one lock keeps what a write captures and the order it is queued in
    // together.
    private val state = Any()
    private var appActive = false
    private var widgetPhase: Session.Phase = Session.Phase.STARTING
    private var latestRooms: List<RoomRow> = emptyList()
    private var liveTurn: WidgetLiveTurn? = null
    private var watching = emptyList<Job>()

    /**
     * The app came forward, or left (`:96-99`). The recap is what happened
     * since the reader last had the app open, so it starts again both on the
     * way in and on the way out: what arrived while they were looking is not
     * news later.
     */
    fun activeChanged(active: Boolean) = synchronized(state) {
        appActive = active
        if (widgetPhase == Session.Phase.SIGNED_IN) writeWidgets { it.opened() }
    }

    /** `:142, :152`: a sign-in while in front starts the recap; a sign-out resets the snapshot. */
    internal fun widgetPhaseChanged(phase: Session.Phase) = synchronized(state) {
        val before = widgetPhase
        if (phase == before) return@synchronized
        widgetPhase = phase
        when (phase) {
            Session.Phase.SIGNED_IN -> if (appActive) writeWidgets { it.opened() }
            Session.Phase.SIGNED_OUT -> if (before == Session.Phase.SIGNED_IN) {
                liveTurn = null
                writeWidgets { it.signedOut() }
            }
            Session.Phase.STARTING -> Unit
        }
    }

    /** The roster, as the widgets' authoritative picture of the agents (`:222-225`). */
    internal fun roomsChanged(rows: List<RoomRow>) = synchronized(state) {
        latestRooms = rows
        writeRoster()
    }

    private fun writeRoster() {
        if (widgetPhase != Session.Phase.SIGNED_IN) return
        val rows = latestRooms
        val live = listOfNotNull(liveTurn)
        val asOf = now()
        writeWidgets { it.apply(rows, live, asOf) }
    }

    /**
     * The open room can see what no push says: a card the board resolved, or
     * a permission answered in the room. Read only when the widgets hold a
     * decision for that room (`:297-307`).
     */
    internal fun timelineChanged(roomId: String?, rows: List<TimelineRow>) = synchronized(state) {
        if (roomId == null || widgetPhase != Session.Phase.SIGNED_IN) return@synchronized
        writeWidgets { if (it.holdsDecision(roomId)) it.apply(roomId, rows) else false }
    }

    /**
     * The turn the app is watching, as the Agents widget's step (`:271-283`).
     * Written only when it changes.
     */
    internal fun liveChanged(roomId: String?, step: String?) = synchronized(state) {
        val turn = if (roomId != null && step != null) WidgetLiveTurn(roomId, step) else null
        if (turn == liveTurn) return@synchronized
        liveTurn = turn
        writeRoster()
    }

    /** A decision answered from its card: the widgets stop offering it (`:85-88, :309-311`). */
    fun decisionAnswered(roomId: String, eventId: String, optionId: String) = synchronized(state) {
        writeWidgets { it.markAnswered(roomId, eventId, optionId) }
    }

    /**
     * Starts feeding the widgets from [session], on [scope] (which outlives
     * the Activity, so a change after it stops still lands). Stopped by
     * [stopWatching]. The roster and the timeline are looked at no more than
     * once per [FEED_THROTTLE_MS], as the notification feed does.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun watch(session: Session, scope: CoroutineScope) {
        stopWatching()
        watching = listOf(
            phases.onEach(::widgetPhaseChanged).launchIn(scope),
            session.rooms.rooms.sample(FEED_THROTTLE_MS).onEach(::roomsChanged).launchIn(scope),
            combine(session.timeline.roomId, session.timeline.items) { id, rows -> id to rows }
                .sample(FEED_THROTTLE_MS)
                .onEach { (id, rows) -> timelineChanged(id, rows) }
                .launchIn(scope),
            // The core's own words for the step, and only while the turn is going:
            // a finished one is not "an agent working".
            session.live.summary
                .map { if (it?.inProgress == true) it.roomId to it.currentStepTitle else null to null }
                .distinctUntilChanged()
                .onEach { (room, step) -> liveChanged(room, step) }
                .launchIn(scope),
        )
    }

    fun stopWatching() {
        watching.forEach { it.cancel() }
        watching = emptyList()
    }
}
