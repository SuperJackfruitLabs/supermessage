// "Keep running in the tray when the window is closed" (parity M7, Q6): off by default, kept on
// this device in `localStorage` like the appearance preferences (it belongs to the device, not the
// account, so sign-out leaves it alone), and handed to Rust at start, on every change and each
// time Account opens. Rust answers whether a tray can be seen (it exists and, on Linux, the session
// bus has a StatusNotifier watcher to show it); with none the setting is unavailable there and
// closing quits.

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
  // Only the newest ask's answer lands: the tray's state may have changed between two.
  let asked = 0;

  async function send(): Promise<void> {
    const mine = ++asked;
    try {
      const answer = await deps.send(keepRunning);
      if (mine === asked) available = answer;
    } catch (e) {
      // Storybook or a plain browser: no host to tell.
      if (mine === asked) available = false;
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
    /** The setting is being shown: ask Rust again whether the tray can be seen. */
    refresh(): Promise<void> {
      return send();
    },
  };
}

export const closeBehaviour = createCloseBehaviour();
