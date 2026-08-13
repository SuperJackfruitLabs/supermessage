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

import type { TimelineItem } from "$lib/ipc";

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
    // per `docs/matrix-events.md` §G) — schema work is M1's first task, so
    // for now this is a placeholder naming the event type rather than
    // silence or "Unsupported event".
    case "customMessage":
      return { render: "placeholder", text: `Custom event (${item.detail ?? "unknown"})` };

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
