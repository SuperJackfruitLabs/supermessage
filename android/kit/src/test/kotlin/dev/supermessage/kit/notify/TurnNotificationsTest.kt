package dev.supermessage.kit.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.Counterpart
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_core.TurnEnding
import uniffi.supermessage_core.TurnSummary
import uniffi.supermessage_core.TurnUpdate

/** Real `turnCard`, fixture summaries as the core emits them. */
class TurnNotificationsTest {
    private fun summary(
        room: String = "!r:x",
        turn: ULong = 1uL,
        revision: ULong = 1uL,
        inProgress: Boolean = true,
        endedBy: TurnEnding? = null,
        startedAtMs: Long = 1_000L,
        endedAtMs: Long? = null,
        total: Int = 0,
        completed: Int = 0,
        failedStep: String? = null,
        step: String = "",
        agentId: String? = null,
    ) = TurnUpdate(
        TurnSummary(
            roomId = room, turnId = turn, revision = revision, inProgress = inProgress, endedBy = endedBy,
            startedAtMs = startedAtMs.toULong(), endedAtMs = endedAtMs?.toULong(), lastActivityMs = startedAtMs.toULong(),
            answering = false, hasThought = false, totalSteps = total.toUInt(), completedSteps = completed.toUInt(),
            failedStep = failedStep, currentStepTitle = step, stepIsTool = false, recordLabel = null, agentId = agentId,
        ),
        null,
    )

    private val names = TurnNotifications(nameOf = { _, agentId -> if (agentId == "@lyra:x") "Lyra" else "Ops" })

    private fun shown(change: TurnChange) = (change as TurnChange.Show).notice

    @Test
    fun aRunningTurnShowsItsStepAndCounts() {
        val notice = shown(names.apply(summary(step = "Reading the logs", total = 4, completed = 2)))
        assertEquals("Reading the logs", notice.headline)
        assertEquals("Ops · step 2 of 4", notice.subtitle)
        assertEquals(1_000L, notice.startedAtMs)
        assertEquals(4, notice.totalSteps)
        assertEquals(2, notice.completedSteps)
        // No steps yet: the core says "Working", and the name alone.
        val bare = shown(TurnNotifications({ _, _ -> "Ops" }).apply(summary()))
        assertEquals("Working", bare.headline)
        assertEquals("Ops", bare.subtitle)
    }

    @Test
    fun aRunningTurnWithAFailedToolStillReadsAsRunning() {
        val notice = shown(names.apply(summary(step = "Retrying", failedStep = "git push", total = 3, completed = 1)))
        assertEquals("Retrying", notice.headline)
    }

    /** The core's `Done` and `Reply` are how every answered turn ends; the reply has its own notification. */
    @Test
    fun aTurnThatEndsByReplyLeavesAndLeavesNoFinishedNote() {
        names.apply(summary(step = "Writing", total = 2, completed = 1))
        val end = names.apply(summary(revision = 2uL, inProgress = false, endedBy = TurnEnding.REPLY, endedAtMs = 61_000L, total = 2, completed = 2))
        assertEquals(TurnChange.Clear("!r:x"), end)
        assertTrue(names.running().isEmpty())
        val done = names.apply(summary(turn = 2uL, step = "Again"))
        assertTrue(done is TurnChange.Show)
        assertEquals(TurnChange.Clear("!r:x"), names.apply(summary(turn = 2uL, revision = 2uL, inProgress = false, endedBy = TurnEnding.DONE, endedAtMs = 5_000L)))
    }

    /** RF1: a bridge agent edits one progress message; the core emits running summaries, then one ended by Reply. */
    @Test
    fun aBridgeAgentsEditedProgressTurnEndsItsCard() {
        val first = names.apply(summary(step = "Working on it", agentId = null))
        val edited = names.apply(summary(revision = 2uL, step = "Still working", agentId = null))
        val ended = names.apply(summary(revision = 3uL, inProgress = false, endedBy = TurnEnding.REPLY, endedAtMs = 9_000L))
        assertEquals("Working on it", shown(first).headline)
        assertEquals("Still working", shown(edited).headline)
        assertEquals(TurnChange.Clear("!r:x"), ended)
    }

