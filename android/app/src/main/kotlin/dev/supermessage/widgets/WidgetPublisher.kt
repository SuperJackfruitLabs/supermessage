package dev.supermessage.widgets

import dev.supermessage.kit.Session
import dev.supermessage.notify.FEED_THROTTLE_MS
import dev.supermessage.kit.widgets.WidgetWriting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_core.WidgetLiveTurn

/**
 * When the app asks the widgets' snapshot to merge
 * (`PlatformCoordinator.swift:85-99, 142, 152, 222-225, 271-311`): the
 * widget half of what iOS's one app-lifetime coordinator does.
 *
 * **Process-wide**, held by `AppCore`, so it knows what the process knows and
 * nothing a screen does: whether the app is in front is the *process's*
 * foreground (`ProcessLifecycleOwner`), not an Activity's start and stop, and
 * the signed-in phase survives an Activity being recreated. A recreation
 * therefore never restarts the recap. It holds no Activity.
 *
 * - The recap starts again when the app comes forward and when it leaves.
 * - The roster and the turn the app is watching are merged in.
 * - The open room's timeline is merged **only while the snapshot holds a
 *   decision for that room** (a streaming answer re-emits it several times a second).
 * - A decision answered from its card is marked answered.
 * - Any sign-out resets the snapshot (idempotent in the core), a cold start
 *   into SIGNED_OUT included.
 *
 * Every merge is the core's (`WidgetFeed`); this decides only when to ask.
 */
internal class WidgetPublisher(
    private val queue: WidgetWriteQueue,
    private val now: () -> Long = System::currentTimeMillis,
    /** Whose snapshot this is, kept for a widget's button to check ([WidgetAccountStamp]). */
    private val account: WidgetAccountStamp? = null,
) {
    // Callers are the main thread (the process lifecycle) and the flows' scope; one
    // lock keeps what a write captures and the order it is queued in together.
    private val state = Any()

    @Volatile
    private var appActive = false
    private var phase: Session.Phase = Session.Phase.STARTING
    private var latestRooms: List<RoomRow> = emptyList()
    private var liveTurn: WidgetLiveTurn? = null
    private var watching = emptyList<Job>()

    private fun write(write: suspend (WidgetWriting) -> Boolean) = queue.submit(write)

    /** Returns once every write asked so far has been made. */
    suspend fun writesDone() = queue.done()

    /**
     * The app came forward, or left (`:96-99`): the process, not an Activity. The recap is
     * what happened since the reader last had the app open, so it starts again both on the
     * way in and on the way out: what arrived while they were looking is not news later.
     */
    fun activeChanged(active: Boolean) = synchronized(state) {
        appActive = active
        if (phase == Session.Phase.SIGNED_IN) write { it.opened() }
    }

    /** `:142, :152`: a sign-in while in front starts the recap; a sign-out resets the snapshot. */
    fun phaseChanged(next: Session.Phase) = synchronized(state) {
        if (next == phase) return@synchronized
        phase = next
        when (next) {
            Session.Phase.SIGNED_IN -> if (appActive) write { it.opened() }
            Session.Phase.SIGNED_OUT -> {
                liveTurn = null
                write { it.signedOut() }
            }
            Session.Phase.STARTING -> Unit
        }
    }

    /** The roster, as the widgets' authoritative picture of the agents (`:222-225`). */
    fun roomsChanged(rows: List<RoomRow>) = synchronized(state) {
        latestRooms = rows
        writeRoster()
    }

    private fun writeRoster() {
        if (phase != Session.Phase.SIGNED_IN) return
        val rows = latestRooms
        val live = listOfNotNull(liveTurn)
        val asOf = now()
        write { it.apply(rows, live, asOf) }
    }

    /** The open room can see what no push says: a card the board resolved (`:297-307`). */
    fun timelineChanged(roomId: String?, rows: List<TimelineRow>) = synchronized(state) {
        if (roomId == null || phase != Session.Phase.SIGNED_IN) return@synchronized
        write { if (it.holdsDecision(roomId)) it.apply(roomId, rows) else false }
    }

    /** The turn the app is watching, as the Agents widget's step (`:271-283`). Written only when it changes. */
    fun liveChanged(roomId: String?, step: String?) = synchronized(state) {
        val turn = if (roomId != null && step != null) WidgetLiveTurn(roomId, step) else null
        if (turn == liveTurn) return@synchronized
        liveTurn = turn
        writeRoster()
    }

    /** A decision answered from its card: the widgets stop offering it (`:85-88, :309-311`). */
    fun decisionAnswered(roomId: String, eventId: String, optionId: String) = synchronized(state) {
        write { it.markAnswered(roomId, eventId, optionId) }
    }

    /**
     * Starts feeding the widgets from [phases] and [session], on [scope] (which outlives the
     * Activity, so a change after it stops still lands). Stopped by [stopWatching]; the
     * Activity calls both, and a recreation's second `watch` re-reads the same phase,
     * which changes nothing here.
     *
     * Returns the handle that stops **this** watch only: a recreated Activity's `watch` runs
     * before the old one's `onDestroy`, and the old one stopping must not cancel the new feed.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun watch(phases: StateFlow<Session.Phase>, session: Session, scope: CoroutineScope): Watch {
        stopWatching()
        val jobs = listOf(
            phases.onEach(::phaseChanged).launchIn(scope),
            phases.onEach { phase -> stamp(phase, session) }.launchIn(scope),
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
        watching = jobs
        return Watch(jobs)
    }

    /** One `watch`'s jobs. [stop] cancels them, and clears the publisher's record only if they are still its current ones. */
    inner class Watch internal constructor(private val jobs: List<Job>) {
        fun stop() {
            jobs.forEach { it.cancel() }
            if (watching === jobs) watching = emptyList()
        }
    }

    private suspend fun stamp(phase: Session.Phase, session: Session) {
        val stamp = account ?: return
        when (phase) {
            Session.Phase.SIGNED_IN -> session.account()?.userId?.let { withContext(Dispatchers.IO) { stamp.write(it) } }
            Session.Phase.SIGNED_OUT -> withContext(Dispatchers.IO) { stamp.clear() }
            Session.Phase.STARTING -> Unit
        }
    }

    fun stopWatching() {
        watching.forEach { it.cancel() }
        watching = emptyList()
    }
}
