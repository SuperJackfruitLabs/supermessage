import type { AudioView, ItemView, RichBlock } from "$lib/ipc";
import type { NotePlayback } from "$lib/stores/audioPlayback.svelte";

/**
 * Audio messages, as `core::audio::audio_view` draws them, and the player
 * states a row can be in.
 *
 * The labels are the core's wording, written out by hand: "0:07" rounded at
 * rest, "Voice message, 7 seconds" for a screen reader. A story shows them;
 * it never formats them.
 */

/** A deterministic, speech-like shape: loud and quiet runs, never flat. */
function speech(bars: number, seed = 1): number[] {
  return Array.from({ length: bars }, (_, i) => {
    const v = 0.12 + 0.85 * Math.abs(Math.sin((i + seed) * 0.37) * Math.cos((i + seed) * 0.11));
    return Math.round(v * 1000) / 1000;
  });
}

export function audioNote(overrides: Partial<AudioView> = {}): AudioView {
  return {
    isVoice: true,
    durationMs: 7000,
    lengthLabel: "0:07",
    waveform: speech(64),
    title: "Voice message",
    filename: "Voice message.ogg",
    size: 7992,
    mimetype: "audio/ogg",
    caption: null,
    accessibilityLabel: "Voice message, 7 seconds",
    ...overrides,
  };
}

export const voiceNoteShort = audioNote();

export const voiceNoteLong = audioNote({
  durationMs: 299_000,
  lengthLabel: "4:59",
  waveform: speech(120, 7),
  size: 480_120,
  accessibilityLabel: "Voice message, 4 minutes 59 seconds",
});

/** A sender that wrote no waveform: the player draws an even placeholder. */
export const voiceNoteNoWaveform = audioNote({ waveform: null });

export const audioFile = audioNote({
  isVoice: false,
  durationMs: 65_000,
  lengthLabel: "1:05",
  waveform: null,
  title: "standup-2026-09-26.m4a",
  filename: "standup-2026-09-26.m4a",
  size: 1_048_576,
  mimetype: "audio/mp4",
  accessibilityLabel: "Audio, standup-2026-09-26.m4a, 1 minute 5 seconds",
});

export const voiceNoteCaptioned = audioNote({ caption: "The bit about the migration is at the end." });

export function audioView(audio: AudioView = voiceNoteShort): ItemView {
  return { render: "audio", audio };
}

export const playbackIdle: NotePlayback = { status: "idle", positionMs: 0, durationMs: null };
export const playbackLoading: NotePlayback = { status: "loading", positionMs: 0, durationMs: 7000 };
export const playbackPlaying: NotePlayback = { status: "playing", positionMs: 3200, durationMs: 7000 };
export const playbackPaused: NotePlayback = { status: "paused", positionMs: 5100, durationMs: 7000 };
export const playbackError: NotePlayback = { status: "error", positionMs: 0, durationMs: null };
export const playbackLongPlaying: NotePlayback = { status: "playing", positionMs: 151_400, durationMs: 299_000 };

/**
 * An agent's answer, spoken, as `core::item_view::voice_reply_view` retitles
 * the voice note it carries. Pinned by the Rust test
 * `host_fixtures::voice_replies`.
 */
export function voiceReplyAudio(overrides: Partial<AudioView> = {}): AudioView {
  return audioNote({
    durationMs: 4210,
    lengthLabel: "0:04",
    waveform: speech(60, 3),
    title: "Voice reply",
    size: 48_213,
    accessibilityLabel: "Voice reply, 4 seconds",
    ...overrides,
  });
}

export const voiceReplyShortAudio = voiceReplyAudio();

export const voiceReplyLongAudio = voiceReplyAudio({
  durationMs: 41_800,
  lengthLabel: "0:42",
  waveform: speech(60, 11),
  accessibilityLabel: "Voice reply, 42 seconds",
});

export const voiceReplyCodeAudio = voiceReplyAudio({
  durationMs: 6900,
  lengthLabel: "0:07",
  waveform: speech(60, 4),
  accessibilityLabel: "Voice reply, 7 seconds",
});

/** The texts those voice replies speak, as the core parses them. */
export const voiceReplyShortBlocks: RichBlock[] = [
  { block: "paragraph", inlines: [{ inline: "text", text: "Yes — the review moved to Thursday at ten." }] },
];

export const voiceReplyLongBlocks: RichBlock[] = [
  "I went through the three proposals for the review slot. Thursday at ten works for everyone who has to be there, and it leaves Friday free for the follow-ups the last review kept pushing into the weekend.",
  "Two things to settle before then. The token audit is still waiting on the dark-mode contrast numbers, which I can have by Wednesday evening if nothing else lands on me, and the Android parity list needs one more pass now that the voice player is shared.",
  "If Thursday slips, the next slot with everyone free is Monday afternoon, which pushes the release notes by a week. I would rather hold Thursday and cut scope than move it.",
].map((text) => ({ block: "paragraph", inlines: [{ inline: "text", text }] }));

/** Pinned by `host_fixtures::voice_replies`, trailing newline included. */
export const voiceReplyCodeBlocks: RichBlock[] = [
  {
    block: "paragraph",
    inlines: [
      { inline: "text", text: "Run " },
      { inline: "code", text: "pnpm check" },
      { inline: "text", text: " first, then:" },
    ],
  },
  { block: "codeBlock", language: "bash", text: "cargo test -p supermessage-core voice_reply\n" },
];
