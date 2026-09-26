// How a voice note's transcript sits under the note.
//
// Everything a reader is *told* — the caption ("Transcript · hi · 0:42"), the
// words — is `core::voice_transcript`'s, and which side the note is on arrives
// as `onOwnNote`. What is left here is presentation, shared with iOS
// (`VoiceTranscriptPresentation.swift`) and Android (`VoiceTranscript.kt`):
// a transcript is a quote of the note, drawn quietly under it on the note's
// side, and a long one opens at its first six lines.

/** How many lines a transcript shows before "Show more". */
export const CLAMPED_LINES = 6;

/** The disclosure under a transcript longer than {@link CLAMPED_LINES}. */
export function toggleLabel(expanded: boolean): string {
  return expanded ? "Show less" : "Show more";
}

/** Which edge of the reading column the transcript hugs. */
export function transcriptSide(onOwnNote: boolean): "end" | "start" {
  return onOwnNote ? "end" : "start";
}

/**
 * Whether clamped text is hiding anything: its full height is more than the
 * box it is drawn in. A pixel of slack, because line boxes round.
 */
export function isClamped(scrollHeight: number, clientHeight: number): boolean {
  return scrollHeight > clientHeight + 1;
}
