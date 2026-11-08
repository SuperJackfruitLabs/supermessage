package dev.supermessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.AttentionReason
import uniffi.supermessage_core.AttentionV2Decision
import uniffi.supermessage_core.CustomEventDecision
import uniffi.supermessage_core.CustomEventDecisionOption
import uniffi.supermessage_core.GateOptionMeaning
import uniffi.supermessage_core.GateOptionV2
import uniffi.supermessage_core.GateSubjectBinding
import uniffi.supermessage_core.GateV2Decision
import uniffi.supermessage_core.GateVariantV2
import uniffi.supermessage_core.OptionInput
import uniffi.supermessage_core.OptionInputKind
import uniffi.supermessage_core.OptionRequires
import uniffi.supermessage_core.OptionScope
import uniffi.supermessage_core.PermissionV2Decision
import uniffi.supermessage_core.QuestionFreeText
import uniffi.supermessage_core.QuestionV2Decision
import uniffi.supermessage_core.SubjectV2

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

    // ---- permission.v2 and subject-bound questions (corpus v5)

    private val permissionSubject = SubjectV2(
        binding = GateSubjectBinding(
            id = "acps_3f0c:12",
            digest = "bb03bfd8ee6d757e6957d029cd6a7b8e004f16386755ce672e3f97f445563878",
        ),
        schema = "harness-permission/v1",
        preview = emptyList(),
        expiresAt = "2026-11-07T10:30:00.000Z",
    )

    private val allowOnce = GateOptionV2(
        "allow_once", "Allow once", GateOptionMeaning.APPROVE, false, null, OptionScope.ONCE, null,
    )
    private val allowAlways = GateOptionV2(
        "allow_always", "Always allow", GateOptionMeaning.APPROVE, false, null, OptionScope.ALWAYS, null,
    )
    private val rejectOnce = GateOptionV2(
        "reject_once", "Reject", GateOptionMeaning.REJECT, false, null, OptionScope.ONCE, null,
    )
    private val rejectAlways = GateOptionV2(
        "reject_always", "Never allow", GateOptionMeaning.REJECT, false, null, OptionScope.ALWAYS, null,
    )

    /** The core withholds allowing options itself; [options] is what it handed over. */
    private fun permission(options: List<GateOptionV2>, withheld: String? = null) = CustomEventDecision(
        prompt = "Allow Run the test suite?",
        options = listOf(CustomEventDecisionOption(label = "Reject", id = "Reject")),
        subject = null,
        permissionV2 = PermissionV2Decision(
            requestId = "acps_3f0c:12",
            title = "Run the test suite",
            toolKind = "execute",
            options = options,
            subject = permissionSubject,
            approvalsWithheld = withheld,
        ),
    )

    @Test
    fun aPermissionIsDrawnByMeaningNotFromTheLegacyList() {
        val (all, primaries) = DecisionChoice.of(permission(listOf(allowOnce, allowAlways, rejectOnce, rejectAlways)))
        assertEquals(listOf("allow_once", "allow_always", "reject_once", "reject_always"), all.map { it.id })
        assertEquals(listOf("allow_once", "allow_always"), primaries.map { it.id })
        assertEquals(DecisionOptionRole.REJECT, all[3].role)
        assertEquals(listOf("Once", "Always", "Once", "Always"), all.map { it.qualifier })
    }

    @Test
    fun aWithheldPermissionOffersNoAllow() {
        val (all, primaries) = DecisionChoice.of(
            permission(listOf(rejectOnce, rejectAlways), withheld = "Answer it in the console to allow it."),
        )
        assertEquals(listOf("reject_once", "reject_always"), all.map { it.id })
        assertTrue(primaries.isEmpty())
        assertFalse(all.any { it.role == DecisionOptionRole.APPROVE })
    }

    @Test
    fun aQualifierSaysReachThenWhoMayTakeIt() {
        assertEquals("Once", DecisionChoice.qualifier(OptionScope.ONCE, null))
        assertEquals("For this run", DecisionChoice.qualifier(OptionScope.RUN, null))
        assertEquals("Always · Admins only", DecisionChoice.qualifier(OptionScope.ALWAYS, OptionRequires.WORKSPACE_ADMIN))
        assertEquals("Admins only", DecisionChoice.qualifier(null, OptionRequires.WORKSPACE_ADMIN))
        assertEquals(null, DecisionChoice.qualifier(null, null))
        // A gate option that says neither draws no caption.
        assertEquals(null, DecisionChoice.v2(GateOptionV2("ship_it", "Ship it", GateOptionMeaning.APPROVE, false)).qualifier)
    }

    @Test
    fun aPermissionReceiptSaysMeaningAndScope() {
        fun said(option: GateOptionV2): String {
            val choice = DecisionChoice.permission(option)
            return choice.receipt ?: receiptOutcome(choice.record, choice.role)
        }
        assertEquals("Allowed once by you", said(allowOnce))
        assertEquals("Allowed always by you", said(allowAlways))
        assertEquals("Rejected by you", said(rejectOnce))
        assertEquals("Rejected always by you", said(rejectAlways))
        assertEquals("Allowed for this run by you", permissionReceipt(GateOptionMeaning.APPROVE, OptionScope.RUN))
        assertEquals("Allowed by you", permissionReceipt(GateOptionMeaning.APPROVE, null))
        assertEquals("Rejected for this run by you", permissionReceipt(GateOptionMeaning.REJECT, OptionScope.RUN))
        assertEquals(null, permissionReceipt(GateOptionMeaning.CHOOSE, null))
        // A gate option keeps its own receipt.
        assertEquals(null, DecisionChoice.v2(allowOnce).receipt)
    }

    @Test
    fun aSubjectBoundQuestionWithWithheldApprovalsOffersOnlyTheDecline() {
        val deny = GateOptionV2("deny", "Deny", GateOptionMeaning.REJECT, false, null, OptionScope.ONCE, null)
        val decision = CustomEventDecision(
            prompt = "Edit app.js in the workspace?",
            options = listOf(CustomEventDecisionOption(label = "Deny", id = "Deny")),
            subject = null,
            questionV2 = QuestionV2Decision(
                elicitationId = "elc_9b44",
                question = "Edit app.js in the workspace?",
                options = listOf(deny),
                freeText = null,
                subject = SubjectV2(
                    binding = GateSubjectBinding("8f9a0d0e-4b2a-4c1e-9c55-1a2b3c4d5e6f", "0588c96c"),
                    schema = "salaam-operation/v1",
                    preview = emptyList(),
                    expiresAt = null,
                ),
                approvalsWithheld = "This card can't show everything you would be approving. Open the card to approve it.",
            ),
        )
        val (all, primaries) = DecisionChoice.of(decision)
        assertEquals(listOf("deny"), all.map { it.id })
        assertTrue(primaries.isEmpty())
        assertEquals("Once", all[0].qualifier)
    }

    // ---- attention.v2: a card waiting on a person (corpus v6)

    private val instruction = OptionInput(OptionInputKind.TEXT, "What should the next attempt do?", true, 2730u)
    private val resumeHere = GateOptionV2("resume_here", "Resume here", GateOptionMeaning.APPROVE, false, instruction)
    private val resumeAtPlan = GateOptionV2("resume_at:plan", "Resume at Plan", GateOptionMeaning.CHOOSE, false, instruction)
    private val archive = GateOptionV2("archive", "Archive", GateOptionMeaning.REJECT, false)

    /** The blocked card as the core hands it over: no plain-reply options at all. */
    private fun waiting(options: List<GateOptionV2> = listOf(resumeHere, resumeAtPlan, archive)) = CustomEventDecision(
        prompt = "“Ship the release” is waiting on you",
        options = emptyList(),
        subject = null,
        attentionV2 = AttentionV2Decision(
            boardId = "brd_7c1f",
            cardId = "crd_6b40",
            waitId = "wait_0123456789abcdef",
            cardTitle = "Ship the release",
            stageName = "Build",
            reason = AttentionReason.BLOCKED,
            reasonText = "Blocked",
            summary = "Staging DB down",
            detail = "the staging database is down",
            failureCount = null,
            options = options,
        ),
    )

    @Test
    fun aWaitingCardLeadsWithResumeHereAndArchivesQuietly() {
        val (all, primaries) = DecisionChoice.of(waiting())
        assertEquals(listOf("resume_here", "resume_at:plan", "archive"), all.map { it.id })
        assertEquals(listOf("resume_here"), primaries.map { it.id })
        assertEquals(DecisionOptionRole.OTHER, all[1].role)
        assertEquals(DecisionOptionRole.REJECT, all[2].role)
    }

    @Test
    fun everyResumeAsksForItsRequiredInstruction() {
        val (all, _) = DecisionChoice.of(waiting())
        for (resume in all.take(2)) {
            assertEquals(instruction, resume.input)
            assertEquals("What should the next attempt do? is needed.", resume.inputProblem("  "))
            assertEquals("At most 2730 characters.", resume.inputProblem("x".repeat(2731)))
            assertEquals(null, resume.inputProblem("x".repeat(2730)))
        }
        // Archive asks for nothing and is sent as tapped.
        assertEquals(null, all[2].input)
        assertEquals(DecisionChoice.CommentAsk.NONE, all[2].comment)
    }

    @Test
    fun aWaitingCardReceiptSaysWhatTheBoardWillDo() {
        fun said(option: GateOptionV2): String {
            val choice = DecisionChoice.attention(option)
            return choice.receipt ?: receiptOutcome(choice.record, choice.role)
        }
        assertEquals("Resumed by you", said(resumeHere))
        assertEquals("Resumed at Plan by you", said(resumeAtPlan))
        assertEquals("Archived by you", said(archive))
        assertEquals("Resumed at design by you", attentionReceipt("resume_at:design", "Go back"))
        assertEquals(null, attentionReceipt("move_to:ship", "Move to Ship"))
    }

    @Test
    fun aWaitingCardAtTheFirstStageOffersNoEarlierStage() {
        val (all, primaries) = DecisionChoice.of(waiting(listOf(resumeHere, archive)))
        assertEquals(listOf("resume_here", "archive"), all.map { it.id })
        assertEquals(listOf("resume_here"), primaries.map { it.id })
    }

    @Test
    fun aSuperToolkitApprovalSaysAdminsOnlyAndWithholdsAllowLikeAnySubject() {
        val allow = GateOptionV2(
            "allow_once", "Allow once", GateOptionMeaning.APPROVE, false, null,
            OptionScope.ONCE, OptionRequires.WORKSPACE_ADMIN,
        )
        val deny = GateOptionV2("deny", "Deny", GateOptionMeaning.REJECT, false, null, OptionScope.ONCE, null)
        fun request(options: List<GateOptionV2>, withheld: String?) = CustomEventDecision(
            prompt = "Allow github issues.create on acme/site?",
            options = listOf(CustomEventDecisionOption(label = "Deny", id = "Deny")),
            subject = null,
            questionV2 = QuestionV2Decision(
                elicitationId = "apr_5d2e9c41",
                question = "Allow github issues.create on acme/site?",
                options = options,
                freeText = null,
                subject = SubjectV2(
                    binding = GateSubjectBinding("stkreq_8f2c", "sha256:4be1c0de9a7f30b2"),
                    schema = "stk-capability/v1",
                    preview = emptyList(),
                    expiresAt = "2026-11-08T10:30:00.000Z",
                ),
                approvalsWithheld = withheld,
                approvalRequestId = "apr_5d2e9c41",
            ),
        )
        val (all, primaries) = DecisionChoice.of(request(listOf(allow, deny), null))
        assertEquals(listOf("allow_once"), primaries.map { it.id })
        assertEquals("Once · Admins only", all[0].qualifier)
        val (withheld, none) = DecisionChoice.of(
            request(listOf(deny), "This card can't show everything this request would do, so it can't be allowed from here."),
        )
        assertEquals(listOf("deny"), withheld.map { it.id })
        assertTrue(none.isEmpty())
    }

    @Test
    fun aStrictDecisionsAlwaysOptionSaysAdminsOnly() {
        val always = GateOptionV2(
            "always", "Always allow", GateOptionMeaning.APPROVE, false, null,
            OptionScope.ALWAYS, OptionRequires.WORKSPACE_ADMIN,
        )
        assertEquals("Always · Admins only", DecisionChoice.v2(always).qualifier)
    }

    @Test
    fun theAskedByLineCarriesTheDeadlineWhenThereIsOne() {
        // The deadline's words are the core's (`decisionDeadlineLine`); the card only joins them.
        assertEquals("Asked by Hermes", askedByLine("Hermes", null))
        assertEquals("Asked by Hermes", askedByLine("Hermes", ""))
        assertEquals("Asked by Hermes · Escalates in 3 h", askedByLine("Hermes", "Escalates in 3 h"))
        assertEquals("Asked by Hermes · Escalated", askedByLine("Hermes", "Escalated"))
    }

    // ---- a choice of versions (corpus `matrix_gate_events.json`, v8)

    private fun version(id: String, label: String, n: Int, approvable: Boolean) = GateVariantV2(
        id = id,
        label = label,
        subject = SubjectV2(
            binding = GateSubjectBinding(id = "aps_v$n", digest = "sha256:v${n}d1"),
            schema = "social-publish/v1",
            preview = emptyList(),
            expiresAt = "2030-01-02T03:04:05Z",
        ),
        approvable = approvable,
        withheld = if (approvable) null else "Too long to show here — choose it on the board.",
    )

    /** The corpus's choice as the core hands it over: the third version was cut, so it is withheld alone. */
    private val choice = GateV2Decision(
        gateId = "gate_5d72",
        options = publishingOptions,
        preview = emptyList(),
        approvalsWithheld = null,
        subject = null,
        variants = listOf(
            version("short", "Short", 1, approvable = true),
            version("thread", "With the thread", 2, approvable = true),
            version("long", "Long form", 3, approvable = false),
        ),
    )

    @Test
    fun aChoiceApprovesOnlyOnceAVersionShownWholeIsPicked() {
        assertFalse("nothing picked yet", variantApprovalReady(choice, null))
        assertTrue(variantApprovalReady(choice, "short"))
        assertTrue(variantApprovalReady(choice, "thread"))
        assertFalse("cut to its share: chosen on the board", variantApprovalReady(choice, "long"))
        assertFalse("not a version of this gate", variantApprovalReady(choice, "fourth"))
    }

    @Test
    fun aGateWithoutVersionsNeverWaitsOnAPick() {
        assertTrue(variantApprovalReady(publishing().gateV2, null))
        assertTrue(variantApprovalReady(null, null))
    }

    @Test
    fun onlyAnApprovalOfAChoiceCarriesTheVersion() {
        val (all, primaries) = DecisionChoice.of(publishing().copy(gateV2 = choice))
        // Both approvals still lead; whether they are pressable is the pick's.
        assertEquals(listOf("approve_manual", "approve_automatic"), primaries.map { it.id })
        val byId = all.associateBy { it.id }
        assertEquals("thread", chosenVariantFor(choice, byId.getValue("approve_automatic").role, "thread"))
        // The set is sent back or rejected whole.
        assertEquals(null, chosenVariantFor(choice, byId.getValue("reject").role, "thread"))
        assertEquals(null, chosenVariantFor(choice, byId.getValue("request_changes").role, "thread"))
        // A gate without versions refuses one, so none is sent.
        assertEquals(null, chosenVariantFor(publishing().gateV2, DecisionOptionRole.APPROVE, "thread"))
    }
}
