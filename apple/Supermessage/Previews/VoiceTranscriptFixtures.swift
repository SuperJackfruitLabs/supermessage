#if DEBUG
import SupermessageFFI
import SupermessageKit
import SwiftUI

/// Voice notes and the hub's transcripts of them, as the core draws them.
///
/// Hand-written from `core::voice_transcript`, and pinned there: the Rust
/// test `host_fixtures::voice_transcripts` parses the same payloads and
/// asserts these captions, so a wording change fails on the side that makes
/// it.
extension PreviewFixtures {
    static func transcript(
        _ text: String, language: String? = nil, seconds: UInt32? = nil
    ) -> VoiceNoteTranscript {
        let duration = seconds.map { String(format: "%d:%02d", $0 / 60, $0 % 60) }
        let caption = (["Transcript"] + [language, duration].compactMap { $0 })
            .joined(separator: " · ")
        return VoiceNoteTranscript(
            text: text, language: language, seconds: seconds, duration: duration,
            caption: caption, accessibilityLabel: "Transcript of voice note: \(text)")
    }

    static let transcriptShort = transcript(
        "Can you move the review to Thursday?", language: "en", seconds: 42)

    static let transcriptLong = transcript(
        """
        Quick update on the migration before tomorrow's standup. The staging \
        database finished copying overnight and the row counts match, so I think \
        we're clear to point the read replicas at it this afternoon. Two things \
        are still open. First, the nightly export job still writes to the old \
        bucket, and I'd rather not flip it until Priya has checked the retention \
        rules on the new one. Second, the search index needs a full rebuild after \
        the cutover, which took about forty minutes last time, so let's schedule \
        it for after six when traffic drops. If anyone sees errors from the \
        billing service in the next hour, ping me directly rather than filing a \
        ticket, because I'll be watching the logs anyway.
        """, language: "en", seconds: 138)

    static let transcriptBare = transcript("Running ten minutes late, start without me.")

    static let transcriptHindi = transcript(
        "कल सुबह दस बजे टीम की बैठक है", language: "hi", seconds: 4)

    static func voiceNote(
        id: String, sender: String, isOwn: Bool, audio: AudioView = PreviewFixtures.voiceAudio()
    ) -> TimelineItemDto {
        item(
            id: id, sender: sender, body: audio.filename, isOwn: isOwn, msgtype: "m.audio",
            media: MediaMetaDto(
                filename: audio.filename, mimetype: audio.mimetype, size: audio.size, width: nil,
                height: nil,
                audio: AudioMetaDto(
                    isVoice: audio.isVoice, durationMs: audio.durationMs, waveform: audio.waveform)))
    }

    /// Your own voice note, as the recorder sends it.
    static var ownVoiceNote: TimelineRow {
        row(
            voiceNote(id: "$note", sender: "@me:example.org", isOwn: true),
            view: .audio(audio: voiceAudio(), transcript: nil),
            senderName: "You", senderShort: "You", senderInitial: "Y")
    }

    /// Krishna's voice note.
    static var colleagueVoiceNote: TimelineRow {
        let audio = voiceAudio(ms: 12_000, label: "0:12", spoken: "12 seconds", size: 31_200, seed: 5)
        return row(
            voiceNote(id: "$note-krishna", sender: "@krishna:example.org", isOwn: false, audio: audio),
            view: .audio(audio: audio, transcript: nil),
            senderName: "Krishna", senderShort: "Krishna", senderInitial: "K")
    }

    /// `note` with `transcript` folded in, as the core draws a note once
    /// the hub's transcript of it is in the timeline too — wherever the
    /// notice landed (`core::voice_transcript::reconcile`).
    static func transcribed(_ note: TimelineRow, _ transcript: VoiceNoteTranscript) -> TimelineRow {
        guard case let .audio(audio, _) = note.view else { return note }
        var row = note
        row.view = .audio(audio: audio, transcript: transcript)
        return row
    }

    /// What you typed a second after the note: "Hi?", which the transcript
    /// landed after — and which the transcript must not be drawn under.
    static var ownFollowUp: TimelineRow {
        row(
            item(id: "$hi", sender: "@me:example.org", body: "Hi?", isOwn: true),
            view: .bubble(muted: false, blocks: [.paragraph(inlines: [.text(text: "Hi?")])], voice: nil),
            senderName: "You", senderShort: "You", senderInitial: "Y")
    }

    /// The hub's notice carrying `transcript`, a reply to the note above —
    /// drawn standalone only while that note is not loaded.
    static func transcriptRow(_ transcript: VoiceNoteTranscript, onOwnNote: Bool) -> TimelineRow {
        let body = "Transcript: \(transcript.text)"
        return row(
            item(
                id: "$transcript", sender: "@agentpod:example.org", body: body, msgtype: "m.notice"),
            view: .voiceTranscript(transcript: transcript, onOwnNote: onOwnNote),
            senderName: "AgentPod", senderShort: "AgentPod", senderInitial: "A",
            replyPreview: body)
    }
}
#endif
