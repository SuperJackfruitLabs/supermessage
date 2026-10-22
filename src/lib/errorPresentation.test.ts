/**
 * Every panel words a failed command through `errorMessage`.
 *
 * Tauri rejects `invoke` with the plain `{kind, message}` object, so
 * `String(err)` prints `[object Object]`, and `err instanceof Error` is never
 * true for a command failure. Asserted on the source because `vite.config.js`
 * pins `environment: "node"`: components are not mounted in unit tests.
 */
import { describe, expect, test } from "vitest";

const sources = import.meta.glob("/src/**/*.svelte", {
  query: "?raw",
  import: "default",
  eager: true,
}) as Record<string, string>;

function offenders(pattern: RegExp): string[] {
  return Object.entries(sources)
    .filter(([, text]) => pattern.test(text))
    .map(([path]) => path)
    .sort();
}

describe("error presentation", () => {
  test("globSeesTheComponents", () => {
    expect(Object.keys(sources).length).toBeGreaterThan(20);
  });

  test("noPanelStringifiesAnError", () => {
    expect(offenders(/String\(err\)|instanceof Error \? err\.message/)).toEqual([]);
  });

  test("noUndefinedDestructiveUtility", () => {
    expect(offenders(/text-destructive/)).toEqual([]);
  });
});
