# Component Extraction and Storybook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every store-reading component a pure function of props, and stand up a Storybook catalogue where all of them can be looked at in three appearances.

**Architecture:** Containers keep state and resolve caches; leaves take view-models as props and callbacks for actions. One `$lib/fixtures` module feeds both the stories and the existing tests. Storybook imports the real `src/app.css`, so the catalogue cannot drift from the palette.

**Tech Stack:** Svelte 5 runes, SvelteKit (SPA), Tailwind v4, Storybook 10.6 + `@storybook/sveltekit` + `@storybook/addon-svelte-csf` + `@storybook/addon-a11y` (all MIT devDependencies), vitest (node env, unchanged), Tauri MCP bridge for DOM verification.

**Spec:** `docs/superpowers/specs/2026-09-12-component-extraction-storybook-design.md`

## Global Constraints

- **No redesign.** Every component renders exactly what it renders today. A visual change is a defect, and the DOM diff in Task 1 is the definition of done.
- **Leaves read no stores.** No `import ... from "$lib/stores/..."` in any extracted leaf. Data arrives as view-model props, actions as callback props.
- **Leaves never take a cache.** A leaf takes `avatarUrl: string | null`, never an `AvatarCache`. Containers call `avatarCache.get(...)` and pass the result.
- **Every leaf exports its `Props` interface**, so component, story and any future test share one type.
- **View-model types are used unchanged** from `src/lib/ipc.ts` and `timelineGrouping.ts`. No mapping layer — that is where two hosts begin to disagree.
- **`$lib/fixtures` must never reach the production bundle.** Verified by Task 14, not assumed.
- **vitest stays `environment: "node"`.** No component render tests in this project. The DOM diff is the gate.
- **The scroll, follow and pagination machinery is not refactored.** `timelineFollow.ts`, `gapSync.ts`, `timelinePane.ts` and their callers in the container are out of bounds.
- **Amber (`signal`) means a pending decision and nothing else** — `docs/design-language.md` §2 still binds. Stories must not invent new uses.
- **A test that has never failed is not a regression test.** Every check this plan adds must be shown to fail against a deliberately broken input.

### The fifteen components

Six that split into containers plus leaves:

| File | Lines | Leaves |
|---|---|---|
| `Timeline.svelte` | 2,618 | `TimelineRow`, `MessageBubble`, `ReplyQuote`, `ReactionsRow`, `MessageActions`, `SeenMarker`, `LogLine`, `DispatchCard`, `ImageAttachment`, `FileAttachment`, `UnreadMarker` |
| `+page.svelte` | 1,070 | `PaneShell`, `RoomHeader`, `EmptyRoomState` |
| `Composer.svelte` | 882 | `MentionMenu`, `StagedAttachmentChip`, `ReplyBanner` |
| `RoomList.svelte` | 475 | `RoomRow`, `RosterSection`, `ArrangementMenu` |
| `RoomInfoPanel.svelte` | 352 | `MemberRow`, `RoomIdentityHeader` |
| `LiveTurn.svelte` | 315 | `LiveTurnBubble` |

Nine that keep their file and stop reading stores:

`AgentReasoning` (135) · `SpacesRail` (156) · `NewRoomPanel` (186) · `SearchPanel` (146) · `SpaceInvitePanel` (112) · `InvitationPanel` (79) · `LiveActivity` (75) · `TypingIndicator` (52) · `ConnectionBanner` (43)

### The existing snippet signatures — these become Props

From `Timeline.svelte`, verbatim. Each becomes a component whose props are these parameters:

```
replyQuote(quote: ReplyQuoteView | null, isOwn: boolean)
reactionsRow(item: TimelineItem, interactive: boolean, alignEnd = item.isOwn)
messageActions(row: ItemRow, alignEnd = row.item.isOwn)
seenMarker(item: TimelineItem, alignEnd = item.isOwn)
logLine(text: string)
messageBlock(row: ItemRow, content: Snippet)
```

`ItemRow` is `Extract<TimelineDisplayRow, { type: "item" }>` from `timelineGrouping.ts`, already a typed discriminated union carrying `item`, `view`, `canReplyOrReact`, `replyQuote`, `senderName`, `replyPreview` and `continuesRun`.

---

## Task 1: Capture the DOM baseline

**This task must run before any refactor.** There is nothing to diff against otherwise, and the 386 existing tests do not cover rendered markup — see the spec's §8.

**Files:**
- Create: `scripts/dom-baseline.mjs`, `scripts/dom-baseline/README.md`
- Create (git-ignored): `scripts/dom-baseline/*.html`

**Interfaces:**
- Consumes: nothing.
- Produces: `node scripts/dom-baseline.mjs capture <label>` writes one normalised HTML file per surface; `node scripts/dom-baseline.mjs diff <a> <b>` exits non-zero on any difference.

- [ ] **Step 1: Write the capture script**

`scripts/dom-baseline.mjs`:

