package dev.supermessage.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.supermessage.AudioNote
import dev.supermessage.TimelineRow
import dev.supermessage.kit.NotePlayback
import java.time.Instant
import uniffi.supermessage_core.AudioView
import uniffi.supermessage_core.ItemView

/** Fixed, for the reason [NOW] in `RosterPreviews.kt` gives. */
private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

private val PHONE = 411.dp

private val voice: AudioView
    get() = PreviewFixtures.voiceView()

/**
 * A note in a given state, on its side, the way `AudioRow` places it. The
 * states that need a player (playing, paused, loading, failed) are drawn
 * straight from [AudioNote], since a preview has no player to put them in.
 */
@Composable
private fun NoteIn(playback: NotePlayback, isOwn: Boolean, audio: AudioView = voice) {
    AudioNote(
        audio = audio,
        isOwn = isOwn,
        playback = playback,
        onToggle = {},
        onSeek = {},
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(if (isOwn) Alignment.End else Alignment.Start),
    )
}

/** Your own voice note at rest: trailing, in the own bubble's tint, "0:07". */
@Preview(name = "Voice note, own", showBackground = true, heightDp = 120)
@Composable
internal fun VoiceOwnIdle() {
    PreviewGround(width = PHONE) {
        TimelineRow(row = PreviewFixtures.voiceOwn, now = NOW)
    }
}

/** Someone else's, leading, named above it once. */
@Preview(name = "Voice note, peer", showBackground = true, heightDp = 140)
@Composable
internal fun VoicePeerIdle() {
    PreviewGround(width = PHONE) {
        TimelineRow(row = PreviewFixtures.voicePeer, now = NOW)
    }
}

/**
 * Three seconds into seven: the bars fill in the accent up to there, the
 * clock shows elapsed time (the core's `audioClockLabel`), and the button is
 * the drawn pause glyph. Below it, the same note paused.
 */
@Preview(name = "Voice note, playing and paused", showBackground = true, heightDp = 160)
@Composable
internal fun VoicePlaying() {
    PreviewGround(width = PHONE) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoteIn(NotePlayback.Playing(positionMs = 3_000, durationMs = 7_000), isOwn = true)
            NoteIn(NotePlayback.Paused(positionMs = 5_200, durationMs = 7_000), isOwn = false)
        }
    }
}

/** Fetching the file: a small spinner inside the button, nothing else moves. */
@Preview(name = "Voice note, loading", showBackground = true, heightDp = 100)
@Composable
internal fun VoiceLoading() {
    PreviewGround(width = PHONE) {
        NoteIn(NotePlayback.Loading, isOwn = false)
    }
}

/** Could not be played: danger, not amber, and the button retries. */
@Preview(name = "Voice note, failed", showBackground = true, heightDp = 160)
@Composable
internal fun VoiceFailed() {
    PreviewGround(width = PHONE) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoteIn(NotePlayback.Failed, isOwn = true)
            NoteIn(NotePlayback.Failed, isOwn = false)
        }
    }
}

/** No waveform in the event: even, quiet bars rather than an invented shape. */
@Preview(name = "Voice note, no waveform", showBackground = true, heightDp = 140)
@Composable
internal fun VoiceNoWaveform() {
    PreviewGround(width = PHONE) {
        TimelineRow(row = PreviewFixtures.voiceNoWaveform, now = NOW)
    }
}

/** The longest note: "4:59" at rest, and the label does not crowd the bars. */
@Preview(name = "Voice note, 4:59", showBackground = true, heightDp = 180)
@Composable
internal fun VoiceLong() {
    PreviewGround(width = PHONE) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TimelineRow(row = PreviewFixtures.voiceLong, now = NOW)
            NoteIn(
                NotePlayback.Playing(positionMs = 134_000, durationMs = 299_000),
                isOwn = true,
                audio = (PreviewFixtures.voiceLong.view as ItemView.Audio).audio,
            )
        }
    }
}

/** An audio file that is not a voice note: its name above the player, its caption below. */
@Preview(name = "Audio file", showBackground = true, heightDp = 200)
@Composable
internal fun AudioFile() {
    PreviewGround(width = PHONE) {
        TimelineRow(row = PreviewFixtures.audioFile, now = NOW)
    }
}

