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
import uniffi.supermessage_core.OptionInput
import uniffi.supermessage_core.OptionInputKind
import uniffi.supermessage_core.QuestionFreeText
import uniffi.supermessage_core.QuestionV2Decision

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
        // The receipt says it in the hub's words for the two publishing approvals …
        assertEquals("Approved for automatic delivery by you", receiptOutcome(all[1].record, all[1].role))
        assertEquals("Approved for manual delivery by you", receiptOutcome(all[0].record, all[0].role))
        // … and by meaning, not by what the id looks like, for anything else.
        val shipIt = DecisionChoice.v2(GateOptionV2("ship_it", "Ship it", GateOptionMeaning.APPROVE, false))
        assertEquals("Approved by you", receiptOutcome(shipIt.record, shipIt.role))
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

    // ---- question.v2 and delivery.v2 (corpus `matrix_gate_events.json`, v4)

    private val typed = OptionInput(OptionInputKind.TEXT, "What should I do instead?", true, 500u)
    private val liveLink = OptionInput(OptionInputKind.URL, "Live link", true, 2000u)

    private fun question(options: List<GateOptionV2>, freeText: QuestionFreeText? = null) = CustomEventDecision(
        prompt = "The tests need network access. May I run them?",
        options = emptyList(),
        subject = null,
        questionV2 = QuestionV2Decision(
            elicitationId = "elc_7f3c",
            question = "The tests need network access. May I run them?",
            options = options,
            freeText = freeText,
        ),
    )

    private fun delivery(options: List<GateOptionV2>, withheld: String? = null) = CustomEventDecision(
        prompt = "Where did “Announce the release” go live?",
        options = emptyList(),
        subject = null,
        deliveryV2 = GateV2Decision(
            gateId = "gate_8f3a",
            options = options,
            preview = emptyList(),
            approvalsWithheld = withheld,
            subject = GateSubjectBinding(id = "aps_2c9e71", digest = "sha256:9b2f4e0c1a7d"),
        ),
    )

    private val record = GateOptionV2("record_live_url", "Record the live link", GateOptionMeaning.DEFER, false, liveLink)
    private val switch = GateOptionV2("switch_to_automatic", "Post it automatically", GateOptionMeaning.APPROVE, false)

    @Test
    fun aQuestionsChoicesAreNeutralAndCarryTheirInput() {
        val (all, primaries) = DecisionChoice.of(
            question(
                listOf(
                    GateOptionV2("run_them", "Run the tests", GateOptionMeaning.CHOOSE, false),
                    GateOptionV2("skip", "Skip them", GateOptionMeaning.CHOOSE, false),
                    GateOptionV2("other", "Something else", GateOptionMeaning.CHOOSE, false, typed),
                ),
            ),
        )
        assertEquals(listOf("run_them", "skip", "other"), all.map { it.id })
        assertTrue("choose options are neutral, none leads", primaries.isEmpty())
        assertEquals(null, all[0].input)
        assertEquals(typed, all[2].input)
        assertEquals(DecisionChoice.CommentAsk.NONE, all[2].comment)
    }

    @Test
    fun aRequiredInputRefusesEmptyAndOverLongText() {
        val other = DecisionChoice.v2(GateOptionV2("other", "Something else", GateOptionMeaning.CHOOSE, false, typed))
        assertEquals("What should I do instead? is needed.", other.inputProblem("   "))
        assertEquals("At most 500 characters.", other.inputProblem("x".repeat(501)))
        assertEquals(null, other.inputProblem("Run only the unit tests"))
        // An option with no input never has a problem.
        assertEquals(null, DecisionChoice.v2(GateOptionV2("skip", "Skip", GateOptionMeaning.CHOOSE, false)).inputProblem(""))
    }

    @Test
    fun aUrlInputTakesOnlyHttps() {
        val choice = DecisionChoice.v2(record)
        assertEquals("The link must start with https://.", choice.inputProblem("http://social.example/posts/1"))
        assertEquals("The link must start with https://.", choice.inputProblem("social.example/posts/1"))
        assertEquals("The link must start with https://.", choice.inputProblem("https://"))
        assertEquals("Live link is needed.", choice.inputProblem(""))
        assertEquals(null, choice.inputProblem(" https://social.example/posts/1 "))
    }

    @Test
    fun aFollowUpLeadsWithTheSwitchOnlyWhenTheCoreOffersIt() {
        val (all, primaries) = DecisionChoice.of(delivery(listOf(record, switch)))
        assertEquals(listOf("record_live_url", "switch_to_automatic"), all.map { it.id })
        assertEquals(listOf("switch_to_automatic"), primaries.map { it.id })
        val (withheld, none) = DecisionChoice.of(delivery(listOf(record), withheld = "Open the card to switch."))
        assertEquals(listOf("record_live_url"), withheld.map { it.id })
        assertTrue(none.isEmpty())
    }

    @Test
    fun followUpAndFreeTextReceiptsSayWhatHappened() {
        val recorded = DecisionChoice.v2(record)
        assertEquals("Live link recorded by you", receiptOutcome(recorded.record, recorded.role))
        val switched = DecisionChoice.v2(switch)
        assertEquals("Switched to automatic delivery by you", receiptOutcome(switched.record, switched.role))
        val typedAnswer = DecisionChoice.freeText("main")
        assertEquals("", typedAnswer.id)
        assertEquals("You answered “main”", receiptOutcome(typedAnswer.record, typedAnswer.role))
    }

    @Test
    fun aFreeTextQuestionOffersNoChoices() {
        val (all, primaries) = DecisionChoice.of(question(emptyList(), QuestionFreeText(2000u)))
        assertTrue(all.isEmpty())
        assertTrue(primaries.isEmpty())
    }
}
