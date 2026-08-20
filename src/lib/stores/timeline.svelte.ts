// The focused room's timeline, kept in sync with the core's
// `sm://timeline/diff` channel via `gapSync`'s gap/resync ordering.
//
// Only one room is ever subscribed at a time (see
// `src-tauri/src/core/timeline.rs`'s module doc comment): switching rooms
// restarts the core's sequence counter at 1, so `subscribeTo` resets local
// tracking to a fresh generation *before* issuing the subscribe command —
// see `gapSync.ts`'s `resetForNewSubscription` doc comment for why a stale
// in-flight resync must not be allowed to land after that reset.
//
// Resetting is necessary but was not sufficient, which is what `focusedId`
// and the `accepts` predicate below are for. Every envelope on this channel
// carries the room it belongs to as its `subject` (spec §4: the sequence is
// "monotonic, per channel+subject"), and the core's `timeline_resync` now
// returns that subject alongside `[seq, items]`. Without checking it, this
// sequence corrupted the pane permanently:
//
//   1. `subscribeTo("!b")` resets tracking, then awaits the subscribe
//      command, which has to build `room.timeline()` — slow.
//   2. Room A's subscription is still installed and still emitting. Its
//      next envelope arrives at, say, seq 12 against a tracker expecting
//      1: a gap.
//   3. The resync that gap triggers is a mutex read, so it easily beats
//      the subscribe — and it is served out of room A's still-installed
//      handle. The tracker now holds A's items at A's high seq.
//   4. Room B's stream finally starts, at seq 1, 2, 3 — all below what the
//      tracker expects, so all discarded as duplicates.
//
// Room A's messages then sit under room B's header until the next room
// switch. Rejecting anything whose subject isn't the focused room turns
// steps 2 and 3 into no-ops.

import {
  markRoomRead as defaultMarkRoomRead,
  onTimelineDiff as defaultOnTimelineDiff,
  sendMessage as defaultSendMessage,
  sendReply as defaultSendReply,
  setTyping as defaultSetTyping,
  timelinePaginateBack as defaultTimelinePaginateBack,
  timelineResync as defaultTimelineResync,
  timelineSubscribe as defaultTimelineSubscribe,
  toggleReaction as defaultToggleReaction,
  type TimelineRow,
} from "$lib/ipc";
import { startGapSync } from "./gapSync";
import { typingStore } from "./typing.svelte";

export interface TimelineStoreDeps {
  timelineSubscribe: typeof defaultTimelineSubscribe;
  timelinePaginateBack: typeof defaultTimelinePaginateBack;
  timelineResync: typeof defaultTimelineResync;
  sendMessage: typeof defaultSendMessage;
  sendReply: typeof defaultSendReply;
  toggleReaction: typeof defaultToggleReaction;
  setTyping: typeof defaultSetTyping;
  markRoomRead: typeof defaultMarkRoomRead;
  onTimelineDiff: typeof defaultOnTimelineDiff;
}

const defaultDeps: TimelineStoreDeps = {
  timelineSubscribe: defaultTimelineSubscribe,
  timelinePaginateBack: defaultTimelinePaginateBack,
  timelineResync: defaultTimelineResync,
  sendMessage: defaultSendMessage,
  sendReply: defaultSendReply,
  toggleReaction: defaultToggleReaction,
  setTyping: defaultSetTyping,
  markRoomRead: defaultMarkRoomRead,
  onTimelineDiff: defaultOnTimelineDiff,
};

/** Default page size for `paginateBack()` when the caller doesn't specify one. */
const DEFAULT_PAGE_SIZE = 30;

