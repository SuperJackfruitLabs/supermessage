import { describe, expect, it, vi } from "vitest";
import type { PersonDto } from "$lib/ipc";
import {
  creationProblem,
  emptyState,
  isRoomTarget,
  isUserId,
  openConversation,
  parseInvitees,
  peopleHeading,
  personSubtitle,
  shouldBeDirect,
} from "./roomCreation";

const atlas: PersonDto = {
  userId: "@atlas:x",
  name: "Atlas",
  initial: "A",
  runtime: null,
  avatarUrl: null,
};

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

describe("openConversation", () => {
  it("openConversation reuses an existing DM", async () => {
    const directRoomWith = vi.fn(async () => "!dm");
    const createRoom = vi.fn(async () => "!new");
    expect(await openConversation(atlas, { directRoomWith, createRoom })).toBe("!dm");
    expect(directRoomWith).toHaveBeenCalledWith("@atlas:x");
    expect(createRoom).not.toHaveBeenCalled();
  });

  it("openConversation creates a DM named for the person", async () => {
    const createRoom = vi.fn(async () => "!new");
    const id = await openConversation(atlas, { directRoomWith: async () => null, createRoom });
    expect(id).toBe("!new");
    expect(createRoom).toHaveBeenCalledWith("Atlas", ["@atlas:x"], true);
  });

  it("openConversation creates when the lookup fails", async () => {
    const createRoom = vi.fn(async () => "!new");
    const directRoomWith = vi.fn(async () => {
      throw { kind: "network", message: "offline" };
    });
    expect(await openConversation(atlas, { directRoomWith, createRoom })).toBe("!new");
    expect(createRoom).toHaveBeenCalledTimes(1);
  });

  it("rejects with the core's error when the create fails", async () => {
    const refused = { kind: "refused", message: "no" };
    await expect(
      openConversation(atlas, {
        directRoomWith: async () => null,
        createRoom: async () => {
          throw refused;
        },
      }),
    ).rejects.toBe(refused);
  });
});

describe("personSubtitle", () => {
  it("names an agent's harness and host", () => {
    expect(personSubtitle({ ...atlas, runtime: { harness: "Claude", host: "Ci" } })).toBe("Claude on Ci");
  });
  it("is the user id for anyone else", () => {
    expect(personSubtitle(atlas)).toBe("@atlas:x");
  });
});

describe("peopleHeading and emptyState", () => {
  it("headings", () => {
    expect(peopleHeading("")).toBe("Who you know");
    expect(peopleHeading("at")).toBe("Matches");
  });
  it("empty states", () => {
    expect(emptyState("")).toEqual({
      title: "Nobody yet",
      description: "Agents and people you share a room with appear here.",
    });
    expect(emptyState("zz")).toEqual({
      title: "No one matching zz",
      description: "Try a name, a machine, or a full address.",
    });
  });
});
