package dev.supermessage.kit

import java.time.Instant
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RosterSection
import uniffi.supermessage_core.RosterView
import uniffi.supermessage_ffi.rosterHiddenInvitations
import uniffi.supermessage_ffi.rosterSections
import uniffi.supermessage_ffi.rosterState

/**
 * Which arrangement the reader chose.
 *
 * A Kotlin enum in front of the core's `RosterView` rather than the core's
 * type directly, for the same host-shaped reason Swift gives: a picker
 * needs a stable, enumerable set of choices with a display title, and the
 * *rules* are not here — see `RosterArrangement`.
 */
enum class RosterChoice(val title: String) {
    RECENT("Recent"),
    WAITING("Waiting"),
    MACHINE("Machine");

    /** The core's spelling of the same choice. */
    val core: RosterView
        get() = when (this) {
            RECENT -> RosterView.RECENT
            WAITING -> RosterView.WAITING
            MACHINE -> RosterView.MACHINE
        }
}

/**
 * What the roster says out loud.
 *
 * The words are the core's — `AgentState::word` — repeated here because a
 * `&'static str` on a Rust enum does not cross a UniFFI boundary. If they
 * ever diverge, the core is right.
 */
val AgentState.word: String
    get() = when (this) {
        AgentState.NEEDS_YOU -> "needs you"
        AgentState.ACTIVE -> "active"
        AgentState.IDLE -> "idle"
        AgentState.QUIET -> "quiet"
    }

/**
 * Turning a flat roster into the arrangement a reader chose.
 *
 * **Every rule moved to `core::roster`.** They are product decisions about
 * what a fleet looks like — how long silence takes to become quiet, which
 * room outranks which, what a section is called when it is the only one —
 * and two hosts each holding a copy is two clients that disagree about what
 * a roster is, which is exactly what happened. What is left here is the
 * call, and the two host-shaped conveniences above it.
 */
object RosterArrangement {
    /**
     * What the roster may say about a room.
     *
     * Rarely needed on its own: [sections] already carries each row's
     * state, so a list should read it there rather than asking per row.
     */
    fun state(row: RoomRow, now: Instant): AgentState = rosterState(row, milliseconds(now))

    /** Arrange [rows] for one view. */
    fun sections(
        rows: List<RoomRow>,
        view: RosterChoice,
        showsInvitations: Boolean,
        now: Instant,
    ): List<RosterSection> = rosterSections(rows, view.core, showsInvitations, milliseconds(now))

    /** How many invitations are being withheld, for the picker to admit to. */
    fun hiddenInvitations(rows: List<RoomRow>, showsInvitations: Boolean): Int =
        rosterHiddenInvitations(rows, showsInvitations).toInt()

    private fun milliseconds(now: Instant): ULong = maxOf(0L, now.toEpochMilli()).toULong()
}
