// Turns `typingStore.users` into the single line `TypingIndicator.svelte`
// renders. Pure and framework-free, like `core::item_view`'s
// `displayReactionKey` — display names are server-controlled, arbitrary
// text (see `TypingUser.displayName`'s doc comment), so this is also where
// that gets bounded before it ever reaches a template.

import type { TypingUser } from "$lib/ipc";

/**
 * How many typers are named individually before the rest collapse into
 * "and N others". Small on purpose: this indicator is one line of chrome
 * below the timeline, not a roster — see this task's brief on capping the
 * list before "and N others".
 */
export const MAX_NAMED_TYPERS = 2;

/**
 * Cap, in `char`s, on one typer's *displayed* name — same discipline
 * `displayReactionKey` (`core::item_view`) applies to a reaction key: a
 * display name is sender-controlled and unbounded in principle, and this is
 * a single-line strip, not a bubble that can wrap.
 */
const DISPLAY_NAME_MAX_CHARS = 32;

/** Truncates `name` to {@link DISPLAY_NAME_MAX_CHARS}, appending an ellipsis when anything was cut. */
function truncateName(name: string): string {
  const codePoints = Array.from(name);
  if (codePoints.length <= DISPLAY_NAME_MAX_CHARS) return name;
  return `${codePoints.slice(0, DISPLAY_NAME_MAX_CHARS).join("")}…`;
}

/** A typer's display name, falling back to their user id — same convention every other sender-name field in this codebase uses — then bounded. */
function shortName(user: TypingUser): string {
  return truncateName(user.displayName ?? user.userId);
}

/**
 * The line `TypingIndicator.svelte` shows, or `null` when no one is typing
 * (nothing to render). Never lists more than {@link MAX_NAMED_TYPERS} names —
 * anyone past that collapses into "and N others".
 */
export function typingIndicatorText(users: TypingUser[]): string | null {
  if (users.length === 0) return null;

  const names = users.map(shortName);
  if (names.length === 1) return `${names[0]} is typing…`;
  if (names.length <= MAX_NAMED_TYPERS) return `${names.join(" and ")} are typing…`;

  const shown = names.slice(0, MAX_NAMED_TYPERS);
  const remaining = names.length - MAX_NAMED_TYPERS;
  const rest = remaining === 1 ? "1 other" : `${remaining} others`;
  return `${shown.join(", ")} and ${rest} are typing…`;
}
