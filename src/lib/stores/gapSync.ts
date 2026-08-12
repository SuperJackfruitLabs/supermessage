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
// returned `(seq, items)`, and the next live envelope — guaranteed by the
// core to be `seq + 1` — resumes normally.

import { DiffTracker, type DiffEnvelope } from "./diff";

export type Unlisten = () => void;

export interface GapSyncDeps<T> {
  /**
   * Subscribes to the channel's diff event, calling `onEnvelope` for every
   * envelope received. May return the unlisten function directly or a
   * promise of one (matches `@tauri-apps/api/event`'s `listen`).
   */
  subscribe: (onEnvelope: (env: DiffEnvelope<T>) => void) => Promise<Unlisten> | Unlisten;
  /** Fetches a full snapshot — `[seq, items]` — to recover from a gap. */
  resync: () => Promise<[number, T[]]>;
  /** Called with the new materialized list whenever it changes. */
  onUpdate: (items: T[]) => void;
}

export interface GapSyncController<T> {
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

  async function doResync(gen: number): Promise<void> {
    // Belt and suspenders: `handleEnvelope` never calls this while
    // `resyncing` is already true, but guarding here too means this
    // function is safe to call from anywhere without relying on that.
    if (resyncing) return;
    resyncing = true;
    try {
      const [seq, items] = await deps.resync();
      // A newer subscription context has started since this resync was
      // issued (e.g. the user switched rooms) — its result belongs to a
      // context that no longer exists and must not clobber the new one.
      if (stopped || gen !== generation) return;
      tracker.reset(items, seq);
      publish();
    } finally {
      resyncing = false;
    }
  }

  function handleEnvelope(env: DiffEnvelope<T>): void {
    if (stopped) return;
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
