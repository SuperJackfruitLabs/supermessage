/**
 * Timeline view-model fixtures: builders, and the named scenarios built on
 * them.
 *
 * These were private to `timelineGrouping.test.ts`. They are here so the
 * story catalogue and the tests share one definition of what a
 * `TimelineRow` looks like, rather than five — and moved VERBATIM, so a
 * failure after this change means the move broke something, not that a
 * rewrite did.
 */
import type { TimelineItem, TimelineRow } from "$lib/ipc";

/**
 * The verbs this file's fixtures need, standing in for the core.
 *
 * A deliberate stand-in, not a duplicate of the real table: `membershipVerb`
 * lives in `core::item_view` now, and grouping is what is under test here —
 * these tests assert that a run of five joins composes into one sentence, not
 * that "joined" is the word for joining. Anything not listed falls through to
 * the same generic the core uses.
 */
export const FIXTURE_VERBS: Record<string, string> = {
  joined: "joined the room",
  left: "left the room",
  invited: "was invited",
};

export function item(overrides: Partial<TimelineItem> & Pick<TimelineItem, "kind" | "id">): TimelineRow {
  const dto: TimelineItem = {
    eventId: null,
    msgtype: null,
    detail: null,
    sender: "@someone:example.org",
    senderAvatar: null,
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
    editable: false,
    ...overrides,
  };
  return row(dto);
}

/**
 * Wrap a DTO the way the core does. The grouper reads `senderName` and
 * `membershipVerb`; the rest is carried through untouched, so it is filled
 * with the cheapest thing that type-checks rather than with a second
 * implementation of `view_for`.
 */
export function row(dto: TimelineItem): TimelineRow {
  return {
    item: dto,
    view: { render: "none" },
    senderName: dto.senderDisplayName ?? dto.sender ?? "Someone",
    senderShort: dto.senderDisplayName ?? dto.sender ?? "Someone",
    membershipVerb:
      dto.kind === "membership"
        ? (FIXTURE_VERBS[dto.detail ?? ""] ?? "updated their membership")
        : null,
    replyQuote: null,
    canReplyOrReact: true,
    replyPreview: null,
  };
}

/** Overrides shape shared by every object-argument fixture builder below. */
export type ItemOverrides = Partial<TimelineItem> & Pick<TimelineItem, "id">;

// `membership` and `dateDivider` below keep their original (id, ...) call
// shape for the membership-grouping tests above, and additionally accept a
// single overrides object — the shape the sender-run tests need (they set
// `sender`/`timestampMs` directly, which the positional shape has no room
// for) — via a second overload. Same fixture builder, not a second one.
export function membership(id: string, detail: string | null, name: string): TimelineRow;
export function membership(overrides: ItemOverrides): TimelineRow;
export function membership(idOrOverrides: string | ItemOverrides, detail?: string | null, name?: string): TimelineRow {
  if (typeof idOrOverrides === "string") {
    return item({ id: idOrOverrides, kind: "membership", detail: detail ?? null, senderDisplayName: name ?? null });
  }
  return item({ kind: "membership", ...idOrOverrides });
}

// Same two-shape pattern as `membership`/`dateDivider` above: the original
// positional call (used throughout the membership-grouping tests) plus an
// overrides-object overload (used by the sender-run tests below, which need
// `sender`/`timestampMs` set per-call). Previously duplicated as a second
// `msg()` builder; collapsed into one name per code review.
export function message(id: string, body?: string): TimelineRow;
export function message(overrides: ItemOverrides): TimelineRow;
export function message(idOrOverrides: string | ItemOverrides, body = "hi"): TimelineRow {
  if (typeof idOrOverrides === "string") {
    return item({ id: idOrOverrides, kind: "message", msgtype: "m.text", body });
  }
  return item({ kind: "message", msgtype: "m.text", body: "hi", ...idOrOverrides });
}

export function dateDivider(id: string): TimelineRow;
export function dateDivider(overrides: ItemOverrides): TimelineRow;
export function dateDivider(idOrOverrides: string | ItemOverrides): TimelineRow {
  if (typeof idOrOverrides === "string") {
    return item({ id: idOrOverrides, kind: "dateDivider", timestampMs: 1_700_000_000_000 });
  }
  return item({ kind: "dateDivider", ...idOrOverrides });
}

/** A custom-message item (`kind: "customMessage"`) — spec §7's bordered card. */
export function custom(overrides: ItemOverrides): TimelineRow {
  return item({ kind: "customMessage", ...overrides });
}

// ---------------------------------------------------------------------------
// Named scenarios
// ---------------------------------------------------------------------------
//
// The states worth looking at. This list is the answer to a question the
// design-language audit raised and could not settle: what states does this
// UI have? They were uncountable, which is why drift between the three
// platforms was invisible — nobody could see that web had `AgentReasoning`
// and `Shimmer` while native had neither.
//
// Each one asserts its own name in `fixtures.test.ts`. A fixture that has
// drifted from what it is called is worse than no fixture, because a story
// then demonstrates the wrong thing convincingly.

