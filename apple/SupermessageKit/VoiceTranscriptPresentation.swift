import SupermessageFFI

/// How a voice note's transcript lays out under the note.
///
/// Everything a reader is *told* — the caption ("Transcript · hi · 0:42"), the
/// spoken sentence — arrives decided on `VoiceNoteTranscript` from
/// `core::voice_transcript`, and which side of the timeline the note is on
/// arrives as `onOwnNote`. What is left is presentation, shared with the web's
/// `voiceTranscriptView.ts` and Android's `VoiceTranscript.kt`:
///
/// A transcript is a quote of the note, not a message of the agent that
/// posted it, so it is drawn quietly under the note on the note's side, and a
/// long one opens at its first six lines.
public enum VoiceTranscriptPresentation {
    /// How many lines a transcript shows before "Show more".
    public static let clampedLines = 6

    /// The disclosure under a transcript longer than ``clampedLines``.
    public static func toggleLabel(expanded: Bool) -> String {
        expanded ? "Show less" : "Show more"
    }
}
