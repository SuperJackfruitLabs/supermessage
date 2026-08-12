// The focused room's timeline, kept in sync with the core's
// `sm://timeline/diff` channel via `gapSync`'s gap/resync ordering.
//
// Only one room is ever subscribed at a time (see
// `src-tauri/src/core/timeline.rs`'s module doc comment): switching rooms
// restarts the core's sequence counter at 1, so `subscribeTo` resets local
// tracking to a fresh generation *before* issuing the subscribe command —
// see `gapSync.ts`'s `resetForNewSubscription` doc comment for why a stale
// in-flight resync must not be allowed to land after that reset.

import {
  onTimelineDiff as defaultOnTimelineDiff,
  sendMessage as defaultSendMessage,
  timelinePaginateBack as defaultTimelinePaginateBack,
  timelineResync as defaultTimelineResync,
  timelineSubscribe as defaultTimelineSubscribe,
  type TimelineItem,
} from "$lib/ipc";
import { startGapSync } from "./gapSync";

export interface TimelineStoreDeps {
  timelineSubscribe: typeof defaultTimelineSubscribe;
  timelinePaginateBack: typeof defaultTimelinePaginateBack;
  timelineResync: typeof defaultTimelineResync;
  sendMessage: typeof defaultSendMessage;
  onTimelineDiff: typeof defaultOnTimelineDiff;
}

const defaultDeps: TimelineStoreDeps = {
  timelineSubscribe: defaultTimelineSubscribe,
  timelinePaginateBack: defaultTimelinePaginateBack,
  timelineResync: defaultTimelineResync,
  sendMessage: defaultSendMessage,
  onTimelineDiff: defaultOnTimelineDiff,
};

/** Default page size for `paginateBack()` when the caller doesn't specify one. */
const DEFAULT_PAGE_SIZE = 30;

export function createTimelineStore(deps: TimelineStoreDeps = defaultDeps) {
  let items = $state<TimelineItem[]>([]);

  const gapSync = startGapSync<TimelineItem>({
    subscribe: (onEnvelope) => deps.onTimelineDiff(onEnvelope),
    resync: () => deps.timelineResync(),
    onUpdate: (next) => {
      items = next;
    },
  });

  /**
   * Subscribes to `roomId`'s timeline, replacing whatever was focused
   * before. Resets tracking first so an event arriving before the command
   * promise resolves is captured against fresh state, not the previous
   * room's.
   */
  async function subscribeTo(roomId: string): Promise<void> {
    gapSync.resetForNewSubscription();
    await deps.timelineSubscribe(roomId);
  }

  /** Paginates the focused timeline backwards. Resolves `true` at the start of history. */
  async function paginateBack(count: number = DEFAULT_PAGE_SIZE): Promise<boolean> {
    return deps.timelinePaginateBack(count);
  }

  /** Sends a plain-text message to the focused room. */
  async function send(body: string): Promise<void> {
    await deps.sendMessage(body);
  }

  return {
    get items(): TimelineItem[] {
      return items;
    },
    subscribeTo,
    paginateBack,
    send,
  };
}

export const timelineStore = createTimelineStore();
