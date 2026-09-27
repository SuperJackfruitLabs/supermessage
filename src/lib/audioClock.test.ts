import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";
import { audioClockLabel } from "./audioClock";

// Read from disk rather than imported, so the table can never be bundled
// into the app: it is the core's fixture, and `core::audio`'s own test reads
// the same file. This test is the drift guard between the two.
const TABLE = fileURLToPath(
  new URL("../../crates/supermessage-core/tests/fixtures/audio-clock.json", import.meta.url),
);
const { cases } = JSON.parse(readFileSync(TABLE, "utf8")) as { cases: { ms: number; label: string }[] };

describe("the audio clock", () => {
  test("the shared table is there to check against", () => {
    // Guard the guard: an empty table passes every case below vacuously.
    expect(cases.length).toBeGreaterThan(10);
  });

  test.each(cases)("$ms ms reads $label, as the core writes it", ({ ms, label }) => {
    expect(audioClockLabel(ms)).toBe(label);
  });
});
