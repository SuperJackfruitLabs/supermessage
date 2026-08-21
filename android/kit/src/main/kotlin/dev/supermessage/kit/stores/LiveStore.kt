package dev.supermessage.kit.stores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An agent's turn while it is still being written.
 *
 * **None of this is history.** It arrives on to-device messages, nothing
 * here has been stored in a room, and the real message follows when the
 * turn ends. So it is kept only for the focused room and thrown away the
 * moment the turn lands — anything else would leave a ghost above a
 * message that already says the same thing.
 *
 * Ported from `apple/SupermessageKit/Stores/LiveStore.swift`. Swift's
 * `@MainActor @Observable` becomes [answer], [thought], [tools] and
 * [finished] exposed as [StateFlow]s, matching the rest of `stores/` — see
 * [DraftStore]'s KDoc for why `@MainActor` becomes a documented, not
 * checked, invariant here.
 *
 * **This store does not pace [answer] onto the screen.** In Swift, pacing
 * belongs to the view — `LiveTurnView` owns its own `StreamingText` and
 * feeds it from `.onChange(of: live.answer)` — and the same split holds
 * here: a future UI is what owns and drives a `StreamingText` for this
 * store's raw `answer`, the same way `LiveTurnView` does on iOS.
 */
class LiveStore {
    private val _answer = MutableStateFlow<String?>(null)

    /** What the agent is writing, or `null` when no turn is live. */
    val answer: StateFlow<String?> = _answer.asStateFlow()

    private val _thought = MutableStateFlow<String?>(null)

    /**
     * Its reasoning, if it is sharing any. Collapsed by default in the
     * view: it is context, not the answer.
     */
    val thought: StateFlow<String?> = _thought.asStateFlow()

    private val _tools = MutableStateFlow<List<ToolCall>>(emptyList())

    /** Tool calls this turn, in the order they fired. */
    val tools: StateFlow<List<ToolCall>> = _tools.asStateFlow()

    private val _finished = MutableStateFlow(false)

    /**
     * Whether the turn has finished.
     *
     * The reasoning and the tool calls **outlive it**. They used to be
     * thrown away the instant the turn landed, which meant the record of
     * how an agent reached its answer was on screen only while it was
     * still being written — and gone by the time anyone had read the
     * answer it belongs to. What goes on [finished] is the streamed
     * *answer*, because the real message arrives on the timeline and says
     * it better; what stays is everything the message does not carry.
     */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    data class ToolCall(
        val id: String,
        val title: String,
        val status: String,
        /** ACP's tool kind, when the harness said. Display text. */
        val kind: String?,
        /** What the call touched — paths, mostly. */
        val locations: List<String>,
        /**
         * What it was given and what it produced, bounded by the core.
         *
         * `null` from a harness that does not report them — which is every
         * harness today. `dev.agentpod.tool.update` carries the fields;
         * the agent side has to start filling them in.
         */
        val input: String?,
        val output: String?,
    ) {
        /** Whether there is anything to open this row onto. */
        val hasDetail: Boolean
            get() = input != null || output != null || locations.isNotEmpty()
    }

    private var roomId: String? = null

    /**
     * The last sequence seen per stream, so a late delta cannot rewind the
     * text. The core numbers these for the same reason the diff channels
     * are numbered.
     */
    private var answerSeq: ULong = 0uL
    private var thoughtSeq: ULong = 0uL

    /**
     * Whether there is anything to show — a turn in progress, or the
     * record of the one that just ended.
     */
    val isLive: Boolean
        get() = answer.value != null || thought.value != null || tools.value.isNotEmpty()

    fun handleLive(roomId: String, seq: ULong, text: String, done: Boolean) {
        if (!accept(roomId)) return
        if (done) {
            // The turn landed. The streamed answer goes, because the real
            // message is arriving on the timeline channel and says it
            // better — but the reasoning and the tool calls stay, because
            // nothing else on screen carries them. They go when the *next*
            // turn starts, or when the reader leaves the room.
            _answer.value = null
            answerSeq = 0uL
            _finished.value = true
            return
        }
        beginTurnIfFinished()
        if (seq < answerSeq) return
        answerSeq = seq
        _answer.value = text
    }

    fun handleThought(roomId: String, seq: ULong, text: String, done: Boolean) {
        if (!accept(roomId)) return
        if (done) {
            // Kept, for the same reason as the tool calls above: reasoning
            // that vanishes the moment the answer appears is reasoning
            // nobody has had time to read.
            _finished.value = true
            return
        }
        beginTurnIfFinished()
        if (seq < thoughtSeq) return
        thoughtSeq = seq
        _thought.value = text
    }

    fun handleTool(
        roomId: String,
        seq: ULong,
        toolCallId: String,
        title: String,
        kind: String?,
        status: String,
        locations: List<String>,
        input: String?,
        output: String?,
    ) {
        if (!accept(roomId)) return
        beginTurnIfFinished()
        val call = ToolCall(
            id = toolCallId, title = title, status = status, kind = kind, locations = locations,
            input = input, output = output,
        )
        val current = _tools.value
        val index = current.indexOfFirst { it.id == toolCallId }
        _tools.value = if (index >= 0) {
            // A call reports again as it progresses — running, then
            // completed. Replacing rather than appending is what keeps one
            // row per call.
            current.toMutableList().also { it[index] = call }
        } else {
            current + call
        }
    }

    /** Focus a room, discarding anything belonging to the last one. */
    fun focus(roomId: String?) {
        this.roomId = roomId
        clear()
    }

    /**
     * The first delta of a new turn clears the last one's record.
     *
     * Here rather than on `done` because that is the whole point: the
     * record has to survive the end of its own turn. It ends when it is
     * replaced.
     */
    private fun beginTurnIfFinished() {
        if (!_finished.value) return
        clear()
    }

    fun clear() {
        _answer.value = null
        _thought.value = null
        _tools.value = emptyList()
        answerSeq = 0uL
        thoughtSeq = 0uL
        _finished.value = false
    }

    /**
     * Whether this belongs to the room on screen.
     *
     * A turn in another room is not this pane's business — showing it
     * would put one agent's writing under another's name.
     */
    private fun accept(roomId: String): Boolean = this.roomId == roomId
}
