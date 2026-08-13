# Console design pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give supermessage a visual identity — operator-console information design, editorial reading typography, one signal colour reserved for decisions — replacing today's functional-but-generic Tailwind-default look.

**Architecture:** A token-first pass. `src/app.css` grows the full palette, three bundled typefaces and a type scale; every component then consumes those tokens. Two new pure modules (`roomIdentity.ts`, a `timelineGrouping.ts` extension) carry the information design that is currently missing — parsing the structure already present in room names, and collapsing sender runs — so the components stay presentational and the logic stays unit-tested. No Rust, no IPC changes.

**Tech Stack:** Svelte 5 runes, SvelteKit SPA, Tailwind v4 `@theme` token layer, `@fontsource*` bundled woff2, vitest, virtua.

**Spec:** `docs/superpowers/specs/2026-08-13-console-design.md` — read it in full before any task. It carries every exact colour, size and copy string; this plan never repeats a value the spec owns.

## Global Constraints

- **No hardcoded colours.** Every colour is a `--color-*` token from `src/app.css`. `Timeline.svelte`'s `.message-html` block keeps reading through `currentColor`. A literal hex or a Tailwind palette class (`text-gray-500`, `bg-blue-600`) anywhere outside `app.css` is a defect.
- **Amber (`--color-signal`) is reserved exclusively for a pending decision** (spec §3, §7.1). Any other use is a defect.
- **No new IPC commands and no changes under `src-tauri/`.** Frontend only.
- **Never invent a Kaambaan wire schema** (`customEvents.ts` module doc). Task 7 extends *our own* renderer-result type; it does not touch the event payload contract.
- **Never ship a visible control that does nothing** (spec §7.1).
- **The `{@html}` rule is unchanged:** only `item.formattedBody`, already hardened in the core. Custom-event payload values stay plain-text interpolation forever.
- **Fonts are bundled from npm, never fetched.** CSP is `default-src 'self'`.
- **`font-synthesis: none` is set** — every weight used must be a real bundled weight.
- **Preserve exactly:** the `--inset-*` safe-area handling, the `user-select` discipline (chrome not selectable, content `.selectable`), `aria-*` attributes, `role="alert"`/`role="status"`/`role="separator"`, and every existing keyboard affordance.
- **Preserve exactly:** all timeline behaviour — virtua keys, `getKey`, `shift`, `onscroll`, the `min-w-0` + `overflow-x: auto` layout-blowout guards, `break-words` on every sender-controlled string. These are all scar tissue from shipped bugs; the doc comments explaining them must survive the edit.
- Every task ends with `pnpm test` and `pnpm check` both clean.
- Do **not** run the app, `pnpm tauri dev`, or any `cargo` command. The binary auto-restores the owner's real session against a live homeserver and can send real messages. Verification is `pnpm test`, `pnpm check`, and `pnpm build`.

---

### Task 1: Design tokens and typefaces

**Files:**
- Modify: `src/app.css`
- Modify: `package.json` (dependencies)

