// Classifies a `TimelineItem` into a render decision for `Timeline.svelte`.
//
// This is the webview half of the event-handling refactor described in
// `docs/matrix-events.md`. The core (`core::timeline::classify_content`)
// projects the SDK's `TimelineItemContent` taxonomy into a semantic `kind`
// (plus `msgtype`/`detail` context) instead of a raw Matrix event-type
// string — but the core is a hard no-filter zone: `VectorDiff` indices refer
// to positions in the SDK's own vector, so it must keep emitting exactly one
// item per SDK item (see that module's doc comment). Suppression happens
// here instead, by this module returning `{ render: "none" }` and the
// component rendering nothing for it.
//
// Kept in its own module rather than inline in the component so the
// classification is unit-testable without a DOM (same reasoning as
// `draftTracker.ts`), and so `Timeline.svelte` never has to know the wire
// vocabulary directly.

import type { ReplyTo, TimelineItem } from "$lib/ipc";
import { customEventRegistry, resolveCustomEvent, type CustomEventView } from "./customEvents";

/** What `Timeline.svelte` should render for a given timeline item. */
export type ItemView =
  | { render: "bubble"; muted: boolean }
  | { render: "emote" }
  | { render: "system"; text: string }
  | { render: "placeholder"; text: string }
  /**
   * An `m.image` message. `alt` is always non-empty (falls back through
   * `media.filename`, then the plain `body`, to a generic "Image" — never
   * `aria-hidden`, unlike the decorative room-list avatars, since this is
   * genuine message content). `width`/`height` are the image's own pixel
   * dimensions from `ImageInfo` (`null` when the sender's client never
   * reported them) — `Timeline.svelte` uses them to reserve the thumbnail's
   * box *before* its bytes have even been requested, so the virtualized
   * list never reflows once they land.
   */
  | { render: "image"; alt: string; width: number | null; height: number | null }
  /**
   * An `m.file`/`m.audio`/`m.video` message: no playback or download yet
   * (a follow-up's job — see `Timeline.svelte`), just an informative row
   * naming what the message is. `filename`/`size`/`mimetype` mirror
   * `TimelineItem.media`'s fields one-for-one; `label` is the human-facing
   * kind name (kept as a plain string here, precomputed, so the component
   * never needs its own msgtype-to-label table).
   */
  | {
      render: "mediaFile";
      label: "File" | "Audio" | "Video";
      filename: string;
      size: number | null;
      mimetype: string | null;
    }
  /**
   * A `kind: "customMessage"` item — Kaambaan cards/runs/permission
   * requests/station status, per `docs/matrix-events.md` §G, once those
   * schemas land; the shipped `dev.supermessage.demo.note.v1` renderer
   * until then. `view` is the whole fallback-chain decision
   * (`$lib/components/customEvents.ts`'s `resolveCustomEvent`) —
   * `Timeline.svelte` renders its three states (a known renderer's fields,
   * the plain-text fallback body, or the generic placeholder) but never
   * makes that decision itself.
   */
  | { render: "customEvent"; view: CustomEventView }
  | { render: "none" };

/** msgtype -> the render decision's `label` for the non-image media kinds. */
const MEDIA_FILE_LABELS: Record<string, "File" | "Audio" | "Video"> = {
  "m.file": "File",
  "m.audio": "Audio",
  "m.video": "Video",
};

/** Short verb phrases for the membership-change `detail` values that matter to a reader. */
const MEMBERSHIP_VERBS: Record<string, string> = {
  joined: "joined the room",
  left: "left the room",
  invited: "was invited",
  banned: "was banned",
  unbanned: "was unbanned",
  kicked: "was removed",
  kickedAndBanned: "was removed and banned",
  invitationAccepted: "accepted the invite",
  invitationRejected: "rejected the invite",
  invitationRevoked: "had their invite revoked",
  knocked: "asked to join",
  knockAccepted: "was let in",
  knockRetracted: "withdrew their request to join",
  knockDenied: "was denied entry",
};

/**
 * The name to attribute a system line to: display name, falling back to the
 * raw sender id. Exported for `timelineGrouping.ts`, which needs the same
 * resolution to name the members in a collapsed membership-change sentence.
 */
export function attributedName(item: TimelineItem): string {
  return item.senderDisplayName ?? item.sender ?? "Someone";
}

/**
 * The verb phrase for a membership item's `detail`. Exported for
 * `timelineGrouping.ts`, so a collapsed run's sentence uses exactly the same
 * wording a single, ungrouped membership line would.
 */
export function membershipVerb(detail: string | null): string {
  return (detail && MEMBERSHIP_VERBS[detail]) || "updated their membership";
}

