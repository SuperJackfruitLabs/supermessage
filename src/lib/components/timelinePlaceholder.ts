// What to render for a timeline item that isn't a plain text message.
//
// `Timeline.svelte` used to render only date dividers and
// `m.room.message`-with-a-body, and *nothing at all* for everything else.
// That is not a cosmetic gap: a fresh device that logged in with a password
// holds none of the room keys for history it wasn't present for, so every
// one of those events arrives as `kind: "m.room.encrypted"` — and an
// encrypted room therefore rendered as blank rows between date dividers.
// The app looks broken on first contact with a real organisation, and spec
// §7 explicitly promises "encrypted rooms render an explicit placeholder".
//
// Kept in its own module rather than inline in the component so the
// classification is unit-testable without a DOM (same reasoning as
// `draftTracker.ts`).

import type { TimelineItem } from "$lib/ipc";

/**
 * Virtual items that legitimately render nothing.
 *
 * These are not unsupported — the core projects them deliberately (see
 * `core::timeline::project_virtual_item`) — they simply have no visual form
 * in M0. Returning a placeholder for them would print "Unsupported event"
 * above every timeline, which is worse than the blank they replace.
 * `dateDivider` is the third virtual kind and is handled by the component
 * itself, since it renders real content.
 */
const SILENT_KINDS = new Set(["readMarker", "timelineStart"]);

/**
 * The placeholder text for `item`, or `null` when it should render nothing.
 *
 * Callers must handle `dateDivider` and renderable `m.room.message` items
 * before reaching here; this covers everything left over.
 */
export function placeholderFor(item: TimelineItem): string | null {
  if (SILENT_KINDS.has(item.kind)) return null;

  // The case that actually matters for dogfooding. Distinguished from the
  // generic fallback because "we can see this event but hold no key for it"
  // is a completely different thing for a user to understand than "this
  // client doesn't render this kind of event yet" — the first is expected
  // on a new device and resolves itself for messages sent from now on, the
  // second never resolves without a new release.
  if (item.kind === "m.room.encrypted") {
    return "Encrypted message — this device has no key for it";
  }

  // A message-shaped event whose body the core couldn't project (a type
  // this build has no renderer for: an image, a file, a notice variant).
  if (item.kind === "m.room.message") {
    return "Unsupported message";
  }

  // Membership changes, redactions (which the core projects as "unknown"),
  // topic/name changes, everything else. The kind is included on purpose:
  // M0 exists to be dogfooded, and "which event is this?" is the first
  // question anyone asks when they see one of these.
  return `Unsupported event (${item.kind})`;
}
