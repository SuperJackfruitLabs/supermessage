package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uniffi.supermessage_core.CustomEventDecision
import uniffi.supermessage_core.CustomEventField
import uniffi.supermessage_core.CustomEventView

/**
 * A suite event — a Kaambaan card or run, a permission request, station
 * status. `view` is the whole fallback-chain decision from
 * `core::custom_events::resolve_custom_event`: this renders its three states
 * and never makes the choice itself. Every field on it is **text**, bounded
 * and validated on the Rust side before it crossed, and nothing read out of
 * a payload may be rendered as anything but text. Mirrors
 * `apple/Supermessage/Timeline/DecisionCard.swift`, with two departures:
 *
 * 1. **No `senderName` parameter.** The signature this task was handed omits
 *    it, so [CustomEventView.FallbackBody] renders only its `text` here —
 *    unlike iOS, which pairs it with the sender's name. That is this app's
 *    given interface, not a rediscovery of iOS's behaviour; noted rather than
 *    silently matched.
 * 2. **`eventType` is rendered, not merely spoken.** iOS folds it only into
 *    an accessibility label and never draws it. Android draws it (see
 *    `item_view.rs`'s doc on `ItemView::CustomEvent::event_type`: "the card's
 *    header should show it") — which is why this file, not the core, owns
 *    the render-time RTL guard described below.
 *
 * The `when` below has **no `else` branch**. Kotlin enforces exhaustiveness
 * over a sealed class, so a fourth `CustomEventView` variant this build has
 * never seen is a compile error here rather than a blank card — the same
 * discipline `TimelineRow`'s own `ItemView` `when` holds itself to, for the
 * same reason: `ItemView.DateDivider`'s doc records iOS rendering
 * "Unsupported event (dateDivider)" in the middle of a conversation after a
 * variant went unhandled behind only a comment.
 */
@Composable
fun DecisionCard(
    view: CustomEventView,
    label: String,
    eventType: String,
    modifier: Modifier = Modifier,
    onDecide: ((GateAnswer) -> Unit)? = null,
) {
    when (view) {
        is CustomEventView.Rendered ->
            RenderedCard(
                label = label,
                eventType = eventType,
                fields = view.fields,
                reasoning = view.reasoning,
                newerVersion = view.newerVersion,
                decision = view.decision,
                link = view.link,
                modifier = modifier,
                onDecide = onDecide,
            )

        // A type nothing here can render, but which carried a plain-text
        // `body` fallback, as Matrix convention asks. Show what it said.
        is CustomEventView.FallbackBody ->
            Text(
                view.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("custom-event-fallback"),
            )

        // Not a card. A type we cannot render at all is not worth a
        // bordered object — the same quiet centred line every other
        // unrenderable item gets (mirrors `ItemView.Placeholder`'s
        // `SystemLine` in `TimelineRow.kt`).
        is CustomEventView.Placeholder ->
            Text(
                view.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("custom-event-placeholder"),
            )
    }
}

@Composable
private fun RenderedCard(
    label: String,
    eventType: String,
    fields: List<CustomEventField>,
    reasoning: String?,
    newerVersion: Boolean,
    decision: CustomEventDecision?,
    link: String? = null,
    modifier: Modifier = Modifier,
    onDecide: (suspend (GateAnswer) -> Boolean)? = null,
) {
    // Amber marks a pending decision and nothing else on this card — see
    // `SupermessageColorRoles.signal`'s own note in `Theme.kt`.
    val pending = decision != null
    val signal = SupermessageTheme.colors.signal

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pending) signal.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (pending) signal else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp)
            .testTag("decision-card"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // What it is, in the words the renderer uses — a reader should
            // not have to parse `dev.agentpod.turn.v1` to learn they are
            // looking at a turn.
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The schema address itself, still shown — for a card whose type
            // nothing here recognises, and for anyone diagnosing a room.
            // `eventType` is sender-controlled and already truncated
            // left-first and trimmed by `display_event_type` on the Rust
            // side before it crossed; what remains for this layer is the
            // render-time half of that same rule — forcing an LTR paragraph
            // direction so a crafted type carrying strong-RTL characters
            // cannot reorder itself (and, in particular, the string's own
            // leading "…" truncation marker) on screen.
            Text(
                eventType,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                modifier = Modifier.testTag("event-type"),
            )
            Spacer(Modifier.weight(1f))
            if (newerVersion) {
                // Rendered best-effort against a newer minor schema. Said
                // quietly rather than hidden, so a reader knows there may be
                // more to this event than is shown.
                Text(
                    "newer version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.testTag("newer-version-badge"),
                )
            }
        }

        fields.forEach { field ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(84.dp),
                )
                Text(field.value, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (reasoning != null) {
            ReasoningDisclosure(reasoning)
        }

        // The one thing on this card that is not text.
        //
        // `link` came through `core::custom_events::safe_link`, which accepts
        // `https://` and printable ASCII and nothing else — so this is the only
        // place a payload may become somewhere a tap can go. Before it existed
        // the deep link was printed at the reader as characters to retype,
        // which looks like an affordance and is not.
        if (link != null) {
            val uriHandler = LocalUriHandler.current
            Text(
                "Open the card",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClickLabel = "Open the card") { uriHandler.openUri(link) }
                    .testTag("decision-link"),
            )
        }

        if (decision != null) {
            DecisionPrompt(decision, onDecide = onDecide)
        }
    }
}

