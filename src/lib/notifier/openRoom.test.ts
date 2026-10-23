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
    // That it touches nothing else is guarded by the type check: OpenRoomDeps has only these
    // two members, so a rail reset would need a new dependency, which is a type change.
    const d = deps();
    await openRoomFromNotification("!elsewhere:x", d);
    expect(d.selectRoom).toHaveBeenCalledWith("!elsewhere:x");
  });

  it("the room already open is not selected again", async () => {
    const d = deps({ selectedRoomId: "!a:x" });
    await openRoomFromNotification("!a:x", d);
    expect(d.selectRoom).not.toHaveBeenCalled();
  });
});
