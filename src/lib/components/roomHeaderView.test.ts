import { describe, expect, it, vi } from "vitest";

import type { AgentState, RoomRow, RoomStatus, RosterRow } from "$lib/ipc";
import {
  createHeaderStatus,
  headerRows,
  headerTurnFlag,
  memberCountLabel,
  statusDotClass,
  statusPillClass, type HeaderStatusDeps,
} from "./roomHeaderView";

const row = (state: AgentState, describesAgent: boolean): RosterRow =>
  ({ row: {} as RoomRow, state, describesAgent }) as RosterRow;

const WORDS: Record<RoomStatus, string> = {
  working: "Working",
  needsYou: "Needs you",
  active: "Active",
  idle: "Idle",
  quiet: "Quiet",
};

// A fake core that follows the core's rule closely enough to tell the tests apart.
function fakeDeps(rows: Record<string, RosterRow | null>): HeaderStatusDeps & {
  roomStatus: ReturnType<typeof vi.fn>;
} {
  return {
    rosterRow: vi.fn(async (_rows, roomId: string) => rows[roomId] ?? null),
    roomStatus: vi.fn(async (state: AgentState, describesAgent: boolean, turn: boolean) => {
      if (!describesAgent) return null;
      if (state === "needsYou") return "needsYou";
      if (turn) return "working";
      return state as RoomStatus;
    }),
    roomStatusWord: vi.fn(async (s: RoomStatus) => WORDS[s]),
    roomInfo: vi.fn(async () => ({ activeMemberCount: 3 }) as never),
  } as never;
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

describe("memberCountLabel", () => {
  it("says 1 member and N members", () => {
    expect(memberCountLabel(1)).toBe("1 member");
    expect(memberCountLabel(2)).toBe("2 members");
    expect(memberCountLabel(40)).toBe("40 members");
  });

  it("says nothing for none or unknown", () => {
    expect(memberCountLabel(0)).toBeNull();
    expect(memberCountLabel(null)).toBeNull();
    expect(memberCountLabel(undefined)).toBeNull();
  });
});

describe("the pill's colours", () => {
  it("dots follow iOS", () => {
    expect(statusDotClass("working")).toBe("bg-ok");
    expect(statusDotClass("active")).toBe("bg-ok");
    expect(statusDotClass("needsYou")).toBe("bg-signal");
    expect(statusDotClass("idle")).toBe("bg-content-faint");
    expect(statusDotClass("quiet")).toBe("bg-border");
  });

  it("only Needs you is amber", () => {
    expect(statusPillClass("needsYou")).toEqual({ ground: "bg-signal-soft", word: "text-signal" });
    for (const s of ["working", "active", "idle", "quiet"] as const) {
      expect(statusPillClass(s)).toEqual({ ground: "bg-surface-raised", word: "text-content-muted" });
    }
  });
});

describe("createHeaderStatus", () => {
  it("an agent's room with a live turn is Working", async () => {
    const deps = fakeDeps({ "!a:x": row("idle", true) });
    const h = createHeaderStatus(deps);
    await h.refresh("!a:x", [], 1, true);
    expect(deps.roomStatus).toHaveBeenCalledWith("idle", true, true);
    expect(h.status).toBe("working");
    expect(h.word).toBe("Working");
  });

  it("without a live turn it reads the roster's state", async () => {
    const h = createHeaderStatus(fakeDeps({ "!a:x": row("active", true) }));
    await h.refresh("!a:x", [], 1, false);
    expect(h.status).toBe("active");
    expect(h.word).toBe("Active");
  });

  it("a room of people has no status", async () => {
    const h = createHeaderStatus(fakeDeps({ "!p:x": row("idle", false) }));
    await h.refresh("!p:x", [], 1, true);
    expect(h.status).toBeNull();
    expect(h.word).toBeNull();
  });

  it("a room the roster does not have has no status", async () => {
    const h = createHeaderStatus(fakeDeps({}));
    await h.refresh("!gone:x", [], 1, false);
    expect(h.status).toBeNull();
  });

  it("a room left while asking keeps the new room's status", async () => {
    const slow = deferred<RosterRow | null>();
    const deps = fakeDeps({ "!b:x": row("idle", true) });
    deps.rosterRow = vi.fn((_r, id: string) => (id === "!a:x" ? slow.promise : Promise.resolve(row("idle", true))));
    const h = createHeaderStatus(deps);
    const first = h.refresh("!a:x", [], 1, false);
    await h.refresh("!b:x", [], 1, true);
    expect(h.status).toBe("working");
    slow.resolve(row("needsYou", true));
    await first;
    expect(h.status).toBe("working");
    expect(h.word).toBe("Working");
  });

  it("leaving a room forgets its status before the new one answers", async () => {
    const never = deferred<RosterRow | null>();
    const deps = fakeDeps({ "!a:x": row("active", true) });
    const real = deps.rosterRow;
    deps.rosterRow = vi.fn((r, id: string, n) => (id === "!b:x" ? never.promise : real(r, id, n)));
    const h = createHeaderStatus(deps);
    await h.refresh("!a:x", [], 1, false);
    expect(h.status).toBe("active");
    void h.refresh("!b:x", [], 1, false);
    expect(h.status).toBeNull();
    expect(h.word).toBeNull();
  });

  it("a failed ask is no status", async () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const deps = fakeDeps({ "!a:x": row("active", true) });
    deps.rosterRow = () => Promise.reject(new Error("no"));
    const h = createHeaderStatus(deps);
    await h.refresh("!a:x", [], 1, false);
    expect(h.status).toBeNull();
    spy.mockRestore();
  });

  it("tells its host when the status changes, not when it stays", async () => {
    const changed = vi.fn();
    const h = createHeaderStatus(fakeDeps({ "!a:x": row("active", true) }), changed);
    await h.refresh("!a:x", [], 1, false);
    const after = changed.mock.calls.length;
    await h.refresh("!a:x", [], 2, false);
    expect(changed.mock.calls.length).toBe(after);
    await h.refresh("!a:x", [], 3, true);
    expect(changed.mock.calls.length).toBe(after + 1);
  });

  it("member count: a room left while asking is dropped", async () => {
    const slow = deferred<{ activeMemberCount: number }>();
    const deps = fakeDeps({});
    deps.roomInfo = vi.fn((id: string) =>
      id === "!a:x" ? slow.promise : Promise.resolve({ activeMemberCount: 2 }),
    ) as never;
    const h = createHeaderStatus(deps);
    const first = h.refreshMembers("!a:x");
    await h.refreshMembers("!b:x");
    slow.resolve({ activeMemberCount: 9 });
    await first;
    expect(h.members).toBe(2);
  });
});

