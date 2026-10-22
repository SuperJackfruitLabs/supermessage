import type { CoreError } from "$lib/ipc";

/**
 * Maps a `CoreError.kind` to copy a user can act on at sign-in. A network
 * failure shows the homeserver's own words when it gave any (as iOS's
 * ErrorPresenter does), else a generic line.
 */
export function loginMessage(err: CoreError): string {
  switch (err.kind) {
    case "auth":
      return "Incorrect username or password.";
    case "network":
      return err.message.trim() !== "" ? err.message : "Can't reach the homeserver.";
    case "store":
      // A missing/locked OS keyring or a saved store that will not open
      // (left from an earlier install, say) — a problem on this machine,
      // not the server and not the password. Say so, then the core's words.
      return `Supermessage could not open what it saved on this computer, so signing in cannot finish. ${err.message}`;
    default:
      return err.message;
  }
}