    @Test
    fun aFailedTurnLeavesToo() {
        names.apply(summary(step = "Pushing", total = 3, completed = 1))
        val failed = names.apply(summary(revision = 2uL, inProgress = false, endedBy = TurnEnding.DONE, endedAtMs = 7_000L, failedStep = "git push", total = 3, completed = 1))
        assertEquals(TurnChange.Clear("!r:x"), failed)
    }

    @Test
    fun aTimedOutTurnLeaves() {
        names.apply(summary(step = "Waiting"))
        val out = names.apply(summary(revision = 2uL, inProgress = false, endedBy = TurnEnding.TIMED_OUT, endedAtMs = 99_000L))
        assertEquals(TurnChange.Clear("!r:x"), out)
    }

    /** RF2: two agents in one room; the turn's own `agentId` names the card, not the room or the other agent. */
    @Test
    fun theCardNamesTheAgentDoingTheTurn() {
        val cast = RoomCast(
            roomId = "!r:x",
            people = listOf(
                PersonDto("@lyra:x", "Lyra", "L", null, null),
                PersonDto("@atlas:x", "Atlas", "A", null, null),
            ),
            counterpart = Counterpart("@atlas:x", "Atlas"),
            agentIds = listOf("@lyra:x", "@atlas:x"),
            addressee = null,
            mentionables = emptyList(),
        )
        assertEquals("Lyra", agentDisplayName("@lyra:x", cast))
        assertEquals("Atlas", agentDisplayName("@atlas:x", cast))
        assertNull(agentDisplayName("@stranger:x", cast))
        assertNull(agentDisplayName(null, cast))
        assertNull(agentDisplayName("@lyra:x", null))
        val notice = shown(names.apply(summary(agentId = "@lyra:x", total = 2, completed = 1, step = "Reading")))
        assertEquals("Lyra · step 1 of 2", notice.subtitle)
    }

    @Test
    fun aStaleRevisionIsIgnored() {
        names.apply(summary(revision = 5uL, step = "Newest"))
        assertEquals(TurnChange.None, names.apply(summary(revision = 4uL, step = "Older")))
        // An older turn too, and a stale running update after the end does not bring the card back.
        assertEquals(TurnChange.None, names.apply(summary(turn = 0uL, revision = 9uL, step = "Before")))
        names.apply(summary(revision = 6uL, inProgress = false, endedBy = TurnEnding.REPLY, endedAtMs = 2_000L))
        assertEquals(TurnChange.None, names.apply(summary(revision = 5uL, step = "Late")))
    }

    @Test
    fun theSameWordsAgainChangeNothing() {
        names.apply(summary(revision = 1uL, step = "Reading", total = 2, completed = 1))
        assertEquals(TurnChange.None, names.apply(summary(revision = 2uL, step = "Reading", total = 2, completed = 1)))
        assertTrue(names.apply(summary(revision = 3uL, step = "Reading", total = 2, completed = 2)) is TurnChange.Show)
    }

    @Test
    fun twoRoomsGiveTwoNotices() {
        names.apply(summary(room = "!a:x", step = "One"))
        names.apply(summary(room = "!b:x", step = "Two"))
        assertEquals(setOf("!a:x", "!b:x"), names.running().map { it.roomId }.toSet())
        assertEquals(TurnChange.Clear("!a:x"), names.apply(summary(room = "!a:x", revision = 2uL, inProgress = false, endedBy = TurnEnding.REPLY, endedAtMs = 3_000L)))
        assertEquals(listOf("!b:x"), names.running().map { it.roomId })
    }

    @Test
    fun forgettingDropsEveryRoom() {
        names.apply(summary(room = "!a:x", step = "One"))
        names.forget()
        assertTrue(names.running().isEmpty())
        // After sign-out a restart of the numbering is not "stale".
        assertTrue(names.apply(summary(room = "!a:x", turn = 1uL, revision = 1uL, step = "One")) is TurnChange.Show)
    }
}
