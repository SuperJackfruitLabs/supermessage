package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.TypingUserDto

/**
 * Who is typing in the focused room.
 *
 * Scoped to that room because the channel is: the core only reports typing
 * for the room it has focused, so there is nothing to show on a roster row
 * and pretending otherwise would invent it.
 *
 * Ported from `apple/SupermessageKit/Stores/TypingStore.swift`. Swift's
 * `@MainActor @Observable` becomes [typers] exposed as a [StateFlow],
 * matching the rest of `stores/` — see [DraftStore]'s doc comment for why
 * `@MainActor` becomes a documented, not checked, invariant here rather than
 * a compiler-enforced one.
 */
class TypingStore {
    /**
     * Who, by user id, and what to call them. Swift's `(userId: String,
     * label: String)` tuple becomes this data class — Kotlin has no
     * anonymous labelled-tuple equivalent.
     */
    data class Typer(val userId: String, val label: String)

    private val _typers = MutableStateFlow<List<Typer>>(emptyList())

    /**
     * Who, by **user id**, and what to call them.
     *
     * Keyed on the id rather than the name, because the name is not an
     * identity: the core hands out `label` ("Super Chotu") for the line and
     * a message arrives carrying `senderName` ("Super Chotu (Hermes on
     * Guild)"), and matching one against the other is how the indicator got
     * stuck. Two strings that describe the same person are not the same
     * string; the id is.
     */
    val typers: StateFlow<List<Typer>> = _typers.asStateFlow()

    private var roomId: String? = null

    fun handle(roomId: String, users: List<TypingUserDto>) {
        if (roomId != this.roomId) return
        _typers.value = users.map { Typer(it.userId, it.label) }
    }

    /**
     * Someone spoke, so they are no longer about to.
     *
     * Matrix typing notices expire on a server-side timeout, and a sender
     * that never explicitly retracts one leaves the line up for as long as
     * that timeout runs — which is why "X is typing…" sat on screen long
     * after X's message had arrived. The client does not have to wait for
     * the timeout: the message is better evidence than the notice.
     *
     * **Takes user ids.** It used to take display names, and the names it
     * was given were the timeline's composed attribution while the ones it
     * held were the raw profile names — so nothing ever matched and nothing
     * was ever removed. The bug was invisible because the code read as
     * though it did the right thing.
     *
     * Deliberately not latching. An agent that sends one message and starts
     * writing the next is typing again, and the next notice must be able to
     * bring the line back.
     */
    fun messagesArrived(senderIds: List<String>) {
        if (_typers.value.isEmpty()) return
        _typers.value = _typers.value.filterNot { senderIds.contains(it.userId) }
    }

    fun focus(roomId: String?) {
        this.roomId = roomId
        _typers.value = emptyList()
    }

    /**
     * The line to show, or `null` when nobody is typing.
     *
     * Names rather than a count: in a room of agents, *which* one is about
     * to speak is the useful half.
     */
    val line: String?
        get() {
            val names = _typers.value.map { it.label }
            return when (names.size) {
                0 -> null
                1 -> "${names[0]} is typing…"
                2 -> "${names[0]} and ${names[1]} are typing…"
                else -> "${names[0]} and ${names.size - 1} others are typing…"
            }
        }
}
