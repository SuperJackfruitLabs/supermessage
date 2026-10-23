import { describe, expect, it, vi } from "vitest";
import { openRoomFromNotification, type OpenRoomDeps } from "./openRoom";

function deps(over: Partial<OpenRoomDeps> = {}): OpenRoomDeps {
  return { selectedRoomId: null, selectRoom: vi.fn(), ...over };
}

describe("openRoomFromNotification", () => {
  it("selects the room", async () => {
    const d = deps();
    await openRoomFromNotification("!a:x", d);
    expect(d.selectRoom).toHaveBeenCalledWith("!a:x");
  });

  it("selects a room outside the selected space and moves nothing else (O2)", async () => {
    // The only things it may touch are in its dependencies: a rail reset would need one here.
    const d = deps();
    await openRoomFromNotification("!elsewhere:x", d);
    expect(d.selectRoom).toHaveBeenCalledWith("!elsewhere:x");
    expect(Object.keys(d).sort()).toEqual(["selectRoom", "selectedRoomId"]);
  });

  it("the room already open is not selected again", async () => {
    const d = deps({ selectedRoomId: "!a:x" });
    await openRoomFromNotification("!a:x", d);
    expect(d.selectRoom).not.toHaveBeenCalled();
  });
});
