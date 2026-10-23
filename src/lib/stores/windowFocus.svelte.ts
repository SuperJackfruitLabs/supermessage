// Whether the app window has focus: `document.hasFocus()` plus `focus`, `blur` and
// `visibilitychange`, the pattern of `Timeline.svelte`'s own copy. Desktop's "active" (iOS's
// `appActive`, `PlatformCoordinator.swift:214-220`).

export function createWindowFocus(target: Window = window, doc: Document = document) {
  let focused = $state(doc.hasFocus());
  const listeners = new Set<(focused: boolean) => void>();

  function update(): void {
    const next = doc.hasFocus();
    if (next === focused) return;
    focused = next;
    for (const listener of listeners) listener(next);
  }

  return {
    get focused(): boolean {
      return focused;
    },
    /** Starts listening; returns the stop function. */
    start(): () => void {
      update();
      target.addEventListener("focus", update);
      target.addEventListener("blur", update);
      doc.addEventListener("visibilitychange", update);
      return () => {
        target.removeEventListener("focus", update);
        target.removeEventListener("blur", update);
        doc.removeEventListener("visibilitychange", update);
      };
    },
    /** Called with the new value whenever focus changes. Returns the unsubscribe. */
    onChange(listener: (focused: boolean) => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
