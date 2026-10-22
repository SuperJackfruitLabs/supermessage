// The desktop's display bound on typers' names. The typing line's wording is
// the core's (`typing_view`, `core::typing_line`); what stays here is the
// bound on a *display* name — server-controlled, arbitrary text (see
// `TypingUser.displayName`'s doc comment) — applied before the names go to
// the core, so it never reaches a template unbounded.

import type { TypingUser } from "$lib/ipc";

/**
 * Cap, in code points, on one typer's *displayed* name — same discipline
 * `displayReactionKey` (`core::item_view`) applies to a reaction key: this
 * is a single-line strip, not a bubble that can wrap.
 */
const DISPLAY_NAME_MAX_CHARS = 32;

function truncateName(name: string): string {
  const codePoints = Array.from(name);
  if (codePoints.length <= DISPLAY_NAME_MAX_CHARS) return name;
  return `${codePoints.slice(0, DISPLAY_NAME_MAX_CHARS).join("")}…`;
}

/** `users` with each `label` cut to {@link DISPLAY_NAME_MAX_CHARS} code points plus `…`. */
export function boundTypers(users: TypingUser[]): TypingUser[] {
  return users.map((user) => ({ ...user, label: truncateName(user.label) }));
}
