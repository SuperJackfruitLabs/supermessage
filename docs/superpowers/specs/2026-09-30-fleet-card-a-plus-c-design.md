# Fleet card, A + C

Date: 2026-09-30. This addendum builds on
`2026-09-29-fleet-live-activity-and-recap-widgets-design.md`. The operator
approved the look from the mockups in `2026-09-30-fleet-card-mockups.html`
(the "A + C" section at the top). Each card there is measured against the
160pt limit.

## Why

The first card worked end to end on a device, but looked plain beside
delivery-app Live Activities. The operator chose a mix of direction A (a
headline agent with a big clock and a track through Thinking → Tools → Writing
→ Done) and direction C (a counts header and compact rows for everyone else).

## Constraints

- The Lock Screen draws a Live Activity at most **160pt** tall and truncates
  anything beyond. The card must fit at the iPhone 13 mini's width (about
  344pt) at the default text size, and degrade at larger sizes by dropping, in
  order:
  1. the stage labels;
  2. the rows;
  3. the hero's subtitle.

  It never crops the header or the decision.
- A Live Activity cannot load images from the network. Avatars come from files
  the app caches in the App Group.
- The push payload stays under 4 KB; the hub's `fitPayload` still applies.

## Part A — hub (agentpod)

### A1. ContentState additions

All additions are optional, so build 37 keeps decoding: it ignores unknown
keys, per the original A4.

Per agent, in `agents[]`:

| Key | Type | Meaning |
|---|---|---|
| `mxid` | string | The agent's Matrix user id (for example `@agent_writer-quill:id.agentpod.dev`). The app keys the cached avatar by it. |
| `phase` | `"thinking"` \| `"tools"` \| `"writing"` | Present only when `state` is `working`. It is the stage the track shows. |
| `endedAt` | unix seconds | Present when `state` is `done` or `failed`. With `since`, which is set to the turn's start for a finished row, it gives "Done in 3m 57s". |

The phase rule, from the live events the hub already sees (the bridge's ACP
updates and the Guild plugin's reports; see #629):

- `thinking`: from turn start, and after a thought chunk, until something else
  happens.
- `tools`: while any tool call is in progress, or the most recent event was a
  tool update.
- `writing`: once answer text streams, meaning a message chunk or the plugin's
  first answer delta.

The last transition wins. A permission ask leaves the phase as it was, since
the row's state becomes `needs_you`.

- **Plugin reports:** where the Guild plugin's reports lack a signal needed for
  the phase, extend the plugin minimally in `integrations/hermes/agentpod-live`,
  with its contract test. The phase is optional: without it the app draws the
  track from the counts alone. It is not a blocker.
- **The finish:** `turn-finished` sets `endedAt`. For a finished row, `since`
  becomes the turn's start (today it may be the last activity).

### A2. Tests

- Pure-state tests for the phase transitions, `endedAt` and `since` on the
  finish, and `mxid` on every row.
- The contract schema gains the optional keys, and
  `fixtures/fleet-content-state-v2.json` round-trips through it.
- The v1 fixture still validates.

## Part B — app (supermessage)

### B1. Decoding

`FleetActivityAttributes.ContentState` gains the optional keys, and decoding
stays lenient. Both fixtures decode in a Kit test.

### B2. The card, as mocked (A + C)

**The header row** (C):
- counts, as "3 working", "1 needs you", "1 done" and "1 failed";
- then a fixed time on the right: "Updated 9:41 PM" while live, "Finished
  9:45 PM" once ended. It never ticks.

**The hero** is chosen in this order:
1. a pending decision, which replaces the hero with the one amber element (the
   existing `WidgetDecisionCard` look, made more compact as in the mockup);
2. otherwise a `needs_you` or working agent, the most recently active first;
3. otherwise the most recent finished row.

**The hero row:**
- the avatar (cached, else initials on a tinted circle);
- the headline: the step while working, or "<Name> finished" / "<Name> failed";
- the subtitle: "<Name> · step 3 of 7", or "Done in 3m 57s · 7 steps";
- a big elapsed clock while working, which is the one live-ticking element
  (`Text(timerInterval:)`). It stops when the card is stale or finished.

**The track:**
- four stops (Thinking, Tools, Writing, Done), filled up to the phase, with a
  marker at the current position;
- tool progress interpolates within the Tools segment;
- red from the failed stop onwards for a failed row;
- all filled for a done row;
- stage labels under it only when there are no rows.

**Rows** (C), for the other listed agents:
- avatar, "Name · step", a thin progress bar, and "3/7" or the elapsed time;
- a done or failed row shows a check or "!" and its result line.

**Fitting:** use `ViewThatFits` over the degradation order above. Previews at
the mini's width, light and dark, default and `.xLarge` text, must cover every
mockup state:
- one working;
- three working;
- needs you;
- finished with a failed row;
- failed;
- stale.

Colours are the design tokens. Amber (`signal`) appears only on an owed
decision.

### B3. Watch: the Smart Stack

On watchOS 11 the iPhone's Live Activities appear in the Smart Stack. Add
`.supplementalActivityFamilies([.small])`, and a small layout of the hero and
track only, as in the mockup. Add a preview of it.

### B4. Avatars

- **Caching:** when the app runs, it writes each agent room's avatar,
  downscaled to 64×64 PNG, to `<App Group>/avatars/<sha256(mxid)>.png`. It does
  this for the rooms in the roster and refreshes when an avatar changes.
- **Reading:** the widget extension reads the file by `mxid`, and falls back
  to initials.
- **Rules:** the core owns the mxid → filename rule, per AGENTS.md's rule that
  the app parses nothing. It is a one-line FFI function, tested.

### B5. Queued fixes, in the same build

- The Lock Screen card header is a fixed time, never a ticking relative time
  (covered by B2).
- A failed row keeps its red mark when the card is stale.
- The recap widget: "Nothing new since …" never sits under a "N working"
  header. When agents are working but nothing new has happened, it says who is
  working instead.

### B6. Out of scope

- The watchOS app itself.
- The Dynamic Island, beyond keeping it compiling and simple.

## Verification

- CI green in both repos.
- An archive-only TestFlight run.
- On a device, after the hub deploy and a TestFlight upload:
  - **One tool-using turn:** the track advances through Thinking → Tools (n/m)
    → Writing → Done, and the card finishes as "Done in …".
  - **Two agents at once:** a hero and a row.
  - **A permission:** it takes the top.
  - **The watch:** the Smart Stack shows the small layout.
