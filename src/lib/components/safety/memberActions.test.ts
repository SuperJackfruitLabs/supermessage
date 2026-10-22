import { describe, expect, it } from "vitest";
import { blockLabel, memberActionsMessage, memberIsBlocked, memberName, reportLabel, unblockLabel } from "./memberActions";

const m = (isIgnored: boolean) => ({ userId: "@p:x", isIgnored });
const none = new Set<string>();

describe("memberIsBlocked", () => {
  it("is blocked when the store says so", () => {
    expect(memberIsBlocked(m(false), new Set(["@p:x"]), none)).toBe(true);
  });
  it("is blocked when the member's own flag says so", () => {
    expect(memberIsBlocked(m(true), none, none)).toBe(true);
  });
  it("is not blocked when the flag lags an unblock made here", () => {
    expect(memberIsBlocked(m(true), none, new Set(["@p:x"]))).toBe(false);
  });
  it("is not blocked otherwise", () => {
    expect(memberIsBlocked(m(false), none, none)).toBe(false);
  });
});

describe("member action words", () => {
  it("names", () => {
    expect(memberName({ displayName: "Priya", userId: "@p:x" })).toBe("Priya");
    expect(memberName({ displayName: null, userId: "@p:x" })).toBe("@p:x");
  });
  it("labels", () => {
    expect(blockLabel("Priya")).toBe("Block Priya");
    expect(unblockLabel("Priya")).toBe("Unblock Priya");
    expect(reportLabel("Priya")).toBe("Report Priya…");
  });
  it("messages", () => {
    expect(memberActionsMessage(true, false)).toBe(
      "Unblocking shows their messages again, including ones sent while they were blocked.",
    );
    expect(memberActionsMessage(false, true)).toBe(
      "Its messages are hidden everywhere, on all your devices. The agent keeps running and isn't told.",
    );
    expect(memberActionsMessage(false, false)).toBe(
      "You won't see their messages in any room, on any of your devices. They aren't told.",
    );
  });
});
