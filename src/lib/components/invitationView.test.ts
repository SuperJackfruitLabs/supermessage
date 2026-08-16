import { describe, expect, it } from "vitest";
import {
  invitationPrompt,
  isInvitation,
  roomAffordance,
} from "./invitationView";

describe("roomAffordance", () => {
  it("offers a composer for a joined room", () => {
    expect(roomAffordance("joined")).toBe("compose");
  });

  it("offers Accept / Decline for an invitation", () => {
    // Issue #1: every bridged agent room arrives as one of these, and the
    // roster listed them with no way to act on them at all.
    expect(roomAffordance("invited")).toBe("respondToInvitation");
  });

  it("offers no composer for a room this account cannot post to", () => {
    // A composer that fails at the homeserver is worse than no composer: it
    // takes a message, loses it, and blames the network.
    expect(roomAffordance("left")).toBe("nothing");
    expect(roomAffordance("knocked")).toBe("nothing");
    expect(roomAffordance("banned")).toBe("nothing");
  });
});

describe("isInvitation", () => {
  it("is true only for an invitation", () => {
    expect(isInvitation("invited")).toBe(true);
    expect(isInvitation("joined")).toBe(false);
    expect(isInvitation("banned")).toBe(false);
  });
});

describe("invitationPrompt", () => {
  it("names the room, because 32 invitations look alike otherwise", () => {
    expect(invitationPrompt("analyst-echo")).toBe(
      "You have been invited to analyst-echo.",
    );
  });
});