```js
#!/usr/bin/env node
/**
 * Serialise the running app's DOM, so a refactor can be proven not to have
 * changed it.
 *
 * This exists because the 386 vitest tests are pure-module tests in a node
 * environment — `vite.config.js` pins `environment: "node"` and there are
 * zero component render tests. Extraction moves markup, which is exactly
 * what nothing covers. A pure extraction must produce identical DOM, so the
 * diff IS the test.
 *
 * Normalisation strips what legitimately varies between runs: Svelte's
 * scoped class hashes (which change when markup moves between files, by
 * design), blob/data URLs, and timestamps. Everything structural stays.
 */
import { writeFileSync, readFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const DIR = "scripts/dom-baseline";
const SURFACES = {
  roster: '[aria-label="Rooms"]',
  timeline: '[data-testid="timeline"], main',
};

function normalise(html) {
  return html
    // Svelte scopes styles with a per-file hash. Moving markup to a new file
    // changes the hash by design, so it cannot be part of the comparison.
    .replace(/\s?s-[A-Za-z0-9_-]{6,}/g, "")
    .replace(/\sclass="\s*"/g, "")
    // Media resolves to a fresh blob/data URI per run.
    .replace(/(src|href)="(blob:|data:)[^"]*"/g, '$1="[resolved]"')
    // Relative times and ISO stamps.
    .replace(/\d{4}-\d{2}-\d{2}T[\d:.]+Z?/g, "[time]")
    .replace(/>[^<]*\b(just now|\d+ (minutes?|hours?|days?) ago)\b[^<]*</gi, ">[relative]<")
    // Collapse whitespace so reformatting is not a diff.
    .replace(/>\s+</g, "><")
    .replace(/\s+/g, " ")
    .trim();
}

const [, , cmd, ...args] = process.argv;

if (cmd === "normalise") {
  // Used by the capture step, which pipes raw outerHTML in on stdin.
  const raw = readFileSync(0, "utf8");
  const label = args[0] ?? "unlabelled";
  const surface = args[1] ?? "surface";
  mkdirSync(DIR, { recursive: true });
  const out = join(DIR, `${label}.${surface}.html`);
  writeFileSync(out, normalise(raw));
  console.log(`wrote ${out}`);
} else if (cmd === "diff") {
  const [a, b] = args;
  let failed = false;
  for (const surface of Object.keys(SURFACES)) {
    const pa = join(DIR, `${a}.${surface}.html`);
    const pb = join(DIR, `${b}.${surface}.html`);
    let ta, tb;
    try {
      ta = readFileSync(pa, "utf8");
      tb = readFileSync(pb, "utf8");
    } catch (err) {
      console.error(`missing capture: ${err.message}`);
      failed = true;
      continue;
    }
    if (ta === tb) {
      console.log(`  ${surface}: identical (${ta.length} bytes)`);
    } else {
      failed = true;
      console.error(`  ${surface}: DIFFERS (${ta.length} vs ${tb.length} bytes)`);
      // First divergence, with context, because a 100KB diff is unreadable.
      let i = 0;
      while (i < Math.min(ta.length, tb.length) && ta[i] === tb[i]) i++;
      console.error(`    first divergence at byte ${i}:`);
      console.error(`      ${a}: …${ta.slice(Math.max(0, i - 60), i + 120)}`);
      console.error(`      ${b}: …${tb.slice(Math.max(0, i - 60), i + 120)}`);
    }
  }
  process.exit(failed ? 1 : 0);
} else {
  console.error("usage: dom-baseline.mjs normalise <label> <surface> | diff <a> <b>");
  process.exit(2);
}

export { normalise, SURFACES };
```

- [ ] **Step 2: Prove the normaliser on fixtures before trusting it**

Create `scripts/tests/test_dom_baseline.mjs`:

```js
import assert from "node:assert/strict";
import { normalise } from "../dom-baseline.mjs";

// A scoped-class change alone must NOT register as a difference — moving
// markup to a new file changes Svelte's hash by design, and if that counted
// the diff would be red on every single extraction and therefore useless.
assert.equal(
  normalise('<div class="row s-aB3dEf9">x</div>'),
  normalise('<div class="row s-Zq7Yt2X">x</div>'),
);

// A real structural change MUST register.
assert.notEqual(
  normalise('<div class="row"><span>x</span></div>'),
  normalise('<div class="row"><em>x</em></div>'),
);

// A changed attribute value MUST register.
assert.notEqual(
  normalise('<button aria-label="Room info">i</button>'),
  normalise('<button aria-label="Info">i</button>'),
);

// Whitespace reformatting must not.
assert.equal(normalise("<ul>\n  <li>a</li>\n</ul>"), normalise("<ul><li>a</li></ul>"));

console.log("dom-baseline normaliser: 4 assertions passed");
```

Run: `node scripts/tests/test_dom_baseline.mjs`
Expected: `4 assertions passed`.

- [ ] **Step 3: Mutation-prove the normaliser**

A normaliser that strips too much reports "identical" for a broken refactor, which is worse than having no gate.

1. Change `.replace(/\s?s-[A-Za-z0-9_-]{6,}/g, "")` to also strip `aria-[a-z]+="[^"]*"`. Run the test.
   Expected: the `aria-label` assertion FAILS. Restore.
2. Delete the scoped-class replacement entirely. Run the test.
   Expected: the first assertion FAILS. Restore.

Record both observations.

- [ ] **Step 4: Capture the real baseline**

```bash
pnpm tauri:mcp
```

With the app running and a session restored from the keyring, drive it through the Tauri MCP bridge. For each surface in `SURFACES`, evaluate `document.querySelector(sel).outerHTML` in the webview and pipe it through the script:

```bash
# once per surface, with the roster showing and a room open
node scripts/dom-baseline.mjs normalise before roster   < roster.raw.html
node scripts/dom-baseline.mjs normalise before timeline < timeline.raw.html
```

Capture in **light** appearance with a room selected that has: a message run from one sender, a reply, reactions, and — if reachable — a dispatch card. Note in the README which room and which account, because the baseline is only comparable against the same content.

- [ ] **Step 5: Prove the diff detects a real change**

Hand-edit one captured file (change an `aria-label`), then:

Run: `node scripts/dom-baseline.mjs diff before before`
Expected: exit 0, "identical" for both surfaces.

Then copy `before.roster.html` to `after.roster.html`, alter one attribute, and run `diff before after`.
Expected: exit 1, naming the surface and the first divergence byte. Restore.

A gate that has never caught anything is the same failure as a test that has never failed.

- [ ] **Step 6: Document and commit**

`scripts/dom-baseline/README.md` records: which account and room the baseline was captured against, what the normaliser deliberately ignores and why, and the exact commands. Add `scripts/dom-baseline/*.html` to `.gitignore` — captures are machine- and account-specific and must not be committed.

```bash
git add scripts/dom-baseline.mjs scripts/tests/test_dom_baseline.mjs \
        scripts/dom-baseline/README.md .gitignore
git commit -m "verify: serialise the app's DOM, so extraction can be proven inert

The 386 tests are pure-module tests in a node environment and there are
zero component render tests — vite.config.js pins environment: node and
timelineActionAnchor.test.ts documents why. Extraction moves markup, which
is precisely what nothing covers.

So the gate is a before/after DOM diff of the real app. The normaliser
ignores Svelte's scoped-class hashes, because moving markup between files
changes them by design and a diff that counted them would be red on every
extraction and therefore ignored.

Mutation-proven: widening the strip to include aria attributes fails the
attribute assertion; removing the scoped-class strip fails the hash
assertion."
```

