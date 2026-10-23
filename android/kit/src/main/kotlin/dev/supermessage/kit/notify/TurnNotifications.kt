package dev.supermessage.kit.notify

import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_core.TurnCard
import uniffi.supermessage_core.TurnSummary
import uniffi.supermessage_core.TurnUpdate
import uniffi.supermessage_ffi.turnCard

/**
 * What the ongoing notification of a running agent turn says (P3): the step as its headline, and
 * "{name} · step {c} of {t}" or the name, as the core's `turnCard` words them. The elapsed time is
 * not a word here: the notification's chronometer counts it from [startedAtMs].
 */
data class TurnNotice(
    val roomId: String,
    val headline: String,
    val subtitle: String,
    val startedAtMs: Long,
    val totalSteps: Int,
    val completedSteps: Int,
)

/** What a turn update does to the notification of its room. */
sealed interface TurnChange {
    /** Put [notice] up, or change it in place. Only sent when its words differ from what is up. */
    data class Show(val notice: TurnNotice) : TurnChange

    /** The turn ended (however), or never ran: nothing of it stays. */
    data class Clear(val roomId: String) : TurnChange

    /** Nothing to do: a stale update, or the same words again. */
    data object None : TurnChange
}

/**
 * Maps the core's turn updates to [TurnChange]s, one running turn per room.
 *
 * **It shows the RUNNING turn only, and goes away when the turn ends.** The core's `Done` means
 * the agent's stream ended, which is how every answered turn ends, and the answer arrives as an
 * M6 message notification: a "finished" note here would double it (the desktop dropped its
 * turn-ended note for that reason). A failed or timed-out turn also just leaves; `turnCard`
 * gives a failed turn words, but the room's own card and the reply carry that.
 *
 * Updates older than the last one seen for the room are ignored: the same turn with an older
 * `revision`, or an older turn (the core numbers turns forward, and a host discards anything
 * older than what it holds). A newer turn replaces the old one.
 *
 * [nameOf] is the agent doing the turn: [agentDisplayName] through the room's cast, or the
 * room's name. One thread at a time.
 */
class TurnNotifications(
    private val nameOf: (roomId: String, agentId: String?) -> String,
    private val card: (TurnSummary, String) -> TurnCard? = ::turnCard,
) {
    private class Seen(val turnId: ULong, val revision: ULong, val shown: TurnNotice?)

    private val rooms = HashMap<String, Seen>()

    fun apply(update: TurnUpdate): TurnChange {
        val summary = update.summary
        val room = summary.roomId
        val seen = rooms[room]
        if (seen != null) {
            if (summary.turnId < seen.turnId) return TurnChange.None
            if (summary.turnId == seen.turnId && summary.revision < seen.revision) return TurnChange.None
        }
        val running = if (summary.inProgress) card(summary, nameOf(room, summary.agentId))?.takeIf { it.inProgress } else null
        val notice = running?.let {
            TurnNotice(
                roomId = room,
                headline = it.headline,
                subtitle = it.subtitle,
                startedAtMs = summary.startedAtMs.toLong(),
                totalSteps = summary.totalSteps.toInt(),
                completedSteps = summary.completedSteps.toInt(),
            )
        }
        val before = seen?.shown
        rooms[room] = Seen(summary.turnId, summary.revision, notice)
        return when {
            notice == null -> TurnChange.Clear(room)
            notice == before -> TurnChange.None
            else -> TurnChange.Show(notice)
        }
    }

    /** The notices that are up, by room (to put them up again when the room in front changes). */
    fun running(): List<TurnNotice> = rooms.values.mapNotNull { it.shown }

    /** Sign-out: no room's turn is known any more. */
    fun forget() = rooms.clear()
}

/**
 * The agent doing a turn, by the room cast's display name: the turn's own `agentId` (which the
 * core takes from the sender of its first live event) when the cast knows it, the cast's
 * counterpart or one of its people. `null` when it does not (the caller falls back to the room's
 * name). Mirrors the in-room card's `liveWriterName`.
 */
fun agentDisplayName(agentId: String?, cast: RoomCast?): String? {
    if (agentId == null || cast == null) return null
    val counterpart = cast.counterpart
    return when {
        counterpart != null && counterpart.userId == agentId -> counterpart.name
        agentId in cast.agentIds -> cast.people.firstOrNull { it.userId == agentId }?.name
        else -> null
    }
}
