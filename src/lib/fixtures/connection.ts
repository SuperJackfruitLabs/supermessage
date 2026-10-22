import type { ConnectionState } from "$lib/ipc";

/**
 * What the connection line can be asked to show.
 *
 * `ConnectionState` is a four-member union — `offline`, `syncing`, `live`,
 * `error` — and the line shows three of them and deliberately shows
 * *nothing* for the fourth. All four are here, including `live`, because a
 * line that appears when the connection is fine is the failure mode worth
 * being able to look at.
 */
export interface ConnectionScenario {
  state: ConnectionState;
  message: string | null;
}

export function connection(overrides: Partial<ConnectionScenario> = {}): ConnectionScenario {
  return { state: "live", message: null, ...overrides };
}

/** Nothing renders. The state the app is in almost all of the time. */
export const connectionLive = connection({ state: "live" });

export const connectionOffline = connection({ state: "offline" });

export const connectionSyncing = connection({ state: "syncing" });

/**
 * The one state that uses `--color-danger`, and the only one that does.
 *
 * State is carried by the label text as well as the colour, never by colour
 * alone — so this scenario is also how you check that the text still says
 * which state it is when the hue is taken away.
 */
export const connectionError = connection({
  state: "error",
  message: "the homeserver closed the connection",
});
