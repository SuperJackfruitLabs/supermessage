# DOM baselines

A refactor that only moves markup must not change what the app renders. The
386 vitest tests cannot check that: `vite.config.js` pins
`environment: "node"` and there are **zero component render tests** —
`src/lib/components/timelineActionAnchor.test.ts` documents why. So the gate
for the P2a extraction is a before/after diff of the real app's DOM.

## Usage

Start the app with the MCP bridge, with a session already in the keyring:

```bash
pnpm tauri:mcp
```

Then, in the webview, normalise and fingerprint each surface **in the page**
and record the result:

```bash
echo '<the fingerprint JSON from the webview>' \
  | node scripts/dom-baseline.mjs record before roster
```

The capture snippet is in this file below. It is run in the webview rather
than here because the alternative — shipping each surface's ~30KB of markup
out of the page, twice per task, for thirteen tasks — is a lot of bytes to
move for a yes/no answer. The fingerprint is a length, a tag histogram and a
digest; that answers "did this change", and the histogram makes a failure
readable without the markup.

After a task:

```bash
node scripts/dom-baseline.mjs compare before after-task-7
```

Exit 0 and "identical" on both surfaces is the pass. A non-zero exit names
the surface and prints the tag deltas. When that happens, capture the full
markup — `normalise` / `diff` still exist for exactly that — because the
digest tells you *that* something moved and not *where*.

`node scripts/dom-baseline.mjs list` shows what has been recorded.

### The digest is cyrb53, not sha256

It has to be computable inside the webview, and `SubtleCrypto` is async — the
MCP bridge times out on a script that returns a promise. cyrb53 is
synchronous and deterministic, with ample collision resistance for "did this
markup change". Nothing security-bearing depends on it.

## What the normaliser deliberately ignores, and why

| Ignored | Reason |
|---|---|
| Svelte scoped-class hashes (`s-aB3dEf9`) | **Change by design** when markup moves to a new file. A diff that counted them would be red on every extraction and ignored by the third one. |
| `blob:` / `data:` URLs | Media resolves to a fresh URI per run. |
| ISO timestamps and relative times | Move between runs. |
| Whitespace between tags | Reformatting is not a rendering change. |

Everything else is structural and counts: elements, attribute values
(including `aria-*`), real class names, text content, sibling order.

`scripts/tests/test_dom_baseline.mjs` asserts both directions and is
mutation-proven — stripping `aria-*` too, dropping the hash rule, or
collapsing all classes each fail it. A normaliser that strips too much
reports "identical" for a broken refactor, which is worse than no gate.

## The captures are not committed

`.gitignore` drops `scripts/dom-baseline/*.html`. They are
machine-specific, and they contain real message content from whichever
account was signed in.

## Baseline provenance

A baseline is only comparable against the same content. **If the account's
rooms change mid-project, the comparison is void and the gate silently
becomes decorative** — recapture rather than trusting it.

- **Captured:** 2026-09-12
- **Room open for the room-pane capture:** `Krishna` — chosen because it was
  already read, so opening it sent no read receipt and changed no unread
  state on the account.
- **What that room actually contained:** a date divider (`1 SEP 2026`), a
  membership-change line, own messages, agent messages carrying the bridge
  suffix (`KRISHNA (OPENCLAW @ ASHRAM)` — which is what exercises
  `senderName` vs `senderShort`), reactions with counts, and per-message
  action bars. No dispatch card and no encrypted placeholders were present,
  so those two paths are **not** covered by this baseline; their stories are.
- **Appearance:** light, forced via `data-appearance` — which is only
  possible because of the selectors added in the preceding commit. The
  machine's OS is dark, so without that this would have been a dark capture.
- **Window:** 800x572, which is the **two-pane** layout (roster 344 + room
  pane 456). Below the 1238px panel-column breakpoint, so the room-info panel
  overlays rather than taking a column. Task 13 needs captures at wider
  widths for the three-pane case; this baseline does not cover it.
- **Recorded fingerprints:** roster 32,071 bytes / 47 buttons / 225 spans;
  room pane 28,772 bytes / 110 divs / 65 buttons / 20 paragraphs.

## Surface selectors, and why the first guess was wrong

| Surface | Selector | Covers |
|---|---|---|
| `roster` | `div.flex.shrink-0.bg-surface-sunken` | `SpacesRail`, `RoomList`, `RoomRow`, `ArrangementMenu` |
| `roompane` | `section` | `RoomHeader`, `Timeline` and its leaves, `TypingIndicator`, `Composer` |

