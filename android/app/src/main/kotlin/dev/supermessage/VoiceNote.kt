package dev.supermessage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.supermessage.kit.NotePlayback
import dev.supermessage.kit.VoicePlayback
import dev.supermessage.kit.VoiceWaveform
import uniffi.supermessage_core.AudioView
import uniffi.supermessage_core.VoiceReplyPlayer
import uniffi.supermessage_ffi.audioClockLabel
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * The app's one voice-note player, or `null` where nothing can play — a
 * preview, or a test that composes a row on its own. A row with no player
 * draws at rest and its button does nothing.
 *
 * Provided once, by [AppRoot]: one player app-wide is what makes "one note
 * at a time" true. See [VoicePlayback].
 */
val LocalVoicePlayback = staticCompositionLocalOf<VoicePlayback?> { null }

/**
 * An `m.audio` row: [ItemView.Audio][uniffi.supermessage_core.ItemView.Audio],
 * on the sender's side, wired to [LocalVoicePlayback].
 *
 * A peer's note is named above it the way a peer's picture is, once per run.
 * The row's own event id addresses the file; a local echo has none yet, so
 * it cannot be played until the server has it.
 */
@Composable
internal fun AudioRow(
    row: TimelineRowDto,
    audio: AudioView,
    named: String,
    continuesRun: Boolean,
    modifier: Modifier = Modifier,
) {
    val isOwn = row.item.isOwn
    val eventId = row.item.eventId
    val player = LocalVoicePlayback.current
    val playback = if (player != null && eventId != null) {
        val state by player.state.collectAsStateWithLifecycle()
        state.of(eventId)
    } else {
        NotePlayback.Idle
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (continuesRun) 2.dp else 8.dp, bottom = 2.dp),
        horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!isOwn && !continuesRun) {
            Text(
                named,
                style = MaterialTheme.typography.labelLarge,
                color = row.item.sender?.let { SupermessageTheme.peer(it) } ?: Color.Unspecified,
            )
        }
        AudioNote(
            audio = audio,
            isOwn = isOwn,
            playback = playback,
            onToggle = if (player != null && eventId != null) ({ player.toggle(eventId) }) else null,
            onSeek = if (player != null && eventId != null) ({ f -> player.seek(eventId, f) }) else null,
        )
    }
}

/**
 * An agent's answer, spoken: the voice note's player drawn on the message it
 * speaks, above the text ([ItemView.Bubble][uniffi.supermessage_core.ItemView.Bubble]'s
 * `voice`). The core paired the two and hid the voice message's own row
 * (`core::voice_reply`); this plays the *voice* event — [VoiceReplyPlayer.eventId]
 * — while reactions and replies on the bubble address the text.
 */
@Composable
internal fun SpokenReply(spoken: VoiceReplyPlayer, isOwn: Boolean, modifier: Modifier = Modifier) {
    val eventId = spoken.eventId
    val player = LocalVoicePlayback.current
    val playback = if (player != null) {
        val state by player.state.collectAsStateWithLifecycle()
        state.of(eventId)
    } else {
        NotePlayback.Idle
    }
    AudioNote(
        audio = spoken.audio,
        isOwn = isOwn,
        playback = playback,
        onToggle = if (player != null) ({ player.toggle(eventId) }) else null,
        onSeek = if (player != null) ({ f -> player.seek(eventId, f) }) else null,
        modifier = modifier.testTag("spoken-reply"),
    )
}

/**
 * A voice note or an audio file as a player: a round play/pause button,
 * the waveform filling with what has played, and the time.
 *
 * Everything it says comes from the core — the title, the length at rest
 * (`lengthLabel`, shown as it is), the bars, the screen-reader sentence — and
 * the one number that moves while it plays is formatted by the core's
 * [audioClockLabel]. What this decides is only the drawing.
 *
 * The button is a fixed 40dp and its glyph never grows (design language §7).
 * `material-icons-core` has a play arrow and a refresh arrow but no pause, so
 * pause is two bars drawn here ([PauseGlyph]) rather than a reason to reach
 * for `-extended`.
 *
 * [onToggle] `null` leaves the button inert (no player, or no event id yet);
 * [onSeek] is only honoured by the player for the note that holds it.
 */