/**
 * The reply-quote view for a message's `replyTo` — `null` when the item
 * isn't a reply at all (`TimelineItem.replyTo` is `null`). When it *is* a
 * reply but the parent's details never loaded (`available: false` — a real
 * and common outcome, not an edge case; see `ReplyTo`'s doc comment in
 * `$lib/ipc`), this collapses to `{ available: false }` with nothing else to
 * show, so `Timeline.svelte` renders a neutral "Original message
 * unavailable" quote rather than an empty one or a spinner that will never
 * resolve on its own.
 *
 * A `Ready` parent (`available: true`) can *also* have nothing to quote —
 * a redacted, sticker, poll, or undecryptable parent has a sender but no
 * body. Before, that rendered as a bare sender name with no indication why;
 * `excerpt`/`label` are mutually exclusive here (`label` is only ever
 * non-null when `excerpt` is `null`) so `Timeline.svelte` can fall back to
 * `label` — the same short text `core::timeline::reply_parent_label`
 * classifies the parent's content into, mirroring the vocabulary
 * `viewFor`'s placeholders already use for a top-level item of the same
 * kind (e.g. "Message deleted").
 */
export type ReplyQuoteView =
  | { available: false }
  | { available: true; sender: string; excerpt: string | null; label: string | null };

/**
 * Derives {@link ReplyQuoteView} from `TimelineItem.replyTo`. Resolves the
 * quoted sender's display name the same way {@link attributedName} does for
 * a top-level item (display name, falling back to the raw sender id, then a
 * generic placeholder) — kept as a separate function rather than reusing
 * `attributedName` directly because a reply's parent is a `ReplyTo`, not a
 * `TimelineItem`.
 */
export function replyQuoteView(replyTo: ReplyTo | null): ReplyQuoteView | null {
  if (!replyTo) return null;
  if (!replyTo.available) return { available: false };
  return {
    available: true,
    sender: replyTo.senderDisplayName ?? replyTo.sender ?? "Someone",
    excerpt: replyTo.excerpt,
    label: replyTo.label,
  };
}

/**
 * Whether `item` can be replied to or have a reaction toggled on it —
 * gated on it already carrying a real Matrix event id rather than a local
 * echo's transaction id. `Timeline::send_reply`/`Timeline::toggle_reaction`
 * (`core::timeline::FocusedTimeline`) both take an event id; `TimelineItem.id`
 * only becomes one once the server has echoed the item back
 * (`core::timeline::event_item_id`), which is exactly when `sendState`
 * stops being `"notSentYet"`/`"sendingFailed"` (see `TimelineItem.sendState`'s
 * doc comment) — `null`/`"sent"` both mean "this id is a real event id".
 */
export function canReplyOrReact(item: TimelineItem): boolean {
  return item.sendState !== "notSentYet" && item.sendState !== "sendingFailed";
}

/**
 * Cap on the composer's reply-preview text, in `char`s — a display-only cap
 * on a *fresh* preview built here from a live local item's `body`, distinct
 * from (and not a substitute for) `core::timeline::REPLY_EXCERPT_MAX_CHARS`,
 * which caps a *quoted* parent's excerpt once it's already crossed IPC. Kept
 * short for the same reason the composer's reply-target row is one line:
 * this is a reminder of what's being replied to, not the full message.
 */
const REPLY_PREVIEW_MAX_CHARS = 140;

/**
 * The text to show as a preview of `body` in the composer's "Replying to …"
 * row, or `null` when there's nothing to preview (a `null`/empty/
 * whitespace-only body — e.g. the reader started a reply from a media
 * message with no caption). Truncates by UTF-16 code unit, unlike
 * `truncate_reply_excerpt`'s by-`char` truncation on the Rust side: this is
 * cosmetic single-line preview text, not the enforcement point for bounding
 * what crosses IPC (that's already done in the core before `body` ever
 * reaches this process), so splitting a multi-byte character on a very rare
 * unlucky boundary costs nothing a `char`-aware pass would meaningfully fix
 * here.
 */
export function replyPreviewExcerpt(body: string | null): string | null {
  if (!body) return null;
  const trimmed = body.trim();
  if (trimmed === "") return null;
  if (trimmed.length <= REPLY_PREVIEW_MAX_CHARS) return trimmed;
  return `${trimmed.slice(0, REPLY_PREVIEW_MAX_CHARS)}…`;
}

/**
 * Cap on a reaction key's *rendered* length, in Unicode code points. The
 * core never truncates a reaction key (unlike a reply excerpt) — the spec
 * puts no length limit on it, and a key is compared byte-for-byte against
 * what other clients sent, so silently mutating it on the wire would break
 * that comparison. This is a display-only cap: a key is arbitrary
 * sender-controlled text, not necessarily a single emoji, so without this a
 * long space-free key could still stretch a reaction chip arbitrarily wide
 * (the overflow-guard rule this codebase enforces on every other free-text
 * field from a sender — see `Timeline.svelte`'s reaction-chip markup, which
 * also carries the CSS-level `overflow-wrap` guard as a second, independent
 * layer).
 */
