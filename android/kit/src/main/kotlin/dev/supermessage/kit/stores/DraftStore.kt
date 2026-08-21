package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What is half-typed, per room.
 *
 * Scoped by room and kept when the reader switches away, because a draft that
 * vanished on a room switch would lose work — and the desktop already learned
 * that the *opposite* mistake is worse: a draft that followed the reader into
 * another room once put a half-written message in front of the wrong agent.
 *
 * Ported from `apple/SupermessageKit/Stores/DraftStore.swift`. Swift's
 * `@MainActor @Observable` becomes a plain class exposing [drafts] as a
 * [StateFlow]: the map is the whole observed state, and Kotlin has no
 * compiler-enforced main-thread isolation to mirror `@MainActor` with — this
 * is meant to be read and written only from the main thread, the same
 * discipline `Session` (Task 15) gives every store here, documented rather
 * than checked.
 */
class DraftStore {
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    fun draft(roomId: String): String = _drafts.value[roomId] ?: ""

    fun set(text: String, roomId: String) {
        _drafts.update { current ->
            if (text.isEmpty()) current - roomId else current + (roomId to text)
        }
    }

    fun clear(roomId: String) {
        _drafts.update { it - roomId }
    }

    fun clearAll() {
        _drafts.value = emptyMap()
    }
}
