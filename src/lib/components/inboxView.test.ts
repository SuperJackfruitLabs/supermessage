import { describe, expect, it } from "vitest";
import type { NeedsYouInbox, RosterRow } from "$lib/ipc";
import { roomRow, roomSummary } from "$lib/fixtures/rooms";
import { inboxSections, isCaughtUp } from "./inboxView";

function entry(id: string): RosterRow {
  return { row: roomRow({ room: roomSummary({ id, name: id }) }), state: "needsYou", describesAgent: true };
}

function inbox(decisions: string[], invitations: string[]): NeedsYouInbox {
  return {
    decisions: decisions.map(entry),
    invitations: invitations.map(entry),
    count: decisions.length + invitations.length,
    badgeCount: decisions.length,
  };
}

describe("inboxSections", () => {
  it("decisions then invitations, each counted", () => {
    const sections = inboxSections(inbox(["!a", "!b"], ["!c"]));
    expect(sections.map((s) => [s.id, s.title, s.count])).toEqual([
      ["decisions", "Waiting on you", 2],
      ["invitations", "Invitations", 1],
    ]);
    expect(sections[0]!.rows.map((r) => r.row.room.id)).toEqual(["!a", "!b"]);
  });

  it("a section with no rows is left out", () => {
    expect(inboxSections(inbox([], ["!c"])).map((s) => s.id)).toEqual(["invitations"]);
    expect(inboxSections(inbox(["!a"], [])).map((s) => s.id)).toEqual(["decisions"]);
  });

  it("an empty inbox is caught up", () => {
    expect(isCaughtUp(inbox([], []))).toBe(true);
    expect(inboxSections(inbox([], []))).toEqual([]);
    expect(isCaughtUp(inbox([], ["!c"]))).toBe(false);
  });

  it("a row that stops needing you leaves", () => {
    expect(inboxSections(inbox(["!a", "!b"], [])).flatMap((s) => s.rows).map((r) => r.row.room.id)).toContain("!a");
    const next = inbox(["!b"], []);
    expect(inboxSections(next).flatMap((s) => s.rows).map((r) => r.row.room.id)).not.toContain("!a");
    const none = inbox([], []);
    expect(inboxSections(none).flatMap((s) => s.rows)).toEqual([]);
    expect(isCaughtUp(none)).toBe(true);
  });
});
