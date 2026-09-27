// The one audio message playing, app-wide.
//
// One `<audio>` element, owned here rather than by a row. Two reasons:
//
//   - **One note at a time is structural**, not a convention every player
//     has to honour: there is only one element to play through, so starting
//     a note stops the last.
//   - **The timeline is virtualised.** A row scrolled out of view is
//     unmounted, and a note playing through an element that row owned would
//     stop mid-sentence because the reader scrolled. Here it plays on; the
//     row reads its state back when it returns.
//
// `Timeline.svelte` calls `stop()` when it unmounts — which is what a room
// switch does (`+page.svelte` keys the pane on the room) — so a note never
// plays on under a different room.
//
// Bytes come from the core (`media_audio`), already remuxed for engines that
// read Opus only from CAF; see `choosesOpusInCaf` for how that is probed,
// once. Each note's bytes become a blob URL that is revoked as soon as the
// note stops being the current one.

import { mediaAudio, type PlayableAudio } from "$lib/ipc";
import type { PlayerStatus } from "$lib/components/audioPlayerView";

/** The slice of `HTMLAudioElement` this store drives — a fake in tests. */
export interface AudioElementLike {
  src: string;
  currentTime: number;
  readonly duration: number;
  readonly paused: boolean;
  play(): Promise<void>;
  pause(): void;
  load(): void;
  removeAttribute(name: string): void;
  addEventListener(type: string, listener: () => void): void;
}

export interface AudioPlaybackDeps {
  fetchAudio: (eventId: string, opusInCaf: boolean) => Promise<PlayableAudio | null>;
  createAudio: () => AudioElementLike;
  canPlayType: (type: string) => string;
  createObjectURL: (blob: Blob) => string;
  revokeObjectURL: (url: string) => void;
}

/** One note's player state, as a row draws it. */
export interface NotePlayback {
  status: PlayerStatus;
  positionMs: number;
  /** The length the player learned, when the note has been opened. */
  durationMs: number | null;
}

const IDLE: NotePlayback = Object.freeze({ status: "idle", positionMs: 0, durationMs: null });

/**
 * Whether to ask the core for Opus in CAF rather than Ogg: only when this
 * engine says it cannot play Ogg/Opus and can play CAF/Opus. Anything that
 * plays Ogg gets the note's own bytes untouched.
 */
export function choosesOpusInCaf(canPlayType: (type: string) => string): boolean {
  const ogg = canPlayType('audio/ogg; codecs="opus"');
  const caf = canPlayType('audio/x-caf; codecs="opus"');
  return !ogg && !!caf;
}

