package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_core.TypingUserDto
import uniffi.supermessage_core.TypingView
import uniffi.supermessage_ffi.typingView

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
        rebuildView()
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
        rebuildView()
    }

    fun focus(roomId: String?) {
        this.roomId = roomId
        _typers.value = emptyList()
        // Signing out focuses nothing, and nothing from that account may
        // survive into the next one.
        if (roomId == null) casts.clear()
        rebuildView()
    }

    // -- Who is who ----------------------------------------------------------

    /**
     * The casts the panes have loaded, by room. Keyed by room and not cleared
     * by a room switch: the pane's load can answer before or after [focus]
     * points here, and a cast for a room that is not focused must neither
     * wait for it nor leak into the one that is.
     */
    private val casts = mutableMapOf<String, RoomCast>()

    /**
     * Learn who is who in [roomId], so an agent typing is named the way the
     * header names it and leaves the people's line alone.
     */
    fun recognise(cast: RoomCast, roomId: String) {
        casts[roomId] = cast
        if (roomId == this.roomId) rebuildView()
    }

    private val _view = MutableStateFlow(TypingView(line = null, agents = emptyList()))

    /**
     * What to show for [typers], from **one** `typingView` call per change:
     * the line and the agents are two halves of the same answer and must not
     * disagree. Observable, because a cast arriving changes it with no change
     * to [typers].
     */
    val view: StateFlow<TypingView> = _view.asStateFlow()

    /** The agents whose typing notice is up, by the name the header gives them. */
    val typingAgents: List<String> get() = _view.value.agents

    /**
     * The line to show, or `null` when nobody other than an agent is typing.
     *
     * Names rather than a count: in a room of agents, *which* one is about
     * to speak is the useful half. The wording is the core's (`typing_view`);
     * this host keeps no copy of the rule.
     */
    val line: String? get() = _view.value.line

    private fun rebuildView() {
        _view.value = compute()
    }

    private fun compute(): TypingView {
        val cast = roomId?.let { casts[it] }
        return typingView(
            _typers.value.map { TypingUserDto(it.userId, null, it.label) },
            cast?.agentIds.orEmpty(),
            cast?.counterpart,
        )
    }
}
