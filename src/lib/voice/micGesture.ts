// The mic button's pointer gesture (`ComposerView.swift:395-440`): a press under 300 ms is a tap
// and records; a press held 300 ms records held, release sends, and sliding 80px left before
// release cancels.

export const HOLD_MS = 300;
export const CANCEL_DISTANCE = -80;

export interface MicGestureHandlers {
  tapped(): void;
  holdBegan(): void;
  /** `cancelled` when the pointer slid 80px or more left. */
  holdEnded(cancelled: boolean): void;
}

export interface Timers {
  set(fn: () => void, ms: number): unknown;
  clear(handle: unknown): void;
}

export const realTimers: Timers = {
  set: (fn, ms) => setTimeout(fn, ms),
  clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
};

export function createMicGesture(handlers: MicGestureHandlers, timers: Timers = realTimers) {
  let pressing = false;
  let holding = false;
  let startX = 0;
  let timer: unknown = null;

  return {
    down(x: number): void {
      if (pressing) return;
      pressing = true;
      startX = x;
      timer = timers.set(() => {
        timer = null;
        if (!pressing) return;
        holding = true;
        handlers.holdBegan();
      }, HOLD_MS);
    },
    up(x: number): void {
      if (!pressing) return;
      if (timer !== null) timers.clear(timer);
      timer = null;
      pressing = false;
      if (holding) {
        holding = false;
        handlers.holdEnded(x - startX < CANCEL_DISTANCE);
      } else {
        handlers.tapped();
      }
    },
    /** The pointer was taken away (a lost capture): nothing was tapped, and a hold is cancelled. */
    abort(): void {
      if (!pressing) return;
      if (timer !== null) timers.clear(timer);
      timer = null;
      pressing = false;
      if (holding) {
        holding = false;
        handlers.holdEnded(true);
      }
    },
  };
}
