// Calls with agents (desktop). The "Calls" setting is off by default and kept on this device in
// `localStorage`, like "Keep running in the tray": it belongs to the device, not the account.
// It is handed to Rust at start and on every change; Rust refuses calls while it is off. The
// rest is Rust's `CallSnapshot`, replaced whole by each command's answer and each `sm://call`.

import {
  callDismissNotice,
  callHangUp,
  callSetMuted,
  callStart,
  callsSetEnabled,
  type CallSnapshot,
  type Counterpart,
} from "$lib/ipc";

export const CALLS_ENABLED_KEY = "calls.enabled";

export interface CallsDeps {
  storage: () => Storage | null;
  setEnabled: (enabled: boolean) => Promise<CallSnapshot>;
  start: (roomId: string, agentUserId: string, agentName: string) => Promise<CallSnapshot>;
  hangUp: () => Promise<CallSnapshot>;
  setMuted: (muted: boolean) => Promise<CallSnapshot>;
  dismiss: () => Promise<CallSnapshot>;
}

const defaultDeps: CallsDeps = {
  storage: () => {
    try {
      return typeof localStorage === "undefined" ? null : localStorage;
    } catch {
      return null;
    }
  },
  setEnabled: callsSetEnabled,
  start: callStart,
  hangUp: callHangUp,
  setMuted: callSetMuted,
  dismiss: callDismissNotice,
};

export function createCallsStore(overrides: Partial<CallsDeps> = {}) {
  const deps: CallsDeps = { ...defaultDeps, ...overrides };

  function read(): boolean {
    try {
      return deps.storage()?.getItem(CALLS_ENABLED_KEY) === "true";
    } catch {
      return false;
    }
  }

  let enabled = $state(read());
  let snapshot = $state<CallSnapshot>({ enabled: false, call: null, notice: null, leaving: false });

  async function run(op: () => Promise<CallSnapshot>, what: string): Promise<void> {
    try {
      snapshot = await op();
    } catch (e) {
      // Storybook or a plain browser: no host to ask.
      console.error(`calls: ${what} failed`, e);
    }
  }

  return {
    /** The setting as this device holds it. */
    get enabled(): boolean {
      return enabled;
    },
    get snapshot(): CallSnapshot {
      return snapshot;
    },
    /** Reads the stored choice and tells Rust, once, at start. */
    install(): Promise<void> {
      enabled = read();
      return run(() => deps.setEnabled(enabled), "install");
    },
    set(value: boolean): Promise<void> {
      enabled = value;
      try {
        deps.storage()?.setItem(CALLS_ENABLED_KEY, String(value));
      } catch {
        // Unwritable storage: the choice lasts until the app closes.
      }
      return run(() => deps.setEnabled(value), "setting");
    },
    /** From `sm://call`. */
    apply(next: CallSnapshot): void {
      snapshot = next;
    },
    start(roomId: string, agent: Counterpart): Promise<void> {
      return run(() => deps.start(roomId, agent.userId, agent.name), "start");
    },
    hangUp(): Promise<void> {
      return run(deps.hangUp, "hang up");
    },
    setMuted(muted: boolean): Promise<void> {
      return run(() => deps.setMuted(muted), "mute");
    },
    dismiss(): Promise<void> {
      return run(deps.dismiss, "dismiss");
    },
  };
}

export const callsStore = createCallsStore();