const REACTION_KEY_MAX_CODEPOINTS = 32;

/**
 * The text to actually render for a reaction key, capped to
 * {@link REACTION_KEY_MAX_CODEPOINTS}. Iterates by Unicode code point
 * (`Array.from`), not UTF-16 code unit (`.length`/`.slice`), so a cap
 * landing mid-emoji still cuts on a whole-code-point boundary rather than
 * splitting a surrogate pair into two unpaired halves.
 */
export function displayReactionKey(key: string): string {
  const codePoints = Array.from(key);
  if (codePoints.length <= REACTION_KEY_MAX_CODEPOINTS) return key;
  return `${codePoints.slice(0, REACTION_KEY_MAX_CODEPOINTS).join("")}…`;
}

/**
 * Cap on a custom event type's *rendered* length, in Unicode code points,
 * in the dispatch card's header (spec §7). Sized for the card's own
 * measure: the header is `--text-label` mono (10px, `0.08em` tracking, so
 * ~6.8px per glyph) inside a `68ch` serif card (~500px at 15px) that also
 * carries a timestamp and 12px of padding on each side — a little over 60
 * glyphs fit, and 48 leaves margin at a narrow window without cutting any
 * plausible reverse-DNS type (`dev.supermessage.demo.note.v1` is 29).
 */
const EVENT_TYPE_MAX_CODEPOINTS = 48;

/**
 * The text to render for a custom event's Matrix type, truncated **from the
 * left** with a leading ellipsis — `…supermessage.demo.note.v1`, never
 * `dev.supermessage.dem…` (spec §7). A reverse-DNS type's tail is the
 * informative part; its head is the namespace every event from one suite
 * shares, so cutting the usual end throws away exactly the half that
 * distinguishes one card from another.
 *
 * Done here, in a pure function, rather than with the `direction: rtl`
 * CSS trick, because `eventType` is `TimelineItem.detail` — a
 * sender-controlled string, not a value this app chose. An RTL base
 * direction hands the Unicode bidi algorithm a hostile string and lets a
 * crafted type reorder itself on screen (leading/trailing neutrals migrate
 * across the run under rule N1, and any strong-RTL character pulls
 * surrounding punctuation with it), which turns a header meant to identify
 * a dispatch into a spoofing surface. A code-point slice reorders nothing
 * and is unit-testable without a browser.
 *
 * Iterates by code point (`Array.from`), not UTF-16 code unit, for the same
 * reason {@link displayReactionKey} does: a cut landing mid-surrogate
 * would emit an unpaired half. `null`, empty, or whitespace-only degrades
 * to `"unknown"` — the vocabulary `resolveCustomEvent`'s own generic
 * placeholder already uses — never to an empty header.
 */
export function displayEventType(eventType: string | null): string {
  const trimmed = eventType?.trim() ?? "";
  if (trimmed === "") return "unknown";
  const codePoints = Array.from(trimmed);
  if (codePoints.length <= EVENT_TYPE_MAX_CODEPOINTS) return trimmed;
  return `…${codePoints.slice(codePoints.length - EVENT_TYPE_MAX_CODEPOINTS).join("")}`;
}

/** Render decision for `kind: "message"`, switching on `msgtype`. */
function messageView(item: TimelineItem): ItemView {
  const { msgtype } = item;
  if (msgtype === "m.text") return { render: "bubble", muted: false };
  // Automated/bot output (bridges, agents) — de-emphasised, not suppressed:
  // spec §A calls this out as the msgtype most of this org's agent traffic
  // actually uses.
  if (msgtype === "m.notice") return { render: "bubble", muted: true };
  if (msgtype === "m.emote") return { render: "emote" };
  if (msgtype === "m.image") {
    return {
      render: "image",
      alt: item.media?.filename ?? item.body ?? "Image",
      width: item.media?.width ?? null,
      height: item.media?.height ?? null,
    };
  }
  if (msgtype != null && msgtype in MEDIA_FILE_LABELS) {
    return {
      render: "mediaFile",
      label: MEDIA_FILE_LABELS[msgtype]!,
      filename: item.media?.filename ?? item.body ?? MEDIA_FILE_LABELS[msgtype]!,
      size: item.media?.size ?? null,
      mimetype: item.media?.mimetype ?? null,
    };
  }
  return { render: "placeholder", text: `Unsupported message (${msgtype ?? "unknown"})` };
}

