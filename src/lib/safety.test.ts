import { describe, expect, it, vi } from "vitest";
import { memberIsAgent, report, reportSubjectFor, block, type ReportSubject, type SafetyDeps } from "./safety";

function deps(over: Partial<SafetyDeps> = {}) {
  const store = {
    isBlocked: vi.fn(() => false),
    noteBlocked: vi.fn(),
    noteUnblocked: vi.fn(),
    hide: vi.fn(),
  };
  const d = {
    ignoreUser: vi.fn(async () => {}),
    unignoreUser: vi.fn(async () => {}),
    reportEvent: vi.fn(async () => {}),
    reportRoom: vi.fn(async () => {}),
    reportUser: vi.fn(async () => {}),
    store,
    ...over,
  } as unknown as SafetyDeps & { ignoreUser: ReturnType<typeof vi.fn>; reportEvent: ReturnType<typeof vi.fn>; store: typeof store };
  return d;
}

const message: ReportSubject = { kind: "message", roomId: "!r:x", eventId: "$e", senderId: "@s:x", senderName: "S", isAgent: false };
const room: ReportSubject = { kind: "room", roomId: "!r:x", name: "R" };

describe("report", () => {
  it("reportHidesOnlyAfterTheHomeserverHasIt", async () => {
    let finish!: () => void;
    const d = deps({ reportEvent: vi.fn(() => new Promise<void>((r) => (finish = r))) });
    const pending = report(message, "spam", false, d);
    await Promise.resolve();
    expect(d.store.hide).not.toHaveBeenCalled();
    finish();
    expect(await pending).toEqual({ status: "sent" });
    expect(d.store.hide).toHaveBeenCalledWith("$e");
  });

  it("aFailedReportHidesNothingAndBlocksNoOne", async () => {
    const d = deps({ reportEvent: vi.fn(async () => { throw { kind: "network", message: "" }; }) });
    expect(await report(message, "spam", true, d)).toEqual({ status: "refused", message: "Can't reach the homeserver." });
    expect(d.store.hide).not.toHaveBeenCalled();
    expect(d.ignoreUser).not.toHaveBeenCalled();
  });

  it("a non-core failure reads That didn't work.", async () => {
    const d = deps({ reportEvent: vi.fn(async () => { throw new Error("boom"); }) });
    expect(await report(message, "", false, d)).toEqual({ status: "refused", message: "That didn't work." });
  });

  it("aBlockThatFailsAfterAReportSaysWhichHalf", async () => {
    const d = deps({ ignoreUser: vi.fn(async () => { throw { kind: "refused", message: "You can't block yourself." }; }) });
    expect(await report(message, "spam", true, d)).toEqual({
      status: "reportedNotBlocked",
      message: "Reported, but blocking didn't work: You can't block yourself.",
    });
    expect(d.store.hide).toHaveBeenCalledWith("$e");
  });

  it("a report that blocks notes the block", async () => {
    const d = deps();
    expect(await report(message, "", true, d)).toEqual({ status: "sent" });
    expect(d.ignoreUser).toHaveBeenCalledWith("@s:x");
    expect(d.store.noteBlocked).toHaveBeenCalledWith("@s:x");
  });

  it("anAlreadyBlockedSenderIsNotBlockedAgain", async () => {
    const d = deps();
    d.store.isBlocked.mockReturnValue(true);
    expect(await report(message, "", true, d)).toEqual({ status: "sent" });
    expect(d.ignoreUser).not.toHaveBeenCalled();
  });

  it("aRoomReportNeverBlocks", async () => {
    const d = deps();
    expect(await report(room, "", true, d)).toEqual({ status: "sent" });
    expect(d.ignoreUser).not.toHaveBeenCalled();
    expect(d.store.hide).not.toHaveBeenCalled();
  });
});

describe("block", () => {
  it("blockNotesOnlyWhatLanded", async () => {
    const bad = deps({ ignoreUser: vi.fn(async () => { throw { kind: "network", message: "" }; }) });
    expect(await block("@x:y", bad)).toBe("Can't reach the homeserver.");
    expect(bad.store.noteBlocked).not.toHaveBeenCalled();
    const good = deps();
    expect(await block("@x:y", good)).toBeNull();
    expect(good.store.noteBlocked).toHaveBeenCalledTimes(1);
    expect(good.store.noteBlocked).toHaveBeenCalledWith("@x:y");
  });
});

describe("reportSubjectFor", () => {
  const row = (eventId: string | null, sender: string | null) =>
    ({ senderShort: "Atlas", item: { eventId, sender } });

  it("reportSubjectFor needs an event and a sender", async () => {
    const isAgent = vi.fn(async () => true);
    expect(await reportSubjectFor(row(null, "@a:x"), "!r:x", isAgent)).toBeNull();
    expect(await reportSubjectFor(row("$e", null), "!r:x", isAgent)).toBeNull();
    expect(await reportSubjectFor(row("$e", "@a:x"), "!r:x", isAgent)).toEqual({
      kind: "message", roomId: "!r:x", eventId: "$e", senderId: "@a:x", senderName: "Atlas", isAgent: true,
    });
    expect(isAgent).toHaveBeenCalledWith("@a:x");
  });
});

describe("memberIsAgent", () => {
  const info = (members: { userId: string; isAgent: boolean }[]) => (async () => ({ members })) as never;
  it("memberIsAgent reads the core's flag", async () => {
    expect(await memberIsAgent("!r:x", "@a:x", info([{ userId: "@a:x", isAgent: true }]))).toBe(true);
    expect(await memberIsAgent("!r:x", "@a:x", info([{ userId: "@a:x", isAgent: false }]))).toBe(false);
    expect(await memberIsAgent("!r:x", "@a:x", info([]))).toBe(false);
    expect(await memberIsAgent("!r:x", "@a:x", (async () => { throw { kind: "notReady", message: "" }; }) as never)).toBe(false);
  });
});
