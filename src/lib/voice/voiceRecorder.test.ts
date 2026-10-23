import { afterEach, describe, expect, it, vi } from "vitest";
import type { StagedAttachment, VoiceRecordingSpec } from "$lib/ipc";
import { createMicCapture } from "./micCapture";
import { createVoiceRecorder, MIC_REFUSED, NOT_SUPPORTED, START_FAILED, type Capture, type VoiceDeps } from "./voiceRecorder";

const SPEC: VoiceRecordingSpec = {
  fileStem: "Voice message",
  sampleRate: 48000,
  channels: 1,
  bitRate: 24000,
  meterIntervalMs: 100,
  waveformBins: 60,
  shortestMs: 600,
};
const STAGED = { token: "tok", filename: "Voice message.ogg", sizeBytes: 10, mime: "audio/ogg" } as unknown as StagedAttachment;

function rig(over: Partial<VoiceDeps> = {}, levels: number[] = [-30]) {
  let clock = 1_000;
  const calls: string[] = [];
  let tick: (() => void) | null = null;
  let level = 0;
  const capture: Capture = {
    start: vi.fn(async () => true),
    stop: vi.fn(async () => new Blob([new Uint8Array([1])], { type: "audio/ogg;codecs=opus" })),
    levelDb: vi.fn(() => levels[Math.min(level++, levels.length - 1)]),
  };
  const deps: VoiceDeps = {
    capture,
    spec: SPEC,
    now: () => clock,
    every: (fn) => {
      tick = fn;
      return () => (tick = null);
    },
    waveform: vi.fn(async (db: number[]) => db.map((d) => (d + 50) / 50)),
    // The core's rule, as `voice_long_enough` answers it.
    longEnough: vi.fn(async (ms: number) => ms >= SPEC.shortestMs),
    stageRecording: vi.fn(async () => (calls.push("stage"), STAGED)),
    markVoice: vi.fn(async () => void calls.push("mark")),
    sendStaged: vi.fn(async () => (calls.push("send"), { outcome: "sent" as const })),
    discard: vi.fn(),
    stopPlayback: vi.fn(() => void calls.push("stopPlayback")),
    ...over,
  };
  return {
    deps,
    capture,
    calls,
    advance: (ms: number) => {
      // Sample at the meter's cadence, as the real timer would.
      for (let t = 0; t < ms; t += SPEC.meterIntervalMs) {
        clock += SPEC.meterIntervalMs;
        tick?.();
      }
    },
    rec: createVoiceRecorder(deps),
  };
}

