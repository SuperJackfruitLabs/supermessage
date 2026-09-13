import type { ConnectionState } from "$lib/ipc";

/**
 * What the connection banner can be asked to show.
 *
 * `ConnectionState` is a four-member union — `offline`, `syncing`, `live`,
 * `error` — and the banner renders three of them and deliberately renders
 * *nothing* for the fourth. All four are here, including `live`, because a
 * banner that appears when the connection is fine is the failure mode worth
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

/**
 * A core message long enough to test the strip's single-line geometry.
 *
 * The banner is `h-6` — 24px, one line — and the message comes from the
 * core, so its length is not something this app controls.
 */
export const connectionErrorLongMessage = connection({
  state: "error",
  message:
    "the homeserver closed the connection while a sliding-sync request was " +
    "in flight and the retry budget for this session is exhausted",
});