describe("the header's cost", () => {
  const room = (id: string) => ({ room: { id } }) as unknown as RoomRow;

  it("only the selected room is sent", () => {
    const rooms = Array.from({ length: 200 }, (_, i) => room(`!r${i}:x`));
    expect(headerRows(rooms, "!r7:x")).toEqual([rooms[7]]);
    expect(headerRows(rooms, "!gone:x")).toEqual([]);
  });

  it("a turn step with an unchanged flag makes no IPC call", async () => {
    const deps = fakeDeps({ "!a:x": row("active", true) });
    const h = createHeaderStatus(deps);
    const rows = [room("!a:x")];
    await h.refresh("!a:x", rows, 1, true);
    const calls = () =>
      (deps.rosterRow as ReturnType<typeof vi.fn>).mock.calls.length + deps.roomStatus.mock.calls.length;
    const before = calls();
    // Ten more steps of the same turn: the flag is the same, the room's row is the same.
    for (let i = 0; i < 10; i++) await h.refresh("!a:x", [room("!a:x")], 1, true);
    expect(calls()).toBe(before);
    // A real change in the flag does ask.
    await h.refresh("!a:x", rows, 1, false);
    expect(calls()).toBeGreaterThan(before);
  });

  it("a failed ask is asked again", async () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const deps = fakeDeps({ "!a:x": row("active", true) });
    const ok = deps.rosterRow;
    deps.rosterRow = vi.fn().mockRejectedValueOnce(new Error("no")).mockImplementation(ok);
    const h = createHeaderStatus(deps);
    await h.refresh("!a:x", [], 1, false);
    await h.refresh("!a:x", [], 1, false);
    expect(h.status).toBe("active");
    spy.mockRestore();
  });

  it("the turn flag is iOS's: live and not finished, in this room", () => {
    const live = { roomId: "!a:x", isLive: true, finished: false };
    expect(headerTurnFlag("!a:x", live)).toBe(true);
    expect(headerTurnFlag("!b:x", live)).toBe(false);
    expect(headerTurnFlag("!a:x", { ...live, finished: true })).toBe(false);
    expect(headerTurnFlag("!a:x", { ...live, isLive: false })).toBe(false);
    expect(headerTurnFlag(null, live)).toBe(false);
  });
});
