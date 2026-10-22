package dev.supermessage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.supermessage_core.CustomEventDecision
import uniffi.supermessage_core.CustomEventDecisionOption
import uniffi.supermessage_core.CustomEventField
import uniffi.supermessage_core.CustomEventOutcome
import uniffi.supermessage_core.CustomEventView

/**
 * A suite event — a Superpipeline card or run, a permission request, station
 * status. `view` is the whole fallback-chain decision from
 * `core::custom_events::resolve_custom_event`: this renders its three states
 * and never makes the choice itself. Every field on it is **text**, bounded
 * and validated on the Rust side before it crossed, and nothing read out of
 * a payload may be rendered as anything but text. Mirrors
 * `apple/Supermessage/Timeline/DecisionCard.swift`.
 *
 * **D7, quiet metadata.** The header is the [label] alone; [eventType] is
 * spoken (`"$label, $eventType"`, as iOS does) and never drawn. That removes
 * the render-time right-to-left guard the old header needed: a crafted type
 * can no longer reorder anything on screen, because it is not on screen.
 *
 * **Answered on this device** lives in [answers], keyed by [answerKey] (the
 * event id), *not* in the row. The timeline recycles rows, and a row that
 * remembered its own answer forgot it on the way back into view and offered
 * the buttons again, so a second answer could go out before the board's
 * receipt. The same holder carries the in-flight send, so a row scrolled away
 * mid-send is not re-armed either. A card used on its own gets a holder of its
 * own.
 *
 * The `when` below has **no `else` branch**. Kotlin enforces exhaustiveness
 * over a sealed class, so a fourth `CustomEventView` variant this build has
 * never seen is a compile error here rather than a blank card.
 */
