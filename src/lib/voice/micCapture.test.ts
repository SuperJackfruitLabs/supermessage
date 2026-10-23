import { describe, expect, it } from "vitest";
import { RECORDER_MIME, rmsToDb, voiceCaptureSupported } from "./micCapture";

describe("rmsToDb", () => {
  it("silence is -160, never -Infinity", () => {
    expect(rmsToDb(new Float32Array(1024))).toBe(-160);
    expect(rmsToDb([])).toBe(-160);
  });
  it("full scale is 0 dB and half scale is about -6 dB", () => {
    expect(rmsToDb([1, -1, 1, -1])).toBeCloseTo(0, 5);
    expect(rmsToDb([0.5, -0.5])).toBeCloseTo(-6.02, 1);
  });
  it("never goes below -160", () => {
    expect(rmsToDb([1e-30])).toBe(-160);
  });
});

describe("voiceCaptureSupported", () => {
  const device = { getUserMedia: () => {} };
  it("needs MediaRecorder, getUserMedia and Ogg/Opus", () => {
    const ogg = { isTypeSupported: (t: string) => t === RECORDER_MIME };
    expect(voiceCaptureSupported({ MediaRecorder: ogg, mediaDevices: device })).toBe(true);
    expect(voiceCaptureSupported({ MediaRecorder: undefined, mediaDevices: device })).toBe(false);
    expect(voiceCaptureSupported({ MediaRecorder: ogg, mediaDevices: null })).toBe(false);
    expect(voiceCaptureSupported({ MediaRecorder: ogg, mediaDevices: {} })).toBe(false);
  });
  it("WebM/Opus alone is not enough: the core sends Ogg as it is", () => {
    const webm = { isTypeSupported: (t: string) => t.startsWith("audio/webm") };
    expect(voiceCaptureSupported({ MediaRecorder: webm, mediaDevices: device })).toBe(false);
  });
});
