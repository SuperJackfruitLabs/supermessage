import { describe, expect, it, vi } from "vitest";
import type { RoomRow } from "$lib/ipc";
import { openRoomFromNotification, type OpenRoomDeps } from "./openRoom";

const row = (id: string) => ({ room: { id } }) as unknown as RoomRow;

function deps(over: Partial<OpenRoomDeps> = {}): OpenRoomDeps {
  return {
    rooms: [row("!a:x")],
    selectedRoomId: null,
    spaceSelected: false,
    selectSpace: vi.fn(async () => {}),
    selectRoom: vi.fn(),
    ...over,
  };
}

describe("openRoomFromNotification", () => {
  it("selects the room", async () => {
    const d = deps();
    await openRoomFromNotification("!a:x", d);
    expect(d.selectRoom).toHaveBeenCalledWith("!a:x");
    expect(d.selectSpace).not.toHaveBeenCalled();
  });

  it("a room outside the selected space returns the rail to All rooms first", async () => {
    const order: string[] = [];
    const d = deps({
      spaceSelected: true,
      selectSpace: vi.fn(async () => void order.push("space")),
      selectRoom: vi.fn(() => void order.push("room")),
    });
    await openRoomFromNotification("!elsewhere:x", d);
    expect(d.selectSpace).toHaveBeenCalledWith(null);
    expect(order).toEqual(["space", "room"]);
  });

  it("a room inside the selected space leaves the space alone", async () => {
    const d = deps({ spaceSelected: true });
    await openRoomFromNotification("!a:x", d);
    expect(d.selectSpace).not.toHaveBeenCalled();
    expect(d.selectRoom).toHaveBeenCalledWith("!a:x");
  });

  it("the room already open is not selected again", async () => {
    const d = deps({ selectedRoomId: "!a:x" });
    await openRoomFromNotification("!a:x", d);
    expect(d.selectRoom).not.toHaveBeenCalled();
  });
});
