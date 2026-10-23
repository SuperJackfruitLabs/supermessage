// Addressing someone in a room with more than two people in it.
//
// A mission room holds several agents and a person; a message that says
// "can you retry that" addresses nobody in particular, and there was no way to
// say which. That is the gap this closes — and it is not only ergonomics:
// `m.mentions` is what a client keys a highlight off, so it is also how an
// agent's own Matrix client decides a message was meant for it.
//
// The parts here are pure so they can be tested without a DOM or a homeserver:
// where a mention begins, and what the composer's text
// becomes when one is chosen. The component owns the list, the keyboard and
// the send.

import type { PersonDto, PersonRuntime } from "$lib/ipc";

export interface Mentionable {
  userId: string;
  /** The member's display name, or null when they have none set. */
  displayName: string | null;
  /** Carried through the match so the menu can mark agents. */
  isAgent?: boolean;
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

/** A person the `@` menu offers: what is inserted, plus what the row shows. */
export interface MentionCandidate extends Mentionable {
  /** The glyph the person's face carries. */
  initial: string;
  /** For an agent, where it runs; `null` for a person. */
  runtime: PersonRuntime | null;
}

/**
 * The candidate for a person of the room's cast. Matching is the core's
 * (`people::matching`, over the cast the core ordered agents-first); nothing here ranks.
 */
export function candidateFromPerson(person: PersonDto): MentionCandidate {
  return {
    userId: person.userId,
    displayName: person.name,
    isAgent: person.runtime !== null,
    initial: person.initial,
    runtime: person.runtime,
  };
}

/** "{harness} on {host}", the line under an agent's name. */
export function runtimeLine(runtime: PersonRuntime): string {
  return `${runtime.harness} on ${runtime.host}`;
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
