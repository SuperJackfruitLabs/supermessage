import { describe, expect, it, vi } from "vitest";
import type { NotificationMode, RoomInfo } from "$lib/ipc";
import { NOTIFY_OPTIONS, exclusively, muteTarget, toggleMute, togglePin, type ToggleDeps } from "./roomSettings";

function deps(info: Partial<RoomInfo> | Error, next: NotificationMode = "muted", writeFails = false) {
  const log: string[] = [];
  const d: ToggleDeps = {
    roomInfo: vi.fn(async () => {
      log.push("roomInfo");
      if (info instanceof Error) throw info;
      return info as RoomInfo;
    }),
    nextNotificationMode: vi.fn(async () => {
      log.push("next");
      return next;
    }),
    setRoomNotificationMode: vi.fn(async (id: string, mode: NotificationMode) => {
      log.push(`mode:${id}:${mode}`);
      if (writeFails) throw { kind: "refused", message: "no" };
    }),
    setRoomPinned: vi.fn(async (id: string, pinned: boolean) => {
      log.push(`pin:${id}:${pinned}`);
      if (writeFails) throw { kind: "refused", message: "no" };
    }),
  };
  return { d, log };
}

describe("room settings", () => {
  it("NOTIFY_OPTIONS are iOS's four, in order", () => {
    expect(NOTIFY_OPTIONS).toEqual([
      { mode: "allMessages", label: "Everything" },
      { mode: "mentionsOnly", label: "Mentions only" },
      { mode: "default", label: "Account default" },
      { mode: "muted", label: "Nothing" },
    ]);
  });
  it("muteTarget both ways", () => {
    expect(muteTarget(true)).toBe("muted");
    expect(muteTarget(false)).toBe("default");
  });
  it("toggleMute asks the core first", async () => {
    const { d, log } = deps({ notifications: "allMessages" }, "muted");
    expect(await toggleMute("!a", d)).toEqual({ muted: true });
    expect(log).toEqual(["roomInfo", "next", "mode:!a:muted"]);
  });
  it("toggleMute unmutes to what the core says", async () => {
    const { d } = deps({ notifications: "muted" }, "default");
    expect(await toggleMute("!a", d)).toEqual({ muted: false });
  });
  it("toggleMute does nothing when roomInfo fails", async () => {
    const { d, log } = deps(new Error("down"));
    expect(await toggleMute("!a", d)).toBeNull();
    expect(log).toEqual(["roomInfo"]);
    expect(d.setRoomNotificationMode).not.toHaveBeenCalled();
  });
  it("togglePin flips what the core says", async () => {
    const { d } = deps({ pinned: true });
    expect(await togglePin("!a", d)).toEqual({ pinned: false });
    expect(d.setRoomPinned).toHaveBeenCalledWith("!a", false);
    const other = deps({ pinned: false });
    expect(await togglePin("!a", other.d)).toEqual({ pinned: true });
  });
  it("a refused write reports null", async () => {
    expect(await toggleMute("!a", deps({ notifications: "default" }, "muted", true).d)).toBeNull();
    expect(await togglePin("!a", deps({ pinned: false }, "muted", true).d)).toBeNull();
  });
});

describe("exclusively", () => {
  it("refuses a second run for the same key while the first is in flight", async () => {
    const busy = new Set<string>();
    let release!: () => void;
    const first = exclusively(busy, "!a", () => new Promise<string>((r) => (release = () => r("done"))));
    const second = await exclusively(busy, "!a", async () => "again");
    expect(second).toEqual({ ran: false });
    release();
    expect(await first).toEqual({ ran: true, value: "done" });
    expect(await exclusively(busy, "!a", async () => "later")).toEqual({ ran: true, value: "later" });
  });

  it("lets another key run meanwhile", async () => {
    const busy = new Set<string>(["!a"]);
    expect(await exclusively(busy, "!b", async () => 1)).toEqual({ ran: true, value: 1 });
  });

  it("releases the key when the work throws", async () => {
    const busy = new Set<string>();
    await expect(exclusively(busy, "!a", async () => { throw new Error("x"); })).rejects.toThrow("x");
    expect(busy.has("!a")).toBe(false);
  });
});
