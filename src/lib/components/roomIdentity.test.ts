// The parse moved to `core::room_identity`, with its 16 cases. What is
// left here is `relativeTime`, which reads a clock and stays on this side.
import { describe, expect, it } from "vitest";
import { relativeTime, rowTime } from "./roomIdentity";



describe("rowTime", () => {
  it("gives an invitation no time, since the SDK's is when it was noticed", () => {
    // matrix-sdk stamps a stripped invite with `now()` for sorting
    // (`latest_event/builder.rs`). On 2026-10-03 a fresh sign-in showed all
    // eleven invitations as "now", then "1m", "5m" — the age of the session.
    expect(rowTime(Date.UTC(2026, 9, 3), Date.UTC(2026, 9, 3) + 60_000, true)).toBeNull();
  });

  it("gives any other room its relative time", () => {
    const now = Date.UTC(2026, 9, 3);
    expect(rowTime(now - 60_000, now, false)).toBe(relativeTime(now - 60_000, now));
  });
});

describe("relativeTime", () => {
  const now = Date.UTC(2026, 7, 13, 12, 0, 0);

  it("returns null with no timestamp", () => {
    expect(relativeTime(null, now)).toBeNull();
  });

  it("reads the recent past in coarsening units", () => {
    expect(relativeTime(now - 20_000, now)).toBe("now");
    expect(relativeTime(now - 4 * 60_000, now)).toBe("4m");
    expect(relativeTime(now - 2 * 3_600_000, now)).toBe("2h");
    expect(relativeTime(now - 3 * 86_400_000, now)).toBe("3d");
  });

  it("falls back to a date beyond a week", () => {
    expect(relativeTime(now - 30 * 86_400_000, now)).toMatch(/\d/);
    expect(relativeTime(now - 30 * 86_400_000, now)).not.toMatch(/[dhm]$/);
  });

  it("does not print a negative age for a clock-skewed future timestamp", () => {
    expect(relativeTime(now + 60_000, now)).toBe("now");
  });
});