---

## Task 2: Explicit appearance selectors in the generated CSS

The catalogue must be able to switch appearances. `src/lib/tokens.css` reaches dark only through `@media (prefers-color-scheme: dark)`, so today Storybook could toggle paper but would need the OS theme changed to show dark — and on a dark-set machine, light would be unreachable.

**Files:**
- Modify: `scripts/tokens/emit_css.py`
- Modify: `scripts/tests/test_emit.py`
- Regenerate: `src/lib/tokens.css`, `scripts/tests/golden/app-tokens.css`

**Interfaces:**
- Consumes: `Tokens` from `scripts/tokens/model.py`.
- Produces: `emit_app_css` output in which each of `light`, `dark` and `paper` is reachable via `[data-appearance="<name>"]`, and the media query is guarded by `:root:not([data-appearance])`.

- [ ] **Step 1: Write the failing test**

Append to `scripts/tests/test_emit.py`:

```python
class AppearanceSelectorTests(unittest.TestCase):
    """Every appearance must be explicitly selectable.

    A catalogue that cannot switch appearances cannot do the job it exists
    for, and faking the theme in Storybook's own CSS would mean showing
    something the app cannot produce.
    """

    def setUp(self):
        self.css = emit_app_css(load(SOURCE))

    def test_all_three_are_attribute_selectable(self):
        for name in ("light", "dark", "paper"):
            with self.subTest(name=name):
                self.assertIn(f'[data-appearance="{name}"]', self.css)

    def test_the_media_query_yields_to_an_explicit_choice(self):
        # Without the guard, an explicit light choice on a dark-set machine
        # loses to the media query and the toolbar appears broken.
        self.assertIn(":root:not([data-appearance])", self.css)

    def test_light_is_still_the_bare_default(self):
        # The @theme block must keep carrying light, so a page that sets no
        # attribute and asks for no scheme still has a complete palette.
        root = self.css.split("@layer theme")[0]
        for role in ROLES:
            with self.subTest(role=role):
                self.assertIn(f"--color-{role}:", root)

    def test_each_appearance_block_is_complete(self):
        # A partial override inherits the rest from light, which is how a
        # half-applied theme happens.
        for name in ("light", "dark", "paper"):
            block = self.css.split(f'[data-appearance="{name}"]')[1]
            block = block[: block.index("}")]
            for role in ROLES:
                with self.subTest(name=name, role=role):
                    self.assertIn(f"--color-{role}:", block)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python3 -m unittest scripts.tests.test_emit.AppearanceSelectorTests -v`
Expected: FAIL — `[data-appearance="light"]` and `:root:not([data-appearance])` are absent.

- [ ] **Step 3: Change the emitter**

In `scripts/tokens/emit_css.py`, replace the body of `emit_app_css` after the `@theme` block:

```python
    return (
        HEADER
        + "\n@theme {\n"
        + _block(tokens.appearances["light"], "  ")
        + _type_block(tokens, "  ")
        + _scale_block(tokens, "  ")
        + "}\n"
        + "\n@layer theme {\n"
        + "  /*\n"
        + "   * The OS's choice, but only when nothing has been chosen\n"
        + "   * explicitly. Without the :not() guard an explicit light\n"
        + "   * selection loses to this on a dark-set machine, and the\n"
        + "   * appearance control appears broken in one direction only.\n"
        + "   */\n"
        + "  @media (prefers-color-scheme: dark) {\n"
        + "    :root:not([data-appearance]) {\n"
        + _block(tokens.appearances["dark"], "      ")
        + "    }\n"
        + "  }\n"
        + "\n"
        + "  /*\n"
        + "   * An explicit choice, in all three directions. `paper` is what\n"
        + '   * "light" means on a phone and nothing selects it on desktop;\n'
        + "   * the other two exist so a catalogue — and, later, a user\n"
        + "   * preference — can override the OS either way.\n"
        + "   */\n"
        + "".join(
            f'  [data-appearance="{name}"] {{\n'
            + _block(tokens.appearances[name], "    ")
            + "  }\n"
            for name in ("light", "dark", "paper")
        )
        + "}\n"
    )
```

- [ ] **Step 4: Run the tests**

Run: `python3 -m unittest discover -s scripts/tests -t . -v`
Expected: PASS. The golden test will fail until Step 5.

- [ ] **Step 5: Regenerate, read the diff, refresh the golden**

```bash
python3 scripts/generate-tokens.py
git diff src/lib/tokens.css | head -60
cp src/lib/tokens.css scripts/tests/golden/app-tokens.css
python3 -m unittest discover -s scripts/tests -t .
```

Read the diff before accepting it. Expected: the dark block gains a `:not()` guard, and three `[data-appearance]` blocks are added. No value changes.

- [ ] **Step 6: Verify in a browser that all three are reachable**

```bash
pnpm build && pnpm dev
```

In the browser console:

```js
for (const a of [null, "light", "dark", "paper"]) {
  a ? document.documentElement.setAttribute("data-appearance", a)
    : document.documentElement.removeAttribute("data-appearance");
  console.log(a ?? "(os)", getComputedStyle(document.documentElement).getPropertyValue("--color-surface").trim());
}
```

Expected: `(os)` → `#fdfcff` (or `#1f1838` if the OS is dark), `light` → `#fdfcff`, `dark` → `#1f1838`, `paper` → `#faf8f3`.

Then set the browser to prefer dark and repeat: `light` must still report `#fdfcff`. That is the guard doing its job, and it is the half that would silently not work.

- [ ] **Step 7: Mutation-prove the guard**

Remove `:not([data-appearance])` from the emitter, regenerate, and repeat Step 6's dark-OS check.
Expected: `light` reports `#1f1838` — the explicit choice loses. Restore, regenerate.

- [ ] **Step 8: Commit**

