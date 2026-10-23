package dev.supermessage.kit.notify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.supermessage_core.NotificationContext
import uniffi.supermessage_core.NotificationMode
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.TimelineRow
import uniffi.supermessage_ffi.notificationDelivers
import uniffi.supermessage_ffi.notificationsForRoster
import uniffi.supermessage_ffi.notificationsForTimeline

/** Roster changes are not news for this long after the app comes forward (`PlatformCoordinator.swift:95`). */
const val CATCH_UP_MS = 5_000L

/** How long a room's notification mode is trusted before it is asked again (`PlatformCoordinator.swift:265`). */
const val MODE_CACHE_MS = 60_000L

/**
 * The host half of local notifications: iOS's `PlatformCoordinator.swift:93-117,
 * 214-269`. What notifies, its words, category, muting, "already shown" and the
 * foreground rule are the core's (`notificationsForRoster`,
 * `notificationsForTimeline`, `notificationDelivers`); this class keeps only
 * the lifecycle state the core is handed: the previous roster, the "since"
 * mark, the notified ids, the catch-up window and the mode cache.
 *
 * Calls are serialised by a mutex, so a roster run and a timeline run never
 * interleave around the mode lookup.
 */
class NotificationCoordinator(
    private val poster: Poster,
    private val modeOf: suspend (String) -> NotificationMode?,
    private val now: () -> Long,
) {
    private val lock = Mutex()

    private var previousRooms: List<RoomRow> = emptyList()
    private var timelineRoomId: String? = null
    private var timelineSinceMs: Long = 0
    private val _notified = LinkedHashSet<String>()
    private var catchUpUntilMs: Long = 0
    private val modes = HashMap<String, Pair<NotificationMode?, Long>>()
    private var remotePush = false
    private var active = false
    private var openRoomId: String? = null

    /** Bumped by [signedOut]: a run that was waiting on a mode must not post for the old account. */
    @Volatile
    private var generation = 0

    /**
     * A copy. A push reads it from another thread while a timeline run adds
     * to it, so every touch of the set holds its monitor.
     */
    val notified: Set<String> get() = synchronized(_notified) { _notified.toSet() }

    val context: NotificationContext
        get() = NotificationContext(
            openRoomId = openRoomId,
            appActive = active,
            timelineRoomId = timelineRoomId,
            remotePush = remotePush,
        )

    fun activeChanged(active: Boolean) {
        this.active = active
        if (active) {
            catchUpUntilMs = now() + CATCH_UP_MS
        } else {
            // What the reader saw while looking is not news once they leave:
            // the core suppresses the open room's rows without recording
            // them, so without this mark they would post on the first change
            // after the app goes away.
            timelineSinceMs = maxOf(timelineSinceMs, now())
        }
    }

    fun openRoomChanged(roomId: String?) {
        openRoomId = roomId
    }

    fun setRemotePush(on: Boolean) {
        remotePush = on
    }

    suspend fun roomsChanged(rows: List<RoomRow>): Unit = lock.withLock {
        val before = previousRooms
        previousRooms = rows
        if (now() < catchUpUntilMs) return@withLock
        deliver(notificationsForRoster(before, rows, context))
    }

    suspend fun timelineChanged(roomId: String?, roomName: String, rows: List<TimelineRow>): Unit = lock.withLock {
        if (roomId != timelineRoomId) {
            timelineRoomId = roomId
            timelineSinceMs = now()
        }
        if (now() < catchUpUntilMs) timelineSinceMs = maxOf(timelineSinceMs, now())
        if (roomId == null) return@withLock
        val notes = notificationsForTimeline(
            roomId, roomName, rows, timelineSinceMs.toULong(), synchronized(_notified) { _notified.toList() }, context,
        )
        // Recorded whether or not the room's setting lets them through:
        // "not delivered" is an answer too (`PlatformCoordinator.swift:245-248`).
        synchronized(_notified) { for (note in notes) note.eventId?.let { _notified.add(it) } }
        deliver(notes)
    }

    /** Forgets everything: the next roster is a baseline, nothing is marked notified. */
    fun signedOut() {
        generation++
        previousRooms = emptyList()
        timelineRoomId = null
        timelineSinceMs = 0
        synchronized(_notified) { _notified.clear() }
        catchUpUntilMs = 0
        modes.clear()
        remotePush = false
        openRoomId = null
    }

    private suspend fun deliver(notes: List<uniffi.supermessage_core.PlannedNotification>) {
        val mine = generation
        for (note in notes) {
            val mode = mode(note.roomId)
            if (mine != generation) return
            if (notificationDelivers(note, mode)) poster.post(note)
        }
    }

    private suspend fun mode(roomId: String): NotificationMode? {
        modes[roomId]?.let { (mode, at) -> if (now() - at < MODE_CACHE_MS) return mode }
        val mode = modeOf(roomId)
        modes[roomId] = mode to now()
        return mode
    }
}
