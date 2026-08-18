// Addressing someone in a room with more than two people in it.
//
// A mission room holds several agents and a person; a message that says
// "can you retry that" addresses nobody in particular, and there was no way to
// say which. That is the gap this closes — and it is not only ergonomics:
// `m.mentions` is what a client keys a highlight off, so it is also how an
// agent's own Matrix client decides a message was meant for it.
//
// The parts here are pure so they can be tested without a DOM or a homeserver:
// where a mention begins, what it matches, and what the composer's text
// becomes when one is chosen. The component owns the list, the keyboard and
// the send.

export interface Mentionable {
  userId: string;
  /** The member's display name, or null when they have none set. */
  displayName: string | null;
}

/** What a member is called — display name if they have one, else their id. */
export function mentionLabel(member: Mentionable): string {
  return member.displayName ?? member.userId;
}

export interface MentionQuery {
  /** Index of the `@` that opened this mention. */
  start: number;
  /** What has been typed after it, which may be empty. */
  query: string;
}

/**
 * The mention being typed at `caret`, or null when none is.
 *
 * A mention starts at an `@` that begins the message or follows whitespace —
 * so `user@example.org` and `a@b` are addresses, not mentions, which matters
 * in a room where people paste logs and emails constantly.
 *
 * It ends at whitespace, which bounds how far back this looks and means a
 * finished mention stops being "in progress" the moment a space is typed.
 */
export function findMentionQuery(text: string, caret: number): MentionQuery | null {
  const upToCaret = text.slice(0, caret);
  const at = upToCaret.lastIndexOf("@");
  if (at === -1) return null;

  // Anything between the `@` and the caret is the query; whitespace ends it.
  const query = upToCaret.slice(at + 1);
  if (/\s/.test(query)) return null;

  // The character before decides whether this is a mention at all.
  const before = at === 0 ? "" : upToCaret[at - 1]!;
  if (before !== "" && !/\s/.test(before)) return null;

  return { start: at, query };
}

/**
 * The members `query` matches, best first.
 *
 * Prefix matches on the display name come first, then anything containing the
 * query in either the name or the id. An empty query is everyone — typing `@`
 * alone is how you browse a room you do not know the membership of.
 */
export function matchMentions(
  query: string,
  members: readonly Mentionable[],
  limit = 8
): Mentionable[] {
  const q = query.trim().toLowerCase();
  if (q === "") return members.slice(0, limit);

  const prefix: Mentionable[] = [];
  const rest: Mentionable[] = [];

  for (const member of members) {
    const label = mentionLabel(member).toLowerCase();
    const id = member.userId.toLowerCase();
    if (label.startsWith(q) || id.startsWith(`@${q}`)) prefix.push(member);
    else if (label.includes(q) || id.includes(q)) rest.push(member);
  }

  return [...prefix, ...rest].slice(0, limit);
}

export interface MentionInsertion {
  text: string;
  /** Where the caret should sit afterwards. */
  caret: number;
}

/**
 * The composer's text with the mention at `caret` completed.
 *
 * A trailing space is added because the next thing typed is a word, not more
 * of the name — and because it closes the query, so the list does not reopen
 * on the mention that was just finished.
 */
export function applyMention(
  text: string,
  caret: number,
  member: Mentionable
): MentionInsertion {
  const found = findMentionQuery(text, caret);
  if (!found) return { text, caret };

  const label = `@${mentionLabel(member)} `;
  const next = text.slice(0, found.start) + label + text.slice(caret);
  return { text: next, caret: found.start + label.length };
}

// `collectMentions` moved to `core::mentions`: it produces the `m.mentions`
// that goes on the wire, and an agent decides a message was addressed to it
// from that field. Everything above is caret handling — input UX, which
// differs per platform and stays here.
