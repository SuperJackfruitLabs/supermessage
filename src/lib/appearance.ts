// Appearance preferences: mode, dark style, accent and text size.
//
// The palettes live in `tokens.css` (generated from `design/tokens.toml`) and
// are selected by two attributes on <html>: `data-appearance` and
// `data-accent`. This module decides which attributes a preference set
// produces; it holds no colour. Mirrors iOS `Appearance.swift`, with one
// addition: text size (owner decision O3), applied as webview zoom.

export type AppearanceMode = "system" | "light" | "dark";
export type DarkStyle = "tinted" | "black";
export type Accent = "" | "blue" | "graphite" | "green" | "pink" | "teal";
export type TextSize = "default" | "large" | "larger" | "largest";

export interface AppearancePrefs {
  mode: AppearanceMode;
  darkStyle: DarkStyle;
  accent: Accent;
  textSize: TextSize;
}

export const DEFAULT_PREFS: AppearancePrefs = {
  mode: "system",
  darkStyle: "tinted",
  accent: "",
  textSize: "default",
};

export const MODES: readonly AppearanceMode[] = ["system", "light", "dark"];
export const DARK_STYLES: readonly DarkStyle[] = ["tinted", "black"];
export const ACCENTS: readonly Accent[] = ["", "blue", "graphite", "green", "pink", "teal"];
export const TEXT_SIZES: readonly TextSize[] = ["default", "large", "larger", "largest"];

export const MODE_LABELS: Record<AppearanceMode, string> = {
  system: "System",
  light: "Light",
  dark: "Dark",
};
export const DARK_STYLE_LABELS: Record<DarkStyle, string> = {
  tinted: "Tinted",
  black: "Black",
};
export const ACCENT_LABELS: Record<Accent, string> = {
  "": "Violet",
  blue: "Blue",
  graphite: "Graphite",
  green: "Green",
  pink: "Pink",
  teal: "Teal",
};
export const TEXT_SIZE_LABELS: Record<TextSize, string> = {
  default: "Default",
  large: "Large",
  larger: "Larger",
  largest: "Largest",
};

/** Storage keys; the first three are iOS's. */
export const STORAGE_KEYS = {
  mode: "appearance.mode",
  darkStyle: "appearance.darkStyle",
  accent: "appearance.accent",
  textSize: "appearance.textSize",
} as const;

export interface AppearanceAttributes {
  appearance: "light" | "dark" | "black" | null;
  accent: Exclude<Accent, ""> | null;
}

/**
 * Which attributes to put on <html>. `appearance: null` means "leave it to
 * `prefers-color-scheme`", which the token sheet already follows.
 */
export function appearanceAttributes(
  prefs: AppearancePrefs,
  systemDark: boolean,
): AppearanceAttributes {
  let appearance: AppearanceAttributes["appearance"];
  switch (prefs.mode) {
    case "light":
      appearance = "light";
      break;
    case "dark":
      appearance = prefs.darkStyle === "black" ? "black" : "dark";
      break;
    default:
      appearance = systemDark && prefs.darkStyle === "black" ? "black" : null;
  }
  return { appearance, accent: prefs.accent === "" ? null : prefs.accent };
}

const ZOOM: Record<TextSize, number> = {
  default: 1,
  large: 1.15,
  larger: 1.3,
  largest: 1.5,
};

/** Webview zoom factor for a text size (O3: 100 / 115 / 130 / 150 %). */
export function zoomFor(size: TextSize): number {
  return ZOOM[size];
}

function pick<T extends string>(raw: string | null | undefined, allowed: readonly T[], fallback: T): T {
  return allowed.includes(raw as T) ? (raw as T) : fallback;
}

/** Read stored preferences; anything unknown or missing falls back per field. */
export function readPrefs(storage: Pick<Storage, "getItem"> | null): AppearancePrefs {
  const get = (key: string): string | null => {
    try {
      return storage?.getItem(key) ?? null;
    } catch {
      return null;
    }
  };
  return {
    mode: pick(get(STORAGE_KEYS.mode), MODES, DEFAULT_PREFS.mode),
    darkStyle: pick(get(STORAGE_KEYS.darkStyle), DARK_STYLES, DEFAULT_PREFS.darkStyle),
    accent: pick(get(STORAGE_KEYS.accent), ACCENTS, DEFAULT_PREFS.accent),
    textSize: pick(get(STORAGE_KEYS.textSize), TEXT_SIZES, DEFAULT_PREFS.textSize),
  };
}

/** Write every field; a storage that throws (private mode, quota) is ignored. */
export function writePrefs(storage: Pick<Storage, "setItem"> | null, prefs: AppearancePrefs): void {
  for (const key of Object.keys(STORAGE_KEYS) as (keyof typeof STORAGE_KEYS)[]) {
    try {
      storage?.setItem(STORAGE_KEYS[key], prefs[key]);
    } catch {
      // Not persisted this time; the in-memory choice still applies.
    }
  }
}

/** Set or remove the two attributes on `root`. */
export function applyAttributes(
  root: Pick<Element, "setAttribute" | "removeAttribute">,
  attrs: AppearanceAttributes,
): void {
  if (attrs.appearance) root.setAttribute("data-appearance", attrs.appearance);
  else root.removeAttribute("data-appearance");
  if (attrs.accent) root.setAttribute("data-accent", attrs.accent);
  else root.removeAttribute("data-accent");
}
