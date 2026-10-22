import { describe, expect, it } from "vitest";
import { INVITATION_EMPTY_TIMELINE, invitationPrompt, inviterLine } from "./invitationView";



describe("invitationPrompt", () => {
  it("names the room, because 32 invitations look alike otherwise", () => {
    expect(invitationPrompt("analyst-echo")).toBe(
      "You have been invited to analyst-echo.",
    );
  });
});

describe("inviterLine", () => {
  it("says who invited", () => {
    expect(inviterLine("Krishna")).toBe("from Krishna");
  });
  it("is absent when the inviter is not known", () => {
    expect(inviterLine(null)).toBeNull();
  });
});
