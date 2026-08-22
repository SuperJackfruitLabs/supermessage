package dev.supermessage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.StreamingText
import dev.supermessage.kit.stores.LiveStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * An agent's turn: while it is arriving, and the record of it afterwards.
 *
 * Mirrors `apple/Supermessage/Timeline/LiveTurnView.swift`. **This does not
 * disappear when the turn ends** — the streamed answer does, because the
 * real message lands on the timeline and says it better, but the reasoning
 * and the tool calls stay, because nothing else on screen carries them. That
 * is [LiveStore]'s decision, not this composable's: it renders whatever
 * `answer`/`thought`/`tools`/`finished` it is handed.
 *
 * **The view owns the pacer.** [StreamingText] confines its fields to plain
 * `var`s, not Compose `State` — deliberately, so it stays framework-agnostic
 * — which means nothing recomposes this screen as its reveal loop ticks
 * unless something asks it to. [revealedAnswer] is that something: a
 * Compose-observed mirror of [StreamingText.text], refreshed once per
 * [StreamingText.tick] for exactly as long as there is more to reveal. A
 * caller that instead read `stream.text` straight out of a `Text(...)` call
 * would see whatever value happened to be there at the last recomposition
 * some *other* state triggered — which is either nothing, or (worse) the
 * finished answer the moment it lands, with nothing in between.
 *
 * The reasoning is collapsed by default: it is context, not the answer, and
 * an operator scanning a room wants the conclusion first.
 */
@Composable
fun LiveTurn(
    answer: String?,
    thought: String?,
    tools: List<LiveStore.ToolCall>,
    finished: Boolean,
    modifier: Modifier = Modifier,
) {
    // "Whether there is anything to show" — mirrors `LiveStore.isLive`,
    // recomputed here because this composable is handed the store's fields
    // rather than the store itself.
    val isLive = answer != null || thought != null || tools.isNotEmpty()
    if (!isLive) return

    val scope = rememberCoroutineScope()
    val stream = remember { StreamingText(scope) }
    var revealedAnswer by remember { mutableStateOf("") }
    var showsThought by remember { mutableStateOf(false) }

    LaunchedEffect(answer, finished) {
        if (answer == null) {
            // The turn ended: drain whatever is still queued rather than
            // animating into an empty card.
            stream.finish()
            stream.clear()
            revealedAnswer = ""
            return@LaunchedEffect
        }
        if (finished) {
            // A finished turn is a record, not something still happening —
            // the reader is waiting on nothing, so it lands whole.
            stream.finish(answer)
            revealedAnswer = stream.text
            return@LaunchedEffect
        }
        stream.accept(answer)
        // Mirror the pacer's own progress onto Compose state, one tick at a
        // time, so the reveal is actually visible rather than jumping
        // straight to the end the instant a delta arrives.
        while (isActive && stream.text.length < answer.length) {
            delay(StreamingText.tick)
            revealedAnswer = stream.text
        }
        revealedAnswer = stream.text
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("live-turn")
            // A finished turn steps back: it is a record beside the
            // conversation rather than something happening in it.
            .alpha(if (finished) 0.85f else 1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // What this is: a turn in progress, or the record of the one
            // that just finished. Saying "writing…" over a finished turn
            // would be the app claiming something that is no longer true.
            Text(
                if (finished) "last turn" else "writing…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (!finished) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 1.dp).testTag("live-progress"), strokeWidth = 1.5.dp)
            }
        }

        if (thought != null) {
            Column {
                Row(
                    modifier = Modifier
                        .clickable(onClickLabel = if (showsThought) "Collapse reasoning" else "Expand reasoning") {
                            showsThought = !showsThought
                        }
                        .testTag("thought-toggle"),
                ) {
                    Text(
                        "Reasoning",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (showsThought) {
                    Text(
                        thought,
                        modifier = Modifier.testTag("thought-body"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        tools.forEach { tool -> ToolCallRow(tool) }

        if (answer != null) {
            Text(
                revealedAnswer,
                modifier = Modifier.testTag("live-answer").fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** One tool call, named and stated. Detail (input/output/locations) is a
 * later phase's concern — this phase only lists what fired. */
@Composable
private fun ToolCallRow(tool: LiveStore.ToolCall, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().testTag("tool-row"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            tool.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        tool.kind?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Text(
            tool.status,
            style = MaterialTheme.typography.labelSmall,
            color = if (tool.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )
    }
}