```bash
git add scripts/tokens/emit_css.py scripts/tests/test_emit.py \
        src/lib/tokens.css scripts/tests/golden/app-tokens.css
git commit -m "tokens: make every appearance explicitly selectable

tokens.css reached dark only through a prefers-color-scheme media query,
so a catalogue could switch to paper but needed the OS theme changed to
show dark — and on a dark machine, light was unreachable entirely.

All three now have a [data-appearance] selector, and the media query is
guarded by :root:not([data-appearance]) so an explicit choice wins in both
directions. That is the half that fails silently: without the guard,
selecting light on a dark-set machine does nothing.

This also delivers the user-facing appearance chooser P1's design deferred
as 'available later'.

Mutation-proven: removing the guard makes an explicit light selection
report dark's surface on a dark-set browser."
```

---

## Task 3: Storybook, proven end to end on the smallest component

`ConnectionBanner.svelte` is 43 lines and reads one store. It is the cheapest possible proof that the whole pipeline works — install, config, tokens, a story, the appearance switcher — before the pattern is applied to `Timeline.svelte`.

**Files:**
- Create: `.storybook/main.ts`, `.storybook/preview.ts`
- Create: `src/lib/fixtures/index.ts`, `src/lib/fixtures/connection.ts`
- Create: `src/lib/components/ConnectionBanner.stories.svelte`
- Modify: `package.json`, `src/lib/components/ConnectionBanner.svelte`, `src/routes/+page.svelte`

**Interfaces:**
- Consumes: `--color-*` and `[data-appearance]` from Task 2.
- Produces: `pnpm storybook` (dev) and `pnpm storybook:build`; `ConnectionBannerProps { state: ConnectionState }`; `FIXTURE_MARKER` exported from `$lib/fixtures/index.ts`.

- [ ] **Step 1: Install**

```bash
pnpm add -D storybook@^10.6.0 @storybook/sveltekit@^10.6.0 \
            @storybook/addon-svelte-csf@^5.1.3 @storybook/addon-a11y@^10.6.0
```

All four are MIT. They are devDependencies, so the repository's runtime-licence rule is not engaged — but note the `licences` CI job covers Cargo, not npm, so this is recorded rather than gated.

- [ ] **Step 2: Configure**

`.storybook/main.ts`:

```ts
import type { StorybookConfig } from "@storybook/sveltekit";

const config: StorybookConfig = {
  framework: "@storybook/sveltekit",
  // Co-located with their component, so a story that drifts from its
  // component is a one-directory problem rather than a search.
  stories: ["../src/**/*.stories.svelte"],
  addons: ["@storybook/addon-svelte-csf", "@storybook/addon-a11y"],
};

export default config;
```

`.storybook/preview.ts`:

```ts
import type { Preview } from "@storybook/sveltekit";
// The REAL generated tokens, not a copy. A palette change changes the
// catalogue; there is no second set of values to drift.
import "../src/app.css";

const preview: Preview = {
  parameters: { layout: "centered" },
  // The appearance control. This works only because the token emitter
  // gained [data-appearance] selectors — see Task 2. Faking the theme here
  // would mean showing something the app cannot produce.
  globalTypes: {
    appearance: {
      description: "Which appearance to render",
      defaultValue: "light",
      toolbar: {
        title: "Appearance",
        items: [
          { value: "light", title: "Light — desktop" },
          { value: "dark", title: "Dark" },
          { value: "paper", title: "Paper — mobile" },
        ],
        dynamicTitle: true,
      },
    },
  },
  decorators: [
    (story, context) => {
      document.documentElement.setAttribute("data-appearance", context.globals.appearance);
      document.documentElement.style.background = "var(--color-surface-sunken)";
      return story();
    },
  ],
};

export default preview;
```

Add to `package.json` scripts:

```json
"storybook": "storybook dev -p 6006",
"storybook:build": "storybook build"
```

- [ ] **Step 3: Write the fixtures entry point**

`src/lib/fixtures/index.ts`:

```ts
/**
 * Fixtures for stories and tests. NEVER imported by application code.
 *
 * `FIXTURE_MARKER` exists so the build can be searched for it. Nothing here
 * should ever reach the production bundle, and "Vite tree-shakes it" is a
 * belief until something checks — see the plan's Task 14.
 */
export const FIXTURE_MARKER = "__supermessage_fixture_marker__";

export * from "./connection";
```

`src/lib/fixtures/connection.ts` — read `ConnectionBanner.svelte` and the
`connection` store first to get the real state shape, then write one named
scenario per state the banner can show (connected, connecting, offline,
error). Do not invent states: the set must match what the store can produce.

- [ ] **Step 4: Convert `ConnectionBanner` to props**

Read the component. It imports `connectionStore` and renders from it. Replace that with a `Props` interface carrying exactly what the markup reads, export the interface, and delete the store import. Then have `+page.svelte` — which already imports `connection.svelte` — pass the value down.

The markup must not change. That is what Task 1's baseline is for.

- [ ] **Step 5: Write the story**

`src/lib/components/ConnectionBanner.stories.svelte`:

```svelte
<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import ConnectionBanner from "./ConnectionBanner.svelte";
  import { connectionConnected, connectionOffline, connectionError } from "$lib/fixtures";

  const { Story } = defineMeta({
    title: "Chrome/ConnectionBanner",
    component: ConnectionBanner,
  });
</script>

<Story name="Offline" args={{ state: connectionOffline }} />
<Story name="Error" args={{ state: connectionError }} />
<!-- Connected renders nothing, and that is worth being able to see:
     a banner that appears when it should not is the failure mode. -->
<Story name="Connected (renders nothing)" args={{ state: connectionConnected }} />
```

- [ ] **Step 6: Run it and look at it**

```bash
pnpm storybook
```

Open `http://localhost:6006`. Check: the three stories render; the Appearance toolbar switches light/dark/paper and the banner's colours change with it; the a11y panel reports on each story.

**If the appearance switcher does nothing, stop.** It means Task 2's selectors are not reaching the preview, and every later story inherits the problem.

- [ ] **Step 7: Verify nothing regressed**

```bash
pnpm check && pnpm test && pnpm build && pnpm storybook:build
```

Expected: all clean, 386 tests green.

- [ ] **Step 8: Commit**

