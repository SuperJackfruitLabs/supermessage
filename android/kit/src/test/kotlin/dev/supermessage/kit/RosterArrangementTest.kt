package dev.supermessage.kit

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.Membership
import uniffi.supermessage_core.RoomAffordance
import uniffi.supermessage_core.RoomIdentity
import uniffi.supermessage_core.RoomPreview
import uniffi.supermessage_core.RoomRow
import uniffi.supermessage_core.RoomSummary
import uniffi.supermessage_core.RuntimeDto

/**
 * The roster's rules, as this host sees them through the boundary.
 *
 * **The rules themselves live in `core::roster`,** and are stated once
 * there, in Rust, with their own tests — they are product decisions about
 * what a fleet looks like, and two hosts each holding a copy is two clients
 * that disagree about what a roster is.
 *
 * What remains worth asserting here is that the boundary carries those
 * answers faithfully: the choice maps to the right core view, sections come
 * back in order, and each row arrives already knowing its state. A rule
 * that changes should fail in Rust first and here second.
 */
class RosterArrangementTest {

    companion object {
        private val now: Instant = Instant.ofEpochMilli(1_700_000_000_000L)

        /**
         * Unlike every other kit test, these calls actually cross into
         * Rust: `rosterState`/`rosterSections`/`rosterHiddenInvitations`
         * exist only in `core::roster`, so exercising this file means the
         * JVM test process must load the compiled Core library — not just
         * construct its plain data classes, which every other kit test does
         * without ever touching JNA.
         *
         * Xcode links `SupermessageKitTests` against the framework directly,
         * so the Swift suite never had to ask this question. A JVM test
         * process has to be pointed at a host build by hand (see
         * `:kit`'s build file), and that build is never a Gradle output —
         * `cargo build` produces it, and `target/` is gitignored, so a fresh
         * clone will not have it yet. Skip with a clear reason rather than
         * fail with a bare `UnsatisfiedLinkError` far from its cause.
         */
        @BeforeClass
        @JvmStatic
        fun ensureHostCoreIsBuilt() {
            val dir = System.getProperty("jna.library.path")
            val hostLib = File(dir, "libsupermessage_ffi.so")
            assumeTrue(
                "RosterArrangementTest calls the real Core and needs a host build at " +
                    "$hostLib — run `cargo build -p supermessage-ffi` from the repo root, then retry.",
                hostLib.exists(),
            )
        }

        fun row(
            id: String,
            minutesAgo: Double = 1.0,
            pending: Boolean = false,
            host: String? = null,
            invited: Boolean = false,
        ): RoomRow {
            val ms = (now.toEpochMilli() - (minutesAgo * 60.0 * 1000.0).toLong()).toULong()
            val summary = RoomSummary(
                id = id,
                name = id,
                avatarUrl = null,
                unread = 0uL,
                lastMessage = "hi",
                lastMessageIsOwn = false,
                lastMessageNamesSender = false,
                lastEventType = null,
                lastActivityMs = ms,
                runtime = host?.let { RuntimeDto(harness = "OpenClaw", host = it) },
                membership = if (invited) Membership.INVITED else Membership.JOINED,
            )
            return RoomRow(
                room = summary,
                identity = RoomIdentity(glyph = null, name = id, role = null, initial = "X"),
                preview = RoomPreview(text = "hi", pending = pending),
                affordance = if (invited) RoomAffordance.RESPOND_TO_INVITATION else RoomAffordance.COMPOSE,
            )
        }
    }

    // --- state ------------------------------------------------------------

    /** owing an answer outranks how recently a room spoke */
    @Test
    fun pendingWins() {
        // A room that needs you is not described by its timestamp, however
        // fresh or stale that is.
        val fresh = row("a", minutesAgo = 1.0, pending = true)
        val stale = row("b", minutesAgo = 60.0 * 24 * 30, pending = true)
        assertEquals(AgentState.NEEDS_YOU, RosterArrangement.state(fresh, now))
        assertEquals(AgentState.NEEDS_YOU, RosterArrangement.state(stale, now))
    }

    /** recency reads as active, then idle, then quiet */
    @Test
    fun agesThroughStates() {
        assertEquals(AgentState.ACTIVE, RosterArrangement.state(row("a", minutesAgo = 2.0), now))
        assertEquals(AgentState.IDLE, RosterArrangement.state(row("b", minutesAgo = 120.0), now))
        assertEquals(AgentState.QUIET, RosterArrangement.state(row("c", minutesAgo = 60.0 * 48), now))
    }

    /** a room that never said anything is quiet, not active */
    @Test
    fun silenceIsNotFreshness() {
        // `lastActivityMs` is null for a room with no events. Treating a
        // missing timestamp as "now" would put empty rooms at the top of a
        // roster sorted by life.
        val base = row("a")
        val silent = RoomRow(
            room = RoomSummary(
                id = "a", name = "a", avatarUrl = null, unread = 0uL, lastMessage = null,
                lastMessageIsOwn = false, lastMessageNamesSender = false, lastEventType = null,
                lastActivityMs = null, runtime = null, membership = Membership.JOINED,
            ),
            identity = base.identity,
            preview = null,
            affordance = RoomAffordance.COMPOSE,
        )
        assertEquals(AgentState.QUIET, RosterArrangement.state(silent, now))
    }

