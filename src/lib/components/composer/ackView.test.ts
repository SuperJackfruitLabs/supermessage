import { describe, expect, it, vi } from "vitest";

import type { Acknowledgement, RoomCast } from "$lib/ipc";
import { createAcknowledgement, dockGlyph, type AcknowledgementDeps } from "./ackView";

const cast = (roomId: string, over: Partial<RoomCast> = {}): RoomCast => ({
  roomId,
  people: [],
  counterpart: null,
  agentIds: ["@atlas:x"],
  addressee: "Atlas",
  mentionables: [],
  ...over,
});

const sent = (name: string): Acknowledgement => ({ kind: "sent", names: [name], text: `Sent to ${name}` });
const onIt = (names: string[], text: string): Acknowledgement => ({ kind: "onIt", names, text });

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

function deps(fn: (...a: unknown[]) => Promise<Acknowledgement | null>) {
  const acknowledgement = vi.fn(fn);
  return { deps: { acknowledgement } as unknown as AcknowledgementDeps, acknowledgement };
}

describe("the acknowledgement dock", () => {
  it("asks with the room's facts", async () => {
    const { deps: d, acknowledgement } = deps(async () => sent("Atlas"));
    const ack = createAcknowledgement(d);
    await ack.ask({ roomId: "!a:x", cast: cast("!a:x"), typingAgents: ["Atlas"], inProgress: true });
    expect(acknowledgement).toHaveBeenCalledWith("!a:x", "Atlas", ["@atlas:x"], ["Atlas"], true);
    expect(ack.current?.text).toBe("Sent to Atlas");

    // Another room's cast names nobody here.
    await ack.ask({ roomId: "!b:x", cast: cast("!a:x"), typingAgents: [], inProgress: false });
    expect(acknowledgement).toHaveBeenLastCalledWith("!b:x", null, [], [], false);
  });

  it("a progress message the agent edits ends the dock", async () => {
    // The core answers Sent while the newest row is ours and nothing once an agent's row follows.
    let rows: "own" | "agent" = "own";
    const { deps: d } = deps(async () => (rows === "own" ? sent("Atlas") : null));
    const changes = vi.fn();
    const ack = createAcknowledgement(d, changes);
    const facts = { roomId: "!a:x", cast: cast("!a:x"), typingAgents: [], inProgress: false };
    await ack.ask(facts);
    expect(ack.current?.kind).toBe("sent");
    // The timeline's items change (a Set with `edited`): asked again, the dock ends.
    rows = "agent";
    await ack.ask(facts);
    expect(ack.current).toBeNull();
    expect(changes).toHaveBeenCalledTimes(2);
  });

  it("several agents typing are named and nobody is 'Sent to'", async () => {
    const { deps: d, acknowledgement } = deps(async () =>
      onIt(["Atlas", "Quill", "Sage"], "Atlas, Quill and 1 other are on it…"),
    );
    const ack = createAcknowledgement(d);
    await ack.ask({
      roomId: "!m:x",
      cast: cast("!m:x", { addressee: null, agentIds: ["@atlas:x", "@quill:x", "@sage:x"] }),
      typingAgents: ["Atlas", "Quill", "Sage"],
      inProgress: false,
    });
    expect(acknowledgement).toHaveBeenCalledWith(
      "!m:x",
      null,
      ["@atlas:x", "@quill:x", "@sage:x"],
      ["Atlas", "Quill", "Sage"],
      false,
    );
    expect(ack.current?.text).toBe("Atlas, Quill and 1 other are on it…");
  });

  it("an answer for the room just left is dropped", async () => {
    const slow = deferred<Acknowledgement | null>();
    const calls: string[] = [];
    const { deps: d } = deps((...a) => {
      calls.push(a[0] as string);
      return a[0] === "!a:x" ? slow.promise : Promise.resolve(sent("Quill"));
    });
    const ack = createAcknowledgement(d);
    const first = ack.ask({ roomId: "!a:x", cast: null, typingAgents: [], inProgress: false });
    await ack.ask({ roomId: "!b:x", cast: null, typingAgents: [], inProgress: false });
    expect(ack.current?.text).toBe("Sent to Quill");
    slow.resolve(sent("Atlas"));
    await first;
    expect(ack.current?.text).toBe("Sent to Quill");
    expect(calls).toEqual(["!a:x", "!b:x"]);

    // Same room, a newer ask wins over an older one that lands late.
    const late = deferred<Acknowledgement | null>();
    const { deps: d2 } = deps((...a) => (a[4] ? late.promise : Promise.resolve(null)));
    const ack2 = createAcknowledgement(d2);
    const older = ack2.ask({ roomId: "!a:x", cast: null, typingAgents: [], inProgress: true });
    await ack2.ask({ roomId: "!a:x", cast: null, typingAgents: [], inProgress: false });
    late.resolve(sent("Atlas"));
    await older;
    expect(ack2.current).toBeNull();
  });

  it("a refusal is no dock", async () => {
    let fail = false;
    const { deps: d } = deps(async () => {
      if (fail) throw { kind: "roomChanged", message: "moved" };
      return sent("Atlas");
    });
    const ack = createAcknowledgement(d);
    const facts = { roomId: "!a:x", cast: null, typingAgents: [], inProgress: false };
    await ack.ask(facts);
    expect(ack.current).not.toBeNull();
    fail = true;
    await ack.ask(facts);
    expect(ack.current).toBeNull();
  });

  it("reset forgets the dock", async () => {
    const { deps: d } = deps(async () => sent("Atlas"));
    const ack = createAcknowledgement(d);
    await ack.ask({ roomId: "!a:x", cast: null, typingAgents: [], inProgress: false });
    expect(ack.current).not.toBeNull();
    ack.clear();
    expect(ack.current).toBeNull();
  });

  it("a clear drops an answer still on its way", async () => {
    const slow = deferred<Acknowledgement | null>();
    const { deps: d } = deps(() => slow.promise);
    const ack = createAcknowledgement(d);
    const pending = ack.ask({ roomId: "!a:x", cast: null, typingAgents: [], inProgress: false });
    ack.clear();
    slow.resolve(sent("Atlas"));
    await pending;
    expect(ack.current).toBeNull();
  });
});

describe("the dock's glyph", () => {
  it("a check in ok for Sent, an ellipsis in accent for On it", () => {
    expect(dockGlyph("sent")).toEqual({ glyph: "check", tone: "text-ok" });
    expect(dockGlyph("onIt")).toEqual({ glyph: "ellipsis", tone: "text-accent" });
  });
});
