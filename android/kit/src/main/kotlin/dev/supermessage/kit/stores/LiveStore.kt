package dev.supermessage.kit.stores

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.supermessage_core.TurnSnapshot
import uniffi.supermessage_core.TurnStep
import uniffi.supermessage_core.TurnSummary
import uniffi.supermessage_core.TurnUpdate

/**
 * The open room's agent turn, as the core records it.
 *
 * **The core owns the record** (`core::turns`): when a turn started and
 * ended, its steps merged by call id, the order of late reports, the step
 * being worked on, the counts, the collapsed record's label, and how long a
 * finished record is kept. This store mirrors it for the room on screen:
 * [apply] takes the core's update, [restore] its snapshot when a room opens,
 * and nothing here decides what a turn is.
 *
 * **What it still holds itself** is the text, because text does not travel
 * in a turn update: the answer being written ([handleLive]) and the reasoning
 * ([handleThought]). Both arrive on their own channels, after the update that
 * began the turn.
 *
 * **None of this is history.** The streamed answer arrives on to-device
 * messages and has not been stored in a room; the real message follows when
 * the turn ends.
 *
 * Ported from `apple/SupermessageKit/Stores/LiveStore.swift`. Like the other
 * stores, it is driven from one thread at a time (documented, not checked;
 * see [DraftStore]).
 *
 * @param scope where a gap's re-fetch runs. `null` (tests, previews) never
 *   re-fetches on its own; the caller restores by hand.
 */
class LiveStore(private val scope: CoroutineScope? = null) {
    private val _answer = MutableStateFlow<String?>(null)

    /** What the agent is writing, or `null` when no turn is live. */
    val answer: StateFlow<String?> = _answer.asStateFlow()

    private val _thought = MutableStateFlow<String?>(null)

    /** Its reasoning, if it is sharing any. Context, not the answer. */
    val thought: StateFlow<String?> = _thought.asStateFlow()

    private val _steps = MutableStateFlow<List<TurnStep>>(emptyList())

    /** Steps this turn, in the order they first fired, as the core numbered them. */
    val steps: StateFlow<List<TurnStep>> = _steps.asStateFlow()

    private val _summary = MutableStateFlow<TurnSummary?>(null)

    /** The core's summary of the turn on screen, or `null` when it has none. */
    val summary: StateFlow<TurnSummary?> = _summary.asStateFlow()

    private val _finished = MutableStateFlow(false)

    /**
     * Whether the turn has finished — by the agent's `done`, by its reply
     * landing in the room, or by the core's time limit.
     *
     * The reasoning and the steps **outlive it**: what goes is the streamed
     * *answer*, because the real message arrives on the timeline and says it
     * better; what stays is everything the message does not carry.
     */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _inProgress = MutableStateFlow(false)

    /** Whether a turn is going and has not finished. */
    val inProgress: StateFlow<Boolean> = _inProgress.asStateFlow()

    /** Whether the core has a turn for this room, or an answer is being written. */
    val isLive: Boolean
        get() = _summary.value != null || _answer.value != null

    private var roomId: String? = null

    /**
     * Bumped whenever the focus changes or the store is cleared. An answer to
     * a question asked under an older generation is for a room (or a visit)
     * that has gone, and is dropped; comparing room ids alone would let
     * A, B, A accept a reply meant for the first A.
     */
    private var generation = 0L

    /**
     * The newest revision whose step was placed. A step that had to wait
     * (see [parked]) does not advance it, so the update that was skipped is
     * not made stale by the ones after it.
     */
    private var placedRevision: ULong? = null

    private var answerSeq: ULong = 0uL
    private var thoughtSeq: ULong = 0uL

    /**
     * Steps whose index is past the end of [steps]: an update arrived for a
     * step whose predecessors this store has not seen. They are never
     * appended past the gap (that would put the step at the wrong position,
     * and its next report would append it again); they wait for a snapshot
     * to fill it.
     */
    private val parked = sortedMapOf<Int, TurnStep>()

