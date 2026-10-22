package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.TimelineRow

/** Where the ids of reported messages survive a restart. Synchronous on purpose. */
interface HiddenEventStorage {
    fun load(): Set<String>
    fun save(ids: Set<String>)
    fun clear()
}

class InMemoryHiddenEvents : HiddenEventStorage {
    private var ids: Set<String> = emptySet()
    override fun load(): Set<String> = ids
    override fun save(ids: Set<String>) {
        this.ids = ids
    }
    override fun clear() {
        ids = emptySet()
    }
}

/**
 * Who this account has blocked (the core's list) and which messages it
 * reported (this device's list). Ported from
 * `apple/SupermessageKit/Stores/SafetyStore.swift`.
 *
 * ## Generations
 *
 * [clear] bumps [generation]. Every write takes the generation its caller
 * saw before it started waiting, and does nothing when that is no longer the
 * current one: a report that finishes after a sign-out must not put its
 * message id back into a store the next account will read.
 *
 * Hidden messages stay on the device (open question Q8); only event ids are
 * stored.
 */
class SafetyStore(private val storage: HiddenEventStorage = InMemoryHiddenEvents()) {
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    private val _hidden = MutableStateFlow(storage.load())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    @Volatile
    var generation: Int = 0
        private set

    /** The core's list replaces ours wholesale. */
    fun apply(ignored: List<String>, generation: Int = this.generation) {
        if (generation != this.generation) return
        _blocked.value = ignored.toSet()
    }

    fun isBlocked(userId: String): Boolean = userId in _blocked.value

    fun noteBlocked(userId: String, generation: Int = this.generation) {
        if (generation != this.generation) return
        _blocked.value = _blocked.value + userId
    }

    fun noteUnblocked(userId: String, generation: Int = this.generation) {
        if (generation != this.generation) return
        _blocked.value = _blocked.value - userId
    }

    fun hide(eventId: String, generation: Int = this.generation) {
        if (generation != this.generation) return
        val next = _hidden.value + eventId
        _hidden.value = next
        storage.save(next)
    }

    /** Rows whose event was reported drop out; a row with no event id always passes. */
    fun visible(rows: List<TimelineRow>): List<TimelineRow> {
        val hiding = _hidden.value
        if (hiding.isEmpty()) return rows
        return rows.filter { row ->
            val eventId = row.item.eventId
            eventId == null || eventId !in hiding
        }
    }

    /** Forget everything, including what is stored. */
    fun clear() {
        generation += 1
        _blocked.value = emptySet()
        _hidden.value = emptySet()
        storage.clear()
    }
}
