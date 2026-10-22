import { describe, expect, it } from "vitest";
import { roomSettingsWrites as writes } from "./roomSettingsWrites.svelte";

function deferred() {
  let release!: () => void;
  const done = new Promise<void>((r) => (release = r));
  return { done, release };
}

describe("roomSettingsWrites", () => {
  it("lets one write per room run, and says so to the other surface", async () => {
    const gate = deferred();
    let second = 0;
    const first = writes.run("!a:x", () => gate.done);
    expect(writes.busy("!a:x")).toBe(true);
    // The other surface (row menu or room info) asks while the first is in flight.
    const refused = await writes.run("!a:x", async () => void second++);
    expect(refused).toEqual({ ran: false });
    expect(second).toBe(0);
    gate.release();
    await first;
    expect(writes.busy("!a:x")).toBe(false);
  });

  it("does not hold up another room", async () => {
    const gate = deferred();
    const first = writes.run("!b:x", () => gate.done);
    expect(await writes.run("!c:x", async () => 1)).toEqual({ ran: true, value: 1 });
    gate.release();
    await first;
  });

  it("moves the room's revision when a write finishes, even a failed one, and not for a refused call", async () => {
    const before = writes.revision("!d:x");
    await writes.run("!d:x", async () => {
      throw new Error("x");
    }).catch(() => {});
    expect(writes.revision("!d:x")).toBe(before + 1);
    const gate = deferred();
    const held = writes.run("!d:x", () => gate.done);
    await writes.run("!d:x", async () => {});
    expect(writes.revision("!d:x")).toBe(before + 1);
    gate.release();
    await held;
    expect(writes.revision("!d:x")).toBe(before + 2);
    expect(writes.revision("!other:x")).toBe(0);
  });
});