/** Render decision for `kind: "state"`, switching on `detail` (the state event type). */
function stateView(item: TimelineItem): ItemView {
  switch (item.detail) {
    // Not "Beginning of the room" — see `viewFor`'s `timelineStart` case,
    // which owns that exact text (per `docs/matrix-events.md` Table E).
    // Reaching the true start of a room's history means the SDK loads
    // `m.room.create` (every room's first event, by spec) *and* inserts the
    // `TimelineStart` virtual item in the same page, so both would render
    // back-to-back — often separated only by a date divider, which reads
    // worse, not better ("Beginning of the room" / "January 1, 2024" /
    // "Beginning of the room"). Naming the creator here instead is strictly
    // more informative than repeating the generic marker.
    case "m.room.create":
      return { render: "system", text: `${attributedName(item)} created the room` };
    case "m.room.encryption":
      return { render: "system", text: "Encryption enabled" };
    case "m.room.tombstone":
      return { render: "system", text: "This room has been replaced" };
    default:
      // The general rule from `docs/matrix-events.md` §C: state events are
      // suppressed unless they change something the reader must know about.
      // This is the regression this whole refactor exists to prevent — an
      // `m.room.name` change used to render `Unsupported event
      // (m.room.name)` as a visible row.
      return { render: "none" };
  }
}

/** Render decision for `kind: "membership"`. */
function membershipView(item: TimelineItem): ItemView {
  return { render: "system", text: `${attributedName(item)} ${membershipVerb(item.detail)}` };
}

/**
 * The render decision for `item`.
 *
 * Callers must special-case `dateDivider` before reaching here — it renders
 * real content (a formatted date), not a decision this module's vocabulary
 * covers.
 */
export function viewFor(item: TimelineItem): ItemView {
  switch (item.kind) {
    case "message":
      return messageView(item);

    case "sticker":
      return { render: "placeholder", text: "Sticker" };
    case "poll":
      return { render: "placeholder", text: "Poll" };
    case "liveLocation":
      return { render: "placeholder", text: "Live location" };
    case "callInvite":
      return { render: "placeholder", text: "Call" };
    case "rtcNotification":
      return { render: "placeholder", text: "Call notification" };

    case "redacted":
      return { render: "placeholder", text: "Message deleted" };

    // The case that matters most for dogfooding: "we can see this event but
    // hold no key for it" is expected on a fresh device and resolves itself
    // for messages sent from now on, so it gets its own wording rather than
    // being lumped in with the generic placeholder.
    case "unableToDecrypt":
      return { render: "placeholder", text: "Encrypted message — this device has no key for it" };

    // Suite/custom message-like events (the actual product differentiator,
    // per `docs/matrix-events.md` §G) — the registry/fallback-chain plumbing
    // this refactor exists to build. `item.detail` is the Matrix event type
    // (`core::timeline::classify_content`), `item.customPayload` its
    // bounded `content` object (`null` for a local echo, or one that failed
    // to bound — see `TimelineItem.customPayload`'s doc comment); a
    // registered renderer, the plain-text `body` fallback, or the generic
    // placeholder are `resolveCustomEvent`'s three possible outcomes, never
    // silence. See `customEvents.ts`'s doc comment for the full chain and
    // the versioning rule.
    case "customMessage":
      return {
        render: "customEvent",
        view: resolveCustomEvent(customEventRegistry, item.detail, item.customPayload, item.body),
      };

    case "membership":
      return membershipView(item);

    // Almost always noise (display name / avatar tweaks); a setting can
    // reveal it later.
    case "profileChange":
      return { render: "none" };

    case "state":
      return stateView(item);

    // The *only* legitimate use of "Unsupported event" text — every other
    // fallback in this module has its own wording.
    case "failedToParse":
      return { render: "placeholder", text: `Unsupported event (${item.detail ?? "unknown"})` };

    // `dateDivider` is the third virtual kind and is handled by the
    // component itself, since it renders real content (a formatted date).
    case "readMarker":
      // No visual form yet — M2 per `docs/matrix-events.md` Table E.
      return { render: "none" };

    // The boundary marker the SDK inserts once back-pagination reaches the
    // genuine start of a room's history — always at most once, and always
    // first (see `matrix-sdk-ui`'s `observable_items.rs`). Table E quotes
    // this exact text; see `stateView`'s `m.room.create` case for why that
    // case uses different wording rather than repeating it.
    case "timelineStart":
      return { render: "system", text: "Beginning of the room" };

    default:
      // Defensive only: every `kind` the core currently emits is handled
      // above. This exists as a forward-compat net for a future core
      // release this build hasn't been updated for yet, not a path any
      // current event takes.
      return { render: "placeholder", text: `Unsupported event (${item.kind})` };
  }
}
