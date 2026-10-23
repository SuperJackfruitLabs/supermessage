// The `@` menu's candidates: asked of the core per query (`roomMentionCandidates`), after a
// short pause so a fast typist makes one call, and dropped if the reader has moved on (another
// query, another room, the menu closed). Matching and ordering are the core's.

import { roomMentionCandidates as defaultRoomMentionCandidates } from "$lib/ipc";
import { candidateFromPerson, type MentionCandidate } from "../mentions";

export interface MentionSearchDeps {
  roomMentionCandidates: typeof defaultRoomMentionCandidates;
  /** Pause before asking, in ms. */
  delayMs: number;
}

const DEFAULT_DEPS: MentionSearchDeps = {
  roomMentionCandidates: defaultRoomMentionCandidates,
  delayMs: 80,
};

export function createMentionSearch(deps: MentionSearchDeps = DEFAULT_DEPS, onChange?: () => void) {
  let results: MentionCandidate[] = [];
  // Bumped by every search and by close(): an answer that finds it moved is stale.
  let counter = 0;
  // The query the current `results` answer, so a pick never uses the list of an earlier query.
  let resultQuery: string | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;

  function stop(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }
  function set(next: MentionCandidate[]): void {
    if (results.length === 0 && next.length === 0) return;
    results = next;
    onChange?.();
  }

  return {
    get results(): MentionCandidate[] {
      return results;
    },
    /** The query `results` answers, or null when none has been answered. */
    get resultQuery(): string | null {
      return resultQuery;
    },
    /** Ask for `query`'s candidates in `roomId` after the pause; the old list stays meanwhile. */
    search(roomId: string, query: string): void {
      stop();
      const mine = ++counter;
      timer = setTimeout(() => {
        timer = null;
        deps
          .roomMentionCandidates(roomId, query)
          .then((people) => {
            if (mine !== counter) return;
            resultQuery = query;
            set(people.map(candidateFromPerson));
          })
          .catch(() => {
            // A convenience: a room whose cast cannot be read is still a room you can type in.
            if (mine !== counter) return;
            resultQuery = query;
            set([]);
          });
      }, deps.delayMs);
    },
    /** The menu closed, or the room changed: forget the list and drop anything on its way. */
    close(): void {
      stop();
      counter++;
      resultQuery = null;
      set([]);
    },
  };
}