@Composable
fun DecisionCard(
    view: CustomEventView,
    label: String,
    eventType: String,
    senderName: String,
    modifier: Modifier = Modifier,
    answers: DecisionAnswers? = null,
    answerKey: String = "",
    detailsOpenInitially: Boolean = false,
    onDecide: (suspend (GateAnswer) -> Boolean)? = null,
) {
    when (view) {
        is CustomEventView.Rendered ->
            RenderedCard(
                label = label,
                eventType = eventType,
                senderName = senderName,
                fields = view.fields,
                reasoning = view.reasoning,
                newerVersion = view.newerVersion,
                decision = view.decision,
                link = view.link,
                outcome = view.outcome,
                answers = answers ?: rememberDecisionAnswers(),
                answerKey = answerKey,
                detailsOpenInitially = detailsOpenInitially,
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

/** [motion] for the animations that need a finite spec: the full spec, or a jump when motion is reduced. */
private fun <T> still(reduce: Boolean, full: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (reduce) snap() else full

/** What this device answered, and when. Only ever set once the send has landed. */
internal data class AnsweredHere(val outcome: String, val at: Instant)

/**
 * The answers this device has sent, and the sends still in flight, by event id.
 *
 * Held by the list (one per room, because the list is keyed by the room) and
 * not by a row, so a recycled or recomposed row cannot re-arm its buttons. It
 * also owns the coroutine that sends: a row scrolled out of composition
 * mid-send would otherwise cancel the send it started.
 */
@Stable
class DecisionAnswers internal constructor(
    private val scope: CoroutineScope,
    answered: Map<String, AnsweredHere> = emptyMap(),
    failed: Set<String> = emptySet(),
) {
    private val answeredKeys = mutableStateMapOf<String, AnsweredHere>().apply { putAll(answered) }
    private val sendingKeys = mutableStateMapOf<String, Boolean>()
    private val failedKeys = mutableStateMapOf<String, Boolean>().apply { failed.forEach { put(it, true) } }

    internal fun answered(key: String): AnsweredHere? = answeredKeys[key]
    internal fun sending(key: String): Boolean = sendingKeys[key] == true
    internal fun failed(key: String): Boolean = failedKeys[key] == true

    /** Sends once per [key]: a second call while one is in flight, or after it landed, does nothing. */
    internal fun send(
        key: String,
        option: CustomEventDecisionOption,
        answer: GateAnswer,
        handler: suspend (GateAnswer) -> Boolean,
        haptics: HapticFeedback,
        onLanded: () -> Unit,
    ) {
        if (answeredKeys.containsKey(key) || sendingKeys[key] == true) return
        sendingKeys[key] = true
        failedKeys.remove(key)
        scope.launch {
            val landed = try {
                handler(answer)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            // Answered first, so no frame sees neither state.
            if (landed) {
                answeredKeys[key] = AnsweredHere(receiptOutcome(option), Instant.now())
                haptics.play(Haptic.Success)
                onLanded()
            } else {
                failedKeys[key] = true
                haptics.play(Haptic.Failure)
            }
            sendingKeys.remove(key)
        }
    }
}

@Composable
internal fun rememberDecisionAnswers(
    answered: Map<String, AnsweredHere> = emptyMap(),
    failed: Set<String> = emptySet(),
): DecisionAnswers {
    val scope = rememberCoroutineScope()
    return remember(scope) { DecisionAnswers(scope, answered, failed) }
}

@Composable
private fun RenderedCard(
    label: String,
    eventType: String,
    senderName: String,
    fields: List<CustomEventField>,
    reasoning: String?,
    newerVersion: Boolean,
    decision: CustomEventDecision?,
    link: String?,
    outcome: CustomEventOutcome?,
    answers: DecisionAnswers,
    answerKey: String,
    detailsOpenInitially: Boolean,
    modifier: Modifier = Modifier,
    onDecide: (suspend (GateAnswer) -> Boolean)? = null,
) {
    val answeredHere = answers.answered(answerKey)
    // Amber marks a pending decision and nothing else on this card — see
    // `SupermessageColorRoles.signal`'s own note in `Theme.kt`. The core never
    // hands over both, and a resolved or answered gate must not be amber even
    // if it did.
    val pending = decision != null && outcome == null && answeredHere == null
    val signal = SupermessageTheme.colors.signal
    val reduce = reducesMotion()

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
            // looking at a turn. The schema address stays available to
            // accessibility for anyone debugging a room (D7).
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "$label, $eventType" }.testTag("decision-label"),
            )
            Spacer(Modifier.weight(1f))
            if (newerVersion) {
                // Rendered best-effort against a newer minor schema. Said
                // quietly rather than hidden, so a reader knows there may be
                // more to this event than is shown.
                Text(
                    "Newer version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.testTag("newer-version-badge"),
                )
            }
        }

        when {
            // The room says it is over, and who ended it. First, because it is
            // the strongest fact this card can have: whatever this device
            // remembers answering, the board has the answer.
            outcome != null ->
                ReceiptBody(
                    headline = outcome.prompt,
                    outcome = outcome.summary,
                    at = null,
                    awaitingBoard = false,
                    fields = fields,
                    reasoning = reasoning,
                    link = link,
                    detailsOpenInitially = detailsOpenInitially,
                    modifier = Modifier.testTag("decision-resolved"),
                    lineTag = "decision-outcome",
                )

            // Pending and its receipt share one slot, so the change between
            // them is a transition rather than one row swapped for another:
            // a crossfade with a 0.97 scale from the top, or the fade alone
            // when motion is reduced (`DecisionCard.swift:192-194`).
            decision != null ->
                AnimatedContent(
                    targetState = answeredHere,
                    transitionSpec = {
                        val enter = if (reduce) {
                            fadeIn(still(reduce, tween(200)))
                        } else {
                            fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.97f, transformOrigin = TransformOrigin(0.5f, 0f))
                        }
                        enter togetherWith fadeOut(still(reduce, tween(200))) using
                            SizeTransform(clip = false) { _, _ -> still(reduce, tween(200)) }
                    },
                    label = "decision-slot",
                ) { answer ->
                    if (answer != null) {
                        ReceiptBody(
                            headline = decision.prompt,
                            outcome = answer.outcome,
                            at = answer.at,
                            awaitingBoard = true,
                            fields = fields,
                            reasoning = reasoning,
                            link = link,
                            detailsOpenInitially = detailsOpenInitially,
                            lineTag = "decision-receipt",
                        )
                    } else {
                        PendingBody(
                            decision = decision,
                            senderName = senderName,
                            fields = fields,
                            reasoning = reasoning,
                            link = link,
                            answers = answers,
                            answerKey = answerKey,
                            detailsOpenInitially = detailsOpenInitially,
                            onDecide = onDecide,
                        )
                    }
                }

            else -> {
                fields.forEach { field -> FieldRow(field) }
                if (reasoning != null) ReasoningDisclosure(reasoning)
                LinkButton(link)
            }
        }
    }
}

