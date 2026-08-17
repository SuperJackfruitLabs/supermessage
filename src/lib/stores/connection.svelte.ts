// Tracks the core's connection health (`sm://connection`) for the UI's
// connection indicator. No diff/gap machinery here — the event carries the
// latest state directly, not an incremental patch, so there's nothing to
// track a sequence number against.
//
// It both **asks and listens**, and needs to do both. The event fires on
// transitions, and a healthy core transitions to `Running` once and then
// stops — so a store created after that moment (the webview reloaded, or
// vite replaced the module graph) would sit at its `offline` default over a
// live connection, with nothing ever arriving to correct it. Asking on
// startup is what makes the indicator right for a session that was already
// under way. See `connection.test.ts`.

import {
  connectionState as defaultConnectionState,
  onConnection as defaultOnConnection,
  type ConnectionPayload,
  type ConnectionState,
} from "$lib/ipc";

export interface ConnectionStoreDeps {
  onConnection: typeof defaultOnConnection;
  connectionState: typeof defaultConnectionState;
}

const defaultDeps: ConnectionStoreDeps = {
  onConnection: defaultOnConnection,
  connectionState: defaultConnectionState,
};

export function createConnectionStore(deps: ConnectionStoreDeps = defaultDeps) {
  // "offline" until it knows better, which is also the state the core
  // reports before login starts syncing — a reasonable default rather than
  // a lie.
  let state = $state<ConnectionState>("offline");
  let message = $state<string | null>(null);
  // Whether anything has spoken since startup. The startup query's answer is
  // a snapshot of the moment it was asked, so once an event has landed the
  // answer is older news and must not be applied — the connection could have
  // dropped in exactly that window, and reviving it in the UI is the failure
  // this whole store exists to prevent.
  let heard = false;

  function adopt(payload: ConnectionPayload): void {
    heard = true;
    state = payload.state;
    message = payload.message;
  }

  // Subscribed before asking, so an event that fires during the round trip
  // is captured rather than raced past.
  deps.onConnection(adopt).catch((err: unknown) => {
    console.error("connectionStore: failed to subscribe to connection events", err);
  });

  deps
    .connectionState()
    .then((payload) => {
      if (!heard) adopt(payload);
    })
    .catch((err: unknown) => {
      // No session yet is the ordinary case at launch, not a fault: the
      // login screen is what the reader sees, and `offline` is true.
      console.error("connectionStore: failed to read the current connection state", err);
    });

  return {
    get state(): ConnectionState {
      return state;
    },
    get message(): string | null {
      return message;
    },
  };
}

export const connectionStore = createConnectionStore();
