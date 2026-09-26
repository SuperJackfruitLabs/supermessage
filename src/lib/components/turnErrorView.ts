// How a turn error card lays out its attempts.
//
// Everything a reader is *told* — the kind's wording, the headline, which
// attempts were the same and how many times — is `core::turn_error`'s, so
// every host says the same thing. What is left here is presentation, and it is
// shared with iOS (`TurnErrorPresentation.swift`) and Android
// (`TurnErrorCard.kt`):
//
// The headline already names the first attempt — the model that was asked
// for. So under it go only the models the agent *fell back to*, behind a
// disclosure; listing the first again said the same thing twice. When the
// asked-for model was simply retried, that is said once ("Tried 3 times").

import type { TurnErrorAttempt, TurnErrorCard } from "$lib/ipc";

/** Whether the first attempt is the one the headline already names. */
function headlineIsFirst(card: TurnErrorCard): boolean {
  const [first] = card.attempts;
  return first !== undefined && card.source !== null && card.source !== undefined && first.source === card.source;
}

/** The attempts after the headline's own: the models the agent fell back to. */
export function fallbacks(card: TurnErrorCard): TurnErrorAttempt[] {
  return headlineIsFirst(card) ? card.attempts.slice(1) : card.attempts;
}

/** The attempts to draw: the fallbacks when open, none when closed. */
export function attemptsToShow(card: TurnErrorCard, expanded: boolean): TurnErrorAttempt[] {
  return expanded ? fallbacks(card) : [];
}

/** The disclosure — "Fell back to 2 models" — or null when there were none. */
export function moreAttemptsLabel(card: TurnErrorCard): string | null {
  const n = fallbacks(card).length;
  if (n < 1) return null;
  return `Fell back to ${n} ${n === 1 ? "model" : "models"}`;
}

/** "Tried 3 times" when the headline's model was retried, else null. */
export function repeatsLabel(card: TurnErrorCard): string | null {
  const [first] = card.attempts;
  if (!headlineIsFirst(card) || first === undefined || first.count <= 1) return null;
  return `Tried ${first.count} times`;
}

/** "×4" for a folded line, null for a single attempt. */
export function attemptCount(attempt: TurnErrorAttempt): string | null {
  return attempt.count > 1 ? `×${attempt.count}` : null;
}
