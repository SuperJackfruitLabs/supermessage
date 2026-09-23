import type { AgentState } from "$lib/ipc";

/**
 * The state a roster row may actually say, or `null` for none.
 *
 * `core::roster` computes an activity word (`active`, `idle`, `quiet`) for
 * every row, and `RosterRow.describesAgent` says whether it means anything.
 * For an agent's room it does; for a room of people "idle" says nothing a
 * reader wants to know, so it is not drawn and not read out.
 *
 * `needsYou` is shown regardless: owing an answer is not about agents.
 */
export function shownState(state: AgentState, describesAgent: boolean): AgentState | null {
  if (state === "needsYou") return state;
  return describesAgent ? state : null;
}
