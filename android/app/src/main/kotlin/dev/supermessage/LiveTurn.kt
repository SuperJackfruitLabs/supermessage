package dev.supermessage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.StreamingMarkdown
import dev.supermessage.kit.StreamingText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import uniffi.supermessage_core.ToolPhase
import uniffi.supermessage_core.TurnEnding
import uniffi.supermessage_core.TurnStep
import uniffi.supermessage_core.TurnSummary
import uniffi.supermessage_ffi.elapsedLabel
import uniffi.supermessage_ffi.richBlocksFromMarkdown
import uniffi.supermessage_ffi.turnStatusWord

/**
 * The activity card: one agent turn, while it is going.
 *
 * Mirrors `apple/Supermessage/Timeline/LiveTurnView.swift`. It is **one cell
 * at the bottom of the timeline while the turn is in progress**; a finished
 * turn has no cell, and its record is drawn in the answer's own row
 * ([WhatIDid]). Every word that is a rule comes from the core and is rendered
 * as given: the state word ([turnStatusWord]), the elapsed time
 * ([elapsedLabel]), the current step's title, the counts and the failed step
 * (all on [summary]), and each step's status label.
 *
 * **The view owns the pacer.** [StreamingText] keeps plain `var`s, not Compose
 * state, so [revealedAnswer] mirrors it once per tick for as long as there is
 * more to reveal. The answer is paced and formatted as M2 Task 6 built it:
 * settled blocks through the core's parser, only the line being written plain.
 *
 * **The card grows without animating its size**; the history above it glides
 * (see [Timeline]). Disclosures fade; none of them animates height.
 *
 * @param still render for a still frame (previews): no moving spinner, the
 *   answer landed whole, and the elapsed time measured to the last event, never
 *   to [nowMs].
 * @param nowMs the clock the running elapsed time reads; injectable so a frame
 *   or a test is deterministic.
 */
@Composable
fun LiveTurnCard(
    summary: TurnSummary?,
    steps: List<TurnStep>,
    thought: String?,
    answer: String?,
    name: String,
    still: Boolean = false,
    nowMs: () -> Long = System::currentTimeMillis,
    modifier: Modifier = Modifier,
) {
    val colors = SupermessageTheme.colors
    val endedBy = summary?.endedBy
    val finished = endedBy != null
    val reduce = reducesMotion()

    val scope = rememberCoroutineScope()
    val stream = remember { StreamingText(scope) }
    var revealedAnswer by remember { mutableStateOf("") }
    var showsThought by remember { mutableStateOf(false) }
    var showsSteps by remember { mutableStateOf(false) }

    LaunchedEffect(answer, finished, still) {
        if (answer == null) {
            // The turn ended: drain whatever is still queued rather than
            // animating into an empty card.
            stream.finish()
            stream.clear()
            revealedAnswer = ""
            return@LaunchedEffect
        }
        if (finished || still) {
            // A record, or a still frame: nothing is arriving, so it lands whole.
            stream.finish(answer)
            revealedAnswer = stream.text
            return@LaunchedEffect
        }
        stream.accept(answer)
        // Mirror the pacer's own progress onto Compose state, one tick at a
        // time, so the reveal is visible rather than jumping to the end.
        while (isActive && stream.text.length < answer.length) {
            delay(StreamingText.tick)
            revealedAnswer = stream.text
        }
        revealedAnswer = stream.text
    }

    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("live-turn")
            // A finished turn steps back (previews only: a finished turn has no cell).
            .alpha(if (finished) 0.85f else 1f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised, shape)
                .border(BorderStroke(1.dp, colors.border), shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardHeader(summary, name, still, nowMs)

            // "Writing the answer" says nothing the answer, streaming in under
            // it, does not already show; it goes once the text is visible.
            // Thinking, a tool call and starting still need saying.
            if (!finished && (summary?.stepIsTool == true || revealedAnswer.isEmpty())) {
                CurrentStep(summary?.currentStepTitle ?: if (answer != null) "Writing the answer" else "Starting", still)
            }

            if (steps.isNotEmpty() && summary != null) StepSummary(summary)

            if (thought != null) {
                Disclosure(
                    label = "Reasoning",
                    open = showsThought,
                    onToggle = { showsThought = !showsThought },
                    toggleTag = "thought-toggle",
                    regionTag = "thought-region",
                    reduce = reduce,
                ) {
                    SelectionContainer {
                        Text(
                            thought,
                            modifier = Modifier.testTag("thought-body").fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.contentMuted,
                        )
                    }
                }
            }

            if (steps.isNotEmpty()) {
                Disclosure(
                    label = if (steps.size == 1) "1 step" else "All ${steps.size} steps",
                    open = showsSteps,
                    onToggle = { showsSteps = !showsSteps },
                    toggleTag = "steps-toggle",
                    regionTag = "steps-region",
                    reduce = reduce,
                ) {
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        steps.forEach { step -> ToolRow(step, still = still, reduce = reduce) }
                    }
                }
            }

            if (answer != null) {
                // Completed lines are markdown, through the core's parser, and
                // only the line still being written stays plain. This cell
                // grows as text arrives and must not animate doing so
                // (AGENTS.md, streaming rules), so nothing here animates size.
                val (settled, tail) = StreamingMarkdown.split(revealedAnswer, stream.revealed)
                val blocks = remember(settled) { if (settled.isEmpty()) emptyList() else richBlocksFromMarkdown(settled) }
                Column(
                    modifier = Modifier.testTag("live-answer").fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (blocks.isNotEmpty()) RichText(blocks, modifier = Modifier.fillMaxWidth())
                    if (tail.isNotEmpty()) {
                        Text(
                            tail,
                            modifier = Modifier.testTag("live-answer-tail").fillMaxWidth(),
                            // The face and size a paragraph is drawn in, so the
                            // line does not change size when it settles.
                            fontFamily = SupermessageTheme.typography.body,
                        )
                    }
                }
            }
        }
    }
}

