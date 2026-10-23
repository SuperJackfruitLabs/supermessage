// Story-only stand-ins for the core calls the live card makes. They are not the
// core's functions: the real words and parse are `core::turn_label` and
// `core::rich`, covered by the core's own tests. These return the same shapes
// for the fixture states so a story can show them without a Tauri runtime.

import type { RichBlock } from "$lib/ipc";
import type { LiveCardServices } from "./liveCardServices";

/** "8s", "1m 04s", "1h 02m": `core::turn_label::elapsed_label`'s shapes. */
function elapsed(ms: number): string {
  const total = Math.floor(ms / 1000);
  if (total < 60) return `${total}s`;
  const m = Math.floor(total / 60);
  if (m < 60) return `${m}m ${String(total % 60).padStart(2, "0")}s`;
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`;
}

/** The instant story frames read as "now": a turn started at STORY_TURN_START has run 3 s. */
export const STORY_TURN_START = 1_700_000_000_000;
export const STORY_NOW = STORY_TURN_START + 3000;

export const storyServices: LiveCardServices = {
  elapsedLabel: async (ms) => elapsed(ms),
  statusWord: async (endedBy) => (endedBy === null ? "Working" : endedBy === "timedOut" ? "Stopped" : "Done"),
  now: () => STORY_NOW,
  parse: async (markdown) =>
    markdown
      .split(/\n\n+/)
      .filter((p) => p !== "")
      .map((text): RichBlock => ({ block: "paragraph", inlines: [{ inline: "text", text }] })),
};
