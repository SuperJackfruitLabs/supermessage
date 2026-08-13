// Covers the classification `Timeline.svelte` falls back to for anything
// that isn't a date divider or a renderable text message. See
// `timelinePlaceholder.ts` for why rendering nothing was a real bug and not
// just an omission.

import { describe, expect, it } from "vitest";
import { placeholderFor } from "./timelinePlaceholder";
import type { TimelineItem } from "$lib/ipc";

function item(kind: string, body: string | null = null): TimelineItem {
  return {
    id: `id-${kind}`,
    kind,
    sender: "@someone:example.org",
    senderDisplayName: null,
    body,
    timestampMs: 1_700_000_000_000,
    isOwn: false,
    sendState: null,
  };
}

describe("placeholderFor", () => {
  it("names undecryptable events specifically, not generically", () => {
    const text = placeholderFor(item("m.room.encrypted"));
    expect(text).toBeTruthy();
    expect(text?.toLowerCase()).toContain("encrypted");
    // The single most likely thing a dogfooder sees on a fresh device: it
    // must not be lumped in with "this client can't render that".
    expect(text).not.toBe(placeholderFor(item("m.room.member")));
  });

  it("falls back to a generic placeholder naming the event kind", () => {
    expect(placeholderFor(item("m.room.member"))).toBe("Unsupported event (m.room.member)");
    // Redactions reach the webview as "unknown" (the core's
    // `event_type_str()` fallback).
    expect(placeholderFor(item("unknown"))).toBe("Unsupported event (unknown)");
  });

  it("distinguishes a message with no renderable body from a non-message event", () => {
    expect(placeholderFor(item("m.room.message"))).toBe("Unsupported message");
  });

  it("renders nothing for virtual items that legitimately have no visual form", () => {
    expect(placeholderFor(item("readMarker"))).toBeNull();
    expect(placeholderFor(item("timelineStart"))).toBeNull();
  });

  it("never returns an empty string, which would render as a bare empty bubble", () => {
    for (const kind of ["m.room.encrypted", "m.room.member", "unknown", "m.room.message"]) {
      expect(placeholderFor(item(kind))).not.toBe("");
    }
  });
});
