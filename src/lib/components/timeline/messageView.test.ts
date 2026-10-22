import { describe, expect, it } from "vitest";
import { attributionFor, isLongRead, quoteFor, showsAgentLabel, type MessageRow } from "./messageView";

const quote = { state: "available", sender: "Atlas", excerpt: "the plan", label: null } as const;

const row = (over: Partial<MessageRow> & { isOwn?: boolean } = {}): MessageRow => {
  const { isOwn = false, ...rest } = over;
  return {
    item: { isOwn },
    senderName: "Atlas (claude on mac)",
    senderShort: "Atlas",
    replyQuote: quote,
    ...rest,
  };
};

describe("messageView", () => {
  it("an agent's message says Agent and a person's does not", () => {
    expect(showsAgentLabel(row({ isAgent: true }))).toBe(true);
    expect(showsAgentLabel(row({ isAgent: false }))).toBe(false);
    expect(showsAgentLabel(row())).toBe(false);
  });

  it("your own message never does", () => {
    expect(showsAgentLabel(row({ isAgent: true, isOwn: true }))).toBe(false);
  });

  it("attribution follows showsRuntime", () => {
    expect(attributionFor(row({ showsRuntime: true }))).toBe("Atlas (claude on mac)");
    expect(attributionFor(row())).toBe("Atlas");
    expect(attributionFor(row({ showsRuntime: false }))).toBe("Atlas");
  });

  it("a reply under its parent drops the quote", () => {
    expect(quoteFor(row({ hidesQuote: true }))).toBeNull();
    expect(quoteFor(row({ hidesQuote: false }))).toEqual(quote);
    expect(quoteFor(row())).toEqual(quote);
  });

  it("isLongRead reads the row", () => {
    expect(isLongRead(row({ isLongRead: true }))).toBe(true);
    expect(isLongRead(row({ isLongRead: false }))).toBe(false);
    expect(isLongRead(row())).toBe(false);
  });
});
