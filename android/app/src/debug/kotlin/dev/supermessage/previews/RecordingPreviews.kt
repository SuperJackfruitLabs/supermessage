package dev.supermessage.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.supermessage.AudioNote
import dev.supermessage.Composer
import dev.supermessage.LocalReduceMotion
import dev.supermessage.VoiceControls
import dev.supermessage.kit.NotePlayback
import dev.supermessage.kit.VoiceRecording

/**
 * Voice recording in the composer (C2). Motion is off in all of them: the
 * pill's dot pulses forever otherwise, and a still has no canonical frame
 * of a pulse.
 */
private fun voice(
    recording: VoiceControls.Active? = null,
    failure: String? = null,
) = VoiceControls(
    recording = recording,
    failure = failure,
    onTap = {}, onHoldStart = {}, onHoldEnd = {}, onDiscard = {}, onSend = {},
)

@Composable
private fun Still(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReduceMotion provides true) { PreviewGround(content = content) }
}

/** Recording by tap: the pill with its trash button, and the trailing button now sends. */
@Preview(name = "Recording, by tap", showBackground = true, heightDp = 120)
@Composable
internal fun RecordingByTap() {
    Still { Composer(text = "", onTextChange = {}, onSend = {}, voice = voice(VoiceControls.Active(elapsedMs = 3_000, held = false))) }
}

/** Recording by hold: "Slide left to cancel", no trash, and the mic stays under the finger as a danger disc. */
@Preview(name = "Recording, held", showBackground = true, heightDp = 120)
@Composable
internal fun RecordingHeld() {
    Still { Composer(text = "", onTextChange = {}, onSend = {}, voice = voice(VoiceControls.Active(elapsedMs = 12_000, held = true))) }
}

/** Nothing to send: the mic where send would be. */
@Preview(name = "Mic, idle", showBackground = true, heightDp = 120)
@Composable
internal fun MicIdle() {
    Still { Composer(text = "", onTextChange = {}, onSend = {}, voice = voice()) }
}

/** A note playing above the same mic: starting to record stops the note first. */
@Preview(name = "Mic, while a note plays", showBackground = true, heightDp = 220)
@Composable
internal fun MicWhileANotePlays() {
    Still {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AudioNote(
                audio = PreviewFixtures.voiceView(),
                isOwn = false,
                playback = NotePlayback.Playing(positionMs = 3_000, durationMs = 7_000),
                onToggle = {},
                onSeek = {},
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start),
            )
            Composer(text = "", onTextChange = {}, onSend = {}, voice = voice())
        }
    }
}

/** The microphone is off: the line under the capsule, in danger. */
@Preview(name = "Recording, microphone refused", showBackground = true, heightDp = 150)
@Composable
internal fun RecordingRefused() {
    Still { Composer(text = "", onTextChange = {}, onSend = {}, voice = voice(failure = VoiceRecording.MICROPHONE_OFF)) }
}

/** Largest text: the pill still shows its discard button inside the capsule. */
@Preview(name = "Recording, huge text", showBackground = true, heightDp = 160, fontScale = 2f, widthDp = 411)
@Composable
internal fun RecordingHugeText() {
    Still { Composer(text = "", onTextChange = {}, onSend = {}, voice = voice(VoiceControls.Active(elapsedMs = 75_000, held = false))) }
}