/** The same notes in dark, one of them playing. */
@Preview(name = "Voice notes, dark", showBackground = true, heightDp = 300)
@Composable
internal fun VoiceDark() {
    PreviewGround(dark = true, width = PHONE) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TimelineRow(row = PreviewFixtures.voicePeer, now = NOW)
            NoteIn(NotePlayback.Playing(positionMs = 3_000, durationMs = 7_000), isOwn = true)
            NoteIn(NotePlayback.Failed, isOwn = false)
        }
    }
}

/**
 * A note with the hub's transcript under it, on both sides. The core folds
 * the transcript into the note's own row (`ItemView.Audio`'s `transcript`)
 * and hides the notice; this is where the two have to read as one thing.
 */
@Preview(name = "Voice notes with transcripts", showBackground = true, heightDp = 420)
@Composable
internal fun VoiceWithTranscript() {
    PreviewGround(width = PHONE) {
        Column {
            TimelineRow(row = PreviewFixtures.voiceOwnTranscribed, now = NOW)
            TimelineRow(row = PreviewFixtures.voicePeerTranscribed, now = NOW)
        }
    }
}

/**
 * The live case of 2026-09-30: the note, "Hi?" a second later, then the
 * transcript. It is drawn under the note it belongs to, above "Hi?"; the
 * notice's own row, after "Hi?", is hidden by the core.
 */
@Preview(name = "Transcript under its note, after another message", showBackground = true, heightDp = 300)
@Composable
internal fun TranscriptAfterAnotherMessage() {
    PreviewGround(width = PHONE) {
        Column {
            TimelineRow(row = PreviewFixtures.voiceOwnTranscribed, now = NOW)
            TimelineRow(row = PreviewFixtures.ownFollowUp, now = NOW)
        }
    }
}

/** The note is not loaded yet: the transcript stands alone, on the note's side. */
@Preview(name = "Transcript, its note not loaded", showBackground = true, heightDp = 160)
@Composable
internal fun TranscriptWithoutItsNote() {
    PreviewGround(width = PHONE) {
        TimelineRow(row = PreviewFixtures.voiceOwnTranscript, now = NOW)
    }
}

/** At twice the text size the button and bars hold their size; the words grow. */
@Preview(name = "Voice note, huge text", showBackground = true, heightDp = 260, fontScale = 2f)
@Composable
internal fun VoiceHugeText() {
    PreviewGround(width = PHONE) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TimelineRow(row = PreviewFixtures.voicePeer, now = NOW)
            NoteIn(NotePlayback.Playing(positionMs = 3_000, durationMs = 7_000), isOwn = true)
        }
    }
}

/**
 * An agent's answer, spoken: one message — the voice player, then the text it
 * speaks. The voice message's own row is hidden by the core.
 */
@Preview(name = "Voice reply", showBackground = true, heightDp = 420)
@Composable
internal fun VoiceReply() {
    PreviewGround(width = PHONE) {
        Column {
            TimelineRow(row = PreviewFixtures.voiceReplyShort, now = NOW)
            TimelineRow(row = PreviewFixtures.voiceReplyCode, now = NOW)
        }
    }
}

/** A reply long enough to read as a report; the player stays on top. */
@Preview(name = "Voice reply, long", showBackground = true, heightDp = 640)
@Composable
internal fun VoiceReplyLong() {
    PreviewGround(width = PHONE) {
        TimelineRow(row = PreviewFixtures.voiceReplyLong, now = NOW)
    }
}

@Preview(name = "Voice reply, dark", showBackground = true, heightDp = 420)
@Composable
internal fun VoiceReplyDark() {
    PreviewGround(dark = true, width = PHONE) {
        Column {
            TimelineRow(row = PreviewFixtures.voiceReplyShort, now = NOW)
            TimelineRow(row = PreviewFixtures.voiceReplyCode, now = NOW)
        }
    }
}

@Preview(name = "Voice reply, huge text", showBackground = true, heightDp = 480, fontScale = 2f)
@Composable
internal fun VoiceReplyHugeText() {
    PreviewGround(width = PHONE) {
        TimelineRow(row = PreviewFixtures.voiceReplyShort, now = NOW)
    }
}

/** Your note, its transcript, and the agent's spoken answer: the whole exchange. */
@Preview(name = "Voice reply after a transcript", showBackground = true, heightDp = 460)
@Composable
internal fun VoiceReplyAfterTranscript() {
    PreviewGround(width = PHONE) {
        Column {
            TimelineRow(row = PreviewFixtures.voiceOwnTranscribed, now = NOW)
            TimelineRow(row = PreviewFixtures.voiceReplyShort, now = NOW)
        }
    }
}