The first version of this file guessed `[aria-label="Rooms"]` and
`[data-testid="timeline"], main`. Looked at in the running app: there is **no
`data-testid` anywhere** in this application and **no `<main>`**, so the
timeline selector matched nothing. An empty capture diffs clean against
another empty capture, so the gate would have passed by finding nothing.
`record` now refuses empty input for that reason.

## The capture snippet

Run in the webview via `webview_execute_js`. Two calls, not one: select the
room and set the appearance first, then capture — an async script that waits
for the timeline itself times out in the bridge.

The snippet re-implements `normalise` and `cyrb53` inline because it runs in
the page and cannot import them. If either changes in
`../dom-baseline.mjs`, change it here too — `record` accepts a fingerprint
computed either side, so a mismatch between the two would compare values
that were never comparable. That duplication is the one sharp edge in this
setup.

## Re-baselining, and the one time it has happened

A baseline can go void without anything being wrong with the code. When it
does, **recapture and record why** — a comparison against a void baseline
is worse than none, because it reports either a failure nobody caused or a
pass nobody earned.

### 2026-09-12, after Task 5 — the roster only

- **Observed:** roster 32,071 → 31,835 bytes, `<p>: 1 → 0`. Room pane
  byte-identical (`0a4d32bbf16001`), and the room pane is where three of the
  four components that task converted actually render.
- **Cause:** the roster's "No rooms yet." empty-section message. It was
  rendering when the Task 1 baseline was taken — visible in that capture's
  `innerText` — and was not rendering afterwards. One `<p>`, 236 bytes.
  Account state, not code.
- **Proof it was not the refactor**, checked rather than assumed: the roster
  is `SpacesRail` + `aside(RoomList)`. Task 5 changed neither, and
  `git diff HEAD~1 HEAD -- src/routes/+page.svelte` contains no line
  mentioning `SpacesRail`, `RoomList`, `roster`, `aside` or `shrink-0`. All
  four converted components render in the header or the room pane.
- **Action:** the roster baseline was recaptured from the post-task-5 state.
  The original is kept as `before-task1.roster.json` rather than deleted, so
  the re-baselining is auditable.

The lesson for later tasks: an empty-state message that depends on how the
account's rooms happen to be grouped is a poor thing for a baseline to rest
on. If the roster diff moves again by a couple of hundred bytes and one
element, check for this before suspecting the code.


## The live-account problem, and what replaced the global baseline

**A single global baseline against a live Matrix account does not work.** It
drifted twice in two tasks, both times for reasons unrelated to any code:

1. After Task 5 — the roster's "No rooms yet." empty-section message stopped
   rendering. 236 bytes, one `<p>`.
2. After Task 6 — real messages arrived in the room the baseline used. Its
   date dividers went from `1 SEP 2026` to `11 SEP 2026` and `12 SEP 2026`,
   and an agent's state changed from `quiet` to `active`, which the roster
   renders into its `aria-label`s.

Neither is a defect. Both make the comparison void, and a void comparison is
worse than none: it reports either a failure nobody caused or a pass nobody
earned.

### What to do instead

**Capture a before/after PAIR per task, minutes apart, in one session.**
Capture `before-task-N`, apply the change, capture `after-task-N`, compare
those two. That bounds content drift to the length of the task instead of
the length of the project. The global `before` labels are kept for history
but are not a gate.

### And the static half: `scripts/markup-shape.mjs`

For a props-down conversion there is a narrower question that needs no app
at all: did the markup's *shape* change, or only the expressions inside it?

```bash
node scripts/markup-shape.mjs HEAD~1 src/lib/components/Foo.svelte
```

It compares every tag, attribute name, class literal and piece of static
text across two git revs, ignoring the contents of `{...}` — because
changing those IS the conversion. If the shape is identical, the rendered
DOM cannot differ except through the values now arriving by prop.

Mutation-proven in three directions, each a change the extraction could
plausibly make by accident: a renamed class (`shadow-overlay` →
`shadow-lg`, which is the shape of the token renames), a changed element
(`<p>` → `<div>`), and altered static text. All three fail it.

It is weaker than a DOM diff — it cannot see a conditional that now
evaluates differently — and stronger than nothing, which is what the live
account left.
