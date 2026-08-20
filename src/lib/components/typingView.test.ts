import { describe, expect, it } from "vitest";
import { MAX_NAMED_TYPERS, typingIndicatorText } from "./typingView";
import type { TypingUser } from "$lib/ipc";

function user(userId: string, displayName: string | null = null): TypingUser {
  return { userId, displayName, label: displayName ?? userId };
}

describe("typingIndicatorText", () => {
  it("is null when no one is typing", () => {
    expect(typingIndicatorText([])).toBeNull();
  });

  it("names a single typer", () => {
    expect(typingIndicatorText([user("@alice:x.org", "Alice")])).toBe("Alice is typing…");
  });

  it("falls back to the user id when there is no display name", () => {
    expect(typingIndicatorText([user("@alice:x.org")])).toBe("@alice:x.org is typing…");
  });

  it("joins exactly two typers with 'and'", () => {
    const text = typingIndicatorText([user("@a:x.org", "Alice"), user("@b:x.org", "Bob")]);
    expect(text).toBe("Alice and Bob are typing…");
  });

  it("names up to MAX_NAMED_TYPERS and collapses the rest into 'and N others'", () => {
    const users = [
      user("@a:x.org", "Alice"),
      user("@b:x.org", "Bob"),
      user("@c:x.org", "Carol"),
    ];
    expect(users.length).toBeGreaterThan(MAX_NAMED_TYPERS);
    expect(typingIndicatorText(users)).toBe("Alice, Bob and 1 other are typing…");
  });

  it("pluralizes 'others' correctly for more than one extra typer", () => {
    const users = [
      user("@a:x.org", "Alice"),
      user("@b:x.org", "Bob"),
      user("@c:x.org", "Carol"),
      user("@d:x.org", "Dave"),
    ];
    expect(typingIndicatorText(users)).toBe("Alice, Bob and 2 others are typing…");
  });

  it("truncates an overlong display name and guards against overflow", () => {
    const huge = "x".repeat(200);
    const text = typingIndicatorText([user("@a:x.org", huge)]);
    expect(text).not.toBeNull();
    expect(text!.length).toBeLessThan(60);
    expect(text!.endsWith("… is typing…")).toBe(true);
  });
});
