// Tracks the core's connection health (`sm://connection`) for the UI's
// connection indicator. No diff/gap machinery here — the event carries the
// latest state directly, not an incremental patch, so there's nothing to
// track a sequence number against.

import { onConnection as defaultOnConnection, type ConnectionPayload, type ConnectionState } from "$lib/ipc";

export interface ConnectionStoreDeps {
  onConnection: typeof defaultOnConnection;
}

const defaultDeps: ConnectionStoreDeps = { onConnection: defaultOnConnection };

export function createConnectionStore(deps: ConnectionStoreDeps = defaultDeps) {
  // "offline" until the first event arrives, which is also the state the
  // core reports before login starts syncing — a reasonable default rather
  // than a lie.
  let state = $state<ConnectionState>("offline");
  let message = $state<string | null>(null);

  deps.onConnection((payload: ConnectionPayload) => {
    state = payload.state;
    message = payload.message;
  }).catch((err: unknown) => {
    console.error("connectionStore: failed to subscribe to connection events", err);
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