    private var refetch: (suspend (String) -> TurnSnapshot?)? = null
    private var resync: Job? = null

    // -- Who ---------------------------------------------------------------

    /**
     * What to call the agent, by room — set by the pane that knows the room's
     * header. Keyed by room and not cleared by [focus], for the race
     * [TypingStore.recognise] records.
     */
    private val agentNames = mutableMapOf<String, String>()

    fun setAgentName(name: String?, roomId: String) {
        if (name == null) agentNames.remove(roomId) else agentNames[roomId] = name
    }

    /** The header's name for the agent in [roomId], when it has one. */
    fun agentName(roomId: String): String? = agentNames[roomId]

    // -- From the core -----------------------------------------------------

    /**
     * The core's update for a turn. Everything but the focused room's is
     * ignored, and so is anything older than what is held.
     *
     * A different `turnId` is a new turn: the last one's steps, reasoning and
     * answer go. The core sends the update **before** the text that began the
     * turn, so clearing here does not wipe it.
     */
    fun apply(update: TurnUpdate) {
        val incoming = update.summary
        if (!accept(incoming.roomId)) return
        val watermark = placedRevision
        if (watermark != null && incoming.revision < watermark) return
        val held = _summary.value
        if (held != null && held.turnId != incoming.turnId) {
            forgetTurn()
            setSummary(incoming)
        } else if (held == null || incoming.revision >= held.revision) {
            setSummary(incoming)
        }
        if (incoming.endedBy != null) {
            // However it ended the streamed answer is over: the real message
            // replaces it, or nothing more is coming.
            _answer.value = null
            answerSeq = 0uL
        }
        val change = update.step
        if (change == null) {
            placedRevision = maxOf(placedRevision ?: incoming.revision, incoming.revision)
            return
        }
        if (place(change.step, change.index.toInt())) {
            placedRevision = maxOf(placedRevision ?: incoming.revision, incoming.revision)
        } else {
            requestResync()
        }
    }

    /**
     * Put a step at the index the core gave it: `index == size` appends; past
     * it an update was missed, so the step waits. Returns whether it landed.
     */
    private fun place(step: TurnStep, index: Int): Boolean {
        val list = _steps.value
        if (index < list.size) {
            _steps.value = list.toMutableList().also { it[index] = step }
            return true
        }
        if (index > list.size) {
            parked[index] = step
            return false
        }
        val grown = list.toMutableList()
        grown.add(step)
        while (true) {
            grown.add(parked.remove(grown.size) ?: break)
        }
        _steps.value = grown
        return true
    }

    /**
     * Fold the core's steps into what is held, step by step, keeping the one
     * with the higher sequence (a call's own, which only grows). Held steps
     * the snapshot has not reached yet survive, and so do waiting ones that
     * now fit.
     */
    private fun adopt(fetched: List<TurnStep>) {
        val merged = fetched.toMutableList()
        _steps.value.forEachIndexed { index, held ->
            if (index < merged.size) {
                if (held.seq > merged[index].seq) merged[index] = held
            } else {
                merged.add(held)
            }
        }
        for (index in parked.keys.toList()) {
            val waiting = parked.getValue(index)
            if (index < merged.size) {
                if (waiting.seq > merged[index].seq) merged[index] = waiting
                parked.remove(index)
            } else if (index == merged.size) {
                merged.add(waiting)
                parked.remove(index)
            }
        }
        _steps.value = merged
    }

    /** Ask the core for the whole record again, after a step arrived with a gap before it. */
    private fun requestResync() {
        val room = roomId ?: return
        val fetch = refetch ?: return
        val launcher = scope ?: return
        if (resync?.isActive == true) return
        val asked = generation
        resync = launcher.launch {
            val snapshot = fetch(room)
            // Left the room meanwhile: the answer is for the last visit.
            if (asked != generation) return@launch
            resync = null
            restore(snapshot)
        }
    }

