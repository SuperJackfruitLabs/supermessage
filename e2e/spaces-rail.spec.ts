// What the rail is for, checked against the real account.
//
// Two rules that were each tested as pure functions and each shipped wrong
// anyway, because nothing looked at the surface they produce:
//
// 1. **A space is never a roster row.** Invitations to spaces used to land
//    among the conversations — two node spaces among forty agent rooms.
// 2. **Selecting a space shows the rooms filed under it.** The graph counted
//    only *joined* rooms, so a freshly-provisioned fleet — where every agent
//    room is still an invitation — made every space report zero children and
//    filter to an empty roster.
//
// The DOM is read with `browser.execute` rather than a `getText()` per
// element: with forty-odd rooms in the roster, a round trip each blew through
// mocha's timeout before it reached an assertion.

import { writeFileSync } from "node:fs";

const REPORT = "e2e/spaces-rail.json";

/** The node spaces AgentPod files agents under. */
const SPACE_NAMES = ["guild", "ashram"];

function matches(label: string): boolean {
  return SPACE_NAMES.some((name) => label.toLowerCase().includes(name));
}

function labels(selector: string): Promise<string[]> {
  return browser.execute(
    (sel: string) =>
      Array.from(document.querySelectorAll(sel)).map((el) =>
        (el.getAttribute("aria-label") ?? el.textContent ?? "").replace(/\s+/g, " ").trim(),
      ),
    selector,
  ) as Promise<string[]>;
}

describe("the spaces rail", () => {
  before(async () => {
    await browser.waitUntil(
      async () =>
        (await browser.execute(
          () => document.querySelectorAll('nav[aria-label="Rooms"] button').length,
        )) > 0,
      {
        // Generous, and measured rather than guessed: restoring this account
        // — forty rooms, a crypto store with a backlog — took 87 seconds from
        // launch to the first roster on the machine this was written on. At
        // 90s the wait failed while the app was working perfectly.
        timeout: 180_000,
        timeoutMsg: "the roster never populated — is this account signed in?",
      },
    );
  });

  it("keeps spaces out of the roster and shows their rooms when selected", async () => {
    const roster = await labels('nav[aria-label="Rooms"] button');
    const rail = await labels('nav[aria-label="Spaces"] button');

    // A space that is joined says how many rooms it holds; one that is only
    // offered says "Invitation" instead, because we cannot see inside it.
    const index = rail.findIndex((label) => matches(label) && !label.includes("Invitation"));

    let filtered: string[] = [];
    if (index >= 0) {
      await (await $$('nav[aria-label="Spaces"] button'))[index].click();
      await browser.pause(3_000);
      filtered = await labels('nav[aria-label="Rooms"] button');
      // Put the roster back, so a run leaves the app as it found it.
      await (await $$('nav[aria-label="Spaces"] button'))[0].click();
      await browser.pause(1_000);
    }

    writeFileSync(
      REPORT,
      JSON.stringify({ rail, rosterSize: roster.length, selected: rail[index], filtered }, null, 2),
    );

    expect({ strays: roster.filter(matches) }).toEqual({ strays: [] });
    expect({ joinedSpace: index >= 0, rail }).toEqual({ joinedSpace: true, rail });
    expect({ rooms: filtered.length > 0, selected: rail[index], filtered }).toEqual({
      rooms: true,
      selected: rail[index],
      filtered,
    });
    expect({ narrowed: filtered.length < roster.length }).toEqual({ narrowed: true });
  });
});
