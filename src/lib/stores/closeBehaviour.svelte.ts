// "Keep running in the tray when the window is closed" (parity M7, Q6): off by default, kept on
// this device in `localStorage` like the appearance preferences (it belongs to the device, not the
// account, so sign-out leaves it alone), and handed to Rust at start and on every change. Rust
// answers whether a tray exists; with none the setting is unavailable there and closing quits.

import { windowCloseBehaviour } from "$lib/ipc";

export const KEEP_RUNNING_KEY = "closeBehaviour.keepRunning";

export interface CloseBehaviourDeps {
  storage: () => Storage | null;
  /** Tells Rust the choice; resolves to whether Rust can honour it (a tray exists). */
  send: (keepRunning: boolean) => Promise<boolean>;
}

const defaultDeps: CloseBehaviourDeps = {
  storage: () => {
    try {
      return typeof localStorage === "undefined" ? null : localStorage;
    } catch {
      return null;
    }
  },
  send: windowCloseBehaviour,
};

export function createCloseBehaviour(overrides: Partial<CloseBehaviourDeps> = {}) {
  const deps: CloseBehaviourDeps = { ...defaultDeps, ...overrides };

  function read(): boolean {
    try {
      return deps.storage()?.getItem(KEEP_RUNNING_KEY) === "true";
    } catch {
      return false;
    }
  }

  let keepRunning = $state(read());
  // Unknown until Rust answers, and unknown means unavailable.
  let available = $state(false);

  async function send(): Promise<void> {
    try {
      available = await deps.send(keepRunning);
    } catch (e) {
      // Storybook or a plain browser: no host to tell.
      available = false;
      console.error("window close behaviour failed", e);
    }
  }

  return {
    get keepRunning(): boolean {
      return keepRunning;
    },
    get available(): boolean {
      return available;
    },
    set(value: boolean): void {
      keepRunning = value;
      try {
        deps.storage()?.setItem(KEEP_RUNNING_KEY, String(value));
      } catch {
        // Unwritable storage: the choice lasts until the app closes.
      }
      void send();
    },
    /** Reads the stored choice and tells Rust, once, at start. */
    install(): Promise<void> {
      keepRunning = read();
      return send();
    },
  };
}

export const closeBehaviour = createCloseBehaviour();
