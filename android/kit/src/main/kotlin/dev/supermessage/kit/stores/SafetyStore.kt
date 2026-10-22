package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.TimelineRow

/**
 * Where the ids of reported messages survive a restart, one set per account:
 * a set belongs to the user id it was saved under and is read back only for
 * that account. Synchronous on purpose.
 *
 * Older builds kept a single device-wide set. [takeLegacy] hands that over
 * once and forgets it.
 */
interface HiddenEventStorage {
    fun load(owner: String): Set<String>
    fun save(owner: String, ids: Set<String>)

    /** Forget one account's set. */
    fun clear(owner: String)

    /** Forget every account's set, and the legacy one. */
    fun clearAll()

    /** The device-wide set an older build kept, removed as it is read. */
    fun takeLegacy(): Set<String>
}

class InMemoryHiddenEvents : HiddenEventStorage {
    private val sets = mutableMapOf<String, Set<String>>()
    private var legacy: Set<String> = emptySet()

    override fun load(owner: String): Set<String> = sets[owner] ?: emptySet()
    override fun save(owner: String, ids: Set<String>) {
        sets[owner] = ids
    }
    override fun clear(owner: String) {
        sets.remove(owner)
    }
    override fun clearAll() {
        sets.clear()
        legacy = emptySet()
    }
    override fun takeLegacy(): Set<String> = legacy.also { legacy = emptySet() }

    /** For a test: what an older build left behind. */
    fun seedLegacy(ids: Set<String>) {
        legacy = ids
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
    /** The account the hidden set belongs to; `null` until one signs in. */
    @Volatile
    var owner: String? = null
        private set

    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
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

    /**
     * Load the hidden set of the account that just reached signed-in, and
     * only that one's. [adoptLegacy] says the account was restored from the
     * device: the old device-wide set is then assigned to it. After a fresh
     * sign-in the old set belongs to nobody known and is dropped.
     * Bumps [generation], so a write begun before cannot land in this set.
     */
    fun bind(owner: String?, adoptLegacy: Boolean) {
        generation += 1
        this.owner = owner
        val legacy = storage.takeLegacy()
        if (owner == null) {
            _hidden.value = emptySet()
            return
        }
        val stored = storage.load(owner)
        val ids = if (adoptLegacy) stored + legacy else stored
        if (ids != stored) storage.save(owner, ids)
        _hidden.value = ids
    }

    fun hide(eventId: String, generation: Int = this.generation) {
        if (generation != this.generation) return
        val next = _hidden.value + eventId
        _hidden.value = next
        owner?.let { storage.save(it, next) }
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

    /** Sign-out: forget everything, including the signed-in account's stored set. */
    fun clear() {
        generation += 1
        _blocked.value = emptySet()
        _hidden.value = emptySet()
        owner?.let { storage.clear(it) }
        owner = null
    }

    /** Nothing is stored to restore: no account's set, nor the legacy one, outlives that. */
    fun clearAll() {
        clear()
        storage.clearAll()
    }
}
