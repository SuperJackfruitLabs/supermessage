package dev.supermessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.CustomEventDecision
import uniffi.supermessage_core.CustomEventDecisionOption
import uniffi.supermessage_core.GateOptionMeaning
import uniffi.supermessage_core.GateOptionV2
import uniffi.supermessage_core.GateSubjectBinding
import uniffi.supermessage_core.GateV2Decision

/** How the card draws a decision: a gate.v2 by meaning, anything else by id, as before. */
class DecisionChoiceTest {
    private fun legacy(vararg ids: String) = CustomEventDecision(
        prompt = "Approve?",
        options = ids.map { CustomEventDecisionOption(label = it, id = it) },
        subject = "gate_1",
    )

    /** The publishing gate as the core hands it over (corpus `matrix_gate_events.json`). */
    private fun publishing(options: List<GateOptionV2> = publishingOptions) = CustomEventDecision(
        prompt = "Approve \"Announce the release\"?",
        options = listOf(
            CustomEventDecisionOption(label = "Request changes", id = "request_changes"),
            CustomEventDecisionOption(label = "Reject", id = "reject"),
        ),
        subject = "gate_8f3a",
        gateV2 = GateV2Decision(
            gateId = "gate_8f3a",
            options = options,
            preview = emptyList(),
            approvalsWithheld = null,
            subject = GateSubjectBinding(id = "aps_2c9e71", digest = "sha256:9b2f4e0c1a7d"),
        ),
    )

    private val publishingOptions = listOf(
        GateOptionV2("approve_manual", "Approve — I'll post it myself", GateOptionMeaning.APPROVE, false),
        GateOptionV2("approve_automatic", "Approve — post automatically", GateOptionMeaning.APPROVE, false),
        GateOptionV2("request_changes", "Request changes", GateOptionMeaning.REVISE, true),
        GateOptionV2("reject", "Reject", GateOptionMeaning.REJECT, false),
    )

    @Test
    fun aLegacyDecisionIsDrawnAsBefore() {
        val (all, primaries) = DecisionChoice.of(legacy("approve", "request_changes", "reject"))
        assertEquals(listOf("approve", "request_changes", "reject"), all.map { it.id })
        assertEquals(listOf("approve"), primaries.map { it.id })
        assertEquals(DecisionChoice.CommentAsk.OPTIONAL, all[1].comment)
        assertFalse("a v1 comment may still be left empty", all[1].lacksComment(""))
    }

    @Test
    fun bothApprovalsOfAPublishingGateAreMainActions() {
        val (all, primaries) = DecisionChoice.of(publishing())
        assertEquals(listOf("approve_manual", "approve_automatic"), primaries.map { it.id })
        assertEquals(
            listOf(
                DecisionOptionRole.APPROVE,
                DecisionOptionRole.APPROVE,
                DecisionOptionRole.REQUEST_CHANGES,
                DecisionOptionRole.REJECT,
            ),
            all.map { it.role },
        )
        // The receipt says what the meaning was, not what the id looks like.
        assertEquals("Approved by you", receiptOutcome(all[1].record, all[1].role))
    }

    @Test
    fun aRequiredCommentCannotBeLeftEmpty() {
        val revise = DecisionChoice.of(publishing()).first.single { it.id == "request_changes" }
        assertEquals(DecisionChoice.CommentAsk.REQUIRED, revise.comment)
        assertTrue(revise.lacksComment("  \n"))
        assertFalse(revise.lacksComment("Say which release."))
    }

    @Test
    fun withheldApprovalsLeaveNoMainAction() {
        val (all, primaries) = DecisionChoice.of(publishing(publishingOptions.drop(2)))
        assertTrue(primaries.isEmpty())
        assertEquals(listOf("request_changes", "reject"), all.map { it.id })
    }
}