/** Base64 to bytes, without a round trip through a `data:` URL. */
export function decodeBase64(data: string): Uint8Array {
  const binary = atob(data);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export function createAudioPlayback(deps: AudioPlaybackDeps) {
  const now = $state({
    eventId: null as string | null,
    status: "idle" as PlayerStatus,
    positionMs: 0,
    durationMs: null as number | null,
  });
  /** Notes that could not be played, until the reader tries again. */
  const failed = $state<Record<string, true>>({});

  let element: AudioElementLike | null = null;
  let objectUrl: string | null = null;
  let opusInCaf: boolean | null = null;
  /** Bumped by every start and stop, so a fetch that lost the race is dropped. */
  let generation = 0;
  /** Where to begin once the element knows the note's length. */
  let pendingStart = 0;

  function audio(): AudioElementLike {
    if (element) return element;
    const el = deps.createAudio();
    el.addEventListener("loadedmetadata", () => {
      if (Number.isFinite(el.duration) && el.duration > 0) {
        now.durationMs ??= Math.round(el.duration * 1000);
      }
      if (pendingStart > 0 && now.durationMs !== null) {
        el.currentTime = (pendingStart * now.durationMs) / 1000;
      }
      pendingStart = 0;
    });
    el.addEventListener("timeupdate", () => {
      if (now.eventId !== null && now.status !== "loading") now.positionMs = el.currentTime * 1000;
    });
    el.addEventListener("playing", () => {
      if (now.eventId !== null) now.status = "playing";
    });
    el.addEventListener("pause", () => {
      if (now.eventId !== null && now.status === "playing") now.status = "paused";
    });
    // Back to rest: the length label again, not "0:07" of a 0:07 note.
    el.addEventListener("ended", release);
    el.addEventListener("error", () => {
      if (now.eventId !== null && objectUrl !== null) fail(now.eventId);
    });
    element = el;
    return el;
  }

  function release(): void {
    generation++;
    pendingStart = 0;
    if (element) {
      element.pause();
      element.removeAttribute("src");
      element.load();
    }
    if (objectUrl !== null) {
      deps.revokeObjectURL(objectUrl);
      objectUrl = null;
    }
    now.eventId = null;
    now.status = "idle";
    now.positionMs = 0;
    now.durationMs = null;
  }

  function fail(eventId: string): void {
    release();
    failed[eventId] = true;
  }

  async function start(eventId: string, at: number, knownDurationMs: number | null): Promise<void> {
    // The element is made, and `release` calls its `load()`, before the first
    // `await`: still inside the click. WebKit blesses an element for later
    // playback by a gesture on that element, and a `play()` that only runs
    // once the bytes are back from the core is outside the click.
    const el = audio();
    release();
    delete failed[eventId];
    const mine = ++generation;
    now.eventId = eventId;
    now.status = "loading";
    now.durationMs = knownDurationMs;
    opusInCaf ??= choosesOpusInCaf(deps.canPlayType);
    let playable: PlayableAudio | null;
    try {
      playable = await deps.fetchAudio(eventId, opusInCaf);
    } catch {
      playable = null;
    }
    // Another note was started, or this one stopped, while the bytes were
    // on their way: they are not wanted any more, and must not become a URL
    // nobody revokes.
    if (mine !== generation) return;
    if (playable === null) return fail(eventId);
    const url = deps.createObjectURL(
      new Blob([decodeBase64(playable.dataBase64)], { type: playable.mimetype }),
    );
    objectUrl = url;
    now.durationMs ??= playable.durationMs;
    pendingStart = at;
    el.src = url;
    try {
      await el.play();
    } catch {
      // NotSupportedError, a decoder that gave up: the same outcome as the
      // element's own `error` event.
      if (mine === generation) fail(eventId);
    }
  }

  return {
    /** What `eventId`'s player shows now. */
    stateOf(eventId: string): NotePlayback {
      if (now.eventId === eventId) {
        return { status: now.status, positionMs: now.positionMs, durationMs: now.durationMs };
      }
      return failed[eventId] ? { ...IDLE, status: "error" } : IDLE;
    },

    /** Play, pause or resume `eventId`; starting it stops whatever else was playing. */
    toggle(eventId: string, knownDurationMs: number | null): Promise<void> {
      if (now.eventId === eventId) {
        if (now.status === "playing") {
          element?.pause();
          now.status = "paused";
        } else if (now.status === "paused" && element) {
          now.status = "playing";
          void element.play().catch(() => fail(eventId));
        } else if (now.status === "loading") {
          release();
        }
        return Promise.resolve();
      }
      return start(eventId, 0, knownDurationMs);
    },

    /** Move `eventId` to `fraction` of its length, starting it there if it was not playing. */
    seek(eventId: string, fraction: number, knownDurationMs: number | null): Promise<void> {
      const f = Math.min(1, Math.max(0, fraction));
      if (now.eventId === eventId && now.status !== "loading" && element && now.durationMs !== null) {
        element.currentTime = (f * now.durationMs) / 1000;
        now.positionMs = f * now.durationMs;
        return Promise.resolve();
      }
      if (now.eventId === eventId) {
        pendingStart = f;
        return Promise.resolve();
      }
      return start(eventId, f, knownDurationMs);
    },

    /** Silence everything and let go of the bytes. */
    stop(): void {
      release();
    },
  };
}

export type AudioPlayback = ReturnType<typeof createAudioPlayback>;

/** The app's one player. Created on first use, so importing it touches no DOM. */
let shared: AudioPlayback | null = null;
export function audioPlayback(): AudioPlayback {
  shared ??= createAudioPlayback({
    fetchAudio: mediaAudio,
    createAudio: () => new Audio(),
    canPlayType: (type) => document.createElement("audio").canPlayType(type),
    createObjectURL: (blob) => URL.createObjectURL(blob),
    revokeObjectURL: (url) => URL.revokeObjectURL(url),
  });
  return shared;
}
