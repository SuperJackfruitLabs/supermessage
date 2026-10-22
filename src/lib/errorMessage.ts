import type { CoreError } from "./ipc";

const GENERIC = "Something went wrong.";

/**
 * What a person is told when a command rejects.
 *
 * The desktop counterpart of iOS `ErrorPresenter.message(for:)`
 * (apple/SupermessageKit/ErrorPresenter.swift). Tauri rejects `invoke` with
 * the serialized `CoreError`, a plain `{kind, message}` object and not an
 * `Error`, so `err instanceof Error` is never the right test for a command
 * failure. Strings are iOS's, word for word, for the kinds it words.
 *
 * Kinds not worded here show the core's own message when it gave one.
 */
export function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message !== "" ? err.message : GENERIC;
  if (typeof err !== "object" || err === null) return GENERIC;

  const { kind, message } = err as Partial<CoreError>;
  const detail = typeof message === "string" ? message : "";
  switch (kind) {
    case "auth":
      return detail === "" ? "Signed out. Sign in again to continue." : detail;
    case "network":
      return detail === "" ? "Can't reach the homeserver." : detail;
    case "store":
      return detail === "" ? "Couldn't read this device's local store." : detail;
    case "protocol":
      return "The homeserver sent something this app didn't understand.";
    case "notReady":
      return "Still connecting.";
    case "roomChanged":
      return "That room is no longer open — nothing was sent.";
    case "refused":
      return detail === "" ? "The homeserver declined." : detail;
    case "reasonTooLong":
      // iOS words this with two numbers that reach the desktop only inside the
      // core's message; the desktop does not parse it. The report sheet stops
      // Send before the limit, so this is a backstop.
      return detail === "" ? GENERIC : detail.charAt(0).toUpperCase() + detail.slice(1);
    default:
      return detail === "" ? GENERIC : detail;
  }
}
