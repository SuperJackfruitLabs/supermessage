import { describe, expect, it } from "vitest";
import { demoDue, onboarding } from "./onboarding";

function memory() {
  const map = new Map<string, string>();
  return { map, getItem: (k: string) => map.get(k) ?? null, setItem: (k: string, v: string) => void map.set(k, v) };
}

describe("onboarding", () => {
  it("everything is false on a fresh install", () => {
    const o = onboarding(memory());
    expect([o.welcomeSeen, o.demoPending, o.demoSeen]).toEqual([false, false, false]);
  });

  it("the three exact key names, as iOS's", () => {
    const store = memory();
    const o = onboarding(store);
    o.passWelcome();
    o.queueDemo();
    expect([...store.map.keys()].sort()).toEqual(["onboarding.demoPending", "onboarding.welcomeSeen"]);
    o.finishDemo();
    expect([...store.map.keys()].sort()).toEqual([
      "onboarding.demoPending",
      "onboarding.demoSeen",
      "onboarding.welcomeSeen",
    ]);
  });

  it("queueDemo then finishDemo leaves it seen and not pending", () => {
    const o = onboarding(memory());
    o.queueDemo();
    expect(o.demoPending).toBe(true);
    o.finishDemo();
    expect([o.demoSeen, o.demoPending]).toEqual([true, false]);
  });

  it("is remembered across instances over the same storage", () => {
    const store = memory();
    onboarding(store).passWelcome();
    expect(onboarding(store).welcomeSeen).toBe(true);
  });

  it("storage that throws reads as unset and never throws", () => {
    const o = onboarding({
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
    });
    expect(o.welcomeSeen).toBe(false);
    expect(() => o.passWelcome()).not.toThrow();
  });
});

describe("demoDue", () => {
  const queued = { demoPending: true, demoSeen: false };
  it("opens when queued and unseen", () => expect(demoDue(queued, false)).toBe(true));
  it("waits while a new recovery key is showing or being set up", () => expect(demoDue(queued, true)).toBe(false));
  it("never opens twice", () => expect(demoDue({ demoPending: true, demoSeen: true }, false)).toBe(false));
  it("never opens unqueued, as after a restore", () => expect(demoDue({ demoPending: false, demoSeen: false }, false)).toBe(false));
});
