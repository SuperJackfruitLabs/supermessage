// The Needs you count in the window title (parity M6, G4). The count is the core's
// `badgeCount` (decisions only; the number behind the sidebar badge), read from the
// inbox the page already holds, never recounted here. Rust is asked only when the
// count changes, not on every timeline delta.

import { setAttention as defaultSetAttention, type NeedsYouInbox } from "$lib/ipc";

export interface AttentionDeps {
  setAttention: (needsYou: number) => Promise<void>;
}

export function createAttention(deps: AttentionDeps = { setAttention: defaultSetAttention }) {
  let shown = 0;

  function show(count: number): void {
    shown = count;
    void deps.setAttention(count).catch((e) => console.error("set attention failed", e));
  }

  return {
    /** Follows the inbox: a null inbox (nothing read yet) counts as zero. */
    follow(inbox: NeedsYouInbox | null): void {
      const count = inbox?.badgeCount ?? 0;
      if (count !== shown) show(count);
    },
    /** Sign-out or account switch: back to the plain title, whatever was showing. */
    reset(): void {
      show(0);
    },
  };
}

export const attention = createAttention();