```bash
git add package.json pnpm-lock.yaml .storybook src/lib/fixtures \
        src/lib/components/ConnectionBanner.svelte \
        src/lib/components/ConnectionBanner.stories.svelte src/routes/+page.svelte
git commit -m "storybook: the pipeline, proven on a 43-line component

ConnectionBanner is the smallest store-reading component in the app, which
makes it the cheapest possible proof that install, config, real tokens, a
story and the appearance switcher all work together — before any of that is
applied to Timeline.svelte's 2,618 lines.

The preview imports src/app.css rather than a copy, so the catalogue cannot
drift from the palette. The appearance toolbar works only because the token
emitter gained [data-appearance] selectors in the previous commit; faking
the theme in Storybook's own CSS would mean showing something the app
cannot produce."
```

---

## The extraction recipe

**Plan-level, so every task below implicitly includes it.** Written once here
rather than repeated per task, because it is identical for all thirty-odd
conversions and a reader who has this does not need it restated.

For a component that **keeps its file and stops reading stores**:

1. Read the component. List every value its markup reads from a store, and
   every store call its handlers make.
2. Write an exported `Props` interface naming exactly those: values as
   view-model types from `src/lib/ipc.ts`, store calls as callbacks
   (`onSelect`, `onJoin`, `onClose`).
3. Replace `let { ... } = $props()` accordingly and delete every
   `$lib/stores` import.
4. Update the caller — the container or route — to read the store and pass
   the values and callbacks down. Caches resolve here:
   `avatarUrl={avatarCache.get(room.id)}`.
5. Add fixtures for the states the component can show, to
   `src/lib/fixtures/<area>.ts`, and export them from `index.ts`.
6. Write `<Component>.stories.svelte` with one `<Story>` per state.
7. `pnpm check` must be clean. This is the real gate on prop plumbing.

For a component that **splits into a container plus leaves**, additionally:

8. Move each `{#snippet}` block, or each cohesive markup region, to its own
   `.svelte` file under a subdirectory named for its area
   (`timeline/`, `roster/`, `composer/`, `panels/`, `layout/`).
9. The snippet's parameters become the leaf's props, unchanged — those
   signatures are already listed in this plan's header.
10. Move the scoped CSS that styles that markup with it. `:global()` rules
    move as a block and must stay `:global()`; a re-scoped `:global()` rule
    silently stops applying.
11. The container renders the leaves and keeps all state.

**After every task**, in order:

```bash
pnpm check                 # 22+ new Props interfaces; this is the gate
pnpm test                  # the 386 must stay green
pnpm storybook:build       # a story referencing a dead prop is a red build
```

**And after every task that touches rendered markup**, capture and diff:

```bash
# with the app running under `pnpm tauri:mcp`
node scripts/dom-baseline.mjs normalise after-<task> roster   < roster.raw.html
node scripts/dom-baseline.mjs normalise after-<task> timeline < timeline.raw.html
node scripts/dom-baseline.mjs diff before after-<task>
```

Expected: exit 0, "identical". **A non-zero exit is a defect, not a
surprise** — this plan's non-goal is any visual change. If it differs,
find out why before continuing; the diff prints the first divergence.

---

## Task 4: The fixtures module

**Files:**
- Create: `src/lib/fixtures/timeline.ts`, `rooms.ts`, `customEvents.ts`, `members.ts`
- Modify: `src/lib/fixtures/index.ts`
- Modify: `src/lib/components/timelineGrouping.test.ts`, `searchView.test.ts`, `src/lib/stores/rooms.test.ts`, `src/lib/stores/timeline.test.ts`

**Interfaces:**
- Consumes: `TimelineRow`, `TimelineItem`, `RoomRow`, `ItemView` from `src/lib/ipc.ts`; `TimelineDisplayRow` from `timelineGrouping.ts`.
- Produces: builders `message()`, `membership()`, `item()`, `row()`, `roomRow()`, each taking `Partial<T>` overrides; plus the named scenarios listed below. All re-exported from `$lib/fixtures`.

- [ ] **Step 1: Move the existing builders**

Four test files hold 11 builders between them — `timelineGrouping.test.ts` has eight (`item`, `row`, `membership` ×2 overloads, `message` ×2 overloads, and others), and `searchView.test.ts`, `rooms.test.ts`, `timeline.test.ts` have one each.

Read each, move it to the matching fixtures file **unchanged**, and have the test import it. Do not improve them while moving: a behaviour change here would fail tests for a reason unrelated to the move, and the point is one definition, not a better one.

- [ ] **Step 2: Run the tests to prove the move was inert**

Run: `pnpm test`
Expected: 386 pass. Any failure means a builder changed behaviour in transit — revert and move it again verbatim.

- [ ] **Step 3: Add the named scenarios**

On top of the builders, in the matching files:

```
timeline.ts       senderRun · dateDivider · ownMessageSending · ownMessageFailed
                  longUnbrokenToken · encryptedPlaceholder · replyToDeleted
                  reactionsMine · reactionsMany
customEvents.ts   dispatchCardPending · dispatchCardAnswered
                  dispatchCardPlaceholder
rooms.ts          rosterQuiet · rosterApprovalNeeded · rosterAgentWorking
members.ts        memberHuman · memberAgent · memberWithoutAvatar
```

Each is a `const` built from the builders. `longUnbrokenToken` matters more than it looks: the timeline's wrap guards (`break-words`, `max-w-[68ch]`, `min-w-0`) exist because sender-controlled content can widen a card, and a story is the only place that is easy to see.

- [ ] **Step 4: Assert the scenarios are what they claim**

