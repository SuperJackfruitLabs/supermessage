package dev.supermessage

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.supermessage_core.CustomEventDecisionOption
import uniffi.supermessage_core.CustomEventField

/** `FieldDigest` and the receipt wording, quoted from `DecisionCard.swift:443-476` and `DecisionReceipt.swift`. */
class DecisionDigestTest {
    private fun option(id: String, label: String = id) = CustomEventDecisionOption(label = label, id = id)

    @Test
    fun thePromptsOwnWordsAreSkipped() {
        val digest = fieldDigest(
            listOf(CustomEventField("Wants to", "restart gateway"), CustomEventField("Host", "ci-1")),
            prompt = "Allow restart gateway?",
        )
        assertEquals("ci-1", digest.summary)
        assertEquals(emptyList<CustomEventField>(), digest.rest)
    }

    @Test
    fun twoShortValuesShareTheLine() {
        val digest = fieldDigest(
            listOf(CustomEventField("Card", "Launch post"), CustomEventField("Board", "Marketing")),
            prompt = "Approve?",
        )
        assertEquals("Launch post · Marketing", digest.summary)
        assertEquals(emptyList<CustomEventField>(), digest.rest)
    }

    @Test
    fun aLongSecondValueGoesToDetails() {
        val second = CustomEventField("Board", "x".repeat(33))
        val digest = fieldDigest(listOf(CustomEventField("Card", "Launch post"), second), prompt = "Approve?")
        assertEquals("Launch post", digest.summary)
        assertEquals(listOf(second), digest.rest)
    }

    @Test
    fun aThirtyTwoCharacterSecondValueStillJoinsAndAFortyOneCharacterFirstDoesNot() {
        val joined = fieldDigest(
            listOf(CustomEventField("A", "a".repeat(40)), CustomEventField("B", "b".repeat(32))),
            prompt = "",
        )
        assertEquals("a".repeat(40) + " · " + "b".repeat(32), joined.summary)
        val alone = fieldDigest(
            listOf(CustomEventField("A", "a".repeat(41)), CustomEventField("B", "b")),
            prompt = "",
        )
        assertEquals("a".repeat(41), alone.summary)
        assertEquals(1, alone.rest.size)
    }

    @Test
    fun noFieldsMeansNoSummary() {
        assertNull(fieldDigest(emptyList(), prompt = "Approve?").summary)
    }

    @Test
    fun anEmptyValueIsNeverSaidAlreadyAndIsKeptInDetails() {
        val empty = CustomEventField("Note", "")
        val digest = fieldDigest(
            listOf(CustomEventField("Card", "Launch post"), CustomEventField("Board", "y".repeat(40)), empty),
            prompt = "Approve?",
        )
        assertEquals(listOf(CustomEventField("Board", "y".repeat(40)), empty), digest.rest)
    }

    @Test
    fun rolesAreReadFromIds() {
        assertEquals(DecisionOptionRole.APPROVE, DecisionOptionRole.of(option("approve")))
        assertEquals(DecisionOptionRole.REQUEST_CHANGES, DecisionOptionRole.of(option("request_changes")))
        assertEquals(DecisionOptionRole.REJECT, DecisionOptionRole.of(option("reject")))
        assertEquals(DecisionOptionRole.OTHER, DecisionOptionRole.of(option("later")))
        // A permission request's ids are the hub's option names.
        assertEquals(DecisionOptionRole.REJECT, DecisionOptionRole.of(option("Reject")))
    }

    @Test
    fun receiptsSayWhoChose() {
        assertEquals("Approved by you", receiptOutcome(option("approve")))
        assertEquals("Changes requested by you", receiptOutcome(option("request_changes")))
        assertEquals("Rejected by you", receiptOutcome(option("reject")))
        assertEquals("You chose “Later”", receiptOutcome(option("later", "Later")))
    }

    @Test
    fun receiptWhenIsJustNowForTheFirstMinuteThenRelative() {
        val t = Instant.parse("2026-10-22T10:00:00Z")
        assertEquals("just now", receiptWhen(t, t.plusSeconds(59)))
        assertEquals("1 minute ago", receiptWhen(t, t.plusSeconds(60)))
        assertEquals("5 minutes ago", receiptWhen(t, t.plusSeconds(5 * 60 + 20)))
        assertEquals("1 hour ago", receiptWhen(t, t.plusSeconds(3600)))
        assertEquals("2 days ago", receiptWhen(t, t.plusSeconds(2 * 86_400)))
    }
}