**Interfaces:**
- Consumes: nothing.
- Produces: the token names every later task uses. Colour tokens: `--color-surface`, `--color-surface-sunken`, `--color-surface-raised`, `--color-border`, `--color-border-strong`, `--color-content`, `--color-content-muted`, `--color-content-faint`, `--color-accent`, `--color-accent-content`, `--color-accent-soft`, `--color-signal`, `--color-signal-soft`, `--color-danger`. Font tokens: `--font-sans`, `--font-serif`, `--font-mono`, `--font-system` (kept as the documented fallback). Text tokens: `--text-label`, `--text-meta`, `--text-ui`, `--text-ui-lg`, `--text-body`, `--text-body-own`, each with its matching `--text-*--line-height` (Tailwind v4's convention for a `text-*` utility that also sets line-height).

- [ ] **Step 1: Add the font packages**

```bash
pnpm add @fontsource-variable/ibm-plex-sans@^5.3.0 @fontsource-variable/source-serif-4@^5.3.0 @fontsource/ibm-plex-mono@^5.3.0
```

`@fontsource-variable/ibm-plex-mono` does **not** exist — the static package is correct. Import only the latin subsets and only weights 400 and 500 for the mono face.

- [ ] **Step 2: Import the faces at the top of `src/app.css`**

Import *before* `@import "tailwindcss"` is not required, but the `@import` rules must precede every other at-rule in the file per CSS ordering. Use the fontsource entry points that pull latin only, e.g.:

```css
@import "@fontsource-variable/ibm-plex-sans/wght.css";
@import "@fontsource-variable/source-serif-4/opsz.css";
@import "@fontsource/ibm-plex-mono/latin-400.css";
@import "@fontsource/ibm-plex-mono/latin-500.css";
@import "tailwindcss";
```

Verify the exact file names that ship in each package under `node_modules/<pkg>/` before committing — fontsource's variable-axis file naming differs per family, and a wrong path fails silently at build time with no font applied. If a family's variable CSS pulls more subsets than latin, prefer its `latin.css`/`latin-wght-normal.css` variant. Both variable families need their italic file too if one ships (`Source Serif 4` italic is used by emotes and `<em>`); if the package has no italic, note it in the report and let synthesis stay off.

- [ ] **Step 3: Write the token block**

Replace the placeholder ramp in `@theme` with the light-theme values from spec §3, and the `@layer theme` dark block with the dark values. Keep the existing explanatory comment about skins retinting these, updated to reflect that these are now real values rather than placeholders. Add the type-scale tokens from spec §4, and set:

```css
--font-sans: "IBM Plex Sans Variable", var(--font-system);
--font-serif: "Source Serif 4 Variable", Georgia, serif;
--font-mono: "IBM Plex Mono", ui-monospace, SFMono-Regular, Menlo, monospace;
```

`--font-system` stays defined, as the documented one-token revert path for the serif experiment (spec §4).

Tailwind v4's `@theme` turns `--text-label` into a `text-label` utility and `--text-label--line-height` / `--text-label--letter-spacing` / `--text-label--font-weight` into that utility's paired properties. It has **no** convention for text-transform, so `--text-label`'s uppercase is applied at each call site (`class="text-label font-mono uppercase"`). Set the letter-spacing and weight as theme sub-properties so only the uppercase and the family have to be repeated.

- [ ] **Step 4: Set the base face and the reduced-motion opt-out**

`:root` keeps `font-family: var(--font-sans)` (was `--font-system`). Add, in `@layer base`:

```css
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }
}
```

- [ ] **Step 5: Add a focus-visible default**

In `@layer base`, so no later task has to remember it:

```css
:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 2px;
}
```

- [ ] **Step 6: Verify contrast**

For each of these pairs, in both themes, compute the WCAG contrast ratio and record the numbers in the report: `content`/`surface`, `content-muted`/`surface`, `content-faint`/`surface`, `content-muted`/`surface-sunken`, `content-faint`/`surface-sunken`, `accent`/`surface`, `accent-content`/`accent`, `signal`/`signal-soft`, `danger`/`surface`. Every text pair must clear **4.5:1**. If one does not, adjust that token's value (not its usage) until it does, and say which you changed.

- [ ] **Step 7: Verify and commit**

```bash
pnpm test && pnpm check && pnpm build
git add package.json pnpm-lock.yaml src/app.css
git commit -m "design: token ramp, bundled typefaces, type scale"
```

`pnpm build` must emit the woff2 files into the build output — confirm they appear under `build/` (or `.svelte-kit/output/`) and say so in the report. A build that silently ships no fonts is the failure mode this step exists to catch.

---

### Task 2: Room identity parsing and relative time

**Files:**
- Create: `src/lib/components/roomIdentity.ts`
- Test: `src/lib/components/roomIdentity.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```ts
  export interface RoomIdentity {
    glyph: string | null;
    name: string;
    role: string | null;
  }
  export function parseRoomIdentity(rawName: string): RoomIdentity;
  export function roomInitial(identity: RoomIdentity): string;
  export function relativeTime(timestampMs: number | null, nowMs: number): string | null;
  ```
  Task 3 (`RoomList`), Task 4 (room header) and Task 9 (info panel) all consume these.

Rules are spec §5.1 and §6.1. `MAX_ROLE_CHARS = 40`, `MAX_NAME_CHARS = 120`.

- [ ] **Step 1: Write the failing tests**

```ts
import { describe, expect, it } from "vitest";
import { parseRoomIdentity, relativeTime, roomInitial } from "./roomIdentity";

describe("parseRoomIdentity", () => {
  it("splits glyph, name and role on an em dash", () => {
    expect(parseRoomIdentity("🧠 Buddhimaan — Squad Lead")).toEqual({
      glyph: "🧠",
      name: "Buddhimaan",
      role: "Squad Lead",
    });
  });

  it("leaves a hyphenated name alone", () => {
    expect(parseRoomIdentity("aether-dispatches")).toEqual({
      glyph: null,
      name: "aether-dispatches",
      role: null,
    });
  });

  it("splits on the first em dash only", () => {
    expect(parseRoomIdentity("Coder Kai — Code — Build")).toEqual({
      glyph: null,
      name: "Coder Kai",
      role: "Code — Build",
    });
  });

  it("takes a whole astral glyph, never half a surrogate pair", () => {
    // The `initials()` bug this codebase already shipped: `raw[0]` on an
    // emoji-named room yields a lone surrogate and renders as tofu.
    const parsed = parseRoomIdentity("🛡️ Threat Hunter Theo — Security");
    expect(parsed.name).toBe("Threat Hunter Theo");
    expect(parsed.glyph).not.toBeNull();
    expect([...parsed.glyph!].length).toBeGreaterThan(0);
    expect(parsed.glyph!.codePointAt(0)).toBe(0x1f6e1);
  });

  it("does not treat a leading ASCII word as a glyph", () => {
    expect(parseRoomIdentity("Ops Room — Alerts").glyph).toBeNull();
  });

  it("does not treat a leading grapheme as a glyph without a following space", () => {
    expect(parseRoomIdentity("🧠Buddhimaan").glyph).toBeNull();
    expect(parseRoomIdentity("🧠Buddhimaan").name).toBe("🧠Buddhimaan");
  });

  it("yields null for an empty half rather than an empty string", () => {
    // A trailing dash with nothing after it still splits — the separator
    // needs whitespace *before* the dash, not after.
    expect(parseRoomIdentity("Buddhimaan —")).toEqual({
      glyph: null,
      name: "Buddhimaan",
      role: null,
    });
    // No whitespace before the dash, so this is not a separator at all.
    expect(parseRoomIdentity("— Squad Lead").name).toBe("— Squad Lead");
  });

  it("bounds a hostile role and name", () => {
    const long = "x".repeat(500);
    const parsed = parseRoomIdentity(`${long} — ${long}`);
    expect(parsed.name.length).toBeLessThanOrEqual(120);
    expect(parsed.role!.length).toBeLessThanOrEqual(40);
  });

  it("never returns an empty name", () => {
    expect(parseRoomIdentity("   ").name).toBe("Unnamed room");
    expect(parseRoomIdentity("").name).toBe("Unnamed room");
    expect(parseRoomIdentity(" — ").name).toBe("Unnamed room");
  });
});

describe("roomInitial", () => {
  it("prefers the glyph", () => {
    expect(roomInitial({ glyph: "🧠", name: "Buddhimaan", role: null })).toBe("🧠");
  });

  it("falls back to the first code point of the name, uppercased", () => {
    expect(roomInitial({ glyph: null, name: "aether-dispatches", role: null })).toBe("A");
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
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `pnpm test roomIdentity`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `roomIdentity.ts`**

Write it with a module doc comment in this codebase's house style: say what structure it is parsing and *why that structure exists* (agent rooms on `id.agentpod.dev` are named `<glyph> <Name> — <Role>`), and state that a room without that structure is normal and must degrade silently. Cross-reference spec §5.1.

Implementation notes that the tests above pin but the prose should also make explicit:
- Split on `/\s+—\s*/` — em dash U+2014, **whitespace required before it, optional after**, first occurrence only. Whitespace before is what stops `aether-dispatches` splitting; whitespace after being optional is what makes a trailing `"Buddhimaan —"` yield a null role rather than a name with a dangling dash. Match once and slice, rather than using a global regex or `split`, so the role half keeps any later dashes (`"Coder Kai — Code — Build"` → role `"Code — Build"`).
- When the resulting name is empty after trimming, return the literal `"Unnamed room"`. An empty string would collapse the roster row and produce an avatar with no fallback character.
- Detect the glyph by taking `[...trimmed][0]` and requiring both that its code point is `> 0x7f` and that the character after it is whitespace. Emoji with a variation selector or ZWJ sequence must survive whole — take every code point up to the first whitespace as the glyph candidate, then require the remainder to be non-empty.
- `relativeTime` clamps a negative delta to zero before bucketing. The ≥7d branch uses `Intl.DateTimeFormat` with `{month: "short", day: "numeric"}`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `pnpm test roomIdentity`
Expected: PASS, all cases.

- [ ] **Step 5: Verify and commit**

```bash
pnpm test && pnpm check
git add src/lib/components/roomIdentity.ts src/lib/components/roomIdentity.test.ts
git commit -m "design: parse agent room names into glyph, name and role"
```

---

### Task 3: The roster

**Files:**
- Modify: `src/lib/components/RoomList.svelte`

**Interfaces:**
- Consumes: `parseRoomIdentity`, `roomInitial`, `relativeTime` from Task 2; the existing `roomsStore` and `createAvatarCache`.
- Produces: nothing other tasks consume.

Layout, ranks and behaviour are spec §6.1. Read it before writing markup.

- [ ] **Step 1: Rebuild the row**

Replace the local `initials()` helper with `roomInitial(parseRoomIdentity(room.name))`. Keep the existing module doc comment's avatar reasoning (it explains why `roomAvatar` is fetched for *every* room regardless of `avatarUrl`) — that reasoning is unchanged and load-bearing.

Two lines per row, per the spec's ASCII sketch. The unread count keeps its accessible reading: a bare number is not self-describing, so give the count element an `aria-label` of the form `` `${room.unread} unread` `` or keep it inside a `<span class="sr-only">` pairing. Selection keeps `aria-current`.

- [ ] **Step 2: Make `now` reactive without a timer**

`relativeTime` needs a `now`. Do **not** add a `setInterval` — a roster that re-renders every second to age a label is a battery cost for no information. Derive `now` once per render from `Date.now()`; it refreshes naturally whenever the room list changes, which is exactly when activity happened. Say this in a comment so a later reader does not "fix" it by adding a timer.

- [ ] **Step 3: Verify and commit**

```bash
pnpm test && pnpm check && pnpm build
git add src/lib/components/RoomList.svelte
git commit -m "design: two-line roster rows with role and recency"
```

---

### Task 4: Room header, connection banner, empty states

**Files:**
- Modify: `src/routes/+page.svelte`
- Modify: `src/lib/components/ConnectionBanner.svelte`

**Interfaces:**
- Consumes: `parseRoomIdentity` from Task 2; the existing `connectionStore`, `roomsStore`, `createAvatarCache`.
- Produces: nothing other tasks consume.

Spec §6.2 (header), §6.5 (banner), §10 (copy).

- [ ] **Step 1: Build the identity header**

The header currently renders `selectedRoomName` truncated plus a "Room info" button. Replace with the identity bar: avatar (24px, same `avatarCache` pattern the roster uses — import and instantiate one here, keyed by room id), name, role chip, connection dot, `Info` button.

The connection dot needs a text alternative, not colour alone (spec §9): render the state word beside it, and give the pair a `role="status"` wrapper or an `aria-label` naming the state. `live` renders a filled dot; every other state renders a hollow one (a `border` with no fill), so the distinction survives greyscale.

- [ ] **Step 2: Restyle the banner**

Behaviour is unchanged — it renders only when `connectionStore.state !== "live"`. Restyle per spec §6.5. Keep `role="status"` and keep the existing module comment's point that state is conveyed through text, not colour alone.

- [ ] **Step 3: Apply the copy changes**

From spec §10: the room-pane empty state, and the `Info` button label. Leave "Restoring session…", "Signing out…" and "No rooms yet." as they are.

- [ ] **Step 4: Verify and commit**

```bash
pnpm test && pnpm check && pnpm build
git add src/routes/+page.svelte src/lib/components/ConnectionBanner.svelte
git commit -m "design: room identity header, restyled banner, sharper copy"
```

---

### Task 5: Collapse sender runs

**Files:**
- Modify: `src/lib/components/timelineGrouping.ts`
- Modify: `src/lib/components/timelineGrouping.test.ts`

**Interfaces:**
- Consumes: the existing `TimelineDisplayRow` union and `groupTimelineItems`.
- Produces: a `continuesRun: boolean` field on the `{type: "item"}` variant of `TimelineDisplayRow`. Task 6 reads it to decide whether to render the sender line. The `membershipGroup` variant is unchanged. **Do not add a new row type** — the row already wraps the item, and a new type would force `Timeline.svelte` to grow a fourth branch for no gain.

Rule (spec §6.3): an item continues a run when the *immediately preceding display row* is an `item` row whose underlying item has the same non-null `sender`, and both are rendered as message-shaped content, and their timestamps are within `SENDER_RUN_WINDOW_MS = 5 * 60_000`.

- [ ] **Step 1: Write the failing tests**

Add to `timelineGrouping.test.ts`, following whatever item-fixture helper that file already defines (reuse it; do not write a second one):

```ts
describe("sender runs", () => {
  it("marks a second message from the same sender within the window", () => {
    const rows = groupTimelineItems([
      msg({ id: "$1", sender: "@a:x", timestampMs: 1_000 }),
      msg({ id: "$2", sender: "@a:x", timestampMs: 61_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, true]);
  });

  it("breaks a run past the five-minute window", () => {
    const rows = groupTimelineItems([
      msg({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      msg({ id: "$2", sender: "@a:x", timestampMs: 5 * 60_000 + 1 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false]);
  });

  it("breaks a run on a different sender", () => {
    const rows = groupTimelineItems([
      msg({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      msg({ id: "$2", sender: "@b:x", timestampMs: 1_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false]);
  });

  it("breaks a run across a date divider", () => {
    const rows = groupTimelineItems([
      msg({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      dateDivider({ id: "d1", timestampMs: 500 }),
      msg({ id: "$2", sender: "@a:x", timestampMs: 1_000 }),
    ]);
    const flags = rows.filter((r) => r.type === "item").map((r) => r.type === "item" && r.continuesRun);
    expect(flags).toEqual([false, false, false]);
  });

  it("breaks a run across a membership group", () => {
    const rows = groupTimelineItems([
      msg({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      membership({ id: "$m", sender: "@c:x", timestampMs: 500 }),
      msg({ id: "$2", sender: "@a:x", timestampMs: 1_000 }),
    ]);
    const last = rows.at(-1)!;
    expect(last.type === "item" && last.continuesRun).toBe(false);
  });

  it("never continues a run from an item with a null sender", () => {
    const rows = groupTimelineItems([
      msg({ id: "$1", sender: null, timestampMs: 0 }),
      msg({ id: "$2", sender: null, timestampMs: 1_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false]);
  });

  it("does not continue a run into a dispatch card", () => {
    // A custom event is a bordered object of its own (spec §7); it always
    // carries its own header, so it neither continues nor extends a run.
    const rows = groupTimelineItems([
      msg({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      custom({ id: "$2", sender: "@a:x", timestampMs: 1_000 }),
      msg({ id: "$3", sender: "@a:x", timestampMs: 2_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false, false]);
  });
});
```

Add whatever fixture builders (`dateDivider`, `membership`, `custom`) the file lacks, in the same shape as its existing ones.

- [ ] **Step 2: Run and confirm they fail**

Run: `pnpm test timelineGrouping`
Expected: FAIL — `continuesRun` is `undefined`.

- [ ] **Step 3: Implement**

Add `continuesRun` to the `item` variant of `TimelineDisplayRow` and compute it as `groupTimelineItems` builds the row list, from the previous *display row* rather than the previous raw item — that is what makes a collapsed membership group break the run for free. Export `SENDER_RUN_WINDOW_MS`.

Define the "message-shaped" predicate narrowly and in one place: `kind === "message"` only. Custom events, state events, redactions, membership and date dividers all break a run. Do **not** import `timelineItemView.viewFor` here — the module's doc comment explains at length why this module stays decoupled from render decisions, and that reasoning still holds. Extend the doc comment with the new rule and the reason for the `kind === "message"` restriction.

- [ ] **Step 4: Run and confirm they pass**

Run: `pnpm test timelineGrouping`
Expected: PASS, including every pre-existing membership-grouping test unchanged.

- [ ] **Step 5: Verify and commit**

```bash
pnpm test && pnpm check
git add src/lib/components/timelineGrouping.ts src/lib/components/timelineGrouping.test.ts
git commit -m "design: mark consecutive messages from one sender as a run"
```

---

### Task 6: The reading surface

**Files:**
- Modify: `src/lib/components/Timeline.svelte` (markup + `<style>`; the `customEvent` branch is Task 7's and must be left alone in this task)

**Interfaces:**
- Consumes: `continuesRun` from Task 5; all existing helpers in the file (`viewFor`, `formatTime`, `formatDate`, `replyQuoteView`, `imageBoxStyle`, `formatFileSize`, the `mediaCache`, the reaction/action/seen snippets).
- Produces: nothing other tasks consume.

Spec §6.3. This is the largest task in the plan and the one where a careless edit costs the most — read the file's top-of-script doc comment in full first, and preserve every guard it describes.

- [ ] **Step 1: Introduce a shared message-block snippet**

The `bubble`, `image` and `mediaFile` branches currently repeat the same wrapper markup three times (sender line, reply quote, reactions, actions, seen marker, timestamp) with only the middle differing. Factor the wrapper into one `{#snippet messageBlock(item, continuesRun, children)}` and pass each branch's distinct content as the snippet's children. This is the change that makes the peer/own asymmetry expressible once instead of three times, and it removes verbatim duplication the review rubric treats as a defect.

- [ ] **Step 2: Peer messages lose the bubble; own messages keep one**

Per spec §6.3. Peer: no background, no border, `max-width: 68ch`, serif body, sender line above in `--text-label` + `--text-meta`. Own: right-aligned, `--color-accent-soft`, 6px radius, sans body, `max-w-[52ch]`.

Critical: the `min-w-0` guard and the `.message-html` `overflow-x: auto` / `max-width: 100%` rules must survive. A peer block with no `max-w-[70%]` bubble around it still needs an explicit max-width and `min-w-0`, or the 4700px-table regression documented in the `<style>` block returns. Keep that comment and update it to name the new container.

The `seenMarker` and the own-message timestamp currently use `text-accent-content/70` because they sat on an accent-filled bubble. The own bubble is now `--color-accent-soft` with `--color-content` text, so those must become `--color-content-muted`. Sweep the file for every `accent-content` usage and re-derive it; leaving one behind yields invisible text.

- [ ] **Step 3: Collapse runs**

When `continuesRun` is true, omit the sender line and the timestamp and tighten the vertical gap. The reply quote, reactions, actions and seen marker all still render.

- [ ] **Step 4: Restyle dividers, system lines, placeholders and emotes**

Per spec §6.3. The date divider becomes a hairline with the date centred on it — keep `role="separator"`. System lines and placeholders become mono `--text-meta` in `--color-content-faint`; emotes become serif italic.

- [ ] **Step 5: Update the `<style>` block for serif bodies**

`.message-html` now sets serif prose on a peer block. Check each rule: `code`/`pre` stay mono (they already use `var(--font-mono)`); `blockquote`'s `currentColor` mix still works; list indentation should be re-checked against the serif's larger x-height. Keep every existing comment — they document shipped bugs.

- [ ] **Step 6: Verify and commit**

```bash
pnpm test && pnpm check && pnpm build
git add src/lib/components/Timeline.svelte
git commit -m "design: editorial reading surface for peer messages"
```

---

### Task 7: The dispatch card and the decision contract

**Files:**
- Modify: `src/lib/components/customEvents.ts`
- Modify: `src/lib/components/customEvents.test.ts`
- Modify: `src/lib/components/Timeline.svelte` (the `view.render === "customEvent"` branch only)

**Interfaces:**
- Consumes: the `messageBlock` snippet from Task 6 is **not** used here — a dispatch card is left-aligned regardless of sender and does not take the peer/own treatment (spec §7).
- Produces: `CustomEventRenderResult.decision`, and `CustomEventView`'s `rendered` variant gaining `decision: CustomEventDecision | null`.

Spec §7 and §7.1 in full. This is the signature element.

- [ ] **Step 1: Write the failing tests**

Add to `customEvents.test.ts`, using its existing fixture-registry pattern:

```ts
describe("decision", () => {
  // `decision` is deliberately `unknown` here: these tests feed shapes a
  // renderer must never be trusted to get right, so the cast to the
  // renderer interface is the point, not an oversight. Cast through
  // `unknown` to `CustomEventRenderer` — never `as never`.
  function resolve(decision: unknown) {
    const renderer = {
      eventType: "test.decision.v1",
      maxKnownSchemaVersion: 1,
      render: () => ({ fields: [{ label: "Action", value: "Restart gateway" }], decision }),
    } as unknown as CustomEventRenderer;
    const registry = createCustomEventRegistry([renderer]);
    return resolveCustomEvent(registry, "test.decision.v1", {}, "fallback");
  }

  it("passes a well-formed decision through", () => {
    const view = resolve({
      prompt: "Approve restarting hermes-gateway?",
      options: [
        { id: "approve", label: "Approve" },
        { id: "decline", label: "Decline" },
      ],
    });
    expect(view.status).toBe("rendered");
    expect(view.status === "rendered" && view.decision?.options).toHaveLength(2);
  });

  it("bounds the prompt and each label", () => {
    const view = resolve({
      prompt: "p".repeat(1000),
      options: [{ id: "a", label: "l".repeat(500) }],
    });
    if (view.status !== "rendered" || !view.decision) throw new Error("expected a decision");
    expect(view.decision.prompt.length).toBeLessThanOrEqual(301);
    expect(view.decision.options[0]!.label.length).toBeLessThanOrEqual(61);
  });

  it("caps the option count at four", () => {
    const view = resolve({
      prompt: "pick",
      options: Array.from({ length: 20 }, (_, i) => ({ id: `o${i}`, label: `Option ${i}` })),
    });
    expect(view.status === "rendered" && view.decision?.options).toHaveLength(4);
  });

  it("drops a decision with no valid options rather than rendering a dead card", () => {
    expect(resolve({ prompt: "pick", options: [] })).toMatchObject({ decision: null });
    expect(resolve({ prompt: "pick", options: "nope" })).toMatchObject({ decision: null });
    expect(resolve({ prompt: "pick" })).toMatchObject({ decision: null });
  });

  it("drops options with a non-string id or label", () => {
    const view = resolve({
      prompt: "pick",
      options: [{ id: 1, label: "Approve" }, { id: "decline", label: null }, { id: "ok", label: "OK" }],
    });
    expect(view.status === "rendered" && view.decision?.options).toEqual([
      { id: "ok", label: "OK" },
    ]);
  });

  it("is null when a renderer sets nothing", () => {
    const registry = createCustomEventRegistry([
      { eventType: "t.v1", maxKnownSchemaVersion: 1, render: () => ({ fields: [{ label: "a", value: "b" }] }) },
    ]);
    expect(resolveCustomEvent(registry, "t.v1", {}, null)).toMatchObject({ decision: null });
  });

  it("never sets a decision on a fallbackBody or placeholder view", () => {
    const registry = createCustomEventRegistry([]);
    expect(resolveCustomEvent(registry, "unknown.v1", {}, "body")).toEqual({
      status: "fallbackBody",
      text: "body",
    });
  });

  it("keeps the shipped demo renderer decision-free", () => {
    const view = resolveCustomEvent(customEventRegistry, DEMO_NOTE_EVENT_TYPE, { title: "x" }, null);
    expect(view.status === "rendered" && view.decision).toBeNull();
  });
});
```

- [ ] **Step 2: Run and confirm they fail**

Run: `pnpm test customEvents`
Expected: FAIL.

- [ ] **Step 3: Implement the contract in `customEvents.ts`**

Add `CustomEventDecision`, the optional `decision` on `CustomEventRenderResult`, `decision: CustomEventDecision | null` on the `rendered` variant of `CustomEventView`, and a `boundDecision` validator alongside `boundFields`. Constants: `DECISION_MAX_OPTIONS = 4`, reusing `FIELD_VALUE_MAX_CHARS` for the prompt and `FIELD_LABEL_MAX_CHARS` for labels.

`boundDecision` treats its input as hostile (a renderer could echo it from the payload): it must check that `decision` is a non-null object, that `prompt` is a string, that `options` is an array, and that each option is an object with string `id` and `label`. Anything else drops that option; no valid options drops the whole decision to `null`.

Extend the module doc comment: a new "Decisions" section explaining that `decision` is *our UI contract*, not a wire schema, that Kaambaan's permission event will be the first renderer to set it, and that a card with a decision is the only place `--color-signal` appears in the app.

- [ ] **Step 4: Run and confirm they pass**

Run: `pnpm test customEvents`
Expected: PASS.

- [ ] **Step 5: Build the card in `Timeline.svelte`**

Replace the `customEvent` branch's bubble with the card per spec §7: full reading-measure width, left-aligned regardless of `item.isOwn`, 1px `--color-border-strong`, 6px radius, `--color-surface-raised`, a 2px left edge, a mono header row with the event type and timestamp, and a label/value field grid.

The event type truncates from the **left** with a leading ellipsis. Do this in CSS (`direction: rtl` on the element with `text-align: left` and the text wrapped so it does not reorder punctuation, or a `text-overflow` trick) **or** with a small pure helper in `timelineItemView.ts` that keeps the last N characters — whichever you can make correct. If you use a helper, unit-test it; if you use CSS, verify a reverse-DNS string with dots still reads left-to-right and is not visually reordered by the bidi algorithm. State which you chose and why in the report.

The `placeholder` status keeps its current quiet centred system line — it does not become a card (spec §7).

- [ ] **Step 6: Wire the pending variant**

When `view.view.status === "rendered" && view.view.decision`, the left edge becomes `--color-signal`, the ground `--color-signal-soft`, and a decision row renders: the prompt, then `AWAITING YOUR DECISION` in `--text-label`, then one button per option.

Add an `onDecide` handler in the script section that logs at `console.warn` with the item id and option id and does nothing else, with a comment naming what replaces it: Kaambaan's gate-resolution REST call (`docs/positioning.md`, wedge #3). Because no shipped renderer sets `decision`, this branch never renders in production today — say so in a comment right above it so a reader does not go hunting for the button in the running app.

- [ ] **Step 7: Verify and commit**

```bash
pnpm test && pnpm check && pnpm build
git add src/lib/components/customEvents.ts src/lib/components/customEvents.test.ts src/lib/components/Timeline.svelte
git commit -m "design: dispatch card, with the pending-decision slot Kaambaan will fill"
```

---

### Task 8: Composer and typing indicator

**Files:**
- Modify: `src/lib/components/Composer.svelte`
- Modify: `src/lib/components/TypingIndicator.svelte`

**Interfaces:**
- Consumes: existing stores only.
- Produces: nothing.

Spec §6.4.

- [ ] **Step 1: Rebuild the composer strip**

Prompt sigil, borderless textarea, container-level focus ring, compact `Send` with a `⏎` hint. The textarea keeps `bind:value`, `onkeydown`, `oninput`, `disabled`, `rows="1"`, its `max-h-40 min-h-10` growth bounds, and the `--inset-bottom` padding. The focus ring moves to the container, so the textarea itself needs `outline: none` **with** the container's `:focus-within` ring replacing it — never a bare `outline: none`.

The `⏎` hint is decorative given the keyboard shortcut already works; mark it `aria-hidden="true"` so the button's accessible name stays "Send".

- [ ] **Step 2: Restyle the reply strip and the send error**

Per spec §6.4. Keep `role="alert"` on the error and the `aria-label="Cancel reply"` on the `✕`.

- [ ] **Step 3: Restyle the typing indicator**

Mono `--text-meta`, `--color-content-faint`. Its fixed height must stay — the comment explains it prevents the timeline shifting, and that is still true. Keep `aria-live="polite"`.

- [ ] **Step 4: Verify and commit**

```bash
pnpm test && pnpm check && pnpm build
git add src/lib/components/Composer.svelte src/lib/components/TypingIndicator.svelte
git commit -m "design: composer as an instrument, quieter typing line"
```

---

### Task 9: Room info panel

**Files:**
- Modify: `src/lib/components/RoomInfoPanel.svelte`
- Modify: `src/lib/components/roomInfoView.ts` and its test, only if the sigil rendering needs a pure helper

**Interfaces:**
- Consumes: `parseRoomIdentity` from Task 2.
- Produces: nothing.

Spec §5.2 and §6.6.

- [ ] **Step 1: Apply the sigil treatment**

Drop the "Address" / "Alternative addresses" / "Room ID" headings; the sigil is the label. Render each identifier in mono with the leading sigil character in `--color-content-faint` and the rest in `--color-content-muted`. The `Copy` button for the room id stays, and keeps its `Copied` confirmation.

Splitting a sigil off an identifier is one line of string work, but it is sender-adjacent data and it appears in four places — put it in `roomInfoView.ts` as a pure `splitSigil(id: string): {sigil: string | null; rest: string}` with tests, rather than repeating an inline slice.

- [ ] **Step 2: Apply the identity parse and the type ranks**

The panel header shows the parsed name and, when present, the role chip — matching the room header from Task 4. The topic is prose: serif `--text-body`. Member display names stay sans; mxids become sigil-led mono. Section headings become `--text-label`.

Keep the `break-words`-not-`truncate` decision on member names and its comment — it documents a shipped bug.

- [ ] **Step 3: Verify and commit**

```bash
pnpm test && pnpm check && pnpm build
git add src/lib/components/RoomInfoPanel.svelte src/lib/components/roomInfoView.ts src/lib/components/roomInfoView.test.ts
git commit -m "design: sigil-led identifiers in the room info panel"
```

---

### Task 10: Responsive layout and the accessibility sweep

**Files:**
- Modify: `src/routes/+page.svelte` (breakpoints)
- Modify: any component whose focus, contrast or colour-only signalling the sweep finds wanting

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

Spec §9. This task closes the carried "responsive layout polish" follow-up.

- [ ] **Step 1: Make the three-column layout responsive**

Below `840px` the room-info panel overlays the room pane (absolutely positioned, full height, with the close button already present) instead of taking a third column. Below `640px` the roster collapses: the room pane fills the window and a back affordance returns to the roster. Implement the narrow case with a `$state` flag in `+page.svelte` driven by a `matchMedia` listener, cleaned up on unmount — not with CSS alone, because the back affordance must not exist in the wide layout at all.

Selecting a room in the narrow layout moves to the room pane; the back affordance clears the pane, not the selection — leaving the room selected means returning to it is instant and the timeline subscription is not torn down.

- [ ] **Step 2: Sweep for the quality floor**

Walk every interactive element added or touched by Tasks 3–9 and confirm each of:
- a visible `:focus-visible` ring (the `app.css` default covers most; anything with a custom `outline` must supply its own),
- no information carried by colour alone,
- 4.5:1 contrast in both themes for the text ranks it uses,
- `prefers-reduced-motion` honoured (the `app.css` block covers transitions; check nothing added an animation outside it).

Record the walk as a table in the report: element, focus, colour-alone, contrast, motion. A sweep reported as "clean" without that table is not a sweep.

- [ ] **Step 3: Verify and commit**

```bash
pnpm test && pnpm check && pnpm build
git add -A src/
git commit -m "design: responsive three-pane collapse and accessibility sweep"
```

---

## Self-review notes

- **Spec coverage.** §3 → T1. §4 → T1. §5.1 → T2. §5.2 → T9. §5.3 → T6, T9. §6.1 → T3. §6.2 → T4. §6.3 → T5, T6. §6.4 → T8. §6.5 → T4. §6.6 → T9. §7 → T7. §8 → T1 (the reduced-motion block) and enforced in T10. §9 → T1 (focus default) and T10. §10 → T4, T7. §11 is a constraint, not work.
- **Type consistency.** `parseRoomIdentity`/`roomInitial`/`relativeTime` are named identically in T2, T3, T4 and T9. `continuesRun` is named identically in T5 and T6. `CustomEventDecision` is named identically in T7's contract and markup steps.
- **Known ordering dependency.** T6 refactors `Timeline.svelte`'s message branches into a shared snippet; T7 edits the same file's `customEvent` branch, which T6 is explicitly told not to touch. T7 must run after T6, and its diff should not overlap T6's lines.