Create `src/lib/fixtures/fixtures.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import * as f from "./index";

describe("named scenarios mean what their names say", () => {
  it("dispatchCardPending has a decision and dispatchCardAnswered does not", () => {
    expect(f.dispatchCardPending.view.status).toBe("rendered");
    expect(f.dispatchCardPending.view.decision).not.toBeNull();
    expect(f.dispatchCardAnswered.view.decision?.answeredOptionId).toBeTruthy();
  });

  it("senderRun is the same sender twice inside the run window", () => {
    const [a, b] = f.senderRun;
    expect(a.item.sender).toBe(b.item.sender);
    expect(b.item.timestamp - a.item.timestamp).toBeLessThan(5 * 60_000);
  });

  it("longUnbrokenToken is actually unbreakable", () => {
    // A fixture named for a wrap hazard that contains a space is not one.
    const body = f.longUnbrokenToken.item.body ?? "";
    expect(body).not.toMatch(/\s/);
    expect(body.length).toBeGreaterThan(120);
  });

  it("rosterApprovalNeeded is the only roster fixture with a pending decision", () => {
    expect(f.rosterApprovalNeeded.pendingDecision).toBe(true);
    expect(f.rosterQuiet.pendingDecision).toBe(false);
    expect(f.rosterAgentWorking.pendingDecision).toBe(false);
  });
});
```

Run: `pnpm test`
Expected: PASS.

- [ ] **Step 5: Mutation-prove the fixture assertions**

1. Put a space in `longUnbrokenToken`'s body. Run.
   Expected: the wrap-hazard test FAILS. Restore.
2. Set `rosterQuiet.pendingDecision = true`. Run.
   Expected: the roster test FAILS. Restore.

A fixture that has drifted from its name is worse than no fixture, because a story then demonstrates the wrong thing convincingly.

- [ ] **Step 6: Commit**

```bash
git add src/lib/fixtures src/lib/components/*.test.ts src/lib/stores/*.test.ts
git commit -m "fixtures: one definition of what a view-model looks like

Eleven builders were private to four test files, eight of them in
timelineGrouping.test.ts. They move here unchanged — deliberately not
improved in transit, so a failure would mean the move broke something
rather than the rewrite did.

On top of them sit named scenarios, which are the actual content of a
design surface: the states worth looking at. That list also answers a
question the P1 audit raised and could not settle — what states does this
UI have? They were uncountable, which is why drift between platforms was
invisible.

The scenarios assert their own names. longUnbrokenToken containing a space
would be a fixture that demonstrates the opposite of the wrap hazard it is
named for, and convincingly.

Mutation-proven: a space in longUnbrokenToken, or a pending decision on
rosterQuiet, each fails its test."
```

---

## Task 5: The four remaining indicators

`TypingIndicator` (52) · `LiveActivity` (75) · `InvitationPanel` (79) · `AgentReasoning` (135)

Small, store-reading, no splitting. Follow the recipe. These establish the pattern on cheap components before it reaches the expensive ones.

**Files:** each component, its new `.stories.svelte`, `src/lib/fixtures/` additions, and the callers that must now pass props (`+page.svelte`, `Timeline.svelte`, `LiveTurn.svelte` — check each with `grep -rn "<TypingIndicator" src`).

- [ ] **Step 1: Convert all four** per the recipe's steps 1–5. Their stores: `typing.svelte`, `live.svelte`, `rooms.svelte`.
- [ ] **Step 2: Write one story file per component**, with a `<Story>` per state — including the empty state where the component renders nothing, since "appears when it should not" is the failure mode for three of these four.
- [ ] **Step 3: `pnpm check && pnpm test && pnpm storybook:build`** — expected clean.
- [ ] **Step 4: DOM diff** per the recipe. Expected identical.
- [ ] **Step 5: Look at all four in the catalogue**, in all three appearances. The a11y panel must be clean or its findings recorded.
- [ ] **Step 6: Commit** — `refactor(ui): four indicators take props`

---

## Task 6: SpacesRail and the three modal panels

`SpacesRail` (156) · `SearchPanel` (146) · `NewRoomPanel` (186) · `SpaceInvitePanel` (112)

The panels are the components that use `bg-scrim` and `shadow-overlay` — the two tokens P1 changed — so they are among the most worth seeing in a catalogue.

- [ ] **Step 1: Convert all four** per the recipe. Stores: `spaces.svelte`, `rooms.svelte`, `avatarCache.svelte`.
- [ ] **Step 2: Resolve avatars in the caller.** `SpacesRail` constructs its own `createAvatarCache()`; that moves to `+page.svelte`, and the rail takes resolved URLs. Per the spec, a leaf never takes a cache.
- [ ] **Step 3: Stories**, and for each panel a story with the scrim visible — that is the token P1 derived and the app had exactly one correct call site for.
- [ ] **Step 4: `pnpm check && pnpm test && pnpm storybook:build`.**
- [ ] **Step 5: DOM diff.** Expected identical.
- [ ] **Step 6: Commit** — `refactor(ui): the rail and the modal panels take props`

---

## Task 7: RoomList into RoomRow, RosterSection, ArrangementMenu

**Files:** `src/lib/components/roster/RoomRow.svelte`, `RosterSection.svelte`, `ArrangementMenu.svelte`, their stories; `RoomList.svelte` becomes the container.

- [ ] **Step 1: Split** per the recipe's steps 8–11. `RoomList` keeps roster arrangement and the store reads; `RoomRow` takes a `RoomRow` view-model plus `avatarUrl` and `onSelect`.
- [ ] **Step 2: Note the avatar rule in the container.** `RoomList.svelte`'s existing comment explains that avatars are fetched for *every* room, not gated on `room.avatarUrl` being set, because that field is only populated in some cases. Carry that comment to the container — it is the reason the container resolves rather than the leaf.
- [ ] **Step 3: Stories** for `RoomRow` using `rosterQuiet`, `rosterApprovalNeeded`, `rosterAgentWorking`, plus a long-name case.
- [ ] **Step 4: Checks and DOM diff.** Expected identical.
- [ ] **Step 5: Commit** — `refactor(ui): a roster row is a function of a RoomRow`

---

## Task 8: RoomInfoPanel into MemberRow and RoomIdentityHeader

- [ ] **Step 1: Split** per the recipe. The container keeps member loading and both avatar caches (`avatarCache`, `memberAvatarCache`); the leaves take resolved URLs.
- [ ] **Step 2: Stories.** `RoomIdentityHeader` must include the 64px-avatar fallback-initial case — the one that motivated the `--text-avatar` rank, because a 64px circle previously held a 15px letter.
- [ ] **Step 3: Checks and DOM diff.**
- [ ] **Step 4: Commit** — `refactor(ui): room info splits into a header and member rows`

---

