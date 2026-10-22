// The live appearance preferences: persisted, applied to <html>, and (for
// text size) to the webview. Decisions live in `$lib/appearance`.
import {
  appearanceAttributes,
  applyAttributes,
  readPrefs,
  writePrefs,
  zoomFor,
  type AppearancePrefs,
} from "$lib/appearance";
import { setTextZoom } from "$lib/ipc";

const DARK_QUERY = "(prefers-color-scheme: dark)";

function storage(): Storage | null {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    return null;
  }
}

function createAppearanceStore() {
  let prefs = $state<AppearancePrefs>(readPrefs(storage()));
  let lastZoom: number | null = null;

  const systemDark = () =>
    typeof matchMedia === "function" && matchMedia(DARK_QUERY).matches;

  function apply() {
    applyAttributes(document.documentElement, appearanceAttributes(prefs, systemDark()));
    const zoom = zoomFor(prefs.textSize);
    if (zoom !== lastZoom) {
      lastZoom = zoom;
      // Outside Tauri (Storybook, a plain browser) there is no webview to zoom.
      void setTextZoom(zoom).catch(() => {});
    }
  }

  return {
    get prefs(): AppearancePrefs {
      return prefs;
    },
    update(patch: Partial<AppearancePrefs>): void {
      prefs = { ...prefs, ...patch };
      writePrefs(storage(), prefs);
      apply();
    },
    /** Apply now and follow the OS scheme. Returns the teardown. */
    install(): () => void {
      prefs = readPrefs(storage());
      apply();
      if (typeof matchMedia !== "function") return () => {};
      const mq = matchMedia(DARK_QUERY);
      mq.addEventListener("change", apply);
      return () => mq.removeEventListener("change", apply);
    },
  };
}

export const appearanceStore = createAppearanceStore();