/** Label beside value, or above it once the text is large — 84dp of label is not enough at 2x. */
@Composable
private fun FieldRow(field: CustomEventField) {
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(field.value, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
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
}

/**
 * The one thing on this card that is not text.
 *
 * `link` came through `core::custom_events::safe_link`, which accepts
 * `https://` and printable ASCII and nothing else — so this is the only place
 * a payload may become somewhere a tap can go.
 */
@Composable
private fun LinkButton(link: String?) {
    if (link == null) return
    val uriHandler = LocalUriHandler.current
    Text(
        "Open the card",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = "Open the card") { uriHandler.openUri(link) }
            .testTag("decision-link"),
    )
}

/** The digest line: one line, or four at accessibility sizes. */
@Composable
private fun Summary(summary: String?) {
    if (summary == null) return
    Text(
        summary,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (LocalDensity.current.fontScale >= 1.3f) 4 else 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier.testTag("decision-summary"),
    )
}

/** The remaining fields behind a disclosure. `DecisionCard.swift:340-352`. */
@Composable
private fun Details(fields: List<CustomEventField>, openInitially: Boolean) {
    if (fields.isEmpty()) return
    var open by remember { mutableStateOf(openInitially) }
    val reduce = reducesMotion()
    Column {
        Text(
            "Details",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide details" else "Show details") { open = !open }
                .padding(vertical = 12.dp)
                .testTag("decision-details"),
        )
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(still(reduce, tween(200))) + fadeIn(still(reduce, tween(200))),
            exit = shrinkVertically(still(reduce, tween(200))) + fadeOut(still(reduce, tween(200))),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                fields.forEach { FieldRow(it) }
            }
        }
    }
}

/**
 * What a decision looks like once it is over: a check in `ok`, who chose, and
 * (for an answer sent from here) when. A receipt, not a form — no controls,
 * and **no amber**: amber is a debt the reader owes, and a receipt drawn in
 * `signal` would say they still owe it.
 */