## Task 9: LiveTurn into LiveTurnBubble

- [ ] **Step 1: Split** per the recipe. The container keeps the stream subscription; the bubble takes the turn view-model.
- [ ] **Step 2: Stories** covering a streaming turn, a settled turn, and a turn with reasoning — `AgentReasoning` is already its own component after Task 5, so compose them.
- [ ] **Step 3: Checks and DOM diff.**
- [ ] **Step 4: Commit** — `refactor(ui): a live turn bubble takes its turn`

---

## Task 10: Composer into MentionMenu, StagedAttachmentChip, ReplyBanner

`Composer.svelte` is 882 lines and holds draft, IME and mention behaviour. **The container keeps all of it.** Only the three presentational regions move.

- [ ] **Step 1: Split** per the recipe, moving only markup. The `event.isComposing` gap `AGENTS.md` records as a deferred minor is a container concern and is neither fixed nor moved here.
- [ ] **Step 2: `MentionMenu` keeps its `shadow-overlay`** — it is a popover floating above content, which is what that token means after P1 broadened the rule.
- [ ] **Step 3: Stories** for the mention menu with 0, 1 and many candidates; the attachment chip for an image and a non-image; the reply banner for a normal and a deleted target (`replyToDeleted`).
- [ ] **Step 4: Checks and DOM diff.** Pay attention here: the composer is where a DOM difference is most likely, because its markup is conditional on draft state.
- [ ] **Step 5: Commit** — `refactor(ui): the composer's three panels leave the container`

---

## Task 11: DispatchCard out of Timeline

**The signature element, and the reason this project exists.** ~215 lines of markup at `Timeline.svelte:1817–2032`, already a separate file on iOS and Android.

**Files:** `src/lib/components/timeline/DispatchCard.svelte`, `DispatchCard.stories.svelte`; modify `Timeline.svelte`.

- [ ] **Step 1: Move the markup** from the `{#if view.view.status === "placeholder"}` branch onward into the new component. Props: the `ItemView` outcome and `onResolveDecision`.
- [ ] **Step 2: Move `.dispatch-card` and `.dispatch-card-pending`** with it, including the comment explaining that `--radius-card` is 8px and was deliberately changed from 6px in P1.
- [ ] **Step 3: Carry the security comment verbatim.** The existing markup has a long comment recording that every value is plain-text interpolation — never `{@html}`, never an `href`/`src`/inline style — because `content` is arbitrary JSON from anyone who can send to the room. That comment is load-bearing documentation for a security property, and it must travel with the markup it describes.
- [ ] **Step 4: Stories** for all three states: `dispatchCardPending` (amber, the one reserved use), `dispatchCardAnswered` (buttons settled), `dispatchCardPlaceholder` (a log line, deliberately not a card). Plus `longUnbrokenToken` as a field value, to show the wrap guards working.
- [ ] **Step 5: Assert the security property in the story file's comment**, and check by hand that the moved markup contains no `{@html}`:

```bash
grep -n "@html\|href=\|src=" src/lib/components/timeline/DispatchCard.svelte
```

Expected: no `{@html}`, and no `href`/`src` fed from `content`.

- [ ] **Step 6: Checks and DOM diff.** Expected identical.
- [ ] **Step 7: Commit** — `refactor(timeline): the dispatch card becomes a component`, noting it is now a file on all three platforms.

---

## Task 12: The remaining Timeline leaves

`TimelineRow` · `MessageBubble` · `ReplyQuote` · `ReactionsRow` · `MessageActions` · `SeenMarker` · `LogLine` · `ImageAttachment` · `FileAttachment` · `UnreadMarker`

Each corresponds to an existing snippet whose signature is in this plan's header. The container keeps scroll, follow, pagination and gap-sync — out of bounds per the Global Constraints.

- [ ] **Step 1: Move one leaf at a time, checking after each.** Ten at once makes a DOM diff unattributable; the whole value of the gate is knowing which move broke it.
- [ ] **Step 2: `MessageActions` must keep its positioning contract.** `timelineActionAnchor.test.ts` asserts that any wrapper marked `group` is also `relative`, because the bar is `absolute top-full` and resolves against its nearest positioned ancestor — when it was not, the bar stretched across the window. That test asserts on the source, so it will keep passing after the move only if the relationship is preserved. Read it before moving this leaf.
- [ ] **Step 3: `.message-html` and its 23 `:global()` rules** move as one block, staying `:global()`, to whichever component renders message HTML. A re-scoped `:global()` rule silently stops applying, and rendered message content is where that is least visible.
- [ ] **Step 4: `.reaction-chip-mine` → `ReactionsRow`; `.fade-in` → `TimelineRow`.**
- [ ] **Step 5: Stories per leaf**, using the Task 4 scenarios: `senderRun` for run continuation, `reactionsMine`/`reactionsMany`, `encryptedPlaceholder` for `LogLine`, `ownMessageSending`/`ownMessageFailed` for `MessageBubble`.
- [ ] **Step 6: Checks and DOM diff after each leaf.**
- [ ] **Step 7: Confirm `Timeline.svelte` is now a container.** Expect roughly 950 script lines plus a thin markup shell. Record the before and after line counts in the commit.
- [ ] **Step 8: Commit** — one commit per leaf or one per coherent pair; not one commit for ten.

---

## Task 13: +page.svelte into PaneShell, RoomHeader, EmptyRoomState

The route keeps pane `matchMedia`, store wiring and selection — including the `belowToken` derivation P1 added. Only the three presentational regions move.

- [ ] **Step 1: Split** per the recipe. `PaneShell` takes pane-visibility booleans and snippets for each pane; it computes nothing.
- [ ] **Step 2: Do not move the breakpoint logic.** The long comment deriving 1238 and 1294 from the pane widths stays with the route, and `PaneShell` receives the resulting booleans. Moving the derivation would separate it from the comment that explains it, which is what P1 spent a commit fixing.
- [ ] **Step 3: Stories** for `PaneShell` at three pane counts, `RoomHeader` with a long room name and with a pending decision, `EmptyRoomState`.
- [ ] **Step 4: Checks and DOM diff at three widths** — the narrow collapse (<640), the overlay band, and the three-column layout. This is the one component where width is the variable that matters.
- [ ] **Step 5: Commit** — `refactor(ui): the route keeps geometry, the shell keeps layout`

