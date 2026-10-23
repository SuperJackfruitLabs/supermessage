package dev.supermessage.kit.widgets

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.supermessage_core.NotificationDto
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_core.WidgetAnswer
import uniffi.supermessage_core.WidgetLiveTurn
import uniffi.supermessage_core.WidgetView
import uniffi.supermessage_core.WidgetWrite
import uniffi.supermessage_ffi.widgetAnswerFor
import uniffi.supermessage_ffi.widgetApplyNotification
import uniffi.supermessage_ffi.widgetApplyOpened
import uniffi.supermessage_ffi.widgetApplyRoster
import uniffi.supermessage_ffi.widgetApplyTimeline
import uniffi.supermessage_ffi.widgetClearAnswer
import uniffi.supermessage_ffi.widgetMarkAnswered
import uniffi.supermessage_ffi.widgetSignedOut
import uniffi.supermessage_ffi.widgetView

/**
 * Where the widgets' snapshot lives: one document, replaced whole.
 * `SupermessageKit/Notifications/WidgetSnapshot.swift:38-85`.
 */
interface SnapshotStore {
    /** The stored snapshot, or `null` when there is none. Needs no lock: a write replaces the file whole. */
    fun read(): String?

    /** Replaces the stored snapshot. A reader sees the old one or the new one, never half. */
    fun write(json: String)
}

/**
 * Every write the widgets' snapshot takes, as the app's one writer sees it.
 * [WidgetFeed] is the real one; a test can stand in a recording one.
 * Each returns whether the widgets are worth redrawing now (the core's `reload`).
 */
interface WidgetWriting {
    /** A push the core decided. */
    suspend fun apply(note: NotificationDto): Boolean

    /** The roster, read at [asOfMs], with the turns the app is watching. */
    suspend fun apply(rows: List<RoomRow>, live: List<WidgetLiveTurn>, asOfMs: Long): Boolean

    /** The open room's loaded timeline. */
    suspend fun apply(roomId: String, rows: List<TimelineRow>): Boolean

    suspend fun markAnswered(roomId: String, eventId: String, optionId: String): Boolean

    suspend fun clearAnswer(roomId: String, eventId: String): Boolean

    /** The app came forward, or left: the recap starts again. */
    suspend fun opened(): Boolean

    suspend fun signedOut(): Boolean

    /** Whether the snapshot holds a decision for [roomId] (a read, no write). */
    fun holdsDecision(roomId: String): Boolean
}

/**
 * Every write to the widgets' snapshot, each one a core merge (`core::widget`)
 * run under the writer's [lock]. `WidgetFeed.swift:13-99`.
 *
 * **The judgement is all the core's** (ruling 8): which decisions are owed,
 * how a roster merges over what pushes wrote, the frames and the "since the
 * app was last opened" recap. This only turns a call into
 * `store.write(core(store.read()))` under the lock, writes when the core says
 * it changed, and hands back whether to redraw. Android has one process, so
 * the lock is a process-wide [Mutex] (iOS's is a file lock across processes);
 * the store's atomic replace is the other half.
 *
 * @param now the clock, in milliseconds since the epoch; a parameter so a test can step it.
 */
class WidgetFeed(
    private val store: SnapshotStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val lock: Mutex = Mutex(),
) : WidgetWriting {
    private suspend fun run(merge: (stored: String?, nowMs: ULong) -> WidgetWrite): Boolean = lock.withLock {
        val write = merge(store.read(), now().coerceAtLeast(0).toULong())
        // The core hands back what was stored when nothing changed; no need to rewrite it.
        if (write.changed) store.write(write.json)
        write.reload
    }

    override suspend fun apply(note: NotificationDto): Boolean =
        run { stored, at -> widgetApplyNotification(stored, note, at) }

    override suspend fun apply(rows: List<RoomRow>, live: List<WidgetLiveTurn>, asOfMs: Long): Boolean =
        run { stored, at -> widgetApplyRoster(stored, rows, live, asOfMs.coerceAtLeast(0).toULong(), at) }

    override suspend fun apply(roomId: String, rows: List<TimelineRow>): Boolean =
        run { stored, at -> widgetApplyTimeline(stored, roomId, rows, at) }

    override suspend fun markAnswered(roomId: String, eventId: String, optionId: String): Boolean =
        run { stored, at -> widgetMarkAnswered(stored, roomId, eventId, optionId, at) }

    override suspend fun clearAnswer(roomId: String, eventId: String): Boolean =
        run { stored, at -> widgetClearAnswer(stored, roomId, eventId, at) }

    override suspend fun opened(): Boolean = run { stored, at -> widgetApplyOpened(stored, at) }

    override suspend fun signedOut(): Boolean = run { stored, at -> widgetSignedOut(stored, at) }

    /**
     * What a widget button for [optionId] on this decision sends, or `null`
     * when the snapshot no longer owes it (resolved, already answered, or not
     * an option a button may send). Reads; never writes.
     */
    fun answer(roomId: String, eventId: String, optionId: String): WidgetAnswer? =
        widgetAnswerFor(store.read(), roomId, eventId, optionId)

    /** The snapshot as a widget draws it at [nowMs]. */
    fun view(nowMs: Long = now()): WidgetView = widgetView(store.read(), nowMs.coerceAtLeast(0).toULong())

    override fun holdsDecision(roomId: String): Boolean =
        view().decisions.any { it.decision.roomId == roomId }
}
