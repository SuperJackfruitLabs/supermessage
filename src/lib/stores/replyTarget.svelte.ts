// Per-room pending reply target — what the composer shows as "Replying to
// …" and what `Composer.svelte`'s `send` routes through `Timeline::send_reply`
// (`timelineStore.sendReply`) instead of a plain `send`.
//
// Scoped per room for the exact same reason `draftTracker.ts` exists:
// `Composer` is *not* remounted on a room switch (see that file's doc
// comment for why — a draft would otherwise evaporate the instant you
// switch away), so nothing about a room switch resets its own local state
// automatically. A pending reply target that leaked across a room switch
// would be a worse version of the stale-draft bug: it would let a reply
// typed after switching to room B still carry an `in_reply_to` event id
// from room A — an event that may not even exist in room B — rather than
// merely showing the wrong *text*.
//
// Unlike a draft, a reply target is never continuously mutated — it only
// changes through an explicit `set`/`clear` call from a room the caller
// already knows (`Timeline.svelte`'s "Reply" button, or `Composer`'s cancel
// button/successful send), and each of those already names the room it
// applies to. So there is nothing here that needs `DraftTracker`'s
// `switchTo` — no in-progress value a room switch could strand — just a
// map keyed by room id: reading a *different* room's entry can never
// observe another room's target, because nothing is ever written under the
// wrong room in the first place.
//
// A plain `$state`-backed factory, not a framework-free class like
// `DraftTracker`: unlike the composer's own draft (private to one component
// instance), a reply target has to be visible to two independent
// components — `Timeline.svelte` sets it, `Composer.svelte` reads and
// clears it — so it has to be a shared, reactive singleton the way
// `timelineStore` is, not something a single component instantiates for
// itself. `$state` still works directly in a plain module under this
// project's node-environment vitest (see `timeline.svelte.ts`/`rooms.svelte.ts`
// for the identical precedent), so the per-room scoping logic stays
// unit-testable without mounting anything — see `replyTarget.test.ts`.

import type { TimelineItem } from "$lib/ipc";
import { replyPreviewExcerpt } from "$lib/components/timelineItemView";

/** The message a reply is being composed against. */
export interface PendingReply {
  /** The parent event's id — what `Timeline::send_reply`'s `in_reply_to` needs. */
  eventId: string;
  /** Display name (falling back to sender id) shown in the composer's "Replying to …" row. */
  sender: string;
  /**
   * A short preview of the parent's own content, or `null` when there's
   * nothing to show (see {@link replyPreviewExcerpt}). This is a snapshot
   * taken at the moment the reply was started, not a live binding to the
   * parent's current state in `timelineStore.items` — if the parent is
   * later redacted, or scrolls out of the locally materialized timeline
   * (see `core::timeline`'s "Recovering from an emptied timeline" doc
   * comment for how a re-seed can do that), this preview does not change
   * and does not disappear. Sending still works in that case: `Timeline::
   * send_reply` resolves `in_reply_to` by fetching the event by id
   * (falling back to the homeserver when it isn't cached locally), not by
   * requiring it to still be present in this timeline's own item list — see
   * `core::timeline::FocusedTimeline::send_reply`'s doc comment. If the
   * event id doesn't resolve at all (never existed, or the fetch fails),
   * the send rejects and surfaces through `Composer`'s existing
   * `catch` — the same failure path an ordinary send already has, not a new
   * one this feature needs to add.
   */
  excerpt: string | null;
}

export function createReplyTargetStore() {
  // Keyed by room id. A plain object (not a `Map`) so it participates in
  // Svelte's fine-grained reactivity the same way `timelineStore.items`
  // does — reassigning it wholesale on every write, never mutated in place,
  // mirrors `applyOps`'s "never mutate in place" rule in `diff.ts` for the
  // identical reason: a reader (`Composer`'s `$derived`) must see every
  // write, not just the ones that happen to trigger a re-render some other
  // way.
  let targets = $state<Record<string, PendingReply>>({});

  return {
    /** The pending reply target stored for `roomId`, or `null` if it has none. */
    get(roomId: string): PendingReply | null {
      return targets[roomId] ?? null;
    },

    /** Sets `roomId`'s pending reply target, replacing any previous one. */
    set(roomId: string, target: PendingReply): void {
      targets = { ...targets, [roomId]: target };
    },

    /** Clears `roomId`'s pending reply target, if it has one. A safe no-op otherwise. */
    clear(roomId: string): void {
      if (!(roomId in targets)) return;
      const next = { ...targets };
      delete next[roomId];
      targets = next;
    },

    /** Builds the {@link PendingReply} `startReply` stores for a given timeline item. */
    fromItem(item: TimelineItem): PendingReply {
      return {
        eventId: item.id,
        sender: item.senderDisplayName ?? item.sender ?? "Someone",
        excerpt: replyPreviewExcerpt(item.body),
      };
    },
  };
}

export const replyTargetStore = createReplyTargetStore();
