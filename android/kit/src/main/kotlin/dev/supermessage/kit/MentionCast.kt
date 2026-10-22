package dev.supermessage.kit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.Mentionable
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_ffi.peopleMatching

/**
 * Who could be mentioned in the open room (`RoomCast.swift:43-62, 103-114`).
 *
 * Only the join that the mention picker needs: counterpart and addressee are
 * the core's later work (M1.2) and are not here.
 */
class MentionCast {
    private val _people = MutableStateFlow<List<PersonDto>>(emptyList())
    val people: StateFlow<List<PersonDto>> = _people.asStateFlow()

    /**
     * Load the cast for [roomId]. Closures rather than a client, because the
     * calls are `Session`'s. [memberIds] answering `null` (the room's detail
     * could not be read) falls back to everyone known: a picker offering too
     * many people is a smaller fault than one offering nobody.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun load(
        roomId: String,
        allPeople: suspend () -> List<PersonDto>,
        memberIds: suspend () -> List<String>?,
    ) {
        val everyone = allPeople()
        val members = memberIds()
        _people.value = if (members != null) {
            val ids = members.toSet()
            everyone.filter { it.userId in ids }
        } else {
            everyone
        }
    }

    /** The people an `@` query offers, agents first, by the core's matching. */
    fun candidates(query: String, limit: Int = 6): List<PersonDto> =
        peopleMatching(_people.value, query).take(limit)

    /**
     * Everyone a message here could address, labelled exactly as the picker
     * inserts them, since `collectMentions` matches on `@` + that label.
     */
    val mentionables: List<Mentionable>
        get() = _people.value.map { Mentionable(userId = it.userId, displayName = MentionComposing.label(it)) }
}