    /**
     * A room's whole record, from the core, when it opens.
     *
     * The live channel may have shown something newer in the time the
     * question took. For another turn, that wins outright. For the same turn,
     * the summary stays at the newer revision but the steps are merged: an
     * update that arrived first carries one step, and the snapshot has the
     * ones before it.
     */
    fun restore(snapshot: TurnSnapshot?) {
        if (snapshot == null || !accept(snapshot.summary.roomId)) return
        val held = _summary.value
        val sameTurn = held?.turnId == snapshot.summary.turnId
        if (held != null && !sameTurn && snapshot.summary.revision < held.revision) return
        if (held != null && !sameTurn) {
            forgetTurn()
            setSummary(snapshot.summary)
        } else if (held == null || snapshot.summary.revision >= held.revision) {
            setSummary(snapshot.summary)
        }
        adopt(snapshot.steps)
        // A thought streamed in since the question was asked is newer.
        if (thoughtSeq == 0uL) _thought.value = snapshot.thought
        placedRevision = maxOf(placedRevision ?: snapshot.summary.revision, snapshot.summary.revision)
    }

    // -- Text --------------------------------------------------------------

    fun handleLive(roomId: String, seq: ULong, text: String, done: Boolean) {
        if (!accept(roomId)) return
        if (done) {
            // The turn landed. The streamed answer goes, because the real
            // message is arriving on the timeline channel and says it better.
            // The reasoning and the steps stay: nothing else on screen
            // carries them.
            _answer.value = null
            answerSeq = 0uL
            return
        }
        if (seq < answerSeq) return
        answerSeq = seq
        _answer.value = text
    }

    fun handleThought(roomId: String, seq: ULong, text: String, done: Boolean) {
        if (!accept(roomId)) return
        // A thought's own `done` keeps the text: reasoning that vanishes the
        // moment the answer appears is reasoning nobody has had time to read.
        if (done) return
        if (seq < thoughtSeq) return
        thoughtSeq = seq
        _thought.value = text
    }

    // -- Focus -------------------------------------------------------------

    /**
     * Focus a room: reset, then show the core's record of its turn.
     *
     * The core keeps a record for every room, including one still in
     * progress, so leaving a room and coming back loses nothing. The streamed
     * answer is not part of the record: a turn restored mid-answer shows the
     * rest of it from the next delta.
     *
     * A reply for a room that was left while [fetch] ran is dropped.
     */
    suspend fun focus(roomId: String?, fetch: suspend (String) -> TurnSnapshot?) {
        select(roomId)
        if (roomId == null) return
        refetch = fetch
        val asked = generation
        val snapshot = fetch(roomId)
        if (asked != generation) return
        restore(snapshot)
    }

    /**
     * Point the store at a room, with nothing of the last one's on screen.
     * Synchronous, for callers that cannot wait on the core.
     */
    fun select(roomId: String?) {
        generation += 1
        this.roomId = roomId
        refetch = null
        forgetTurn()
        setSummary(null)
        placedRevision = null
    }

    /** Forget everything — signing out. The core clears its own book. */
    fun clear() {
        select(null)
        agentNames.clear()
    }

    private fun setSummary(summary: TurnSummary?) {
        _summary.value = summary
        _finished.value = summary?.endedBy != null
        _inProgress.value = summary?.inProgress == true
    }

    /** Drop the turn on screen but not the summary, which the caller sets. */
    private fun forgetTurn() {
        _answer.value = null
        _thought.value = null
        _steps.value = emptyList()
        answerSeq = 0uL
        thoughtSeq = 0uL
        parked.clear()
        resync?.cancel()
        resync = null
        placedRevision = null
    }

    /**
     * Whether this belongs to the room on screen.
     *
     * A turn in another room is not this pane's business — showing it would
     * put one agent's writing under another's name.
     */
    private fun accept(roomId: String): Boolean = this.roomId == roomId
}
