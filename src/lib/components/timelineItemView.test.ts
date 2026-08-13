// Covers the render classification `Timeline.svelte` switches on. See
// `timelineItemView.ts` for why suppression happens here rather than in the
// core, and why rendering nothing for unrecognised state events used to be
// a real bug and not just an omission.

import { describe, expect, it } from "vitest";
import { viewFor } from "./timelineItemView";
import type { TimelineItem } from "$lib/ipc";

function item(overrides: Partial<TimelineItem> & Pick<TimelineItem, "kind">): TimelineItem {
  return {
    id: `id-${overrides.kind}`,
    msgtype: null,
    detail: null,
    sender: "@someone:example.org",
    senderDisplayName: null,
    body: null,
    formattedBody: null,
    timestampMs: 1_700_000_000_000,
    isOwn: false,
    sendState: null,
    ...overrides,
  };
}

describe("viewFor", () => {
  describe("message", () => {
    it("renders m.text as a plain bubble", () => {
      expect(viewFor(item({ kind: "message", msgtype: "m.text", body: "hi" }))).toEqual({
        render: "bubble",
        muted: false,
      });
    });

    it("renders m.notice as a bubble, muted — not dropped", () => {
      expect(viewFor(item({ kind: "message", msgtype: "m.notice", body: "build ok" }))).toEqual({
        render: "bubble",
        muted: true,
      });
    });

    it("renders m.emote as its own kind, distinct from a bubble", () => {
      const view = viewFor(item({ kind: "message", msgtype: "m.emote", body: "waves" }));
      expect(view.render).toBe("emote");
    });

    it("renders media msgtypes as a placeholder naming the type", () => {
      expect(viewFor(item({ kind: "message", msgtype: "m.image" })).render).toBe("placeholder");
      const view = viewFor(item({ kind: "message", msgtype: "m.image" }));
      expect(view).toMatchObject({ render: "placeholder" });
      if (view.render === "placeholder") {
        expect(view.text.toLowerCase()).toContain("image");
      }
    });

    it("falls back to a placeholder naming the msgtype for anything else", () => {
      const view = viewFor(item({ kind: "message", msgtype: "m.location" }));
      expect(view).toEqual({ render: "placeholder", text: "Unsupported message (m.location)" });
    });
  });

  it("names undecryptable events specifically, not generically", () => {
    const view = viewFor(item({ kind: "unableToDecrypt" }));
    expect(view.render).toBe("placeholder");
    if (view.render === "placeholder") {
      expect(view.text.toLowerCase()).toContain("encrypted");
    }
  });

  it("renders redactions as a deletion tombstone, not a blank", () => {
    expect(viewFor(item({ kind: "redacted" }))).toEqual({
      render: "placeholder",
      text: "Message deleted",
    });
  });

  describe("state", () => {
    it("renders nothing for m.room.name — the regression this refactor exists to prevent", () => {
      expect(viewFor(item({ kind: "state", detail: "m.room.name" }))).toEqual({ render: "none" });
    });

    it("renders nothing for state events in general by default", () => {
      expect(viewFor(item({ kind: "state", detail: "m.room.topic" }))).toEqual({ render: "none" });
      expect(viewFor(item({ kind: "state", detail: "m.room.power_levels" }))).toEqual({
        render: "none",
      });
    });

    it("surfaces room creation naming the creator, not the generic 'Beginning of the room' text", () => {
      // Not the same text as `timelineStart` (below): both can appear in the
      // same fully-loaded room (the SDK loads `m.room.create` and inserts
      // `TimelineStart` together once back-pagination reaches genuine
      // history), so this uses different wording rather than repeating it —
      // see `stateView`'s `m.room.create` case for the full reasoning.
      const view = viewFor(
        item({ kind: "state", detail: "m.room.create", senderDisplayName: "Alice" }),
      );
      expect(view).toEqual({ render: "system", text: "Alice created the room" });
    });

    it("falls back to the raw sender id for room creation when there is no display name", () => {
      const view = viewFor(
        item({ kind: "state", detail: "m.room.create", sender: "@alice:example.org" }),
      );
      expect(view).toEqual({ render: "system", text: "@alice:example.org created the room" });
    });

    it("surfaces encryption being enabled", () => {
      const view = viewFor(item({ kind: "state", detail: "m.room.encryption" }));
      expect(view.render).toBe("system");
      if (view.render === "system") expect(view.text.toLowerCase()).toContain("encryption");
    });

    it("surfaces a tombstone as a system line", () => {
      const view = viewFor(item({ kind: "state", detail: "m.room.tombstone" }));
      expect(view.render).toBe("system");
    });
  });

  describe("membership", () => {
    it("renders a system line naming the sender and the change", () => {
      const view = viewFor(
        item({ kind: "membership", detail: "joined", senderDisplayName: "Alice" }),
      );
      expect(view).toEqual({ render: "system", text: "Alice joined the room" });
    });

    it("falls back to the raw sender id when there is no display name", () => {
      const view = viewFor(
        item({ kind: "membership", detail: "left", sender: "@bob:example.org" }),
      );
      expect(view).toEqual({ render: "system", text: "@bob:example.org left the room" });
    });
  });

  it("suppresses profile changes by default", () => {
    expect(viewFor(item({ kind: "profileChange" }))).toEqual({ render: "none" });
  });

  it("renders a failed-to-parse event with the wire text naming the type", () => {
    expect(viewFor(item({ kind: "failedToParse", detail: "m.some.custom" }))).toEqual({
      render: "placeholder",
      text: "Unsupported event (m.some.custom)",
    });
  });

  it("renders nothing for the read marker, which legitimately has no visual form yet", () => {
    expect(viewFor(item({ kind: "readMarker" }))).toEqual({ render: "none" });
  });

  it("renders the timeline-start virtual item as the 'Beginning of the room' system line", () => {
    expect(viewFor(item({ kind: "timelineStart" }))).toEqual({
      render: "system",
      text: "Beginning of the room",
    });
  });

  it("names stickers, polls, calls and custom suite events as placeholders, not silence", () => {
    expect(viewFor(item({ kind: "sticker" })).render).toBe("placeholder");
    expect(viewFor(item({ kind: "poll" })).render).toBe("placeholder");
    expect(viewFor(item({ kind: "liveLocation" })).render).toBe("placeholder");
    expect(viewFor(item({ kind: "callInvite" })).render).toBe("placeholder");
    expect(viewFor(item({ kind: "rtcNotification" })).render).toBe("placeholder");
    const custom = viewFor(item({ kind: "customMessage", detail: "org.supermessage.card" }));
    expect(custom).toEqual({ render: "placeholder", text: "Custom event (org.supermessage.card)" });
  });

  it("never returns an empty placeholder string, which would render as a bare empty line", () => {
    const kinds = [
      "message",
      "sticker",
      "poll",
      "redacted",
      "unableToDecrypt",
      "customMessage",
      "liveLocation",
      "callInvite",
      "rtcNotification",
      "failedToParse",
    ];
    for (const kind of kinds) {
      const view = viewFor(item({ kind }));
      if (view.render === "placeholder") {
        expect(view.text).not.toBe("");
      }
    }
  });
});
