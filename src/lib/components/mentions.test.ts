// `collectMentions` moved to `core::mentions` — it produces what goes on the
// wire. The caret handling below is input UX and stays here.
import { describe, expect, it } from "vitest";
import { applyMention, candidateFromPerson, findMentionQuery, mentionLabel, runtimeLine } from "./mentions";
import type { Mentionable } from "$lib/ipc";

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

describe("candidates from the room's cast", () => {
  const atlas = { userId: "@atlas:x", name: "Atlas", initial: "A", runtime: { harness: "Claude Code", host: "Ashram" }, avatarUrl: null };
  it("an agent keeps its runtime and is marked; its label is the cast's name", () => {
    const c = candidateFromPerson(atlas);
    expect(c).toMatchObject({ userId: "@atlas:x", displayName: "Atlas", isAgent: true, initial: "A" });
    expect(mentionLabel(c)).toBe("Atlas");
    expect(runtimeLine(atlas.runtime)).toBe("Claude Code on Ashram");
  });
  it("a person is not marked", () => {
    expect(candidateFromPerson({ ...atlas, runtime: null }).isAgent).toBe(false);
  });
});