---

## Task 14: CI, and proving fixtures never ship

**Files:** `.github/workflows/ci.yml`, `scripts/tests/test_fixture_leak.mjs`

- [ ] **Step 1: Write the leak check**

`scripts/tests/test_fixture_leak.mjs`:

```js
/**
 * Fixtures must never reach the production bundle.
 *
 * Nothing in application code imports $lib/fixtures, so Vite tree-shakes
 * it — but "so it should" is a belief. FIXTURE_MARKER exists to be searched
 * for.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const MARKER = "__supermessage_fixture_marker__";
const ROOT = "build";

function* files(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* files(p);
    else yield p;
  }
}

const hits = [...files(ROOT)].filter((p) => readFileSync(p, "utf8").includes(MARKER));

if (hits.length) {
  console.error(`::error::fixtures reached the production bundle: ${hits.join(", ")}`);
  process.exit(1);
}
console.log(`no fixture marker in ${ROOT} — fixtures are not shipped`);
```

- [ ] **Step 2: Prove it catches a leak**

Temporarily add `import { FIXTURE_MARKER } from "$lib/fixtures"; console.log(FIXTURE_MARKER);` to `src/routes/+page.svelte`, then:

```bash
pnpm build && node scripts/tests/test_fixture_leak.mjs
```

Expected: exit 1, naming the file. Remove the import, rebuild, re-run.
Expected: exit 0. **If it passes with the import present, the check is worthless** — Vite may have inlined the string; use a longer marker until it fails.

- [ ] **Step 3: Add to CI**

Extend the `frontend` job's filter to include `.storybook/**` and `src/lib/fixtures/**`, and add two steps after the existing build:

```yaml
      - name: Fixtures are not in the bundle
        run: node scripts/tests/test_fixture_leak.mjs

      - name: The story catalogue builds
        # A story referencing a prop that no longer exists should be a red
        # build, not a surprise next time someone opens the catalogue.
        run: pnpm storybook:build
```

- [ ] **Step 4: Verify the YAML parses**

```bash
python3 -c "import yaml; d=yaml.safe_load(open('.github/workflows/ci.yml')); print([s.get('name') for s in d['jobs']['frontend']['steps']])"
```

- [ ] **Step 5: Commit** — `ci: the catalogue builds, and fixtures stay out of the bundle`

---

## Task 15: Look at all of it

**Files:** none — this task produces evidence.

- [ ] **Step 1: Final DOM diff against the Task 1 baseline.**

```bash
node scripts/dom-baseline.mjs diff before after-final
```

Expected: identical on both surfaces. This is the definition of done: thirty-odd components moved, nothing rendered differently.

- [ ] **Step 2: Walk the whole catalogue in all three appearances.**

```bash
pnpm storybook
```

Every story, light then dark then paper. Looking specifically for: amber appearing anywhere that is not a pending decision (a review defect per `docs/design-language.md` §2); `content-faint` unreadable on the roster ground; the scrim veiling rather than erasing; the overlay shadow present on modals and popovers and nowhere else.

- [ ] **Step 3: Record the a11y findings.** The addon runs axe per story. Contrast should pass — P1 derived it — so anything it reports is ARIA or focus order, which is new information. Write the findings down rather than fixing them here; they are P4's material.

- [ ] **Step 4: Count the catalogue against the spec.** Every one of the fifteen components and twenty-three leaves has at least one story, or the gap is recorded with a reason. This list is what P2b's parity table compares iOS and Android against, so a missing story becomes a missing comparison.

- [ ] **Step 5: Write the findings into the PR description**, then open it. "Built successfully" is not evidence that it looks right.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §2.1 props down, stores at the route | 5–13 (recipe) |
| §2.2 all fifteen components | 5, 6, 7, 8, 9, 10, 11, 12, 13 |
| §2.3 caches resolve in containers | recipe step 4; 6 step 2; 7 step 2; 8 step 1 |
| §2.4 Storybook as design surface + a11y | 3; 15 step 3 |
| §2.5 one fixtures module | 4 |
| §2.6 vitest stays node-only | Global Constraints; no task adds a render test |
| §2.7 explicit appearance selectors | 2 |
| §4 the component breakdown | 5–13 |
| §4.1 scroll machinery untouched | Global Constraints; 12 |
| §4.2 styles travel with markup | recipe step 10; 11 step 2; 12 steps 3–4 |
| §5 what crosses the boundary | recipe steps 2–4 |
| §6 fixtures, builders and scenarios | 4 |
| §6 fixtures must not ship | 14 |
| §7.1 Storybook setup | 3 |
| §7.2 the generator change | 2 |
| §7.3 a11y addon | 3 step 2; 15 step 3 |
| §8 DOM diff as primary gate | 1; recipe; 15 step 1 |
| §8 supporting checks | recipe; 14 |
| §9 open questions | recorded, not implemented — correct |
| §10 non-goals | Global Constraints |

**Placeholder scan:** no TBDs. Tasks 5–13 use the plan-level recipe rather than restating it thirty times; that is a Global-Constraints-style shared requirement, not a "similar to Task N" reference, and each task carries its own specifics (which stores, which styles, which fixtures, which risks). Task 15 has no code because it produces evidence, which is stated.

**Type consistency:** `Props` is the exported interface name throughout. Snippet signatures in the header are quoted verbatim from `Timeline.svelte` and are the source for Tasks 11–12. `ItemRow` is used as defined in `timelineGrouping.ts` (`Extract<TimelineDisplayRow, { type: "item" }>`). `FIXTURE_MARKER` is defined in Task 3 and consumed in Task 14. `normalise`/`SURFACES` are defined in Task 1 and consumed by the recipe and Task 15.

**One risk I want on the record:** Task 1's baseline depends on a logged-in session and a specific room's content. If the account's rooms change between the baseline and the final diff, the comparison is void and the gate silently becomes decorative. Task 1 step 6 records the account and room for that reason, and Task 15 step 1 should be run against the same state or the baseline recaptured.