/**
 * How the agent got here, when it said. Collapsed by default: it is
 * context, not the conclusion, and a reader scanning a room wants the
 * conclusion first. Mirrors `LiveTurn`'s own reasoning toggle
 * (`thought-toggle`/`thought-body`), the same posture for the same reason —
 * except this reasoning is a room event rather than a live-only field, so
 * it is still here tomorrow and on every other client.
 */
@Composable
private fun ReasoningDisclosure(reasoning: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = if (expanded) "Collapse reasoning" else "Expand reasoning") {
                    expanded = !expanded
                }
                .testTag("reasoning-toggle"),
        ) {
            Text("Reasoning", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (expanded) {
            Text(
                reasoning,
                modifier = Modifier.testTag("reasoning-body").fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The prompt a pending decision asks, and the answers it offers.
 *
 * Answering means sending a Matrix event as this account, never an HTTP call
 * to a gate: the suite's separation-of-duties check refuses a decision whose
 * author it cannot attribute, so the sender has to be the person. That is what
 * [GateAnswer] carries up to a caller holding a session.
 *
 * When there is nothing to answer with — no `subject` from the renderer, or no
 * [onDecide] in this context — the options render as **plainly
 * non-interactive labels rather than as disabled buttons**. A disabled button
 * still affords a tap, and this surface must not claim an affordance it does
 * not have; a wrong shape here approves things.
 */
@Composable
private fun DecisionPrompt(
    decision: CustomEventDecision,
    modifier: Modifier = Modifier,
    onDecide: (suspend (GateAnswer) -> Boolean)? = null,
) {
    val subject = decision.subject
    val answerable = subject != null && onDecide != null && !sending

    // The option awaiting a comment, if one is. Only `request_changes` ever
    // sets this: approve and reject are decisions, and request-changes is
    // feedback that becomes the rework's context — Kaambaan merges it into the
    // card's handoff, so an empty one costs the next agent the reason.
    var commenting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var comment by remember { mutableStateOf("") }

    // The option this reader chose, once it has actually landed.
    //
    // A gate is answered once. Leaving three live buttons after an answer
    // invites a second tap that Kaambaan refuses with GATE_NOT_PENDING — a
    // round trip whose only outcome is a message saying nothing happened, and
    // which reads as though the first tap failed.
    //
    // Set only after the send succeeds, never optimistically: a card claiming
    // "Approved" when the send did not land is the one wrong answer here,
    // because the reader stops trying.
    var answered by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun send(optionId: String, label: String, text: String?) {
        val gate = subject ?: return
        val handler = onDecide ?: return
        val trimmed = text?.trim()
        sending = true
        scope.launch {
            val landed = handler(
                GateAnswer(
                    subject = gate,
                    optionId = optionId,
                    comment = if (trimmed.isNullOrEmpty()) null else trimmed,
                    prompt = decision.prompt,
                ),
            )
            sending = false
            if (landed) answered = label
        }
    }

    Column(
        modifier = modifier.testTag("decision-pending"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            decision.prompt,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        val chosen = answered
        if (chosen != null) {
            // What was chosen, not a row of buttons that would refuse.
            Text(
                "\u2713 " + chosen,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = SupermessageTheme.colors.signal,
                modifier = Modifier.testTag("decision-answered"),
            )
        } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            decision.options.forEach { option ->
                val base = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                Text(
                    // `option.label` only. `option.id` is an identifier handed
                    // back verbatim when the reader answers, never rendered —
                    // see `CustomEventDecisionOption`'s own doc.
                    option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = (
                        if (answerable) {
                            base.clickable(onClickLabel = option.label) {
                                if (option.id == GateAnswer.REQUEST_CHANGES) {
                                    commenting = option.id to option.label
                                } else {
                                    send(option.id, option.label, null)
                                }
                            }
                        } else {
                            base
                        }
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("decision-option"),
                )
            }
        }
        }
    }

    val pendingOption = commenting
    if (pendingOption != null) {
        AlertDialog(
            onDismissRequest = { commenting = null; comment = "" },
            title = { Text("Request changes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This goes back to the agent as the reason, so it can pick the work up again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("What needs changing?") },
                        modifier = Modifier.fillMaxWidth().testTag("decision-comment"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        send(pendingOption.first, pendingOption.second, comment)
                        commenting = null
                        comment = ""
                    },
                    modifier = Modifier.testTag("decision-comment-send"),
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { commenting = null; comment = "" }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One answer to a decision, on its way out of the card.
 *
 * Carries [subject] — what the decision resolves, a Kaambaan `gate_id` today —
 * because the card is the only place that knows it: the renderer read it out of
 * the payload, and the row above has only an event id. Both are needed to
 * answer and neither side has both.
 */
data class GateAnswer(
    val subject: String,
    val optionId: String,
    val comment: String?,
    val prompt: String,
) {
    companion object {
        /** Kaambaan's only option id that expects a comment. */
        const val REQUEST_CHANGES = "request_changes"
    }
}
