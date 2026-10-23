// The microphone and encoder behind `Capture`: `getUserMedia`, a `MediaRecorder` producing
// Ogg/Opus (what the core sends as it is, `C/attachments.rs:910-922`), and an `AnalyserNode`
// for the input level. WebM is not recorded: it would need the core's remux (CP4).

import type { VoiceRecordingSpec } from "$lib/ipc";
import { SILENCE_DB, type Capture } from "./voiceRecorder";

export const RECORDER_MIME = "audio/ogg;codecs=opus";

/** Whether this webview can record Ogg/Opus at all (WebKitGTK needs GStreamer's plugins). */
export function voiceCaptureSupported(
  env: {
    MediaRecorder?: { isTypeSupported(type: string): boolean };
    mediaDevices?: { getUserMedia?: unknown } | null;
  } = {
    MediaRecorder: (globalThis as { MediaRecorder?: { isTypeSupported(type: string): boolean } }).MediaRecorder,
    mediaDevices: typeof navigator === "undefined" ? null : navigator.mediaDevices,
  },
): boolean {
  return (
    typeof env.MediaRecorder?.isTypeSupported === "function" &&
    typeof env.mediaDevices?.getUserMedia === "function" &&
    env.MediaRecorder.isTypeSupported(RECORDER_MIME)
  );
}

/** Time-domain samples (-1..1) to dBFS. Silence is -160, never `-Infinity`. */
export function rmsToDb(samples: ArrayLike<number>): number {
  if (samples.length === 0) return SILENCE_DB;
  let sum = 0;
  for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
  const rms = Math.sqrt(sum / samples.length);
  if (!(rms > 0)) return SILENCE_DB;
  return Math.max(SILENCE_DB, 20 * Math.log10(rms));
}

export function createMicCapture(spec: VoiceRecordingSpec): Capture {
  let stream: MediaStream | null = null;
  let recorder: MediaRecorder | null = null;
  let context: AudioContext | null = null;
  let analyser: AnalyserNode | null = null;
  let samples: Float32Array | null = null;
  let chunks: Blob[] = [];

  function release(): void {
    stream?.getTracks().forEach((t) => t.stop());
    void context?.close().catch(() => undefined);
    stream = null;
    recorder = null;
    context = null;
    analyser = null;
    samples = null;
  }

  return {
    async start() {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: spec.channels, sampleRate: spec.sampleRate },
      });
      try {
        chunks = [];
        recorder = new MediaRecorder(stream, {
          mimeType: RECORDER_MIME,
          audioBitsPerSecond: spec.bitRate,
        });
        recorder.ondataavailable = (e) => {
          if (e.data.size > 0) chunks.push(e.data);
        };
        context = new AudioContext();
        analyser = context.createAnalyser();
        analyser.fftSize = 1024;
        samples = new Float32Array(analyser.fftSize);
        context.createMediaStreamSource(stream).connect(analyser);
        recorder.start();
      } catch (err) {
        release();
        throw err;
      }
    },

    stop() {
      const active = recorder;
      if (!active) {
        release();
        return Promise.resolve(new Blob([], { type: RECORDER_MIME }));
      }
      return new Promise<Blob>((resolve) => {
        const done = () => {
          const blob = new Blob(chunks, { type: RECORDER_MIME });
          chunks = [];
          release();
          resolve(blob);
        };
        if (active.state === "inactive") done();
        else {
          active.onstop = done;
          active.stop();
        }
      });
    },

    levelDb() {
      if (!analyser || !samples) return SILENCE_DB;
      analyser.getFloatTimeDomainData(samples);
      return rmsToDb(samples);
    },
  };
}
