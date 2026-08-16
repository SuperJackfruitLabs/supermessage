import { describe, expect, it } from "vitest";
import {
  applyMention,
  collectMentions,
  findMentionQuery,
  matchMentions,
  mentionLabel,
  type Mentionable,
} from "./mentions";

const ANA: Mentionable = { userId: "@ana:example.org", displayName: "Ana" };
const LYRA: Mentionable = { userId: "@lyra:example.org", displayName: "Ana Lyra" };
const ECHO: Mentionable = { userId: "@agent_echo:example.org", displayName: "analyst-echo" };
const NAMELESS: Mentionable = { userId: "@quiet:example.org", displayName: null };
const MEMBERS = [ANA, LYRA, ECHO, NAMELESS];

describe("finding the mention being typed", () => {
  it("finds one at the start of a message", () => {
    expect(findMentionQuery("@ana", 4)).toEqual({ start: 0, query: "ana" });
  });

  it("finds one after a space", () => {
    expect(findMentionQuery("hey @an", 7)).toEqual({ start: 4, query: "an" });
  });

  it("finds a bare @ so the whole room can be browsed", () => {
    // Typing `@` alone is how you look up someone whose name you do not know.
    expect(findMentionQuery("hey @", 5)).toEqual({ start: 4, query: "" });
  });

  it("does not see an email address or a handle inside a word as a mention", () => {
    // People paste logs and addresses into these rooms constantly. Opening a
    // member list on `user@example.org` would fire on half of them.
    expect(findMentionQuery("mail user@example.org", 21)).toBeNull();
    expect(findMentionQuery("a@b", 3)).toBeNull();
  });

  it("ends the mention at whitespace", () => {
    // A finished mention is not still being typed.
    expect(findMentionQuery("@ana said", 9)).toBeNull();
  });

  it("reads from the caret, not the end of the text", () => {
    // The caret can sit anywhere; a mention being edited mid-message is still
    // the one that matters.
    expect(findMentionQuery("@an rest of it", 3)).toEqual({ start: 0, query: "an" });
  });
});

describe("matching members", () => {
  it("puts a name that starts with the query first", () => {
    expect(matchMentions("ana", MEMBERS)[0]).toEqual(ANA);
  });

  it("matches on the user id too, for a member with no display name", () => {
    expect(matchMentions("quiet", MEMBERS)).toEqual([NAMELESS]);
  });

  it("offers everyone for a bare @", () => {
    expect(matchMentions("", MEMBERS)).toHaveLength(MEMBERS.length);
  });

  it("ignores case", () => {
    expect(matchMentions("ECHO", MEMBERS).map((m) => m.userId)).toContain(ECHO.userId);
  });
});

describe("completing a mention", () => {
  it("replaces what was typed and leaves a trailing space", () => {
    // The next thing typed is a word, not more of the name — and the space
    // closes the query, so the list does not reopen on the finished mention.
    const result = applyMention("hey @an", 7, ANA);

    expect(result.text).toBe("hey @Ana ");
    expect(result.caret).toBe(9);
  });

  it("keeps whatever followed the caret", () => {
    const result = applyMention("@an, are you there?", 3, ANA);
    expect(result.text).toBe("@Ana , are you there?");
  });

  it("uses the user id when a member has no display name", () => {
    expect(applyMention("@qu", 3, NAMELESS).text).toBe("@@quiet:example.org ");
    expect(mentionLabel(NAMELESS)).toBe("@quiet:example.org");
  });
});

describe("collecting mentions from a finished message", () => {
  it("finds the members addressed", () => {
    expect(collectMentions("@Ana take a look", MEMBERS)).toEqual([ANA.userId]);
  });

  it("prefers the longest name, so a prefix does not steal the match", () => {
    // "@Ana Lyra" contains "@Ana". Reporting both would tell Ana she was
    // addressed when she was not — a false highlight is worse than none.
    expect(collectMentions("@Ana Lyra please retry", MEMBERS)).toEqual([LYRA.userId]);
  });

  it("reports a member once however often they appear", () => {
    expect(collectMentions("@Ana @Ana @Ana", MEMBERS)).toEqual([ANA.userId]);
  });

  it("finds nobody in a message that mentions nobody", () => {
    expect(collectMentions("just talking", MEMBERS)).toEqual([]);
  });

  it("does not mistake an email for a mention", () => {
    expect(collectMentions("write to ana@example.org", MEMBERS)).toEqual([]);
  });
});
