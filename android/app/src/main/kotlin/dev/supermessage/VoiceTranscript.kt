package dev.supermessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.supermessage_core.VoiceNoteTranscript

/** How many lines a transcript shows before "Show more". Same as iOS and the web. */
internal const val TRANSCRIPT_CLAMPED_LINES = 6

/**
 * What a voice note said — `ItemView.VoiceTranscript`, which
 * `core::voice_transcript` parsed off the AgentPod hub's transcript notice.
 *
 * It belongs to the note the notice replies to, not to the agent that posted
 * it: no sender header, and it sits on the note's side (`onOwnNote`, decided
 * by the core). Quieter than a message — a small caption and the words in the
 * secondary colour, set off by a 2dp rule the way a quote is. Every string is
 * text only. A long transcript opens at [TRANSCRIPT_CLAMPED_LINES] lines, and
 * "Show more" appears only when the clamp actually hid something.
 *
 * Same rules as iOS's `VoiceTranscriptView` and the web's `VoiceTranscript.svelte`.
 */
@Composable
fun VoiceTranscriptView(transcript: VoiceNoteTranscript, onOwnNote: Boolean, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var clamped by remember { mutableStateOf(false) }
    val rule = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentWidth(if (onOwnNote) Alignment.End else Alignment.Start)
            .widthIn(max = if (onOwnNote) 520.dp else 600.dp)
            .padding(top = 2.dp, bottom = 4.dp)
            .drawBehind {
                drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2.dp.toPx())
            }
            .padding(start = 10.dp)
            .testTag("voice-transcript"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            transcript.caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        SelectionContainer {
            Text(
                transcript.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else TRANSCRIPT_CLAMPED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) clamped = it.hasVisualOverflow },
                modifier = Modifier.semantics { contentDescription = transcript.accessibilityLabel },
            )
        }
        if (clamped || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show more", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
