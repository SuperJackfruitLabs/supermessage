package dev.supermessage.kit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A room asked for from outside the app: a `supermessage://` link now, a notification tap or a
 * widget later (iOS's `NotificationRouter`). One pending room; a later request replaces it. The
 * shell consumes it once it can show a room, which is why a request made before sign-in waits.
 */
class NavigationRequests {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun request(roomId: String) {
        _pending.value = roomId
    }

    /** The pending room, cleared; `null` when there is none. */
    fun consume(): String? = _pending.getAndUpdate { null }

    fun clear() {
        _pending.value = null
    }
}
