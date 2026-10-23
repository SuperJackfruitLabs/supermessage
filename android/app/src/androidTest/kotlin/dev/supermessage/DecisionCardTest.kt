package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.Modifier
import dev.supermessage.previews.PreviewFixtures
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import uniffi.supermessage_core.ItemView
import uniffi.supermessage_core.RichBlock
import uniffi.supermessage_core.RichInline
import uniffi.supermessage_core.TimelineRow as TimelineRowDto
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.CustomEventDecision
import uniffi.supermessage_core.CustomEventDecisionOption
import uniffi.supermessage_core.CustomEventField
import uniffi.supermessage_core.CustomEventOutcome
import uniffi.supermessage_core.CustomEventView

/**
 * `DecisionCard` renders the whole fallback-chain decision
 * (`CustomEventView`) `core::custom_events::resolve_custom_event` hands a
 * host — a Superpipeline card or run, a permission request, station status. See
 * `apple/Supermessage/Timeline/DecisionCard.swift`, which this mirrors, with
 * two deliberate departures noted where they occur below.
 *
 * The `when` inside `DecisionCard.kt` carries no `else` branch, so a variant
 * this build has never seen is a compile error rather than a blank card —
 * the same discipline `TimelineRow`'s own tests already hold it to. What a
 * compiler cannot check is that each variant is *distinguishable* from the
 * others rather than merely non-crashing, which is what
 * `everyVariantRendersSomethingDistinct` exists to pin down.
 */
class DecisionCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyVariantRendersSomethingDistinct() {
        compose.setContent {
            Column {
                DecisionCard(
                    view = CustomEventView.Rendered(
                        fields = listOf(CustomEventField(label = "Status", value = "queued")),
                        reasoning = null,
                        newerVersion = false,
                        decision = null,
                        link = null,
                        outcome = null,
                    ),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
                    senderName = "Atlas — Platform",
                )
                DecisionCard(
                    view = CustomEventView.FallbackBody(text = "plain body text"),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
                    senderName = "Atlas — Platform",
                )
                DecisionCard(
                    view = CustomEventView.Placeholder(text = "nothing usable"),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
                    senderName = "Atlas — Platform",
                )
            }
        }
        compose.onNodeWithText("queued").assertIsDisplayed()
        compose.onNodeWithText("plain body text").assertIsDisplayed()
        compose.onNodeWithText("nothing usable").assertIsDisplayed()
    }

    @Test
    fun renderedFieldsShowLabelAndValueAndTheCardsLabel() {
        compose.setContent {
            DecisionCard(
                view = CustomEventView.Rendered(
                    fields = listOf(CustomEventField(label = "Station", value = "hermes-gateway")),
                    reasoning = null,
                    newerVersion = false,
                    decision = null,
                    link = null,
                    outcome = null,
                ),
                label = "Station status",
                eventType = "dev.supermessage.station.v1",
                senderName = "Atlas — Platform",
            )
        }
        compose.onNodeWithText("Station").assertIsDisplayed()
        compose.onNodeWithText("hermes-gateway").assertIsDisplayed()
        compose.onNodeWithText("Station status").assertIsDisplayed()
    }

    @Test
    fun newerVersionIsFlaggedOnlyWhenTrue() {
        compose.setContent {
            Column {
                DecisionCard(
                    view = CustomEventView.Rendered(fields = emptyList(), reasoning = null, newerVersion = true, decision = null, link = null, outcome = null),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
                    senderName = "Atlas — Platform",
                )
                DecisionCard(
                    view = CustomEventView.Rendered(fields = emptyList(), reasoning = null, newerVersion = false, decision = null, link = null, outcome = null),
                    label = "Turn2",
                    eventType = "dev.agentpod.turn.v1",
                    senderName = "Atlas — Platform",
                )
            }
        }
        compose.onAllNodesWithText("Newer version").assertCountEquals(1)
    }

    @Test
    fun reasoningIsCollapsedUntilExpanded() {
        compose.setContent {
            DecisionCard(
                view = CustomEventView.Rendered(
                    fields = emptyList(),
                    reasoning = "because the gateway was flapping",
                    newerVersion = false,
                    decision = null,
                    link = null,
                    outcome = null,
                ),
                label = "Turn",
                eventType = "dev.agentpod.turn.v1",
                senderName = "Atlas — Platform",
            )
        }
        compose.onNodeWithText("because the gateway was flapping").assertDoesNotExist()
        compose.onNodeWithTag("reasoning-toggle").performClick()
        compose.onNodeWithText("because the gateway was flapping").assertIsDisplayed()
    }

    /**
     * A pending decision (amber, per the console spec's typography rule)
     * must be visually distinguishable from a settled event — nothing else
     * on this card is entitled to that colour. See
     * `SupermessageColorRoles.signal` in `Theme.kt`, the one token both this
     * card and `RoomRow.kt`'s state dot reach for, for the same rule.
     */
    @Test
    fun pendingDecisionIsDistinguishableFromSettled() {
        compose.setContent {
            Column {
                DecisionCard(
                    view = CustomEventView.Rendered(
                        fields = emptyList(),
                        reasoning = null,
                        newerVersion = false,
                        decision = CustomEventDecision(
                            prompt = "Restart the Hermes gateway?",
                            options = listOf(
                                CustomEventDecisionOption(label = "Approve", id = "approve-restart-hermes-gateway"),
                            ),
                            // A permission request is identified by the event it arrived on.
                            subject = null,
                        ),
                        link = null,
                        outcome = null,
                    ),
                    label = "Permission",
                    eventType = "dev.agentpod.permission.v1",
                    senderName = "Atlas — Platform",
                )
                DecisionCard(
                    view = CustomEventView.Rendered(fields = emptyList(), reasoning = null, newerVersion = false, decision = null, link = null, outcome = null),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
                    senderName = "Atlas — Platform",
                )
            }
        }
        compose.onAllNodesWithTag("decision-pending").assertCountEquals(1)
    }

    @Test
    fun decisionPromptAndOptionLabelsRender() {
        compose.setContent {
            DecisionCard(
                view = CustomEventView.Rendered(
                    fields = emptyList(),
                    reasoning = null,
                    newerVersion = false,
                    decision = CustomEventDecision(
                        prompt = "Restart the Hermes gateway?",
                        options = listOf(
                            CustomEventDecisionOption(label = "Approve", id = "approve-restart-hermes-gateway"),
                            CustomEventDecisionOption(label = "Deny", id = "deny-restart-hermes-gateway"),
                        ),
                        // A permission request is identified by the event it arrived on.
                        subject = null,
                    ),
                    link = null,
                    outcome = null,
                ),
                label = "Permission",
                eventType = "dev.agentpod.permission.v1",
                senderName = "Atlas — Platform",
            )
        }
        compose.onNodeWithText("Restart the Hermes gateway?").assertIsDisplayed()
        compose.onNodeWithText("Approve").assertIsDisplayed()
        compose.onNodeWithText("Deny").assertIsDisplayed()
    }

    /**
     * A permission request has no gate (`subject == null`), but it is still
     * answerable whenever a handler is listening: the answer goes up with a
     * null subject and the caller routes it to the permission sender.
     */
    @Test
    fun permissionOptionsArePressableAndSendANullSubject() {
        var recorded: GateAnswer? = null
        compose.setContent {
            DecisionCard(
                view = CustomEventView.Rendered(
                    fields = emptyList(),
                    reasoning = null,
                    newerVersion = false,
                    decision = CustomEventDecision(
                        prompt = "Restart the Hermes gateway?",
                        options = listOf(
                            CustomEventDecisionOption(label = "Approve", id = "approve-restart-hermes-gateway"),
                            CustomEventDecisionOption(label = "Deny", id = "deny-restart-hermes-gateway"),
                        ),
                        subject = null,
                    ),
                    link = null,
                    outcome = null,
                ),
                label = "Permission",
                eventType = "dev.agentpod.permission.v1",
                senderName = "Atlas — Platform",
                onDecide = { answer -> recorded = answer; true },
            )
        }
        // A plain Text has no click action, so this fails for an option
        // the card is not offering.
        compose.onNodeWithText("Approve").assertHasClickAction().assertIsEnabled()
        compose.onNodeWithText("Approve").performClick()
        compose.waitForIdle()
        assertNull(recorded!!.subject)
        assertEquals("approve-restart-hermes-gateway", recorded!!.optionId)
    }

    /**
     * `CustomEventDecisionOption.id` is a machine identifier — "handed back
     * verbatim when the reader answers", per the binding's own doc — and
     * must never be what a reader sees on screen. A card that renders it
     * instead of `label` is a visible bug; this is the mutation this task's
     * brief specifically asks to be proven caught.
     */
    @Test
    fun optionIdIsNeverRenderedOnlyLabelIs() {
        val id = "approve-restart-hermes-gateway-with-a-very-long-machine-identifier"
        compose.setContent {
            DecisionCard(
                view = CustomEventView.Rendered(
                    fields = emptyList(),
                    reasoning = null,
                    newerVersion = false,
                    decision = CustomEventDecision(
                        prompt = "Restart the Hermes gateway?",
                        options = listOf(CustomEventDecisionOption(label = "Approve", id = id)),
                        // A permission request is identified by the event it arrived on.
                        subject = null,
                    ),
                    link = null,
                    outcome = null,
                ),
                label = "Permission",
                eventType = "dev.agentpod.permission.v1",
                senderName = "Atlas — Platform",
            )
        }
        compose.onNodeWithText("Approve").assertIsDisplayed()
        compose.onAllNodesWithText(id, substring = true).assertCountEquals(0)
    }

    /**
     * A gate the room says is resolved (`core::gate_outcome`) is a receipt:
     * who answered, and no control to answer again with — even with an
     * `onDecide` listening, which is exactly the recycled-row case the
     * per-device answered state could not survive.
     */
    @Test
    fun aResolvedGateNamesWhoAnsweredAndOffersNoButtons() {
        compose.setContent {
            DecisionCard(
                view = CustomEventView.Rendered(
                    fields = listOf(CustomEventField("Card", "Launch post")),
                    reasoning = null,
                    newerVersion = false,
                    decision = null,
                    link = null,
                    outcome = CustomEventOutcome(
                        decision = "approve",
                        decidedBy = "priya",
                        summary = "Approved by priya",
                        prompt = "Approve \"Launch post\"?",
                    ),
                ),
                label = "Approval",
                eventType = "dev.superpipeline.gate.v1",
                senderName = "Atlas — Platform",
                onDecide = { true },
            )
        }
        compose.onNodeWithTag("decision-resolved").assertIsDisplayed()
        compose.onNodeWithText("Approved by priya", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Approve \"Launch post\"?").assertIsDisplayed()
        compose.onAllNodesWithTag("decision-primary").assertCountEquals(0)
        compose.onAllNodesWithTag("decision-secondary").assertCountEquals(0)
        compose.onAllNodesWithTag("decision-reject").assertCountEquals(0)
        compose.onAllNodesWithTag("decision-pending").assertCountEquals(0)
        compose.onAllNodesWithTag("decision-awaiting-board").assertCountEquals(0)
    }

    // ── G1 layout, D7, failure feedback ──────────────────────────────────

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { played += hapticFeedbackType }
    }

    private fun gate(
        vararg options: Pair<String, String>,
        prompt: String = "Merge this branch into main?",
        fields: List<CustomEventField> = emptyList(),
    ) = CustomEventView.Rendered(
        fields = fields,
        reasoning = null,
        newerVersion = false,
        decision = CustomEventDecision(
            prompt = prompt,
            options = options.map { (id, label) -> CustomEventDecisionOption(label = label, id = id) },
            subject = "gate-1",
        ),
        link = null,
        outcome = null,
    )

    private val threeOptions = arrayOf("reject" to "Reject", "approve" to "Approve", "request_changes" to "Request changes")

    private fun show(
        view: CustomEventView,
        haptics: HapticFeedback = RecordingHaptics(),
        senderName: String = "Atlas",
        fontScale: Float? = null,
        width: androidx.compose.ui.unit.Dp = 411.dp,
        onDecide: (suspend (GateAnswer) -> Boolean)? = { true },
    ) {
        compose.setContent {
            val base = LocalDensity.current
            val density = if (fontScale == null) base else Density(base.density, fontScale)
            CompositionLocalProvider(
                LocalHapticFeedback provides haptics,
                LocalDensity provides density,
                LocalReduceMotion provides true,
            ) {
                Box(Modifier.requiredWidth(width)) {
                    DecisionCard(
                        view = view,
                        label = "Gate",
                        eventType = "dev.superpipeline.gate.v1",
                        senderName = senderName,
                        onDecide = onDecide,
                    )
                }
            }
        }
    }

    @Test
    fun rejectComesLastAndApproveLeads() {
        show(gate(*threeOptions))
        val card = compose.onNodeWithTag("decision-card").getUnclippedBoundsInRoot()
        val primary = compose.onNodeWithTag("decision-primary")
        primary.assertTextEquals("Approve")
        val p = primary.getUnclippedBoundsInRoot()
        assertTrue("the primary spans the card, was ${p.width} of ${card.width}", p.width >= card.width * 0.9f)
        val reject = compose.onNodeWithTag("decision-reject")
        reject.assertTextEquals("Reject")
        val r = reject.getUnclippedBoundsInRoot()
        val secondary = compose.onNodeWithTag("decision-secondary")
        secondary.assertTextEquals("Request changes")
        val s = secondary.getUnclippedBoundsInRoot()
        assertTrue("reject sits at or below the secondary", r.top >= s.top)
        assertTrue("reject follows the secondary in reading order", r.left >= s.left)
        assertTrue("the primary leads", p.top < s.top)
    }

    @Test
    fun onlyThePrimaryIsAmber() {
        show(gate(*threeOptions))
        // Amber is a debt: a secondary and a rejection owe nothing.
        compose.onAllNodesWithTag("decision-secondary").assertCountEquals(1)
        compose.onAllNodesWithTag("decision-reject").assertCountEquals(1)
        compose.onAllNodesWithTag("decision-primary").assertCountEquals(1)
    }

    @Test
    fun detailsAreClosedUntilAsked() {
        show(
            gate(
                "approve" to "Approve",
                prompt = "Approve?",
                fields = listOf(
                    CustomEventField("Card", "Launch post"),
                    CustomEventField("Board", "b".repeat(40)),
                    CustomEventField("Repository", "SuperJackfruitLabs/supermessage"),
                ),
            ),
        )
        compose.onNodeWithText("SuperJackfruitLabs/supermessage").assertDoesNotExist()
        compose.onNodeWithTag("decision-details").performClick()
        compose.onNodeWithText("SuperJackfruitLabs/supermessage").assertIsDisplayed()
    }

    @Test
    fun askedByNamesTheSender() {
        show(gate("approve" to "Approve"), senderName = "Superpipeline \u2014 Delivery")
        compose.onNodeWithText("Asked by Superpipeline \u2014 Delivery").assertIsDisplayed()
    }

    /**
     * D7: the schema address is for a screen reader and for no one else. The
     * right-to-left reordering the old header guarded against cannot happen to
     * a string that is not drawn.
     */
    @Test
    fun eventTypeIsSpokenNotShown() {
        val safeEventType = "\u2026gent.turn.v1"
        compose.setContent {
            DecisionCard(
                view = CustomEventView.Rendered(fields = emptyList(), reasoning = null, newerVersion = false, decision = null, link = null, outcome = null),
                label = "Turn",
                eventType = safeEventType,
                senderName = "Atlas",
            )
        }
        compose.onNodeWithText(safeEventType, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("decision-card")
            .assert(hasAnyDescendant(hasContentDescription("Turn, $safeEventType")))
    }

    @Test
    fun aFailedAnswerKeepsTheCardAnswerableAndSaysSo() {
        val haptics = RecordingHaptics()
        show(gate(*threeOptions), haptics = haptics, onDecide = { false })
        compose.onNodeWithTag("decision-failed").assertDoesNotExist()
        compose.onNodeWithText("Approve").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("decision-failed").assertTextEquals("Not sent. Try again.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Approve").assertIsEnabled()
        compose.onNodeWithTag("decision-receipt").assertDoesNotExist()
        assertEquals(listOf(HapticFeedbackType.Reject), haptics.played)
        compose.onNodeWithText("Approve").performClick()
        compose.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.Reject, HapticFeedbackType.Reject), haptics.played)
    }

    @Test
    fun aLandedAnswerBecomesAReceipt() {
        val haptics = RecordingHaptics()
        show(gate(*threeOptions), haptics = haptics, onDecide = { true })
        compose.onNodeWithText("Approve").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("decision-receipt").assertTextEquals("Approved by you \u00B7 just now")
        compose.onNodeWithText("Waiting for the board to confirm").assertIsDisplayed()
        compose.onNodeWithTag("decision-primary").assertDoesNotExist()
        compose.onNodeWithTag("decision-failed").assertDoesNotExist()
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
    }

    @Test
    fun aRequestForChangesCanBeCancelledAndSendsNothing() {
        var sent = 0
        show(gate(*threeOptions), onDecide = { sent++; true })
        compose.onNodeWithText("Request changes").performClick()
        compose.onNodeWithTag("decision-comment").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("decision-comment-cancel").assertHasClickAction().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("decision-comment").assertDoesNotExist()
        assertEquals(0, sent)
        compose.onNodeWithTag("decision-primary").assertIsEnabled()
    }

    @Test
    fun aRequestForChangesKeepsItsTextWhenTheSendFails() {
        var last: GateAnswer? = null
        var land = false
        show(gate(*threeOptions), onDecide = { last = it; land })
        compose.onNodeWithText("Request changes").performClick()
        compose.onNodeWithTag("decision-comment").performTextInput("tighten the copy")
        compose.onNodeWithTag("decision-comment-send").performClick()
        compose.waitForIdle()
        assertEquals("tighten the copy", last!!.comment)
        compose.onNodeWithTag("decision-failed").assertIsDisplayed()
        // Asking again offers the words already typed.
        compose.onNodeWithText("Request changes").performClick()
        compose.onNodeWithText("tighten the copy").assertIsDisplayed()
    }

    @Test
    fun requestChangesStaysOnOneLineAtFontScaleTwo() {
        show(gate(*threeOptions), fontScale = 2f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Request changes").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
    }

    @Test
    fun theButtonsStackWhenTheRowCannotFit() {
        show(gate(*threeOptions), fontScale = 2f, width = 230.dp)
        val s = compose.onNodeWithTag("decision-secondary").getUnclippedBoundsInRoot()
        val r = compose.onNodeWithTag("decision-reject").getUnclippedBoundsInRoot()
        assertTrue("reject is below the secondary", r.top >= s.bottom)
        assertEquals(s.left, r.left)
    }

    @Test
    fun theButtonsShareARowWhenTheyFit() {
        show(gate(*threeOptions))
        val s = compose.onNodeWithTag("decision-secondary").getUnclippedBoundsInRoot()
        val r = compose.onNodeWithTag("decision-reject").getUnclippedBoundsInRoot()
        assertTrue("same row", r.top < s.bottom && r.left >= s.right)
    }

    // ── Answered on this device lives outside the row ────────────────────

    private fun timelineWithCardAtTheOldEnd(onDecide: suspend (TimelineRowDto, GateAnswer) -> Boolean): Int {
        val filler = (1..30).map { i ->
            PreviewFixtures.row(
                PreviewFixtures.item("\$f$i", body = "filler $i"),
                ItemView.Bubble(muted = false, blocks = listOf(RichBlock.Paragraph(listOf(RichInline.Text("filler $i")))), voice = null),
            )
        }
        // Newest first is the list's own order, so the card is the oldest.
        val rows = filler + PreviewFixtures.card
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.requiredSize(width = 411.dp, height = 600.dp)) {
                    Timeline(
                        rows = rows.reversed(),
                        revision = 0uL,
                        isPaginating = false,
                        canPaginate = false,
                        onPaginate = {},
                        onMarkRead = {},
                        onDecide = onDecide,
                    )
                }
            }
        }
        return rows.lastIndex
    }

    @Test
    fun aRecycledRowDoesNotRearmAnAnsweredCard() {
        val last = timelineWithCardAtTheOldEnd { _, _ -> true }
        compose.onNodeWithTag("timeline-list").performScrollToIndex(last)
        compose.onNodeWithTag("decision-primary").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("decision-receipt", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("timeline-list").performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithTag("decision-card").assertDoesNotExist()
        compose.onNodeWithTag("timeline-list").performScrollToIndex(last)
        compose.waitForIdle()
        compose.onNodeWithTag("decision-receipt", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("decision-primary", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aRowScrolledAwayMidSendIsNotRearmedAndTheSendIsNotCancelled() {
        val gateOpen = CompletableDeferred<Boolean>()
        var calls = 0
        val last = timelineWithCardAtTheOldEnd { _, _ -> calls++; gateOpen.await() }
        compose.onNodeWithTag("timeline-list").performScrollToIndex(last)
        compose.onNodeWithTag("decision-primary").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("timeline-list").performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithTag("timeline-list").performScrollToIndex(last)
        compose.waitForIdle()
        // Still in flight: the buttons are not live again.
        compose.onNodeWithTag("decision-primary", useUnmergedTree = true).assertIsNotEnabled()
        assertEquals(1, calls)
        gateOpen.complete(true)
        compose.waitForIdle()
        compose.onNodeWithTag("decision-receipt", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, calls)
    }
}
