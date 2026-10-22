import { SvelteMap } from "svelte/reactivity";

import type { CustomEventDecisionOption } from "$lib/ipc";

/** What this device answered, and when. */
export interface AnsweredDecision {
  option: CustomEventDecisionOption;
  atMs: number;
}

/**
 * Decisions answered on this device, keyed by Matrix event id, until the
 * board's own outcome replaces them.
 *
 * The timeline is virtualised: a card's row unmounts when scrolled away and
 * remounts later, and component state does not survive that. Without this, a
 * row remounting between the send landing and the board's receipt would show
 * live buttons again, and the same decision could be answered twice. One
 * instance per `Timeline` (so it dies with the room's view, on a room switch
 * or sign-out), never a module singleton that could outlive an account.
 */
export function createAnsweredDecisions() {
  const answers = new SvelteMap<string, AnsweredDecision>();
  return {
    get(eventId: string | null): AnsweredDecision | null {
      return eventId === null ? null : (answers.get(eventId) ?? null);
    },
    /** Only a confirmed event can be keyed; an unconfirmed card keeps its own state. */
    record(eventId: string | null, answer: AnsweredDecision): void {
      if (eventId !== null) answers.set(eventId, answer);
    },
    clear(): void {
      answers.clear();
    },
  };
}