@Composable
private fun ReceiptBody(
    headline: String?,
    outcome: String,
    at: Instant?,
    awaitingBoard: Boolean,
    fields: List<CustomEventField>,
    reasoning: String?,
    link: String?,
    detailsOpenInitially: Boolean,
    lineTag: String,
    modifier: Modifier = Modifier,
) {
    val digest = remember(fields, headline) { fieldDigest(fields, headline.orEmpty()) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (headline != null) {
            // Still said, because a receipt that does not say what was decided
            // is a checkmark about nothing — but muted, because it is no
            // longer a question.
            Text(headline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Text("✓", style = MaterialTheme.typography.labelLarge, color = SupermessageTheme.colors.ok)
            ReceiptSentence(outcome, at, Modifier.testTag(lineTag))
        }
        if (awaitingBoard) {
            // Answered is not resolved: the decision landed in the room, and
            // the board has not said it accepted it.
            Text(
                "Waiting for the board to confirm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("decision-awaiting-board"),
            )
        }
        Summary(digest.summary)
        Details(digest.rest, detailsOpenInitially)
        if (reasoning != null) ReasoningDisclosure(reasoning)
        LinkButton(link)
    }
}

/** "Approved by you · just now", re-read once the first minute is over. */
@Composable
private fun ReceiptSentence(outcome: String, at: Instant?, modifier: Modifier) {
    var now by remember(at) { mutableStateOf(Instant.now()) }
    if (at != null) {
        LaunchedEffect(at) {
            val left = 60_000L - (Instant.now().toEpochMilli() - at.toEpochMilli())
            if (left > 0) delay(left)
            now = Instant.now()
        }
    }
    val sentence = if (at == null) outcome else "$outcome · ${receiptWhen(at, now)}"
    Text(
        sentence,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
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
 * The question a pending decision asks, and the answers it offers.
 *
 * Answering means sending a Matrix event as this account, never an HTTP call
 * to a gate: the suite's separation-of-duties check refuses a decision whose
 * author it cannot attribute, so the sender has to be the person. That is what
 * [GateAnswer] carries up to a caller holding a session.
 *
 * A permission request has no `subject` (it is identified by the event it
 * arrived on) and is answered all the same. When there is nothing to answer
 * with — no [onDecide] in this context — the options render as **plainly
 * non-interactive labels rather than as disabled buttons**: a disabled button
 * still affords a tap, and this surface must not claim an affordance it does
 * not have; a wrong shape here approves things.
 */
@Composable
private fun PendingBody(
    decision: CustomEventDecision,
    senderName: String,
    fields: List<CustomEventField>,
    reasoning: String?,
    link: String?,
    answers: DecisionAnswers,
    answerKey: String,
    detailsOpenInitially: Boolean,
    onDecide: (suspend (GateAnswer) -> Boolean)?,
) {
    val digest = remember(fields, decision.prompt) { fieldDigest(fields, decision.prompt) }
    Column(modifier = Modifier.testTag("decision-pending"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The question itself, first and in plain language, allowed the
            // height to ask it: no line limit.
            Text(decision.prompt, style = MaterialTheme.typography.titleMedium)
            // Who is asking. A decision from nobody in particular is one a
            // reader cannot weigh.
            Text(
                "Asked by $senderName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Summary(digest.summary)
        Details(digest.rest, detailsOpenInitially)
        if (reasoning != null) ReasoningDisclosure(reasoning)
        LinkButton(link)
        DecisionButtons(decision, answers, answerKey, onDecide)
    }
}

/**
 * The answers to a pending decision. **This is the only place that may use
 * `signal`**, and only for the primary answer: amber means one thing, that the
 * reader owes someone an answer.
 *
 * One answer leads — the first whose role is not reject — full width and
 * filled. Every other answer is a bordered secondary; a rejection comes last,
 * as quiet danger-coloured text, present and plainly labelled but never as loud
 * as the answer that moves work on (`DecisionCard.swift:481-600`).
 */
@Composable
private fun DecisionButtons(
    decision: CustomEventDecision,
    answers: DecisionAnswers,
    answerKey: String,
    onDecide: (suspend (GateAnswer) -> Boolean)?,
) {
    val haptics = LocalHapticFeedback.current
    val primary = decision.options.firstOrNull { DecisionOptionRole.of(it) != DecisionOptionRole.REJECT }
    val secondaries = decision.options.filter { it !== primary && DecisionOptionRole.of(it) != DecisionOptionRole.REJECT }
    val rejections = decision.options.filter { DecisionOptionRole.of(it) == DecisionOptionRole.REJECT }

    // The option awaiting a comment, if one is. Only `request_changes` ever
    // sets this: approve and reject are decisions, and request-changes is
    // feedback that becomes the rework's context — Superpipeline merges it into
    // the card's handoff, so an empty one costs the next agent the reason.
    var commenting by remember { mutableStateOf<CustomEventDecisionOption?>(null) }
    // Kept until the answer lands, so a failed send does not cost the typing.
    var comment by remember { mutableStateOf("") }

    val interactive = onDecide != null
    val enabled = interactive && !answers.sending(answerKey)

    fun send(option: CustomEventDecisionOption, text: String?) {
        val handler = onDecide ?: return
        val trimmed = text?.trim()
        answers.send(
            key = answerKey,
            option = option,
            answer = GateAnswer(
                subject = decision.subject,
                optionId = option.id,
                comment = if (trimmed.isNullOrEmpty()) null else trimmed,
                prompt = decision.prompt,
            ),
            handler = handler,
            haptics = haptics,
            onLanded = { comment = "" },
        )
    }

    fun tapped(option: CustomEventDecisionOption) {
        if (option.id == GateAnswer.REQUEST_CHANGES) commenting = option else send(option, null)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (primary != null) {
            DecisionButton(
                text = primary.label,
                kind = ButtonKind.Primary,
                interactive = interactive,
                enabled = enabled,
                onClick = { tapped(primary) },
                modifier = Modifier.fillMaxWidth().testTag("decision-primary"),
            )
        }
        if (secondaries.isNotEmpty() || rejections.isNotEmpty()) {
            // A row while the rest fit, a column when they do not — measured,
            // not a font-scale threshold, as iOS's `ViewThatFits`. At 2x a row
            // once drew "Re-ques t chan ges" over five lines on the one control
            // in this product that must not be missed.
            RowOrColumn(spacing = 8.dp) {
                secondaries.forEach {
                    DecisionButton(
                        text = it.label,
                        kind = ButtonKind.Secondary,
                        interactive = interactive,
                        enabled = enabled,
                        onClick = { tapped(it) },
                        modifier = Modifier.testTag("decision-secondary"),
                    )
                }
                rejections.forEach {
                    DecisionButton(
                        text = it.label,
                        kind = ButtonKind.Reject,
                        interactive = interactive,
                        enabled = enabled,
                        onClick = { tapped(it) },
                        modifier = Modifier.testTag("decision-reject"),
                    )
                }
            }
        }
        if (answers.failed(answerKey)) {
            // The buttons stay enabled; the line says why nothing changed.
            // iOS has no visible copy; this is R1's, and it is `danger`, never
            // amber.
            Text(
                "Not sent. Try again.",
                style = MaterialTheme.typography.labelSmall,
                color = SupermessageTheme.colors.danger,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("decision-failed"),
            )
        }
    }

    val pendingOption = commenting
    if (pendingOption != null) {
        val focus = remember { FocusRequester() }
        // A dialog, so TalkBack and a keyboard land in it, and Cancel, the
        // Back key and a tap outside all leave it.
        AlertDialog(
            onDismissRequest = { commenting = null; comment = "" },
            title = { Text("Request changes") },
            text = {
                // In the dialog's own composition, where the field exists.
                LaunchedEffect(Unit) { focus.requestFocus() }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This goes back to the agent as the reason, so it can pick the work up again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("What needs changing?") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("decision-comment"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        send(pendingOption, comment)
                        commenting = null
                    },
                    modifier = Modifier.testTag("decision-comment-send"),
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(
                    onClick = { commenting = null; comment = "" },
                    modifier = Modifier.testTag("decision-comment-cancel"),
                ) { Text("Cancel") }
            },
        )
    }
}

private enum class ButtonKind { Primary, Secondary, Reject }

@Composable
private fun DecisionButton(
    text: String,
    kind: ButtonKind,
    interactive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SupermessageTheme.colors
    val shape = RoundedCornerShape(if (kind == ButtonKind.Primary) 10.dp else 16.dp)
    val scheme = MaterialTheme.colorScheme
    // Plainly neutral when nothing can resolve the decision: a filled amber
    // pill would claim an affordance this card does not have.
    val look = when {
        !interactive -> Modifier.clip(shape).background(scheme.surfaceVariant)
        kind == ButtonKind.Primary ->
            Modifier.clip(shape).background(if (enabled) colors.signal else colors.signal.copy(alpha = 0.25f))
        kind == ButtonKind.Secondary ->
            Modifier.clip(shape).border(1.dp, scheme.onSurfaceVariant.copy(alpha = 0.6f), shape)
        else -> Modifier.clip(shape)
    }
    // The label colour is set, not inherited: `signal-soft` on `signal` is the
    // pair the token contract holds at 6:1 in every appearance. A disabled
    // label drops to `content` at 0.45, still legible and plainly not pressable.
    val labelColor = when {
        !interactive -> scheme.onSurface
        !enabled -> scheme.onSurface
        kind == ButtonKind.Primary -> colors.signalSoft
        kind == ButtonKind.Reject -> colors.danger
        else -> scheme.onSurface
    }
    val style = MaterialTheme.typography.labelMedium.let {
        if (kind == ButtonKind.Primary) it.copy(fontWeight = FontWeight.SemiBold) else it
    }
    Box(
        modifier = modifier
            .then(look)
            .then(
                if (interactive) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = text, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // `option.label` only. `option.id` is an identifier handed back
            // verbatim when the reader answers, never rendered.
            text,
            style = style,
            color = labelColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(if (interactive && !enabled) 0.45f else 1f),
        )
    }
}

/**
 * Lays its children in one row when every one fits at its natural width (the
 * extra shared out equally), and in a full-width column otherwise.
 */
@Composable
private fun RowOrColumn(spacing: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val available = constraints.maxWidth
        val rowFits = natural.sum() + gap * (measurables.size - 1) <= available
        if (rowFits) {
            val extra = (available - natural.sum() - gap * (measurables.size - 1)) / measurables.size
            val placeables = measurables.mapIndexed { i, m ->
                val w = natural[i] + extra
                m.measure(Constraints(minWidth = w, maxWidth = w))
            }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(available, height) {
                var x = 0
                placeables.forEach { p ->
                    p.place(x, (height - p.height) / 2)
                    x += p.width + gap
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints(minWidth = available, maxWidth = available)) }
            val height = placeables.sumOf { it.height } + gap / 2 * (placeables.size - 1).coerceAtLeast(0)
            layout(available, height) {
                var y = 0
                placeables.forEach { p ->
                    p.place(0, y)
                    y += p.height + gap / 2
                }
            }
        }
    }
}

/**
 * One answer to a decision, on its way out of the card.
 *
 * Carries [subject] — what the decision resolves, a Superpipeline `gate_id` today,
 * or `null` for a permission request, which has no gate — because the card is
 * the only place that knows it: the renderer read it out of the payload, and
 * the row above has only an event id. Both are needed to
 * answer and neither side has both.
 */
data class GateAnswer(
    val subject: String?,
    val optionId: String,
    val comment: String?,
    val prompt: String,
) {
    companion object {
        /** Superpipeline's only option id that expects a comment. */
        const val REQUEST_CHANGES = "request_changes"
    }
}
