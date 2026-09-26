import { describe, expect, test } from "vitest";
import { voiceTranscriptHindi, voiceTranscriptShort, voiceTranscriptView } from "$lib/fixtures/voiceTranscript";
import { CLAMPED_LINES, isClamped, toggleLabel, transcriptSide } from "./voiceTranscriptView";

describe("a voice note's transcript", () => {
  test("sits on the side of the note it replies to", () => {
    // Under your own note on your side, though an agent posted it.
    expect(transcriptSide(true)).toBe("end");
    expect(transcriptSide(false)).toBe("start");
  });

  test("opens at six lines and says how to see the rest", () => {
    expect(CLAMPED_LINES).toBe(6);
    expect(toggleLabel(false)).toBe("Show more");
    expect(toggleLabel(true)).toBe("Show less");
  });

  test("offers the disclosure only when the clamp hides something", () => {
    expect(isClamped(200, 120)).toBe(true);
    expect(isClamped(120, 120)).toBe(false);
    // Line boxes round; a pixel is not a hidden line.
    expect(isClamped(121, 120)).toBe(false);
  });

  test("the fixtures carry the core's captions", () => {
    expect(voiceTranscriptShort.caption).toBe("Transcript · en · 0:42");
    expect(voiceTranscriptHindi.caption).toBe("Transcript · hi · 0:04");
    expect(voiceTranscriptView()).toMatchObject({ render: "voiceTranscript", onOwnNote: true });
  });
});
