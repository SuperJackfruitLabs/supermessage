package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.Acknowledgement

/**
 * The dock above the composer: what the core last said about the open room
 * (iOS `TimelineView.swift:177-236`), and for which room.
 *
 * Held here and not in the pane so that it has the same lifetime as the other
 * per-room stores: it follows the focused room, a late answer for a room that
 * is no longer open is dropped, and sign-out forgets it.
 */
class DockStore {
    /** The room an answer was for, and the answer. */
    data class Held(val roomId: String, val acknowledgement: Acknowledgement)

    private val _held = MutableStateFlow<Held?>(null)
    val held: StateFlow<Held?> = _held.asStateFlow()

    private var roomId: String? = null

    /** Point at [roomId]; anything held for another room goes. */
    fun focus(roomId: String?) {
        this.roomId = roomId
        if (_held.value?.roomId != roomId) _held.value = null
    }

    /**
     * Keep [acknowledgement] as the answer for [roomId] — or drop the dock for
     * `null` — but only while [roomId] is the open room.
     */
    fun hold(roomId: String, acknowledgement: Acknowledgement?) {
        if (roomId != this.roomId) return
        _held.value = acknowledgement?.let { Held(roomId, it) }
    }

    fun clear() {
        roomId = null
        _held.value = null
    }
}