/** Who, whether they are still at it, and for how long. Merged for accessibility. */
@Composable
private fun CardHeader(summary: TurnSummary?, name: String, still: Boolean, nowMs: () -> Long) {
    val colors = SupermessageTheme.colors
    val endedBy = summary?.endedBy
    // The word is the core's, so every host says the same: "Working", "Done",
    // or "Stopped" for a turn the core gave up on, which is neither working nor
    // a success and takes the neutral colour.
    val word = turnStatusWord(endedBy)
    val wordColor = when (endedBy) {
        null -> colors.accent
        TurnEnding.TIMED_OUT -> colors.contentMuted
        TurnEnding.DONE, TurnEnding.REPLY -> colors.ok
    }
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            modifier = Modifier.weight(1f).testTag("live-name"),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            word,
            modifier = Modifier.testTag("live-turn-state"),
            style = MaterialTheme.typography.labelMedium,
            color = wordColor,
            maxLines = 1,
            softWrap = false,
        )
        if (summary != null) {
            // Ticks once a second from the start while running; still, or
            // finished, measured to the last event and never to `nowMs`.
            val running = endedBy == null && !still
            val now by produceState(initialValue = nowMs(), running, summary.startedAtMs) {
                value = nowMs()
                while (running) {
                    delay(1_000)
                    value = nowMs()
                }
            }
            val elapsedMs = if (running) now - summary.startedAtMs.toLong() else summary.lastActivityMs.toLong() - summary.startedAtMs.toLong()
            Text(
                "· " + elapsedLabel(elapsedMs),
                modifier = Modifier.testTag("live-elapsed"),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = colors.contentMuted,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** What the agent is doing this moment, with the one moving part. Merged. */
@Composable
private fun CurrentStep(title: String, still: Boolean) {
    val colors = SupermessageTheme.colors
    val reduce = reducesMotion()
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("live-current-step"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (reduce || still) {
            // A static ring: under Reduce Motion, or in a still render, nothing moves.
            CircularProgressIndicator(
                progress = { 0.75f },
                modifier = Modifier.size(12.dp).testTag("live-progress-still"),
                color = colors.accent,
                strokeWidth = 1.5.dp,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp).testTag("live-progress"),
                color = colors.accent,
                strokeWidth = 1.5.dp,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = colors.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How far it has got, and what went wrong. */
@Composable
private fun StepSummary(summary: TurnSummary) {
    val colors = SupermessageTheme.colors
    val done = summary.completedSteps.toInt()
    Column(
        modifier = Modifier.testTag("live-step-summary-column"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.testTag("live-step-summary").semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(12.dp), tint = colors.contentMuted)
            Text(
                if (done == 1) "1 step done" else "$done steps done",
                style = MaterialTheme.typography.labelMedium,
                color = colors.contentMuted,
            )
        }
        summary.failedStep?.let { failed ->
            Row(
                modifier = Modifier.testTag("live-failed-step").semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.Close, contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp).size(12.dp), tint = colors.danger,
                )
                Text(
                    "$failed failed",
                    modifier = Modifier.testTag("live-failed-step-text"),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A label that opens a region. The region **fades**; it never animates its
 * height: this card sits in the list, and a height animation there is a
 * resize the history would have to follow.
 */
@Composable
private fun Disclosure(
    label: String,
    open: Boolean,
    onToggle: () -> Unit,
    toggleTag: String,
    regionTag: String,
    reduce: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = SupermessageTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Collapse $label" else "Expand $label", onClick = onToggle)
                .testTag(toggleTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.contentMuted)
            Icon(
                Icons.Filled.KeyboardArrowDown, contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(if (open) 180f else 0f), tint = colors.contentMuted,
            )
        }
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.testTag(regionTag),
            // Reduce Motion: no transition at all, so it is there on the very next frame.
            enter = if (reduce) EnterTransition.None else fadeIn(tween(250)),
            exit = if (reduce) ExitTransition.None else fadeOut(tween(250)),
        ) { content() }
    }
}

/** The glyph for a step's phase: done, failed, running, queued or unknown. */
@Composable
internal fun StepGlyph(phase: ToolPhase, tint: Color, still: Boolean, reduce: Boolean) {
    val modifier = Modifier.size(12.dp)
    when (phase) {
        ToolPhase.DONE -> Icon(Icons.Filled.CheckCircle, null, modifier, tint)
        ToolPhase.FAILED -> Icon(Icons.Filled.Close, null, modifier, tint)
        ToolPhase.RUNNING -> {
            val pulse = if (still || reduce) 1f else {
                val transition = rememberInfiniteTransition(label = "step-pulse")
                transition.animateFloat(
                    initialValue = 1f, targetValue = 0.4f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha",
                ).value
            }
            Icon(Icons.Filled.Refresh, null, modifier.alpha(pulse), tint)
        }
        // A clock in iOS; a hollow ring here (the icon set's clock is in the extended artifact).
        ToolPhase.QUEUED, ToolPhase.UNKNOWN ->
            Box(modifier.border(1.5.dp, tint, CircleShape))
    }
}

/**
 * One step: the glyph, the title (the slack), the core's status word (never
 * squeezed). A step with detail opens onto what it touched, was given and
 * returned; one without stays a plain row.
 */
@Composable
private fun ToolRow(step: TurnStep, still: Boolean, reduce: Boolean) {
    val colors = SupermessageTheme.colors
    var open by remember { mutableStateOf(false) }
    val failed = step.phase == ToolPhase.FAILED
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (step.hasDetail) it.clickable(role = Role.Button, onClick = { open = !open }) else it }
                .testTag("tool-row")
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepGlyph(step.phase, if (failed) colors.danger else colors.contentMuted, still, reduce)
            Text(
                step.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = colors.contentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                step.statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) colors.danger else colors.contentFaint,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (step.hasDetail && open) {
            Column(
                modifier = Modifier.testTag("tool-detail").fillMaxWidth().padding(top = 4.dp, start = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (step.locations.isNotEmpty()) StepDetail("Touched", step.locations.joinToString("\n"))
                step.input?.let { StepDetail("Input", it) }
                step.output?.let { StepDetail("Output", it) }
            }
        }
    }
}

/** A labelled block of step text: monospace and selectable, because it is data. */
@Composable
private fun StepDetail(label: String, text: String) {
    val colors = SupermessageTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.contentFaint)
        SelectionContainer {
            Text(
                text,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.contentMuted,
            )
        }
    }
}
