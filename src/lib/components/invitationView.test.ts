import { describe, expect, it } from "vitest";
import { INVITATION_EMPTY_TIMELINE, invitationPrompt } from "./invitationView";



describe("invitationPrompt", () => {
  it("names the room, because 32 invitations look alike otherwise", () => {
    expect(invitationPrompt("analyst-echo")).toBe(
      "You have been invited to analyst-echo.",
    );
  });
});
