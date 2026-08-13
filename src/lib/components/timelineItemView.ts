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
  | { render: "none" };

/** Human-readable labels for the media msgtypes M2 will render properly. */
const MEDIA_LABELS: Record<string, string> = {
  "m.image": "Image",
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

/** The name to attribute a system line to: display name, falling back to the raw sender id. */
function attributedName(item: TimelineItem): string {
  return item.senderDisplayName ?? item.sender ?? "Someone";
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
  if (msgtype != null && msgtype in MEDIA_LABELS) {
    // Full media rendering is M2 (authenticated media endpoints); for now
    // this is what stops an image message from either rendering as a bare
    // bubble with no image, or vanishing.
    return { render: "placeholder", text: `${MEDIA_LABELS[msgtype]} message` };
  }
  return { render: "placeholder", text: `Unsupported message (${msgtype ?? "unknown"})` };
}

/** Render decision for `kind: "state"`, switching on `detail` (the state event type). */
function stateView(item: TimelineItem): ItemView {
  switch (item.detail) {
    case "m.room.create":
      return { render: "system", text: "Beginning of the room" };
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
  const verb = (item.detail && MEMBERSHIP_VERBS[item.detail]) || "updated their membership";
  return { render: "system", text: `${attributedName(item)} ${verb}` };
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

    // Virtual items with no visual form in M0. `dateDivider` is the third
    // virtual kind and is handled by the component itself, since it renders
    // real content.
    case "readMarker":
    case "timelineStart":
      return { render: "none" };

    default:
      // Defensive only: every `kind` the core currently emits is handled
      // above. This exists as a forward-compat net for a future core
      // release this build hasn't been updated for yet, not a path any
      // current event takes.
      return { render: "placeholder", text: `Unsupported event (${item.kind})` };
  }
}
