import { describe, expect, it } from "vitest";
import { boundTypers } from "./typingView";
import type { TypingUser } from "$lib/ipc";

function user(userId: string, label: string): TypingUser {
  return { userId, displayName: label, label };
}

// The wording of the line ("A is typing…", "A and B are typing…",
// "A, B and 1 other are typing…") is the core's now; its tests are
// `core::typing_line`'s. Only the desktop's display bound lives here.
describe("boundTypers", () => {
  it("truncates an overlong label", () => {
    const [bounded] = boundTypers([user("@a:x.org", "x".repeat(200))]);
    expect(bounded.label).toBe(`${"x".repeat(32)}…`);
  });

  it("leaves a short label alone", () => {
    const input = [user("@a:x.org", "Alice")];
    expect(boundTypers(input)).toEqual(input);
  });

  it("counts code points, not UTF-16 units", () => {
    const [bounded] = boundTypers([user("@a:x.org", "😀".repeat(40))]);
    expect(Array.from(bounded.label)).toHaveLength(33);
  });
});
