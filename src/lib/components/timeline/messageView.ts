import type { ReplyQuoteView } from "$lib/ipc";

/**
 * The facts the core decides about a message row (C6, C9): who is an agent,
 * what is a long read, whether the quote hides under its parent and whether
 * the runtime is named. They are optional because a row from before core
 * batch B carries none; absent means "no".
 */
export interface MessageFacts {
  isAgent?: boolean;
  isLongRead?: boolean;
  hidesQuote?: boolean;
  showsRuntime?: boolean;
}

/** The facts a row carries, leaving out any it does not (so a spread never overwrites with `undefined`). */
export function factsOf(row: MessageFacts): MessageFacts {
  const facts: MessageFacts = {};
  if (row.isAgent !== undefined) facts.isAgent = row.isAgent;
  if (row.isLongRead !== undefined) facts.isLongRead = row.isLongRead;
  if (row.hidesQuote !== undefined) facts.hidesQuote = row.hidesQuote;
  if (row.showsRuntime !== undefined) facts.showsRuntime = row.showsRuntime;
  return facts;
}

/** The part of a timeline row these functions read. */
export interface MessageRow extends MessageFacts {
  item: { isOwn: boolean };
  senderName: string;
  senderShort: string;
  replyQuote: ReplyQuoteView | null;
}

/** The sender's name as iOS draws it (`TimelineCollectionView.swift:599`). */
export function attributionFor(row: MessageRow): string {
  return row.showsRuntime === true ? row.senderName : row.senderShort;
}

/** "Agent" marks a peer the core calls an agent; never your own (C6). */
export function showsAgentLabel(row: MessageRow): boolean {
  return !row.item.isOwn && row.isAgent === true;
}

/** The quote, unless the message sits under its parent (D6, C9). */
export function quoteFor(row: MessageRow): ReplyQuoteView | null {
  return row.hidesQuote === true ? null : row.replyQuote;
}

/** Whether the text is cut and opens in the reading view (C6). */
export function isLongRead(row: MessageRow): boolean {
  return row.isLongRead === true;
}
