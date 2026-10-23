// The voice recorder's state machine: a port of `VoiceRecorder.swift:18-190` over an injected
// capture. The recorder's numbers (sample every 100 ms, at most 60 levels, 600 ms shortest) and
// the waveform maths are the core's (`core::voice_recording`), passed in as `spec` and
// `waveform`; nothing here restates them.

import type { AttachmentSendOutcome, StagedAttachment, VoiceRecordingSpec } from "$lib/ipc";

/** `VoiceRecorder.swift:78-81`. */
export const MIC_REFUSED = "Microphone access is off. Turn it on in Settings to record.";
/** `VoiceRecorder.swift:100`, `:116`. */
export const START_FAILED = "Couldn't start recording.";

/**
 * The webview has no recorder (WebKitGTK without GStreamer's good plugins). *(no iOS copy)*
 * The mic is withdrawn afterwards, since pressing it again could only fail the same way.
 */
export const NOT_SUPPORTED = "Voice messages can't be recorded on this computer.";

/** What silence reads as. JSON turns `-Infinity` into `null`, which the core's `Vec<f32>` refuses. */
export const SILENCE_DB = -160;

/** The microphone and encoder, behind the browser APIs. */
export interface Capture {
  start(): Promise<void>;
  /** Stops and returns the finished recording; its `type` is the recorder's mime type. */
  stop(): Promise<Blob>;
  /** The current input level in dBFS. */
  levelDb(): number;
}

export interface VoiceDeps {
  capture: Capture;
  spec: VoiceRecordingSpec;
  now(): number;
  /** Calls `fn` every `ms` until the returned function is called. */
  every(fn: () => void, ms: number): () => void;
  /** `core::voice_recording::waveform`. */
  waveform(decibels: number[], bins: number): Promise<number[]>;
  stageRecording(roomId: string, mime: string, body: Blob): Promise<StagedAttachment>;
  markVoice(roomId: string, token: string, durationMs: number, waveform: number[]): Promise<void>;
  sendStaged(roomId: string, token: string, caption: string | null): Promise<AttachmentSendOutcome>;
  discard(token: string): void;
  /** A note playing aloud would be recorded into the new one (`ComposerView.swift:204`). */
  stopPlayback(): void;
  /** Called after every change of `phase` or `failure`. */
  onChange?(): void;
}

export type VoicePhase = { kind: "idle" } | { kind: "recording"; startedAt: number; held: boolean };

function finiteDb(db: number): number {
  return Number.isFinite(db) ? db : SILENCE_DB;
}

export function createVoiceRecorder(deps: VoiceDeps) {
  let phase: VoicePhase = { kind: "idle" };
  let failure: string | null = null;
  let starting = false;
  let unsupported = false;
  let abandonStart = false;
  let levels: number[] = [];
  let stopMeter: (() => void) | null = null;

  const changed = () => deps.onChange?.();

  function endMeter(): void {
    stopMeter?.();
    stopMeter = null;
  }

  /** Stops the capture and returns the recording with how long it ran and what was sampled. */
  async function finish(): Promise<{ blob: Blob; durationMs: number; decibels: number[] } | null> {
    if (phase.kind !== "recording") return null;
    const durationMs = Math.round(deps.now() - phase.startedAt);
    const decibels = levels;
    endMeter();
    phase = { kind: "idle" };
    levels = [];
    changed();
    const blob = await deps.capture.stop();
    // Shorter than the core's minimum is a slip of the thumb, not a message.
    if (durationMs < deps.spec.shortestMs) return null;
    return { blob, durationMs, decibels };
  }

  return {
    get phase(): VoicePhase {
      return phase;
    },
    get failure(): string | null {
      return failure;
    },
    /** The webview cannot record at all: the composer stops offering the mic. */
    get unsupported(): boolean {
      return unsupported;
    },
    get isRecording(): boolean {
      return phase.kind === "recording";
    },
    get isHeld(): boolean {
      return phase.kind === "recording" && phase.held;
    },

    /** Starts recording; asks for the microphone the first time. `held` when the mic is held down. */
    async start(held: boolean): Promise<boolean> {
      if (phase.kind !== "idle" || starting) return false;
      starting = true;
      abandonStart = false;
      failure = null;
      changed();
      deps.stopPlayback();
      try {
        await deps.capture.start();
      } catch (err) {
        const name = (err as { name?: string } | null)?.name;
        if (name === "NotSupportedError") unsupported = true;
        failure =
          name === "NotAllowedError" || name === "SecurityError"
            ? MIC_REFUSED
            : unsupported
              ? NOT_SUPPORTED
              : START_FAILED;
        starting = false;
        changed();
        return false;
      }
      starting = false;
      if (abandonStart) {
        // The finger lifted during the permission prompt: throw the recording away as it starts.
        abandonStart = false;
        await deps.capture.stop().catch(() => undefined);
        return false;
      }
      levels = [];
      phase = { kind: "recording", startedAt: deps.now(), held };
      stopMeter = deps.every(() => levels.push(finiteDb(deps.capture.levelDb())), deps.spec.meterIntervalMs);
      changed();
      return true;
    },

    /** A hold ended before recording had begun. */
    abandonPendingStart(): void {
      if (starting) abandonStart = true;
    },

    /** Stops and throws the recording away. Stages nothing. */
    async cancel(): Promise<void> {
      if (phase.kind !== "recording") return;
      endMeter();
      phase = { kind: "idle" };
      levels = [];
      changed();
      await deps.capture.stop().catch(() => undefined);
    },

    /**
     * Stops, then stages the recording, marks it as a voice message with the core's waveform and
     * sends it with no text (`ComposerView.swift:304-317`). Resolves `"discarded"` for a slip of
     * the thumb. Rejects when staging or marking fails (a staged file is discarded); a send that
     * fails is the send's own outcome.
     */
    async send(roomId: string): Promise<"discarded" | AttachmentSendOutcome> {
      const recording = await finish();
      if (recording === null) return "discarded";
      const waveform = await deps.waveform(recording.decibels, deps.spec.waveformBins);
      const staged = await deps.stageRecording(roomId, recording.blob.type, recording.blob);
      try {
        await deps.markVoice(roomId, staged.token, recording.durationMs, waveform);
      } catch (err) {
        deps.discard(staged.token);
        throw err;
      }
      return deps.sendStaged(roomId, staged.token, null);
    },
  };
}

export type VoiceRecorder = ReturnType<typeof createVoiceRecorder>;
