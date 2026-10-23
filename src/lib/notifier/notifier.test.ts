import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { NotificationContext, NotificationMode, PlannedNotification, RoomRow } from "$lib/ipc";
import { CATCH_UP_MS, MODE_CACHE_MS, createNotifier, type NotifierDeps } from "./notifier.svelte";

function note(id: string, eventId: string | null = id, roomId = "!a:x"): PlannedNotification {
  return { id, roomId, eventId, title: "Atlas", subtitle: null, body: "hi", category: "message", permission: null, gate: null };
}
const row = (id: string, unread = 0) => ({ room: { id, unread } }) as unknown as RoomRow;

interface Fakes {
  deps: NotifierDeps;
  roster: ReturnType<typeof vi.fn>;
  focused: ReturnType<typeof vi.fn>;
  delivers: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  roomInfo: ReturnType<typeof vi.fn>;
  mode: { value: NotificationMode };
}

function fakes(): Fakes {
  const mode = { value: "default" as NotificationMode };
  const roster = vi.fn(async (): Promise<PlannedNotification[]> => []);
  const focused = vi.fn(async (): Promise<PlannedNotification[]> => []);
  const delivers = vi.fn(async (_n: PlannedNotification, _m: NotificationMode | null) => true);
  const post = vi.fn(async (_n: PlannedNotification) => {});
  const roomInfo = vi.fn(async (_id: string) => ({ notifications: mode.value }) as never);
  return {
    deps: {
      notificationsForRoster: roster as never,
      notificationsForFocused: focused as never,
      notificationDelivers: delivers as never,
      notificationPost: post as never,
      roomInfo: roomInfo as never,
      now: () => Date.now(),
    },
    roster, focused, delivers, post, roomInfo, mode,
  };
}

const flush = () => vi.advanceTimersByTimeAsync(0);
const THROTTLE = 250;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(1_000_000);
});
afterEach(() => vi.useRealTimers());

describe("notifier: roster", () => {
  it("the first roster is a baseline", async () => {
    const f = fakes();
    const n = createNotifier(f.deps);
    n.roomsChanged([row("!a:x", 1)]);
    await flush();
    expect(f.roster).not.toHaveBeenCalled();
    n.roomsChanged([row("!a:x", 2)]);
    await flush();
    expect(f.roster).toHaveBeenCalledTimes(1);
    expect(f.roster.mock.calls[0]![0]).toEqual([row("!a:x", 1)]);
  });

  it("coming back to the window is not a burst", async () => {
    const f = fakes();
    f.roster.mockResolvedValue([note("n1", null)]);
    const n = createNotifier(f.deps);
    n.roomsChanged([row("!a:x", 0)]);
    n.focusChanged(true);
    vi.advanceTimersByTime(CATCH_UP_MS - 1);
    n.roomsChanged([row("!a:x", 1)]);
    await flush();
    expect(f.roster).not.toHaveBeenCalled();
    expect(f.post).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2);
    n.roomsChanged([row("!a:x", 2)]);
    await flush();
    expect(f.post).toHaveBeenCalledTimes(1);
    // The change held back during catch-up was the new baseline, not queued news.
    expect(f.roster.mock.calls[0]![0]).toEqual([row("!a:x", 1)]);
  });

  it("the mode is asked once a minute", async () => {
    const f = fakes();
    f.roster.mockResolvedValue([note("n1", null)]);
    const n = createNotifier(f.deps);
    n.roomsChanged([row("!a:x", 0)]);
    n.roomsChanged([row("!a:x", 1)]);
    await flush();
    n.roomsChanged([row("!a:x", 2)]);
    await flush();
    expect(f.roomInfo).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(MODE_CACHE_MS + 1);
    n.roomsChanged([row("!a:x", 3)]);
    await flush();
    expect(f.roomInfo).toHaveBeenCalledTimes(2);
  });

  it("asks the core about the mode it found, and holds back what the core refuses", async () => {
    const f = fakes();
    f.mode.value = "muted";
    f.delivers.mockResolvedValue(false);
    f.roster.mockResolvedValue([note("n1", null)]);
    const n = createNotifier(f.deps);
    n.roomsChanged([row("!a:x", 0)]);
    n.roomsChanged([row("!a:x", 1)]);
    await flush();
    expect(f.delivers.mock.calls[0]![1]).toBe("muted");
    expect(f.post).not.toHaveBeenCalled();
  });
});

