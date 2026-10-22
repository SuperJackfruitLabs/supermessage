// The new-conversation search box, as logic: `known_people` is uncapped and the core walks every
// joined room on each call, so keystrokes are debounced, and an answer to a query that is no
// longer the latest is dropped (a slow answer to "a" must not replace the answer to "ab").

import type { PersonDto } from "$lib/ipc";

/** How long typing must pause before the core is asked. */
export const PEOPLE_DEBOUNCE_MS = 200;
/** How many rows the panel draws; the rest are reached by narrowing the query. */
export const PEOPLE_RENDER_CAP = 50;

export type PeopleState =
  | { kind: "loading" }
  | { kind: "loaded"; people: PersonDto[]; query: string }
  | { kind: "failed"; error: unknown };

export interface PeopleSearch {
  /** Asks for `query`; `immediate` skips the debounce (the first load). */
  search(query: string, immediate?: boolean): void;
  /** Drops anything pending or in flight. */
  dispose(): void;
}

export function createPeopleSearch(
  load: (query: string | null) => Promise<PersonDto[]>,
  onState: (state: PeopleState) => void,
  delayMs: number = PEOPLE_DEBOUNCE_MS,
): PeopleSearch {
  let latest = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  // Once a list has been shown, a refined query keeps it on screen until its own answer arrives
  // (iOS loads once and filters in place); "loading" is for the first load only.
  let answered = false;

  function cancelTimer(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }

  async function run(mine: number, query: string): Promise<void> {
    try {
      const people = await load(query.trim() === "" ? null : query.trim());
      if (mine === latest) {
        answered = true;
        onState({ kind: "loaded", people, query });
      }
    } catch (error) {
      if (mine === latest) onState({ kind: "failed", error });
    }
  }

  return {
    search(query, immediate = false) {
      cancelTimer();
      const mine = ++latest;
      if (!answered) onState({ kind: "loading" });
      if (immediate) {
        void run(mine, query);
      } else {
        timer = setTimeout(() => void run(mine, query), delayMs);
      }
    },
    dispose() {
      latest += 1;
      cancelTimer();
    },
  };
}
