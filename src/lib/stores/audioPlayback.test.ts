import { describe, expect, test } from "vitest";
import type { PlayableAudio } from "$lib/ipc";
import {
  choosesOpusInCaf,
  createAudioPlayback,
  decodeBase64,
  type AudioElementLike,
} from "./audioPlayback.svelte";

class FakeAudio implements AudioElementLike {
  src = "";
  currentTime = 0;
  duration = NaN;
  paused = true;
  playRejects: Error | null = null;
  private listeners = new Map<string, (() => void)[]>();
  addEventListener(type: string, listener: () => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }
  emit(type: string): void {
    for (const l of this.listeners.get(type) ?? []) l();
  }
  play(): Promise<void> {
    if (this.playRejects) return Promise.reject(this.playRejects);
    this.paused = false;
    this.emit("playing");
    return Promise.resolve();
  }
  pause(): void {
    if (this.paused) return;
    this.paused = true;
    this.emit("pause");
  }
  load(): void {}
  removeAttribute(name: string): void {
    if (name === "src") this.src = "";
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

const OGG: PlayableAudio = { mimetype: "audio/ogg", dataBase64: btoa("OggS"), durationMs: 7000 };

function harness(opts: { canPlay?: Record<string, string> } = {}) {
  const el = new FakeAudio();
  const fetches: { eventId: string; opusInCaf: boolean; reply: ReturnType<typeof deferred<PlayableAudio | null>> }[] = [];
  const created: string[] = [];
  const revoked: string[] = [];
  const probes: string[] = [];
  let n = 0;
  const playback = createAudioPlayback({
    fetchAudio: (eventId, opusInCaf) => {
      const reply = deferred<PlayableAudio | null>();
      fetches.push({ eventId, opusInCaf, reply });
      return reply.promise;
    },
    createAudio: () => el,
    canPlayType: (type) => {
      probes.push(type);
      return opts.canPlay?.[type] ?? "probably";
    },
    createObjectURL: () => {
      const url = `blob:${++n}`;
      created.push(url);
      return url;
    },
    revokeObjectURL: (url) => revoked.push(url),
  });
  return { el, fetches, created, revoked, probes, playback };
}

/** Lets the awaited fetch and play settle. */
const settle = () => new Promise((r) => setTimeout(r, 0));

describe("which container a voice note is asked for in", () => {
  const engine = (ogg: string, caf: string) => (type: string) => (type.includes("ogg") ? ogg : caf);

  test("an engine that plays Ogg/Opus gets the note's own bytes", () => {
    // WKWebView on macOS 15.5, measured: both "probably".
    expect(choosesOpusInCaf(engine("probably", "probably"))).toBe(false);
    // Chromium / WebView2: Ogg yes, CAF no.
    expect(choosesOpusInCaf(engine("probably", ""))).toBe(false);
  });

  test("only an engine without Ogg that has CAF asks for the remux", () => {
    expect(choosesOpusInCaf(engine("", "maybe"))).toBe(true);
    expect(choosesOpusInCaf(engine("", "probably"))).toBe(true);
  });

  test("an engine with neither is not sent CAF it cannot play either", () => {
    expect(choosesOpusInCaf(engine("", ""))).toBe(false);
  });
});

describe("decoding the core's bytes", () => {
  test("base64 becomes the exact bytes, high ones included", () => {
    expect([...decodeBase64("T2dnUwD/gA==")]).toEqual([0x4f, 0x67, 0x67, 0x53, 0x00, 0xff, 0x80]);
  });
});

describe("the one note playing", () => {
  test("a note goes loading, then playing, from a blob URL", async () => {
    const h = harness();
    const done = h.playback.toggle("$a", 7000);
    expect(h.playback.stateOf("$a").status).toBe("loading");
    h.fetches[0].reply.resolve(OGG);
    await done;
    expect(h.playback.stateOf("$a")).toMatchObject({ status: "playing", durationMs: 7000 });
    expect(h.el.src).toBe("blob:1");
  });

  test("starting a second note stops the first and lets go of its bytes", async () => {
    const h = harness();
    const a = h.playback.toggle("$a", 7000);
    h.fetches[0].reply.resolve(OGG);
    await a;
    const b = h.playback.toggle("$b", 3000);
    expect(h.playback.stateOf("$a").status).toBe("idle");
    expect(h.revoked).toEqual(["blob:1"]);
    h.fetches[1].reply.resolve(OGG);
    await b;
    expect(h.playback.stateOf("$a").status).toBe("idle");
    expect(h.playback.stateOf("$b").status).toBe("playing");
    expect(h.el.src).toBe("blob:2");
  });

  test("bytes that arrive after another note was started are dropped, never made a URL", async () => {
    const h = harness();
    const a = h.playback.toggle("$a", 7000);
    const b = h.playback.toggle("$b", 3000);
    h.fetches[1].reply.resolve(OGG);
    await b;
    h.fetches[0].reply.resolve(OGG);
    await a;
    expect(h.playback.stateOf("$b").status).toBe("playing");
    expect(h.playback.stateOf("$a").status).toBe("idle");
    expect(h.created).toEqual(["blob:1"]);
    expect(h.el.src).toBe("blob:1");
  });

  test("stop silences the note and revokes its URL", async () => {
    const h = harness();
    const a = h.playback.toggle("$a", 7000);
    h.fetches[0].reply.resolve(OGG);
    await a;
    h.playback.stop();
    expect(h.el.paused).toBe(true);
    expect(h.el.src).toBe("");
    expect(h.revoked).toEqual(["blob:1"]);
    expect(h.playback.stateOf("$a").status).toBe("idle");
  });

  test("pausing keeps the place; the clock reads it back", async () => {
    const h = harness();
    const a = h.playback.toggle("$a", 7000);
    h.fetches[0].reply.resolve(OGG);
    await a;
    h.el.currentTime = 3.2;
    h.el.emit("timeupdate");
    await h.playback.toggle("$a", 7000);
    expect(h.playback.stateOf("$a")).toMatchObject({ status: "paused", positionMs: 3200 });
    await h.playback.toggle("$a", 7000);
    expect(h.playback.stateOf("$a").status).toBe("playing");
  });

  test("an element that cannot play the bytes leaves the note in error, until tried again", async () => {
    const h = harness();
    h.el.playRejects = new Error("NotSupportedError");
    const a = h.playback.toggle("$a", 7000);
    h.fetches[0].reply.resolve(OGG);
    await a;
    await settle();
    expect(h.playback.stateOf("$a").status).toBe("error");
    expect(h.revoked).toEqual(["blob:1"]);
    h.el.playRejects = null;
    void h.playback.toggle("$a", 7000);
    expect(h.playback.stateOf("$a").status).toBe("loading");
  });

  test("a note the core has no bytes for is an error, not a spinner", async () => {
    const h = harness();
    const a = h.playback.toggle("$a", null);
    h.fetches[0].reply.resolve(null);
    await a;
    expect(h.playback.stateOf("$a").status).toBe("error");
  });

  test("the engine is probed once, and asked for CAF only when it lacks Ogg", async () => {
    const h = harness({ canPlay: { 'audio/ogg; codecs="opus"': "", 'audio/x-caf; codecs="opus"': "maybe" } });
    void h.playback.toggle("$a", 7000);
    void h.playback.toggle("$b", 7000);
    expect(h.fetches.map((f) => f.opusInCaf)).toEqual([true, true]);
    expect(h.probes).toHaveLength(2);
  });

  test("ending returns the note to rest", async () => {
    const h = harness();
    const a = h.playback.toggle("$a", 7000);
    h.fetches[0].reply.resolve(OGG);
    await a;
    h.el.currentTime = 7;
    h.el.emit("timeupdate");
    h.el.emit("ended");
    expect(h.playback.stateOf("$a")).toMatchObject({ status: "idle", positionMs: 0 });
  });

  test("seeking a note that is not playing starts it at that point", async () => {
    const h = harness();
    const a = h.playback.seek("$a", 0.5, 7000);
    h.fetches[0].reply.resolve(OGG);
    await a;
    h.el.duration = 7;
    h.el.emit("loadedmetadata");
    expect(h.el.currentTime).toBe(3.5);
    await h.playback.seek("$a", 0.25, 7000);
    expect(h.el.currentTime).toBe(1.75);
    expect(h.playback.stateOf("$a").positionMs).toBe(1750);
  });
});
