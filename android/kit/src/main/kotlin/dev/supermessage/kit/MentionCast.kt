package dev.supermessage.kit

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.Mentionable
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_ffi.peopleMatching

/**
 * Who could be mentioned in the open room: the core's [RoomCast] for the room
 * on screen, held for the `@` picker (`ComposerView.swift:258-264`).
 *
 * The join of everyone known with the room's members, the agents and the
 * names a message can address are all the core's (`Core.roomCast`); this holds
 * the answer and guards it against arriving for a room that has since gone.
 */
class MentionCast {
    private val _cast = MutableStateFlow<RoomCast?>(null)

    /** The room the newest [load] was asked for; an older answer is dropped. */
    private val newest = AtomicLong(0)

    /** The cast of the room last loaded, or `null` before one has arrived. */
    val cast: StateFlow<RoomCast?> = _cast.asStateFlow()

    /** The people of the room last loaded. */
    val people: List<PersonDto> get() = _cast.value?.people.orEmpty()

    /**
     * Load the cast for [roomId] from [fetch] (`Session::roomCast`).
     *
     * A cast held for another room is dropped at once, so the picker never
     * offers the last room's people while this one is loading. If a newer
     * load starts before this one answers, this answer is discarded: a slow
     * reply for the room left behind must not overwrite the room on screen.
     * `null` from [fetch] (the core could not answer) leaves the cast empty.
     */
    suspend fun load(roomId: String, fetch: suspend (roomId: String) -> RoomCast?) {
        val mine = newest.incrementAndGet()
        if (_cast.value?.roomId != roomId) _cast.value = null
        val loaded = fetch(roomId)
        if (newest.get() != mine) return
        _cast.value = loaded?.takeIf { it.roomId == roomId }
    }

    /** The people an `@` query offers, agents first, by the core's matching. */
    fun candidates(query: String, limit: Int = 6): List<PersonDto> =
        peopleMatching(people, query).take(limit)

    /** Everyone a message here could address, named as the picker inserts them. */
    val mentionables: List<Mentionable>
        get() = _cast.value?.mentionables.orEmpty()
}