    // --- arrangement --------------------------------------------------------

    /** invitations are withheld but counted */
    @Test
    fun invitationsHiddenNotLost() {
        // Hidden must never mean gone. A roster that silently drops a room
        // you were invited to is a roster that lost it.
        val rows = listOf(row("a"), row("i", invited = true))
        val sections = RosterArrangement.sections(rows, RosterChoice.RECENT, showsInvitations = false, now)
        assertEquals(1, sections.flatMap { it.rows }.size)
        assertEquals(1, RosterArrangement.hiddenInvitations(rows, showsInvitations = false))
        assertEquals(0, RosterArrangement.hiddenInvitations(rows, showsInvitations = true))
    }

    /** what needs you comes first, whatever spoke last */
    @Test
    fun waitingIsPromoted() {
        val rows = listOf(
            row("fresh", minutesAgo = 1.0),
            row("owed", minutesAgo = 600.0, pending = true),
        )
        val sections = RosterArrangement.sections(rows, RosterChoice.WAITING, showsInvitations = false, now)
        assertTrue(sections.first().attention)
        assertEquals(listOf("owed"), sections.first().rows.map { it.row.room.id })
        assertEquals(listOf("fresh"), sections.last().rows.map { it.row.room.id })
    }

    /** a quiet fleet gets no headings at all */
    @Test
    fun noWaitingNoSections() {
        // "Everything else" above the whole roster is a label for the
        // absence of a section.
        val sections =
            RosterArrangement.sections(listOf(row("a")), RosterChoice.WAITING, showsInvitations = false, now)
        assertEquals(1, sections.size)
        assertNull(sections[0].title)
    }

    /** machines group their agents and say how many want something */
    @Test
    fun groupsByHost() {
        val rows = listOf(
            row("g", host = "Ashram"),
            row("k", pending = true, host = "Ashram"),
            row("s", host = "Pi"),
        )
        val sections = RosterArrangement.sections(rows, RosterChoice.MACHINE, showsInvitations = false, now)
        assertEquals(listOf("Ashram", "Pi"), sections.map { it.id })
        assertEquals("2 agents · 1 waiting", sections[0].detail)
        assertTrue(sections[0].attention)
        assertEquals("1 agent", sections[1].detail)
        assertFalse(sections[1].attention)
    }

    /** a room with no runtime is filed, not guessed at */
    @Test
    fun roomsWithoutARuntime() {
        // Rooms people made have no harness and no host. They belong in the
        // roster; they do not belong under someone's laptop.
        val sections = RosterArrangement.sections(
            listOf(row("g", host = "Ashram"), row("notes")),
            RosterChoice.MACHINE,
            showsInvitations = false,
            now,
        )
        assertEquals(listOf("Ashram", "Elsewhere"), sections.map { it.id })
    }

    /** a row crosses the boundary already knowing what it is doing */
    @Test
    fun stateRidesOnTheRow() {
        // The reason `RosterRow` exists. A host that asked per row would pay
        // a boundary crossing per visible room per re-render, so if this
        // ever comes back `.quiet` for everything, the list has quietly
        // lost its state and only the dots would show it.
        val rows = listOf(
            row("owed", pending = true),
            row("fresh", minutesAgo = 1.0),
            row("ancient", minutesAgo = 60.0 * 24 * 30),
        )
        val sections = RosterArrangement.sections(rows, RosterChoice.RECENT, showsInvitations = false, now)
        val states = sections.flatMap { it.rows }.associate { it.row.room.id to it.state }

        assertEquals(AgentState.NEEDS_YOU, states["owed"])
        assertEquals(AgentState.ACTIVE, states["fresh"])
        assertEquals(AgentState.QUIET, states["ancient"])
    }

    /** each choice reaches the arrangement it names */
    @Test
    fun choicesMapToCoreViews() {
        // Three enums with the same three cases is exactly the shape that
        // silently maps `.waiting` to `.recent` and looks fine.
        val rows = listOf(row("owed", pending = true), row("fresh"))
        assertEquals(
            "Waiting on you",
            RosterArrangement.sections(rows, RosterChoice.WAITING, showsInvitations = false, now).first().title,
        )
        assertNull(
            RosterArrangement.sections(rows, RosterChoice.RECENT, showsInvitations = false, now).first().title,
        )
        assertEquals(
            "Elsewhere",
            RosterArrangement.sections(rows, RosterChoice.MACHINE, showsInvitations = false, now).first().title,
        )
    }

    /** every arrangement orders by recency inside a section */
    @Test
    fun recencyWithinSections() {
        val rows = listOf(row("old", minutesAgo = 90.0), row("new", minutesAgo = 2.0))
        for (view in RosterChoice.entries) {
            val sections = RosterArrangement.sections(rows, view, showsInvitations = false, now)
            val ordered = sections.flatMap { it.rows }.map { it.row.room.id }
            assertEquals("$view did not put the newest first", "new", ordered.first())
        }
    }
}
