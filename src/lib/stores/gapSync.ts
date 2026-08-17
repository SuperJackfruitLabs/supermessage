// The gap -> resync -> reset sequencing shared by every diff-backed store
// (rooms, timeline). Factored out of the individual stores because the
// ordering hazard it handles is subtle enough that it must be written, and
// tested, exactly once — see `gapSync.test.ts`.
//
// The hazard: `DiffTracker.apply` returning `"gap"` means a resync is
// needed. But while that resync's round trip is in flight, the core keeps
// emitting live diffs on the same channel. Applying those against the
// pre-reset tracker would just rediscover the same gap and trigger another
// resync, forever. So once a resync is in flight, further envelopes on the
// channel are ignored until it lands; the tracker is then hard-reset to the
// returned snapshot, and the next live envelope — guaranteed by the core to
// be `seq + 1` — resumes normally.
//
// The second hazard, and why `accepts` exists: a channel's sequence is
// monotonic per channel *and subject* (spec §4), not per channel alone. The
// timeline channel's subject is the focused room id, and it changes under
// the store while a subscribe round trip is in flight. An envelope — or a
// resync snapshot — belonging to a subject the store is no longer showing
// is not a gap and not a duplicate; it is somebody else's data, and the
// only correct thing to do with it is drop it.

import { DiffTracker, type DiffEnvelope } from "./diff";

export type Unlisten = () => void;

/**
 * A full snapshot for recovering from a gap: the subject it belongs to, the
 * sequence number of the last diff folded into it, and the resulting list.
 *
 * The subject travels with it for the same reason it travels on every
 * envelope — see this module's doc comment and `accepts` below.
 */
export interface Snapshot<T> {
  subject: string;
  seq: number;
  items: T[];
}

export interface GapSyncDeps<T> {
  /**
   * Subscribes to the channel's diff event, calling `onEnvelope` for every
   * envelope received. May return the unlisten function directly or a
   * promise of one (matches `@tauri-apps/api/event`'s `listen`).
   */
  subscribe: (onEnvelope: (env: DiffEnvelope<T>) => void) => Promise<Unlisten> | Unlisten;
  /** Fetches a full snapshot to recover from a gap. */
  resync: () => Promise<Snapshot<T>>;
  /** Called with the new materialized list whenever it changes. */
  onUpdate: (items: T[]) => void;
  /**
   * Whether an envelope (or resync snapshot) carrying `subject` is for the
   * subject this store currently tracks. Anything it rejects is dropped
   * outright — not treated as a gap, not treated as a duplicate.
   *
   * Omit it on single-subject channels (the room list, whose subject is
   * always the empty string), where every envelope is by definition ours.
   */
  accepts?: (subject: string) => boolean;
}

export interface GapSyncController<T> {
  /**
   * Fetch a snapshot now, without waiting for a gap to reveal that one is
   * needed — for a store built after the core has already emitted its opening
   * state. See the implementation for what that costs and why it is not a gap.
   */
  seed: () => Promise<void>;
  /** Stops listening for good (e.g. on logout/teardown). */
  unlisten: () => void;
  /**
   * Hard-resets tracking for a new subscription context — e.g. the
   * timeline store switching which room it's focused on, where the core
   * restarts the sequence at 1. Publishes an empty list immediately.
   *
   * Bumps an internal generation counter so that a resync already in
   * flight when this is called has its result discarded when it lands:
   * without that guard, a slow resync started under the old context could
   * resolve after the reset and roll the new context's state backward to
   * stale data.
   */
  resetForNewSubscription: () => void;
}

export function startGapSync<T>(deps: GapSyncDeps<T>): GapSyncController<T> {
  const tracker = new DiffTracker<T>();
  let resyncing = false;
  let generation = 0;
  let unlistenFn: Unlisten | null = null;
  let stopped = false;

  function publish(): void {
    deps.onUpdate(tracker.items);
  }

  function accepts(subject: string): boolean {
    return deps.accepts === undefined || deps.accepts(subject);
  }

  async function doResync(gen: number): Promise<void> {
    // Belt and suspenders: `handleEnvelope` never calls this while
    // `resyncing` is already true, but guarding here too means this
    // function is safe to call from anywhere without relying on that.
    if (resyncing) return;
    resyncing = true;
    try {
      const snapshot = await deps.resync();
      // A newer subscription context has started since this resync was
      // issued (e.g. the user switched rooms) — its result belongs to a
      // context that no longer exists and must not clobber the new one.
      if (stopped || gen !== generation) return;
      // Belt and braces over the generation check above: the core serves a
      // resync out of whichever subscription is *currently* installed, which
      // during a room switch is still the previous room's. Its own
      // generation may well match ours, so the subject is the only thing
      // that can tell us this snapshot is not ours.
      if (!accepts(snapshot.subject)) return;
      tracker.reset(snapshot.items, snapshot.seq);
      publish();
    } finally {
      resyncing = false;
    }
  }

  function handleEnvelope(env: DiffEnvelope<T>): void {
    if (stopped) return;
    // Somebody else's subject — the previous room's stream, still emitting
    // while this store's subscribe round trip is in flight. Dropping it is
    // the whole point: treating it as a gap would resync off that same
    // previous room and install its messages here.
    if (!accepts(env.subject)) return;
    // A resync is already in flight: ignore further envelopes until it
    // lands and resets state (see this module's doc comment).
    if (resyncing) return;

    const result = tracker.apply(env);
    if (result === "gap") {
      void doResync(generation);
      return;
    }
    publish();
  }

  Promise.resolve(deps.subscribe(handleEnvelope))
    .then((fn) => {
      if (stopped) fn();
      else unlistenFn = fn;
    })
    .catch((err: unknown) => {
      console.error("gapSync: failed to subscribe to diff channel", err);
    });

  return {
    /**
     * Seed from a snapshot without waiting for a gap to reveal that one is
     * needed.
     *
     * The channel only speaks when something *changes*. A store created after
     * the core has already emitted its opening state — a webview reload, or
     * vite replacing the module graph in development — therefore starts empty
     * and stays empty until the next change happens along, which in a quiet
     * account can be minutes. It is not a gap, because no envelope ever
     * arrived to be out of sequence with; the tracker is simply at zero and
     * nothing will tell it otherwise.
     *
     * Observed twice within ten minutes while working on the running app on
     * 2026-08-17: reload the webview mid-session and the roster is empty with
     * a perfectly healthy core behind it. Same shape as the connection
     * indicator's, fixed the same morning — a channel that only pushes needs
     * something that also asks.
     *
     * Goes through the same path as a gap recovery, so the in-flight guard,
     * the generation check and the subject check all apply unchanged.
     */
    async seed(): Promise<void> {
      await doResync(generation);
    },
    unlisten(): void {
      stopped = true;
      unlistenFn?.();
    },
    resetForNewSubscription(): void {
      generation += 1;
      tracker.reset([], 0);
      publish();
    },
  };
}
