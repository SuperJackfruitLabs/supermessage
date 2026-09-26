// How a turn error card lays out its attempts.
//
// Everything a reader is *told* — the kind's wording, the headline, which
// attempts were the same and how many times — is `core::turn_error`'s, so
// every host says the same thing. What is left here is presentation: the
// chain is collapsed to its first line, the model that was asked for, until
// the reader opens it. iOS (`TurnErrorCard.swift`) follows the same rules.

import type { TurnErrorAttempt, TurnErrorCard } from "$lib/ipc";

/** The attempts to draw: all of them when open, otherwise only the first. */
export function attemptsToShow(card: TurnErrorCard, expanded: boolean): TurnErrorAttempt[] {
  return expanded ? card.attempts : card.attempts.slice(0, 1);
}

/**
 * What the disclosure says — "2 more attempts" — or null when there is
 * nothing to disclose. Counts *lines*, because lines are what it reveals: a
 * folded "×4" is one of them.
 */
export function moreAttemptsLabel(card: TurnErrorCard): string | null {
  const hidden = card.attempts.length - 1;
  if (hidden < 1) return null;
  return `${hidden} more ${hidden === 1 ? "attempt" : "attempts"}`;
}

/** "×4" for a folded line, null for a single attempt. */
export function attemptCount(attempt: TurnErrorAttempt): string | null {
  return attempt.count > 1 ? `×${attempt.count}` : null;
}
