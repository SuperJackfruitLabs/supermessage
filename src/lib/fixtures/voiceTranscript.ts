import type { ItemView, VoiceNoteTranscript } from "$lib/ipc";

/**
 * Voice notes' transcripts, as the core draws them.
 *
 * Hand-written from `core::voice_transcript`, and pinned there: the Rust test
 * `host_fixtures::voice_transcripts` parses the same payloads and asserts
 * these captions, so a wording change fails on the side that makes it.
 */

function transcript(text: string, language: string | null = null, seconds: number | null = null): VoiceNoteTranscript {
  const duration = seconds === null ? null : `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
  const caption = ["Transcript", language, duration].filter((part) => part !== null).join(" · ");
  return { text, language, seconds, duration, caption, accessibilityLabel: `Transcript of voice note: ${text}` };
}

export const voiceTranscriptShort = transcript("Can you move the review to Thursday?", "en", 42);

export const voiceTranscriptLong = transcript(
  "Quick update on the migration before tomorrow's standup. The staging database finished copying " +
    "overnight and the row counts match, so I think we're clear to point the read replicas at it this " +
    "afternoon. Two things are still open. First, the nightly export job still writes to the old bucket, " +
    "and I'd rather not flip it until Priya has checked the retention rules on the new one. Second, the " +
    "search index needs a full rebuild after the cutover, which took about forty minutes last time, so " +
    "let's schedule it for after six when traffic drops. If anyone sees errors from the billing service in " +
    "the next hour, ping me directly rather than filing a ticket, because I'll be watching the logs anyway.",
  "en",
  138,
);

export const voiceTranscriptBare = transcript("Running ten minutes late, start without me.");

export const voiceTranscriptHindi = transcript("कल सुबह दस बजे टीम की बैठक है", "hi", 4);

export function voiceTranscriptView(value: VoiceNoteTranscript = voiceTranscriptShort, onOwnNote = true): ItemView {
  return { render: "voiceTranscript", transcript: value, onOwnNote };
}
