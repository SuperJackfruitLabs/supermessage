package dev.supermessage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.ToolPhase
import uniffi.supermessage_core.TurnStep

/**
 * "Thought for 12s · 2 steps": a finished turn's record, collapsed, between
 * the agent's name and its answer (T6).
 *
 * Mirrors `apple/Supermessage/Timeline/WhatIDidFooter.swift`. [label] is the
 * core's `recordLabel`, rendered as given. It opens with a 0.25 s animation
 * unless motion is reduced; this is a row that has finished arriving, so a
 * height animation here moves nothing that is streaming.
 */
@Composable
fun WhatIDid(
    label: String,
    thought: String?,
    steps: List<TurnStep>,
    modifier: Modifier = Modifier,
) {
    val colors = SupermessageTheme.colors
    val reduce = reducesMotion()
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .testTag("what-i-did")
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .background(colors.surfaceSunken, RoundedCornerShape(Metrics.radiusCard))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (open) "Collapse what I did" else "Expand what I did",
                    onClick = { open = !open },
                )
                .padding(vertical = 4.dp)
                .testTag("what-i-did-toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.contentMuted)
            Icon(
                Icons.Filled.KeyboardArrowDown, contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(if (open) 180f else 0f), tint = colors.contentMuted,
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = if (reduce) EnterTransition.None else expandVertically(tween(250)) + fadeIn(tween(250)),
            exit = if (reduce) ExitTransition.None else shrinkVertically(tween(250)) + fadeOut(tween(250)),
        ) {
            Column(
                modifier = Modifier.testTag("what-i-did-body").fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (thought != null) {
                    SelectionContainer {
                        Text(
                            thought,
                            modifier = Modifier.fillMaxWidth().padding(bottom = if (steps.isEmpty()) 0.dp else 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.contentMuted,
                        )
                    }
                }
                steps.forEach { step -> RecordedStep(step) }
            }
        }
    }
}

/** One step of a finished turn: what it was, and how it ended, in the core's word. */
@Composable
private fun RecordedStep(step: TurnStep) {
    val colors = SupermessageTheme.colors
    val failed = step.phase == ToolPhase.FAILED
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.padding(top = 3.dp)) {
            StepGlyph(step.phase, if (failed) colors.danger else colors.contentFaint, still = true, reduce = true)
        }
        Text(
            step.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = colors.contentMuted,
            maxLines = 2,
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
}
