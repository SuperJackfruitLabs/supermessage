// Covers the render classification `Timeline.svelte` switches on. See
// `timelineItemView.ts` for why suppression happens here rather than in the
// core, and why rendering nothing for unrecognised state events used to be
// a real bug and not just an omission.

import { describe, expect, it } from "vitest";
import {
  canReplyOrReact,
  displayEventType,
  displayReactionKey,
  replyPreviewExcerpt,
  replyQuoteView,
  viewFor,
} from "./timelineItemView";
import type { ReplyTo, TimelineItem } from "$lib/ipc";

function item(overrides: Partial<TimelineItem> & Pick<TimelineItem, "kind">): TimelineItem {
  return {
    id: `id-${overrides.kind}`,
    msgtype: null,
    detail: null,
    sender: "@someone:example.org",
    senderDisplayName: null,
    body: null,
    formattedBody: null,
    media: null,
    customPayload: null,
    timestampMs: 1_700_000_000_000,
    isOwn: false,
    sendState: null,
    replyTo: null,
    edited: false,
    reactions: [],
    readBy: [],
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

    it("renders m.image as its own image kind, carrying alt text and dimensions", () => {
      const view = viewFor(
        item({
          kind: "message",
          msgtype: "m.image",
          body: "cat.png",
          media: { filename: "cat.png", mimetype: "image/png", size: 1024, width: 800, height: 600 },
        }),
      );
      expect(view).toEqual({ render: "image", alt: "cat.png", width: 800, height: 600 });
    });

    it("falls back to the plain body, then a generic label, for an image's alt text", () => {
      const withBodyOnly = viewFor(
        item({ kind: "message", msgtype: "m.image", body: "a screenshot", media: null }),
      );
      expect(withBodyOnly).toEqual({ render: "image", alt: "a screenshot", width: null, height: null });

      const withNeither = viewFor(item({ kind: "message", msgtype: "m.image", body: null, media: null }));
      expect(withNeither).toEqual({ render: "image", alt: "Image", width: null, height: null });
    });

    it("renders m.file/m.audio/m.video as an informative row, not a bare placeholder", () => {
      const file = viewFor(
        item({
          kind: "message",
          msgtype: "m.file",
          body: "report.pdf",
          media: { filename: "report.pdf", mimetype: "application/pdf", size: 2048, width: null, height: null },
        }),
      );
      expect(file).toEqual({
        render: "mediaFile",
        label: "File",
        filename: "report.pdf",
        size: 2048,
        mimetype: "application/pdf",
      });

      expect(
        viewFor(item({ kind: "message", msgtype: "m.audio", media: null, body: "voice.ogg" })),
      ).toEqual({ render: "mediaFile", label: "Audio", filename: "voice.ogg", size: null, mimetype: null });

      expect(
        viewFor(item({ kind: "message", msgtype: "m.video", media: null, body: null })),
      ).toEqual({ render: "mediaFile", label: "Video", filename: "Video", size: null, mimetype: null });
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

  it("names stickers, polls, calls as placeholders, not silence", () => {
    expect(viewFor(item({ kind: "sticker" })).render).toBe("placeholder");
    expect(viewFor(item({ kind: "poll" })).render).toBe("placeholder");
    expect(viewFor(item({ kind: "liveLocation" })).render).toBe("placeholder");
    expect(viewFor(item({ kind: "callInvite" })).render).toBe("placeholder");
    expect(viewFor(item({ kind: "rtcNotification" })).render).toBe("placeholder");
  });

  describe("customMessage", () => {
    // The registry/fallback-chain decision itself is covered exhaustively
    // in `customEvents.test.ts` — these just confirm `viewFor` wires
    // `item.detail`/`item.customPayload`/`item.body` into
    // `resolveCustomEvent` (against the production `customEventRegistry`)
    // rather than deciding anything itself.
    it("dispatches to the customEvent render kind, never a bare placeholder for an unrecognized type", () => {
      const view = viewFor(item({ kind: "customMessage", detail: "org.kaambaan.card.v1" }));
      expect(view).toEqual({
        render: "customEvent",
        view: { status: "placeholder", text: "Custom event (org.kaambaan.card.v1)" },
      });
    });

    it("falls back to the plain-text body for an unrecognized type that has one", () => {
      const view = viewFor(
        item({ kind: "customMessage", detail: "org.kaambaan.card.v1", body: "New card: Ship it" }),
      );
      expect(view).toEqual({
        render: "customEvent",
        view: { status: "fallbackBody", text: "New card: Ship it" },
      });
    });

    it("renders through the shipped demo renderer for its own type", () => {
      const view = viewFor(
        item({
          kind: "customMessage",
          detail: "dev.supermessage.demo.note.v1",
          customPayload: { title: "Deployed to staging" },
        }),
      );
      expect(view).toEqual({
        render: "customEvent",
        view: {
          status: "rendered",
          fields: [{ label: "Note", value: "Deployed to staging" }],
          newerVersion: false,
          decision: null,
        },
      });
    });
  });

  it("never returns an empty placeholder string, which would render as a bare empty line", () => {
    const kinds = [
      "message",
      "sticker",
      "poll",
      "redacted",
      "unableToDecrypt",
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

  it("never returns an empty placeholder/fallback string for a customMessage item with nothing recognisable", () => {
    // Same guarantee as the loop above, but `customMessage` nests its text
    // inside `view.view` (the `resolveCustomEvent` result) rather than
    // directly on the `ItemView`, so it can't share that loop's shape.
    const view = viewFor(item({ kind: "customMessage" }));
    expect(view.render).toBe("customEvent");
    if (view.render === "customEvent" && view.view.status !== "rendered") {
      expect(view.view.text).not.toBe("");
    }
  });
});

function replyTo(overrides: Partial<ReplyTo> = {}): ReplyTo {
  return {
    eventId: "$parent:example.org",
    available: true,
    sender: "@alice:example.org",
    senderDisplayName: "Alice",
    excerpt: "the original message",
    label: null,
    ...overrides,
  };
}

describe("replyQuoteView", () => {
  it("is null for an item that isn't a reply", () => {
    expect(replyQuoteView(null)).toBeNull();
  });

  it("resolves the display name, falling back to the raw sender id", () => {
    const view = replyQuoteView(replyTo({ senderDisplayName: null, sender: "@bob:example.org" }));
    expect(view).toEqual({
      available: true,
      sender: "@bob:example.org",
      excerpt: "the original message",
      label: null,
    });
  });

  it("falls back to a generic placeholder when neither name nor sender id is known", () => {
    const view = replyQuoteView(replyTo({ senderDisplayName: null, sender: null }));
    expect(view).toEqual({
      available: true,
      sender: "Someone",
      excerpt: "the original message",
      label: null,
    });
  });

  it("carries a null excerpt through for a parent with nothing to quote", () => {
    const view = replyQuoteView(replyTo({ excerpt: null }));
    expect(view).toEqual({ available: true, sender: "Alice", excerpt: null, label: null });
  });

  it("carries the core's classification label through when the parent has nothing to quote", () => {
    // The review finding this fixes: before, a redacted/sticker/poll/etc.
    // reply parent rendered as a bare sender name with no indication why.
    // `label` is what `Timeline.svelte` falls back to in that case.
    const view = replyQuoteView(replyTo({ excerpt: null, label: "Message deleted" }));
    expect(view).toEqual({
      available: true,
      sender: "Alice",
      excerpt: null,
      label: "Message deleted",
    });
  });

  it("collapses every unavailable state to a single available:false outcome", () => {
    // Renders as "Original message unavailable" in `Timeline.svelte`, not an
    // empty quote or a spinner — the core already folds
    // Unavailable/Pending/Error together (see `ReplyTo`'s doc comment), so
    // this is the one shape this module needs to handle.
    const view = replyQuoteView(
      replyTo({ available: false, sender: null, senderDisplayName: null, excerpt: null }),
    );
    expect(view).toEqual({ available: false });
  });
});

describe("canReplyOrReact", () => {
  it("allows an ordinary received message (sendState: null)", () => {
    expect(canReplyOrReact(item({ kind: "message", sendState: null }))).toBe(true);
  });

  it("allows a message the server has already echoed back", () => {
    expect(canReplyOrReact(item({ kind: "message", sendState: "sent" }))).toBe(true);
  });

  it("disallows a message still only a local echo", () => {
    expect(canReplyOrReact(item({ kind: "message", sendState: "notSentYet" }))).toBe(false);
  });

  it("disallows a message whose send failed", () => {
    expect(canReplyOrReact(item({ kind: "message", sendState: "sendingFailed" }))).toBe(false);
  });
});

describe("replyPreviewExcerpt", () => {
  it("is null for a null body", () => {
    expect(replyPreviewExcerpt(null)).toBeNull();
  });

  it("is null for a whitespace-only body", () => {
    expect(replyPreviewExcerpt("   ")).toBeNull();
  });

  it("trims surrounding whitespace from a short body", () => {
    expect(replyPreviewExcerpt("  hello there  ")).toBe("hello there");
  });

  it("caps a long body with an ellipsis", () => {
    const long = "x".repeat(500);
    const preview = replyPreviewExcerpt(long);
    expect(preview).not.toBeNull();
    expect(preview!.length).toBeLessThan(long.length);
    expect(preview!.endsWith("…")).toBe(true);
  });
});

describe("displayReactionKey", () => {
  it("leaves a short key untouched", () => {
    expect(displayReactionKey("👍")).toBe("👍");
  });

  it("caps a long space-free key and appends an ellipsis", () => {
    const longKey = "x".repeat(100);
    const displayed = displayReactionKey(longKey);
    expect(displayed.length).toBeLessThan(longKey.length);
    expect(displayed.endsWith("…")).toBe(true);
  });

  it("caps by Unicode code point, not UTF-16 code unit, so it never splits a surrogate pair", () => {
    // Each of these emoji is a single code point outside the BMP (two UTF-16
    // code units each) — a naive `.slice()` by code unit could cut one in
    // half and produce an unpaired surrogate.
    const longKey = "🎉".repeat(40);
    const displayed = displayReactionKey(longKey);
    expect(displayed.endsWith("…")).toBe(true);
    // No lone surrogate: every remaining code point round-trips through
    // `Array.from` at the same count it was capped to.
    const codePoints = Array.from(displayed.replace(/…$/, ""));
    expect(codePoints.every((cp) => cp === "🎉")).toBe(true);
  });
});

describe("displayEventType", () => {
  it("leaves a normal reverse-DNS type untouched", () => {
    expect(displayEventType("dev.supermessage.demo.note.v1")).toBe("dev.supermessage.demo.note.v1");
  });

  it("truncates from the LEFT, keeping the informative tail, with a leading ellipsis", () => {
    const type = `org.example.${"namespace.".repeat(20)}permission.request.v1`;
    const displayed = displayEventType(type);
    expect(displayed.startsWith("…")).toBe(true);
    expect(displayed.endsWith("permission.request.v1")).toBe(true);
    // The regression this guards: the ordinary right-truncation everything
    // else in this module does would keep the shared namespace prefix and
    // throw away the only part that names the event.
    expect(displayed.endsWith("…")).toBe(false);
    expect(displayed.startsWith("org.example.")).toBe(false);
  });

  it("caps the rendered length", () => {
    const displayed = displayEventType("a".repeat(500));
    // 48 kept code points plus the one-character leading ellipsis.
    expect(Array.from(displayed)).toHaveLength(49);
  });

  it("caps by Unicode code point, not UTF-16 code unit, so it never splits a surrogate pair", () => {
    // A Matrix event type is sender-controlled and need not be ASCII.
    const displayed = displayEventType("🎉".repeat(80));
    const codePoints = Array.from(displayed.replace(/^…/, ""));
    expect(codePoints).toHaveLength(48);
    expect(codePoints.every((cp) => cp === "🎉")).toBe(true);
  });

  it("degrades a missing, empty or whitespace-only type to 'unknown', never an empty header", () => {
    expect(displayEventType(null)).toBe("unknown");
    expect(displayEventType("")).toBe("unknown");
    expect(displayEventType("   ")).toBe("unknown");
  });

  it("trims surrounding whitespace rather than rendering it", () => {
    expect(displayEventType("  dev.supermessage.demo.note.v1  ")).toBe(
      "dev.supermessage.demo.note.v1",
    );
  });
});
