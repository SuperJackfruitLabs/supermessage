package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uniffi.supermessage_core.TimelineRow

/**
 * The message a reply is being composed against, per room.
 *
 * Everything on [Pending] is a **snapshot** taken when the reply was started,
 * not a live binding: if the parent is redacted, or scrolls out of the
 * locally materialised timeline, the preview must not change or disappear
 * under the person writing. Sending still works — the core resolves the
 * parent by id, fetching from the homeserver when it is not cached.
 *
 * Ported from `apple/SupermessageKit/Stores/ReplyTarget.swift`. See
 * [DraftStore]'s doc comment for why `@MainActor` becomes a documented, not
 * checked, invariant here.
 */
class ReplyTarget {
    data class Pending(val eventId: String, val sender: String, val excerpt: String?)

    private val _targets = MutableStateFlow<Map<String, Pending>>(emptyMap())
    val targets: StateFlow<Map<String, Pending>> = _targets.asStateFlow()

    fun pending(roomId: String): Pending? = _targets.value[roomId]

    /**
     * Start a reply from a row.
     *
     * Every field comes off the row rather than being derived here: the
     * attribution chain and the excerpt's bounding are the core's, so the
     * composer shows exactly what the timeline showed.
     */
    fun start(row: TimelineRow, roomId: String) {
        // The *event* id, not `item.id`. Identity is stable across the
        // local-echo-to-confirmed transition and is therefore not something
        // the homeserver has ever heard of. A reply is only offered once the
        // event exists (`canReplyOrReact` reads exactly this field), so the
        // fallback is unreachable rather than a silent default.
        val eventId = row.item.eventId ?: return
        _targets.update { it + (roomId to Pending(eventId, row.senderName, row.replyPreview)) }
    }

    fun cancel(roomId: String) {
        _targets.update { it - roomId }
    }

    fun clearAll() {
        _targets.value = emptyMap()
    }
}