@Composable
fun AudioNote(
    audio: AudioView,
    isOwn: Boolean,
    playback: NotePlayback,
    onToggle: (() -> Unit)?,
    onSeek: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val position = when (playback) {
        is NotePlayback.Playing -> playback.positionMs to playback.durationMs
        is NotePlayback.Paused -> playback.positionMs to playback.durationMs
        else -> null
    }
    val progress = position?.let { (at, length) ->
        VoiceWaveform.progress(at, length ?: audio.durationMs?.toLong())
    } ?: 0f
    val failed = playback == NotePlayback.Failed
    val time = when {
        failed -> "Can't play"
        position != null -> audioClockLabel(position.first.coerceAtLeast(0).toULong())
        else -> audio.lengthLabel.orEmpty()
    }
    val state = when (playback) {
        NotePlayback.Idle -> null
        NotePlayback.Loading -> "Loading"
        is NotePlayback.Playing -> "Playing, $time"
        is NotePlayback.Paused -> "Paused, $time"
        NotePlayback.Failed -> "Can't play"
    }

    Column(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isOwn) {
                    // The own bubble's own tint — see `MessageBlock`.
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .semantics {
                contentDescription = audio.accessibilityLabel
                state?.let { stateDescription = it }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("audio-note"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // An audio file is named; a voice note's title ("Voice message")
        // would only repeat what the bubble already is.
        if (!audio.isVoice) {
            Text(
                audio.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayButton(
                playback = playback,
                // "Play voice message", "Play voice reply": the core's title.
                label = if (audio.isVoice) "Play ${audio.title.replaceFirstChar { it.lowercase() }}" else "Play ${audio.title}",
                onToggle = onToggle,
            )
            Waveform(
                bars = VoiceWaveform.bars(audio.waveform),
                progress = progress,
                onSeek = if (position != null) onSeek else null,
                modifier = Modifier.weight(1f).height(32.dp),
            )
            Text(
                time,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.semantics { hideFromAccessibility() }.testTag("audio-time"),
            )
        }
        audio.caption?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PlayButton(playback: NotePlayback, label: String, onToggle: (() -> Unit)?) {
    val failed = playback == NotePlayback.Failed
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val danger = MaterialTheme.colorScheme.error
    val description = when (playback) {
        is NotePlayback.Playing -> "Pause"
        NotePlayback.Loading -> "Loading, tap to cancel"
        NotePlayback.Failed -> "Try again"
        else -> label
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (failed) {
                    Modifier.border(1.5.dp, danger, CircleShape)
                } else {
                    Modifier.background(accent)
                },
            )
            .then(
                if (onToggle != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = description, onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description }
            .testTag("audio-button"),
        contentAlignment = Alignment.Center,
    ) {
        when (playback) {
            is NotePlayback.Playing -> PauseGlyph(color = onAccent)
            NotePlayback.Loading ->
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = onAccent,
                    // The ring is always there, so the button reads as busy
                    // at every point of the spin, not only when the arc is long.
                    trackColor = onAccent.copy(alpha = 0.35f),
                    strokeWidth = 2.dp,
                )
            NotePlayback.Failed ->
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = danger, modifier = Modifier.size(24.dp))
            NotePlayback.Idle, is NotePlayback.Paused ->
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = onAccent, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Pause, as two bars: `material-icons-core` has no pause glyph, and
 * `-extended` is not an option (design language §7). Drawn in the same 24dp
 * box the play arrow occupies, with Material's own proportions for it.
 */
@Composable
private fun PauseGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val bar = Size(4f * unit, 14f * unit)
        val radius = CornerRadius(1f * unit)
        drawRoundRect(color, topLeft = Offset(6f * unit, 5f * unit), size = bar, cornerRadius = radius)
        drawRoundRect(color, topLeft = Offset(14f * unit, 5f * unit), size = bar, cornerRadius = radius)
    }
}

/**
 * The bars, filled up to [progress] in the accent and the rest in the strong
 * border tone. A tap or a drag seeks when [onSeek] is given — only for the
 * note that is playing or paused.
 *
 * Hidden from accessibility: the note's own sentence and state say what the
 * bars show.
 */
@Composable
private fun Waveform(
    bars: List<Float>,
    progress: Float,
    onSeek: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.outlineVariant
    val filled = VoiceWaveform.playedBars(progress, bars.size)
    val seek by rememberUpdatedState(onSeek)
    Canvas(
        modifier = modifier
            .semantics { hideFromAccessibility() }
            .testTag("audio-waveform")
            .then(
                if (onSeek != null) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures { seek?.invoke(it.x / size.width) }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                seek?.invoke(change.position.x / size.width)
                            }
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        if (bars.isEmpty()) return@Canvas
        val step = size.width / bars.size
        val barWidth = (step * 0.6f).coerceAtMost(3.dp.toPx()).coerceAtLeast(1f)
        val minHeight = 2.dp.toPx()
        bars.forEachIndexed { i, level ->
            val h = (level.coerceIn(0f, 1f) * size.height).coerceAtLeast(minHeight)
            drawRoundRect(
                color = if (i < filled) played else rest,
                topLeft = Offset(step * i + (step - barWidth) / 2f, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
