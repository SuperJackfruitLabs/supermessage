import type { ConnectionState } from "$lib/ipc";

/**
 * The one connection line's words, iOS's verbatim
 * (`apple/Supermessage/RootView.swift:165-173`, `ConnectionStore.subtitle`).
 *
 * Desktop's `syncing` is iOS's `connecting`. `null` means "say nothing":
 * a status line that always reads "Connected" is noise.
 */
export function connectionSubtitle(state: ConnectionState): string | null {
  switch (state) {
    case "syncing":
      return "Connecting…";
    case "offline":
      return "Offline";
    case "error":
      return "Reconnecting…";
    case "live":
      return null;
  }
}