/** A plain paragraph of rich text, the shape the core hands a bubble. */
function paragraph(text: string) {
  return { block: "paragraph" as const, inlines: [{ inline: "text" as const, text }] };
}

/** A rendered bubble view for the given text. */
function bubble(text: string, muted = false) {
  return { render: "bubble" as const, muted, blocks: [paragraph(text)] };
}

/**
 * Two messages from one sender inside the run window, so the second
 * continues the first rather than repeating the attribution.
 *
 * The window is `SENDER_RUN_WINDOW_MS` (five minutes) in
 * `timelineGrouping.ts`, and these timestamps are stated relative to it
 * rather than as magic numbers.
 */
export const senderRun: TimelineRow[] = [
  {
    ...message({ id: "run-1", sender: "@atlas:example.org", senderDisplayName: "Atlas", timestampMs: 1_700_000_000_000, body: "Staging is green across all four ABIs." }),
    view: bubble("Staging is green across all four ABIs."),
  },
  {
    ...message({ id: "run-2", sender: "@atlas:example.org", senderDisplayName: "Atlas", timestampMs: 1_700_000_060_000, body: "Ready to promote." }),
    view: bubble("Ready to promote."),
  },
];

/** The day separator between two runs. */
export const dayDivider: TimelineRow = {
  ...dateDivider({ id: "divider-1", timestampMs: 1_700_000_000_000 }),
  view: { render: "dateDivider" },
};

/** An own message still in flight. */
export const ownMessageSending: TimelineRow = {
  ...message({ id: "own-sending", isOwn: true, body: "Hold — check the 16KB device first.", sendState: "sending" }),
  view: bubble("Hold — check the 16KB device first."),
};

/** An own message the core could not send. */
export const ownMessageFailed: TimelineRow = {
  ...message({ id: "own-failed", isOwn: true, body: "Hold — check the 16KB device first.", sendState: "failed" }),
  view: bubble("Hold — check the 16KB device first."),
};

/**
 * A single token long enough to widen any container that does not guard.
 *
 * The timeline's wrap guards — `break-words`, `max-w-[68ch]`, `min-w-0` —
 * exist because body text is sender-controlled, and a story is the only
 * place it is easy to see them working. Deliberately contains NO whitespace:
 * a fixture named for a wrap hazard that can break at a space is not one.
 */
const UNBROKEN = "wss://bridge.agentpod.dev/v1/sessions/" + "a".repeat(90) + "/stream";

export const longUnbrokenToken: TimelineRow = {
  ...message({ id: "long-token", body: UNBROKEN }),
  view: bubble(UNBROKEN),
};

/**
 * An encrypted event this build cannot render.
 *
 * Not a card, on purpose: a type the client cannot read at all is a log
 * line like any other. `docs/parity-gap-analysis.md` records that encrypted
 * rooms render a placeholder, so this is a state real users see.
 */
export const encryptedPlaceholder: TimelineRow = {
  ...item({ id: "encrypted-1", kind: "encrypted" }),
  view: { render: "placeholder", text: "Encrypted message" },
};

/** A message the reader has reacted to, plus one they have not. */
export const reactionsMine: TimelineRow = {
  ...message({
    id: "reactions-mine",
    body: "Promote build 214?",
    reactions: [
      { key: "✅", displayKey: "✅", count: 1, byMe: true, senders: ["@me:example.org"] },
      { key: "👀", displayKey: "👀", count: 2, byMe: false, senders: ["@a:example.org", "@b:example.org"] },
    ],
  }),
  view: bubble("Promote build 214?"),
};

/**
 * Enough distinct reaction keys to wrap the row, including a sender-controlled
 * key that is not a single emoji — which `Reaction.key`'s own doc comment
 * warns about.
 */
export const reactionsMany: TimelineRow = {
  ...message({
    id: "reactions-many",
    body: "Ship it?",
    reactions: [
      { key: "✅", displayKey: "✅", count: 4, byMe: true, senders: [] },
      { key: "👍", displayKey: "👍", count: 3, byMe: false, senders: [] },
      { key: "❌", displayKey: "❌", count: 1, byMe: false, senders: [] },
      { key: "👀", displayKey: "👀", count: 7, byMe: false, senders: [] },
      { key: "🎉", displayKey: "🎉", count: 2, byMe: false, senders: [] },
      { key: "not-an-emoji-at-all", displayKey: "not-an-emoji-at-all", count: 1, byMe: false, senders: [] },
    ],
  }),
  view: bubble("Ship it?"),
};

/** A membership change, which renders as a system line rather than a bubble. */
export const membershipLine: TimelineRow = {
  ...membership({ id: "member-1", detail: "joined", senderDisplayName: "Krishna" }),
  view: { render: "system", text: "Krishna joined the room" },
};
