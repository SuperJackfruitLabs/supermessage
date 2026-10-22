// A one-shot "this session just signed in" mark.
//
// /login and the main page are separate routes, and recovery is set up on the
// page that owns `RecoveryPanel`. The login route sets the mark after a
// successful `login`; the page consumes it once it is signed in. A session
// restore never sets it, so recovery is only set up after a fresh sign-in —
// the same rule as iOS `Session.swift`.

let fresh = false;

/** Called by the login route after `login` succeeds. */
export function markFreshSignIn(): void {
  fresh = true;
}

/** True once per `markFreshSignIn`; clears the mark as it reads it. */
export function consumeFreshSignIn(): boolean {
  const was = fresh;
  fresh = false;
  return was;
}

/** Drops the mark without acting on it (test isolation). */
export function clearFreshSignIn(): void {
  fresh = false;
}
