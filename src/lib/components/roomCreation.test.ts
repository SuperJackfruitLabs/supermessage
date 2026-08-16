import { describe, expect, it } from "vitest";
import {
  creationProblem,
  isRoomTarget,
  isUserId,
  parseInvitees,
  shouldBeDirect,
} from "./roomCreation";

describe("isUserId", () => {
  it("accepts the shape every Matrix id has", () => {
    expect(isUserId("@agent_echo:id.agentpod.dev")).toBe(true);
    expect(isUserId("  @rakesh:id.agentpod.dev  ")).toBe(true);
  });

  it("refuses what a typo produces", () => {
    // A request built from these comes back as an opaque homeserver error
    // rather than "that is not a user id".
    expect(isUserId("agent_echo:id.agentpod.dev")).toBe(false);
    expect(isUserId("@agent_echo")).toBe(false);
    expect(isUserId("@ agent:server")).toBe(false);
    expect(isUserId("")).toBe(false);
  });
});

describe("isRoomTarget", () => {
  it("accepts an alias or a room id, which are both things to join", () => {
    expect(isRoomTarget("#agentpod_missions:id.agentpod.dev")).toBe(true);
    expect(isRoomTarget("!abc123:id.agentpod.dev")).toBe(true);
  });

  it("refuses a bare name", () => {
    expect(isRoomTarget("missions")).toBe(false);
    expect(isRoomTarget("@someone:server")).toBe(false);
  });
});

describe("parseInvitees", () => {
  it("splits on commas, spaces or newlines", () => {
    // Somebody pasting a list has no reason to know which one this expects.
    expect(parseInvitees("@a:x.org, @b:x.org\n@c:x.org  @d:x.org")).toEqual([
      "@a:x.org",
      "@b:x.org",
      "@c:x.org",
      "@d:x.org",
    ]);
  });

  it("is empty for an empty box", () => {
    expect(parseInvitees("   ")).toEqual([]);
  });
});

describe("creationProblem", () => {
  it("passes a named room with nobody in it yet", () => {
    // A named room you invite people to later is a normal thing to make.
    expect(creationProblem("Q4 rollout", [])).toBeNull();
  });

  it("passes an unnamed room with somebody in it", () => {
    // A DM has no name and does not need one.
    expect(creationProblem("", ["@ana:x.org"])).toBeNull();
  });

  it("refuses a room with neither, naming what is missing", () => {
    expect(creationProblem("  ", [])?.message).toMatch(/name.*or somebody/i);
  });

  it("names the invitee that is not a user id", () => {
    // Listing the bad one beats "invalid input": with four pasted ids, the
    // operator has to know which.
    const problem = creationProblem("Room", ["@ana:x.org", "bob"]);
    expect(problem?.message).toContain("bob");
    expect(problem?.message).not.toContain("@ana:x.org");
  });
});

describe("shouldBeDirect", () => {
  it("calls a room with one other person a DM", () => {
    expect(shouldBeDirect(["@ana:x.org"])).toBe(true);
  });

  it("calls anything else a room", () => {
    // Two or more is a conversation with a group; none is a room waiting to be
    // filled, which is not a DM either.
    expect(shouldBeDirect(["@ana:x.org", "@bo:x.org"])).toBe(false);
    expect(shouldBeDirect([])).toBe(false);
  });
});