export function createTimelineStore(deps: TimelineStoreDeps = defaultDeps) {
  let items = $state<TimelineRow[]>([]);

  /**
   * Whether the focused room has delivered a batch yet — **not** whether it
   * has anything in it.
   *
   * The pane could not tell those apart, so it rendered "Nothing here yet."
   * for the gap between them: measured on 2026-08-17, that message appeared
   * 10ms into a room switch, over a room holding 1937px of history, and was
   * gone by 66ms. An empty list is the honest state of a room nobody has
   * answered for yet; it just isn't an empty *room*. See
   * `components/timelinePane.ts` for what the pane does with the distinction.
   *
   * Set by the first accepted publish — `accepts` already rejects the
   * outgoing room's still-in-flight envelopes, so those cannot answer for the
   * incoming one. Cleared in `subscribeTo`, *after* the reset that publishes
   * the empty list, since that publish would otherwise set it straight back.
   */
  let loaded = $state(false);

  // The room this store currently shows — the only `subject` it will accept
  // data for. Deliberately not `$state`: nothing renders it, and it must be
  // readable synchronously from `accepts` below the instant `subscribeTo`
  // sets it, before any await.
  let focusedId: string | null = null;

  const gapSync = startGapSync<TimelineRow>({
    subscribe: (onEnvelope) => deps.onTimelineDiff(onEnvelope),
    resync: async () => {
      const [subject, seq, snapshotItems] = await deps.timelineResync();
      return { subject, seq, items: snapshotItems };
    },
    onUpdate: (next) => {
      items = next;
      loaded = true;
    },
    accepts: (subject) => subject === focusedId,
  });

  /**
   * Subscribes to `roomId`'s timeline, replacing whatever was focused
   * before. Narrows what counts as ours and resets tracking *before*
   * issuing the command, so anything the previous room's still-running
   * subscription emits during the round trip is rejected outright rather
   * than mistaken for a gap in this room's stream — see this module's doc
   * comment for what that mistake cost.
   */
  async function subscribeTo(roomId: string): Promise<void> {
    focusedId = roomId;
    gapSync.resetForNewSubscription();
    // After the reset, never before: `resetForNewSubscription` publishes the
    // empty list on its way out, and that publish runs `onUpdate` — which
    // would set this straight back to true for a room that has said nothing.
    loaded = false;
    // Same ordering requirement as `gapSync.resetForNewSubscription()` above
    // and for the identical reason (see this module's doc comment): the
    // core only ever streams typing state for the *focused* room (mirroring
    // `FocusedTimeline`'s single-subscription invariant), so a typing event
    // from the room we're leaving that's still in flight when the new room's
    // subscription is being set up must be rejected as not-ours, not shown
    // under the new room's identity. `typingStore.focus` resets synchronously,
    // before the `timelineSubscribe` command below is even issued, closing
    // that window the same way — see `typing.svelte.ts`'s doc comment.
    typingStore.focus(roomId);
    await deps.timelineSubscribe(roomId);
  }

  /**
   * Paginates `roomId`'s timeline backwards. Resolves `true` at the start of
   * history.
   *
   * `roomId` is not read off `focusedId` above — it comes from the caller
   * (`Timeline.svelte`'s own `roomId` prop), the same room-scoping
   * `send`/`sendReply`/`toggleReaction` below take it for. See `send`'s doc
   * comment for why a store-internal "current room" isn't what closes the
   * race this exists to close.
   */
  async function paginateBack(roomId: string, count: number = DEFAULT_PAGE_SIZE): Promise<boolean> {
    return deps.timelinePaginateBack(roomId, count);
  }

  /**
   * Sends a plain-text message to `roomId`.
   *
   * `roomId` is a required argument, not read off this store's own
   * `focusedId` — `focusedId` is set synchronously the instant a room
   * switch *starts* (`subscribeTo`), before the `timeline_subscribe`
   * command it issues has resolved, so it can already have moved on to a
   * new room by the time an in-flight `send` from the *previous* room
   * actually reaches the core. Taking `roomId` from the caller instead (see
   * `Composer.svelte`'s `sentRoomId`, snapshotted before its own await)
   * means the room a send targets is fixed at the moment the reader hit
   * Enter, not whatever happens to be focused when the round trip
   * completes — the core's own `FocusedTimeline::active_timeline_for` check
   * is what actually enforces this, not this store; the point of passing
   * `roomId` through is only to give that check something real to compare
   * against.
   */
  async function send(roomId: string, body: string, mentions: string[] = []): Promise<void> {
    await deps.sendMessage(roomId, body, mentions);
  }

  /**
   * Sends a plain-text reply to `inReplyTo` (a parent event id) in `roomId`.
   * Never touches `items` itself — same as `send`, the local echo arrives
   * back through the diff stream this store is already folding into
   * `items`. `roomId` is scoped the same way, and for the same reason, as
   * `send`'s.
   */
  async function sendReply(roomId: string, body: string, inReplyTo: string): Promise<void> {
    await deps.sendReply(roomId, body, inReplyTo);
  }

  /**
   * Toggles `key` as a reaction on `eventId` in `roomId`. Resolves to
   * whether the reaction was added. Never touches `items` itself, same
   * reasoning as `sendReply`. `roomId` is scoped the same way, and for the
   * same reason, as `send`'s.
   */
  async function toggleReaction(roomId: string, eventId: string, key: string): Promise<boolean> {
    return deps.toggleReaction(roomId, eventId, key);
  }

  /**
   * Sets (or clears) this device's typing notice in `roomId`. `roomId` is
   * scoped the same way, and for the same reason, as `send`'s — see
   * `$lib/components/typingTracker.ts` for the throttle decision that
   * decides *when* this gets called at all.
   */
  async function setTyping(roomId: string, typing: boolean): Promise<void> {
    await deps.setTyping(roomId, typing);
  }

  /**
   * Marks `roomId` read. Resolves to whether a receipt was actually sent.
   * `roomId` is scoped the same way, and for the same reason, as `send`'s —
   * see `$lib/components/readTracking.ts`'s `shouldMarkRead` for the
   * predicate that decides *whether* this gets called at all.
   */
  async function markRead(roomId: string): Promise<boolean> {
    return deps.markRoomRead(roomId);
  }

  return {
    get items(): TimelineRow[] {
      return items;
    },
    /**
     * Whether the focused room has answered yet. See the field's doc comment
     * for why an empty `items` is not the same thing as an empty room.
     */
    get loaded(): boolean {
      return loaded;
    },
    subscribeTo,
    paginateBack,
    send,
    sendReply,
    toggleReaction,
    setTyping,
    markRead,
  };
}

export const timelineStore = createTimelineStore();
