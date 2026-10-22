// Every way back to /login resets the session's stores. Asserted on the page source, as
// timelineActionAnchor.test.ts does, because the vitest environment is `node`.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";

const source = readFileSync(fileURLToPath(new URL("../../routes/+page.svelte", import.meta.url)), "utf8");

describe("returning to login", () => {
  test("each goto(\"/login\") is preceded by resetSessionState in its own block", () => {
    const parts = source.split('goto("/login")');
    // Guard the guard: a vacuous pass protects nothing.
    expect(parts.length - 1).toBeGreaterThanOrEqual(2);
    for (const before of parts.slice(0, -1)) {
      const nearest = before.slice(-900);
      expect(nearest).toContain("resetSessionState()");
    }
  });
});