describe("notifier: focused timeline", () => {
  it("a held-back row is still recorded as notified", async () => {
    const f = fakes();
    f.delivers.mockResolvedValue(false);
    f.focused.mockResolvedValue([note("$e1")]);
    const n = createNotifier(f.deps);
    n.selectedChanged("!a:x");
    n.timelineChanged("!a:x", "Atlas");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(f.post).not.toHaveBeenCalled();
    expect([...n.notified]).toEqual(["$e1"]);
    // ...and the next call carries it, so a re-emitted row is not asked about again.
    n.timelineChanged("!a:x", "Atlas");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(f.focused.mock.calls[1]![3]).toEqual(["$e1"]);
  });

  it("a room switch starts a new since", async () => {
    const f = fakes();
    const n = createNotifier(f.deps);
    n.selectedChanged("!a:x");
    n.timelineChanged("!a:x", "A");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(f.focused.mock.calls[0]![2]).toBe(1_000_000);
    vi.setSystemTime(1_600_000);
    n.timelineChanged("!a:x", "A");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(f.focused.mock.calls[1]![2]).toBe(1_000_000);
    const switchedAt = Date.now();
    n.selectedChanged("!b:x");
    n.timelineChanged("!b:x", "B");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(f.focused.mock.calls[2]![0]).toBe("!b:x");
    expect(f.focused.mock.calls[2]![2]).toBe(switchedAt);
  });

  it("the focused room in a focused window is passed as open and active", async () => {
    const f = fakes();
    const n = createNotifier(f.deps);
    n.focusChanged(true);
    n.selectedChanged("!a:x");
    n.timelineChanged("!a:x", "A");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    const ctx = f.focused.mock.calls[0]![4] as NotificationContext;
    expect(ctx).toEqual({ openRoomId: "!a:x", appActive: true, timelineRoomId: "!a:x", remotePush: false });
  });

  it("an unfocused window passes appActive false", async () => {
    const f = fakes();
    const n = createNotifier(f.deps);
    n.focusChanged(false);
    n.selectedChanged("!a:x");
    n.timelineChanged("!a:x", "A");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect((f.focused.mock.calls[0]![4] as NotificationContext).appActive).toBe(false);
  });

  it("a burst of timeline changes is one call per 250 ms", async () => {
    const f = fakes();
    const n = createNotifier(f.deps);
    n.selectedChanged("!a:x");
    for (let i = 0; i < 20; i++) {
      n.timelineChanged("!a:x", "A");
      await vi.advanceTimersByTimeAsync(10);
    }
    // 200 ms in: nothing yet; the trailing call lands at 250 ms.
    expect(f.focused).toHaveBeenCalledTimes(0);
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(f.focused).toHaveBeenCalledTimes(1);
    n.timelineChanged("!a:x", "A");
    n.timelineChanged("!a:x", "A");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(f.focused).toHaveBeenCalledTimes(2);
  });

  it("a RoomChanged refusal is ignored", async () => {
    const f = fakes();
    f.focused.mockRejectedValueOnce({ kind: "roomChanged", message: "x" });
    const n = createNotifier(f.deps);
    n.selectedChanged("!a:x");
    n.timelineChanged("!a:x", "A");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(f.post).not.toHaveBeenCalled();
    // And the next change works.
    f.focused.mockResolvedValueOnce([note("$e")]);
    n.timelineChanged("!a:x", "A");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(f.post).toHaveBeenCalledTimes(1);
  });
});

describe("notifier: reset", () => {
  it("reset forgets the baseline, the notified set and the modes", async () => {
    const f = fakes();
    f.focused.mockResolvedValue([note("$e1")]);
    f.roster.mockResolvedValue([note("n1", null)]);
    const n = createNotifier(f.deps);
    n.roomsChanged([row("!a:x", 0)]);
    n.roomsChanged([row("!a:x", 1)]);
    n.selectedChanged("!a:x");
    n.timelineChanged("!a:x", "A");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    expect(n.notified.size).toBe(1);
    expect(f.roomInfo).toHaveBeenCalledTimes(1);

    n.reset();
    expect(n.notified.size).toBe(0);
    f.roster.mockClear();
    // The first roster of the next account is a baseline again.
    n.roomsChanged([row("!z:x", 5)]);
    n.roomsChanged([row("!z:x", 5)]);
    await flush();
    expect(f.roster).toHaveBeenCalledTimes(1);
    expect(f.roster.mock.calls[0]![0]).toEqual([row("!z:x", 5)]);
    // Modes were forgotten: the room asked about before the reset is asked again at once.
    expect(f.roomInfo).toHaveBeenCalledTimes(2);
  });

  it("a call still in flight at reset posts nothing", async () => {
    const f = fakes();
    let release!: (notes: PlannedNotification[]) => void;
    f.focused.mockReturnValueOnce(new Promise((r) => (release = r)));
    const n = createNotifier(f.deps);
    n.selectedChanged("!a:x");
    n.timelineChanged("!a:x", "A");
    await vi.advanceTimersByTimeAsync(THROTTLE);
    n.reset();
    release([note("$old")]);
    await flush();
    expect(f.post).not.toHaveBeenCalled();
    expect(n.notified.size).toBe(0);
  });
});
