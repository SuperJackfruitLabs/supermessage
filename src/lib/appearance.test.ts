import { describe, expect, it } from "vitest";
import {
  applyAttributes,
  appearanceAttributes,
  DEFAULT_PREFS,
  readPrefs,
  writePrefs,
  zoomFor,
  type AppearanceMode,
  type AppearancePrefs,
  type DarkStyle,
} from "./appearance";

const prefs = (over: Partial<AppearancePrefs>): AppearancePrefs => ({ ...DEFAULT_PREFS, ...over });

describe("appearanceAttributes", () => {
  const table: [AppearanceMode, DarkStyle, boolean, string | null][] = [
    ["light", "tinted", false, "light"],
    ["light", "tinted", true, "light"],
    ["light", "black", false, "light"],
    ["light", "black", true, "light"],
    ["dark", "tinted", false, "dark"],
    ["dark", "tinted", true, "dark"],
    ["dark", "black", false, "black"],
    ["dark", "black", true, "black"],
    ["system", "tinted", false, null],
    ["system", "tinted", true, null],
    ["system", "black", false, null],
    ["system", "black", true, "black"],
  ];
  it.each(table)("%s / %s / systemDark=%s -> %s", (mode, darkStyle, systemDark, want) => {
    expect(appearanceAttributes(prefs({ mode, darkStyle }), systemDark).appearance).toBe(want);
  });

  it("passes an accent through and maps the house accent to null", () => {
    expect(appearanceAttributes(prefs({ accent: "teal" }), false).accent).toBe("teal");
    expect(appearanceAttributes(prefs({ accent: "" }), false).accent).toBeNull();
  });
});

describe("zoomFor", () => {
  it("is 100 / 115 / 130 / 150 percent", () => {
    expect([zoomFor("default"), zoomFor("large"), zoomFor("larger"), zoomFor("largest")]).toEqual([
      1, 1.15, 1.3, 1.5,
    ]);
  });
});

describe("readPrefs", () => {
  const store = (o: Record<string, string>) => ({ getItem: (k: string) => o[k] ?? null });
  it("defaults without storage", () => {
    expect(readPrefs(null)).toEqual(DEFAULT_PREFS);
  });
  it("falls back per field on unknown values", () => {
    expect(
      readPrefs(store({ "appearance.accent": "magenta", "appearance.mode": "dark", "appearance.textSize": "huge" })),
    ).toEqual(prefs({ mode: "dark" }));
  });
  it("round-trips a full set through writePrefs", () => {
    const data: Record<string, string> = {};
    const want = { mode: "dark", darkStyle: "black", accent: "pink", textSize: "larger" } as const;
    writePrefs({ setItem: (k, v) => void (data[k] = v) }, want);
    expect(data["appearance.darkStyle"]).toBe("black");
    expect(readPrefs(store(data))).toEqual(want);
  });
  it("survives a storage that throws", () => {
    const throwing = {
      getItem: () => {
        throw new Error("denied");
      },
      setItem: () => {
        throw new Error("denied");
      },
    };
    expect(readPrefs(throwing)).toEqual(DEFAULT_PREFS);
    expect(() => writePrefs(throwing, DEFAULT_PREFS)).not.toThrow();
  });
});

describe("applyAttributes", () => {
  it("sets and removes both attributes", () => {
    const attrs = new Map<string, string>();
    const root = {
      setAttribute: (k: string, v: string) => void attrs.set(k, v),
      removeAttribute: (k: string) => void attrs.delete(k),
    };
    applyAttributes(root, { appearance: "black", accent: "green" });
    expect([...attrs]).toEqual([["data-appearance", "black"], ["data-accent", "green"]]);
    applyAttributes(root, { appearance: null, accent: null });
    expect(attrs.size).toBe(0);
  });
});
