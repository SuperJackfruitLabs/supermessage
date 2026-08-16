// What the timeline actually renders.
//
// The bug this exists for is invisible to every test underneath it: the events
// are in the room, the projection is right, the diff stream carries them — and
// the screen shows an empty bubble where a message should be, or blanks and
// refills and comes back shorter. Reading the rendered rows is the only place
// that evidence lives.
//
// It asserts nothing about *which* messages are present: this runs against
// whatever the reader's own account can see, so the invariants have to hold for
// any room. A message row with no text, and a row with text but no height, are
// both wrong whatever the conversation says.
//
// `ROOM_NAME` picks the room by name (substring, case-insensitive); without it
// the first row in the roster is used.

import { writeFileSync } from "node:fs";

interface RenderedRow {
  text: string;
  height: number;
}

/** Where the full report lands, because stdout gets truncated by whoever runs this. */
const REPORT = "e2e/last-run.json";

async function readRows(): Promise<RenderedRow[]> {
  const rows = await $$('[data-testid="timeline-row"]');
  const report: RenderedRow[] = [];
  for (const row of rows) {
    report.push({
      text: (await row.getText()).trim(),
      height: (await row.getSize()).height,
    });
  }
  return report;
}

/**
 * The two faults the screenshot showed, as assertions.
 *
 * WebdriverIO's `expect` takes one argument — no message parameter — so the
 * offending rows are put in the assertion itself. That reads better anyway:
 * a failure says which row was empty rather than merely that one was.
 */
function check(rows: RenderedRow[], when: string): void {
  const empty = rows
    .filter((row) => row.text === "" && row.height > 0)
    .map((row) => `${when}: a row ${row.height}px tall with no text in it`);
  expect(empty).toEqual([]);

  const collapsed = rows
    .filter((row) => row.text !== "" && row.height < 8)
    .map((row) => `${when}: "${row.text.slice(0, 40)}" rendered ${row.height}px tall`);
  expect(collapsed).toEqual([]);
}

describe("the timeline as it is drawn", () => {
  before(async () => {
    // The app restores a session from the keyring and then syncs. Nothing on
    // screen means anything until the roster has arrived.
    await browser.waitUntil(
      async () => (await $$('nav[aria-label="Rooms"] button')).length > 0,
      {
        timeout: 90_000,
        timeoutMsg: "the roster never populated — is this account signed in?",
      },
    );
  });

  it("draws every message with text in it, and every row with height", async () => {
    const wanted = process.env.ROOM_NAME?.toLowerCase();
    const rooms = await $$('nav[aria-label="Rooms"] button');

    let chosen = rooms[0];
    if (wanted) {
      for (const room of rooms) {
        if ((await room.getText()).toLowerCase().includes(wanted)) {
          chosen = room;
          break;
        }
      }
    }
    const roomLabel = (await chosen.getText()).split("\n")[0];
    await chosen.click();

    // The timeline mounts, seeds, and then back-paginates. What matters is the
    // settled state, not the first frame.
    await browser.pause(5_000);
    const settled = await readRows();

    // Then a second look, after the window in which a gappy sync would clear
    // and rebuild the list — the transition that produced the screenshot.
    await browser.pause(25_000);
    const later = await readRows();

    writeFileSync(
      REPORT,
      JSON.stringify({ room: roomLabel, settled, later }, null, 2),
    );

    check(settled, "on open");
    check(later, "30s later");

    // A room that empties out between the two looks exactly like the reported
    // "previous messages got hidden". Compared as a labelled pair so a failure
    // names the room rather than only reporting that a number shrank.
    expect({ room: roomLabel, rows: later.length >= settled.length }).toEqual({
      room: roomLabel,
      rows: true,
    });
  });
});
