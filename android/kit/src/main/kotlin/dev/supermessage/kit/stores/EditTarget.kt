package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uniffi.supermessage_core.TimelineRow

/**
 * The message being rewritten, per room.
 *
 * Separate from [ReplyTarget] rather than a mode on it, because they are not
 * alternatives to each other in the composer's logic: a reply is addressed to
 * someone else's message and sends something new, an edit replaces one of
 * your own. Merging them would make the composer's send path ask "which kind
 * of pending thing is this" on every keystroke.
 *
 * Like [ReplyTarget], the body on [Pending] is a **snapshot** taken when the
 * edit was started. If the message changes underneath — an edit from another
 * device, say — the text the reader is part-way through typing must not be
 * replaced beneath their cursor.
 *
 * Ported from `apple/SupermessageKit/Stores/EditTarget.swift`. See
 * [DraftStore]'s doc comment for why `@MainActor` becomes a documented, not
 * checked, invariant here.
 */
class EditTarget {
    data class Pending(
        val eventId: String,
        /** What the message said when the edit began, to seed the composer. */
        val body: String,
    )

    private val _targets = MutableStateFlow<Map<String, Pending>>(emptyMap())
    val targets: StateFlow<Map<String, Pending>> = _targets.asStateFlow()

    fun pending(roomId: String): Pending? = _targets.value[roomId]

    /**
     * Begin editing a row, if it is one this account may rewrite.
     *
     * Returns the text the composer should start from, or `null` when the
     * row cannot be edited — so a caller cannot enter an edit mode that has
     * nothing to edit. `editable` is the SDK's answer (see
     * `TimelineItemDto.editable`), never inferred here from `isOwn`.
     */
    fun start(row: TimelineRow, roomId: String): String? {
        if (!row.item.editable) return null
        val eventId = row.item.eventId ?: return null
        val body = row.item.body ?: ""
        _targets.update { it + (roomId to Pending(eventId, body)) }
        return body
    }

    fun cancel(roomId: String) {
        _targets.update { it - roomId }
    }

    fun clearAll() {
        _targets.value = emptyMap()
    }
}
