import { render } from "svelte/server";
import { describe, expect, it } from "vitest";

import ConnectionLine from "./ConnectionLine.svelte";

const html = (props: { state: "live" | "offline" | "syncing" | "error"; message?: string | null }) =>
  render(ConnectionLine, { props }).body;

describe("ConnectionLine", () => {
  it("keeps the status element mounted but empty when live", () => {
    const out = html({ state: "live", message: "detail" });
    expect(out).toContain('role="status"');
    expect(out).not.toContain("detail");
    expect(out).not.toContain("title=");
  });

  it("gives the core's detail to hover and to assistive tech", () => {
    const out = html({ state: "error", message: "the homeserver closed the connection" });
    expect(out).toContain("Reconnecting…");
    expect(out).toContain('title="the homeserver closed the connection"');
    expect(out).toMatch(/<span class="sr-only">[^<]*the homeserver closed the connection/);
  });

  it("says only the word when there is no detail", () => {
    const out = html({ state: "offline" });
    expect(out).toContain("Offline");
    expect(out).not.toContain("sr-only");
  });
});