describe("voiceRecorder", () => {
  it("a slip of the thumb is thrown away", async () => {
    const { rec, advance, deps } = rig();
    await rec.start(false);
    advance(500);
    expect(await rec.send("!r:x")).toBe("discarded");
    expect(deps.stageRecording).not.toHaveBeenCalled();
    expect(rec.phase.kind).toBe("idle");
  });

  it("a recording carries its length and the core's waveform", async () => {
    const { rec, advance, deps } = rig({}, [-50, -25, 0]);
    await rec.start(false);
    advance(3000);
    await rec.send("!r:x");
    // 30 samples, cycling -50, -25, 0, 0, 0... (the fake holds its last level)
    const [db, bins] = (deps.waveform as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(bins).toBe(60);
    expect(db.slice(0, 3)).toEqual([-50, -25, 0]);
    expect(db).toHaveLength(30);
    const [room, token, durationMs, wave] = (deps.markVoice as ReturnType<typeof vi.fn>).mock.calls[0];
    expect([room, token, durationMs]).toEqual(["!r:x", "tok", 3000]);
    expect(wave).toEqual(db.map((d: number) => (d + 50) / 50));
  });

  it("silence is sent as -160, never -Infinity", async () => {
    const { rec, advance, deps } = rig({}, [-Infinity, Number.NaN]);
    await rec.start(false);
    advance(1000);
    await rec.send("!r:x");
    const [db] = (deps.waveform as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(db.every((d: number) => d === -160)).toBe(true);
    expect(JSON.parse(JSON.stringify(db)).every((d: unknown) => d === -160)).toBe(true);
  });

  it("a refused microphone says so", async () => {
    const denied = Object.assign(new Error("denied"), { name: "NotAllowedError" });
    const { rec, capture } = rig();
    (capture.start as ReturnType<typeof vi.fn>).mockRejectedValueOnce(denied);
    expect(await rec.start(false)).toBe(false);
    expect(rec.failure).toBe("Microphone access is off. Turn it on in Settings to record.");
    expect(MIC_REFUSED).toBe("Microphone access is off. Turn it on in Settings to record.");
    expect(rec.phase.kind).toBe("idle");
  });

  it("any other start failure says Couldn't start recording.", async () => {
    const { rec, capture } = rig();
    (capture.start as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error("no device"));
    await rec.start(false);
    expect(rec.failure).toBe("Couldn't start recording.");
    expect(START_FAILED).toBe("Couldn't start recording.");
  });

  it("a webview that cannot record says so and the mic is withdrawn", async () => {
    const { rec, capture } = rig();
    (capture.start as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      Object.assign(new Error("The MediaRecorder is unsupported on this platform"), { name: "NotSupportedError" }),
    );
    expect(rec.unsupported).toBe(false);
    await rec.start(false);
    expect(rec.failure).toBe(NOT_SUPPORTED);
    expect(rec.unsupported).toBe(true);
  });

  it("a hold released before recording starts is abandoned", async () => {
    let release!: (began: boolean) => void;
    const { rec, capture } = rig();
    // A capture that started anyway: the recorder must stop it.
    (capture.start as ReturnType<typeof vi.fn>).mockImplementationOnce(
      () => new Promise<boolean>((resolve) => (release = resolve)),
    );
    const started = rec.start(true);
    rec.abandonPendingStart();
    release(true);
    expect(await started).toBe(false);
    expect(rec.phase.kind).toBe("idle");
    expect(capture.stop).toHaveBeenCalled();
  });

  it("discard throws it away and stages nothing", async () => {
    const { rec, advance, deps, capture } = rig();
    await rec.start(false);
    advance(3000);
    await rec.cancel();
    expect(rec.phase.kind).toBe("idle");
    expect(capture.stop).toHaveBeenCalled();
    expect(deps.stageRecording).not.toHaveBeenCalled();
    expect(deps.sendStaged).not.toHaveBeenCalled();
  });

  it("send stages, marks, then sends with no text", async () => {
    const { rec, advance, calls, deps } = rig();
    await rec.start(false);
    advance(2000);
    expect(await rec.send("!r:x")).toEqual({ outcome: "sent" });
    expect(calls).toEqual(["stopPlayback", "stage", "mark", "send"]);
    expect(deps.sendStaged).toHaveBeenCalledWith("!r:x", "tok", null);
    expect(deps.stageRecording).toHaveBeenCalledWith("!r:x", "audio/ogg;codecs=opus", expect.any(Blob));
  });

  it("recording stops playback first", async () => {
    const { rec, calls, capture } = rig();
    (capture.start as ReturnType<typeof vi.fn>).mockImplementationOnce(async () => (calls.push("capture"), true));
    await rec.start(false);
    expect(calls).toEqual(["stopPlayback", "capture"]);
  });

  it("a mark that fails discards the staged file and reports the error", async () => {
    const { rec, advance, deps } = rig({ markVoice: vi.fn(async () => Promise.reject(new Error("x"))) });
    await rec.start(false);
    advance(2000);
    await expect(rec.send("!r:x")).rejects.toThrow("x");
    expect(deps.discard).toHaveBeenCalledWith("tok");
    expect(deps.sendStaged).not.toHaveBeenCalled();
  });

  it("the meter stops when recording ends", async () => {
    const { rec, advance, capture } = rig();
    await rec.start(false);
    advance(1000);
    await rec.cancel();
    const before = (capture.levelDb as ReturnType<typeof vi.fn>).mock.calls.length;
    advance(1000);
    expect((capture.levelDb as ReturnType<typeof vi.fn>).mock.calls.length).toBe(before);
  });

  it("whether it is long enough is the core's answer", async () => {
    const { rec, advance, deps } = rig({ longEnough: vi.fn(async () => false) });
    await rec.start(false);
    advance(3000);
    expect(await rec.send("!r:x")).toBe("discarded");
    expect(deps.longEnough).toHaveBeenCalledWith(3000);
    expect(deps.stageRecording).not.toHaveBeenCalled();
  });

  describe("a start cancelled while the microphone is being granted", () => {
    /** A capture whose grant resolves when told, honouring `isCurrent` as `micCapture` does. */
    function pendingGrant() {
      let grant!: () => void;
      const recorded = vi.fn();
      const start = vi.fn(
        (isCurrent: () => boolean) =>
          new Promise<boolean>((resolve) => {
            grant = () => {
              if (!isCurrent()) return resolve(false);
              recorded();
              resolve(true);
            };
          }),
      );
      return { start, recorded, grant: () => grant() };
    }

    it("never records, and a later start still can", async () => {
      const every = vi.fn(() => () => {});
      const { rec, capture } = rig({ every });
      const pending = pendingGrant();
      (capture.start as ReturnType<typeof vi.fn>).mockImplementationOnce(pending.start);
      const started = rec.start(false);
      await rec.cancel();
      pending.grant();
      expect(await started).toBe(false);
      expect(pending.recorded).not.toHaveBeenCalled();
      expect(rec.phase.kind).toBe("idle");
      expect(rec.failure).toBeNull();
      expect(every).not.toHaveBeenCalled();
      // The room switch is over; the reader records in the new room.
      expect(await rec.start(false)).toBe(true);
      expect(rec.phase.kind).toBe("recording");
    });

    it("a refusal that arrives after it says nothing", async () => {
      let refuse!: () => void;
      const { rec, capture } = rig();
      (capture.start as ReturnType<typeof vi.fn>).mockImplementationOnce(
        () =>
          new Promise<boolean>((_, reject) => {
            refuse = () => reject(Object.assign(new Error("denied"), { name: "NotAllowedError" }));
          }),
      );
      const started = rec.start(false);
      await rec.cancel();
      refuse();
      expect(await started).toBe(false);
      expect(rec.failure).toBeNull();
    });
  });

  describe("over the real micCapture, with a getUserMedia that resolves after cancel", () => {
    afterEach(() => vi.unstubAllGlobals());

    it("stops the tracks at once and never starts a MediaRecorder", async () => {
      const track = { stop: vi.fn() };
      let grant!: () => void;
      vi.stubGlobal("navigator", {
        mediaDevices: {
          getUserMedia: vi.fn(
            () => new Promise((resolve) => (grant = () => resolve({ getTracks: () => [track] }))),
          ),
        },
      });
      const recorderMade = vi.fn();
      vi.stubGlobal(
        "MediaRecorder",
        class {
          constructor() {
            recorderMade();
          }
          start() {}
        },
      );
      vi.stubGlobal(
        "AudioContext",
        class {
          constructor() {
            recorderMade();
          }
        },
      );
      const { deps } = rig();
      const every = vi.fn(() => () => {});
      const rec = createVoiceRecorder({ ...deps, capture: createMicCapture(SPEC), every });

      const started = rec.start(false);
      await rec.cancel();
      grant();

      expect(await started).toBe(false);
      expect(track.stop).toHaveBeenCalledTimes(1);
      expect(recorderMade).not.toHaveBeenCalled();
      expect(every).not.toHaveBeenCalled();
      expect(rec.phase.kind).toBe("idle");
    });
  });
});
