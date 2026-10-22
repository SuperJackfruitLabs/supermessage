/**
 * Which recovery key the panel should be showing, given one may arrive late.
 *
 * `ensureRecovery` can finish while the panel is already open (the reader
 * clicked "Encryption recovery" during sign-in). A key that arrives is shown
 * once, on the key screen, whatever was open, as iOS does
 * (`RootView.swift` drives the sheet from `session.newRecoveryKey`). A key the
 * panel made itself (a reset) is kept when no new one is coming.
 */
export function keyToShow(shown: string | null, incoming: string | null): string | null {
  return incoming !== null && incoming !== "" ? incoming : shown;
}
