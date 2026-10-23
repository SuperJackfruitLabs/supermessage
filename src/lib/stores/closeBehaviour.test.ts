import { describe, expect, it, vi } from "vitest";
import { createCloseBehaviour, KEEP_RUNNING_KEY } from "./closeBehaviour.svelte";

function fakeStorage(initial: Record<string, string> = {}): Storage {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, String(v)),
    removeItem: (k) => void data.delete(k),
    clear: () => data.clear(),
    key: () => null,
    get length() {
      return data.size;
    },
  };
}

const throwing = (): Storage =>
  new Proxy({} as Storage, {
    get() {
      throw new Error("storage is blocked");
    },
  });

function harness(storage: Storage | null, tray = true) {
  const sent: boolean[] = [];
  const setting = createCloseBehaviour({
    storage: () => storage,
    send: async (keepRunning) => {
      sent.push(keepRunning);
      return tray;
    },
  });
  return { sent, setting };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe("closeBehaviour", () => {
  it("the setting defaults to off", async () => {
    const { setting, sent } = harness(fakeStorage());
    expect(setting.keepRunning).toBe(false);
    await setting.install();
    expect(sent).toEqual([false]);
  });

  it("the setting survives a reload", async () => {
    const storage = fakeStorage();
    harness(storage).setting.set(true);
    const { setting, sent } = harness(storage);
    expect(setting.keepRunning).toBe(true);
    await setting.install();
    expect(sent).toEqual([true]);
  });

  it("a storage that throws reads as off, and setting it still reaches Rust", async () => {
    const { setting, sent } = harness(throwing());
    expect(setting.keepRunning).toBe(false);
    setting.set(true);
    await flush();
    expect(setting.keepRunning).toBe(true);
    expect(sent).toEqual([true]);
  });

  it("no storage at all reads as off", () => {
    expect(harness(null).setting.keepRunning).toBe(false);
  });

  it("anything but the stored 'true' reads as off", () => {
    expect(harness(fakeStorage({ [KEEP_RUNNING_KEY]: "yes" })).setting.keepRunning).toBe(false);
  });

  it("the setting is sent to Rust on start and on change", async () => {
    const { setting, sent } = harness(fakeStorage());
    await setting.install();
    setting.set(true);
    setting.set(false);
    await flush();
    expect(sent).toEqual([false, true, false]);
  });

  it("the setting is available only where Rust says there is a tray", async () => {
    const withTray = harness(fakeStorage(), true).setting;
    const without = harness(fakeStorage(), false).setting;
    expect(withTray.available).toBe(false);
    await withTray.install();
    await without.install();
    expect(withTray.available).toBe(true);
    expect(without.available).toBe(false);
  });

  it("outside Tauri (a rejected call) the setting stays unavailable", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const setting = createCloseBehaviour({
      storage: () => fakeStorage(),
      send: async () => {
        throw new Error("no host");
      },
    });
    await setting.install();
    expect(setting.available).toBe(false);
  });
});
