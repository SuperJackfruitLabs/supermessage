package dev.supermessage

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.CustomEventDecision
import uniffi.supermessage_core.CustomEventDecisionOption
import uniffi.supermessage_core.CustomEventField
import uniffi.supermessage_core.CustomEventView

/**
 * `DecisionCard` renders the whole fallback-chain decision
 * (`CustomEventView`) `core::custom_events::resolve_custom_event` hands a
 * host — a Kaambaan card or run, a permission request, station status. See
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
                    ),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
                )
                DecisionCard(
                    view = CustomEventView.FallbackBody(text = "plain body text"),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
                )
                DecisionCard(
                    view = CustomEventView.Placeholder(text = "nothing usable"),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
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
                ),
                label = "Station status",
                eventType = "dev.supermessage.station.v1",
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
                    view = CustomEventView.Rendered(fields = emptyList(), reasoning = null, newerVersion = true, decision = null),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
                )
                DecisionCard(
                    view = CustomEventView.Rendered(fields = emptyList(), reasoning = null, newerVersion = false, decision = null),
                    label = "Turn2",
                    eventType = "dev.agentpod.turn.v1",
                )
            }
        }
        compose.onAllNodesWithText("newer version").assertCountEquals(1)
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
                ),
                label = "Turn",
                eventType = "dev.agentpod.turn.v1",
            )
        }
        compose.onNodeWithText("because the gateway was flapping").assertDoesNotExist()
        compose.onNodeWithTag("reasoning-toggle").performClick()
        compose.onNodeWithText("because the gateway was flapping").assertIsDisplayed()
    }

    /**
     * A pending decision (amber, per the console spec's typography rule)
     * must be visually distinguishable from a settled event — nothing else
     * on this card is entitled to that colour. See `DecisionAmber` in
     * `DecisionCard.kt` and `RoomRow.kt`'s `PendingAmber` for the same rule.
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
                        ),
                    ),
                    label = "Permission",
                    eventType = "dev.agentpod.permission.v1",
                )
                DecisionCard(
                    view = CustomEventView.Rendered(fields = emptyList(), reasoning = null, newerVersion = false, decision = null),
                    label = "Turn",
                    eventType = "dev.agentpod.turn.v1",
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
                    ),
                ),
                label = "Permission",
                eventType = "dev.agentpod.permission.v1",
            )
        }
        compose.onNodeWithText("Restart the Hermes gateway?").assertIsDisplayed()
        compose.onNodeWithText("Approve").assertIsDisplayed()
        compose.onNodeWithText("Deny").assertIsDisplayed()
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
                    ),
                ),
                label = "Permission",
                eventType = "dev.agentpod.permission.v1",
            )
        }
        compose.onNodeWithText("Approve").assertIsDisplayed()
        compose.onAllNodesWithText(id, substring = true).assertCountEquals(0)
    }

    @Test
    fun eventTypeRendersUntouchedByThisLayer() {
        // The core already truncates and trims `eventType` before it crosses
        // (`display_event_type` in `item_view.rs`), so this layer must not
        // impose its own truncation on top — only guard the render against
        // right-to-left reordering, which is a display-time concern the
        // core's own string slicing cannot fix on its own.
        val safeEventType = "…gent.turn.v1"
        compose.setContent {
            DecisionCard(
                view = CustomEventView.Rendered(fields = emptyList(), reasoning = null, newerVersion = false, decision = null),
                label = "Turn",
                eventType = safeEventType,
            )
        }
        compose.onNodeWithText(safeEventType).assertIsDisplayed()
    }
}
