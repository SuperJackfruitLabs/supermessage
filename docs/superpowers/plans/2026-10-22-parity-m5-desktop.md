# Parity M5 — Desktop Implementation Plan: navigation and first run

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each task is a bundle: one implementer dispatch, targeted
> tests while iterating, the full suites once before the commit, one review. **An implementer reads
> only its own task, plus Global Constraints and Review Focus.**

**Goal:** turn the desktop navigation and onboarding rows of the parity matrix ✅:
- N1, the four destinations (Chats, Needs you, Agents, Search) as **sidebar destinations above the
  roster**, with a count badge on Needs you, and Search moved there so it can be reached without a
  room open (owner gate G2);
- N2, the Needs you inbox and "You're all caught up";
- N3, the Agents view, with each agent's status and last activity;
- R3, the All · Unread filter chips over Chats;
- O1, the welcome screen and the local first-run demo, where nothing is sent anywhere;
- and the M1.6 consumer that has no other milestone: readable space names in the spaces rail and
  the roster's section headings.

Deep links are "n/a" on desktop (the matrix row: desktop routes matrix.to in-app; iOS does not), and
pull to refresh is Android-only in the roadmap (Excluded).

**Core prerequisites: none new, and no new Tauri command.** Every function is already a command
with an `ipc.ts` wrapper, on `main` or in the M1.6 PR (`parity/m1-core-first-run`, which added
`first_run_script`, `first_run_advance`, `first_run_answer` and `space_display_name` to
`src-tauri/src/commands.rs` and `ipc.ts`). This plan touches neither `crates/` nor `src-tauri/`.

**Architecture:**
- Logic in small `.ts` modules with Vitest tests (`inboxView.ts`, `agentsView.ts`,
  `destinations.svelte.ts`, `rosterFilter.ts`, `firstRun.ts`); components stay thin and get stories
  the snapshot gate renders.
- **One inbox source.** `destinations.svelte.ts` asks `rosterNeedsYouInbox(rows, nowMs)` once per
  roster change (latest-only) and serves both the badge and the Needs you list, so they cannot
  disagree.
- **The sidebar** (`<aside>` in `src/routes/+page.svelte`) keeps its title, connection line, "New
  conversation" and "Account"; between the title and the list it gains the destinations row, and
  its list area shows the chosen destination: `RoomList` (Chats), `NeedsYouList`, `AgentsList` or
  `SearchPanel` embedded (Search). The room pane is unchanged: it shows the selected room,
  whichever destination chose it, as the iPad's detail column does (`SplitShell.swift:131-148`).
- **The demo is local.** `FirstRunDemo.svelte` imports only `firstRunScript`, `firstRunAdvance`,
  `firstRunAnswer` from `ipc.ts`; its decision card is the real `DispatchCard`, handed a local
  `onDecide`. Task 4 pins it with an `invoke` allowlist.

**Tech stack:** Svelte 5 (runes), SvelteKit SPA, Tailwind v4 over the generated
`src/lib/tokens.css`, Vitest 4, Storybook 10 with the snapshot gate, Tauri 2.11.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` "Navigation and onboarding" (N1–N3, O1) and
"Room list" (R3); `notes/desktop.md` rows N1, N2, N3, O1, R3. Umbrella:
`docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md` "M5", "Decision gates" G2, "Review Focus" 3
and 5. Gates: `.superpowers/sdd/planning-m1-m2/owner-decisions.md` (G2, O2 "follow iOS exactly", O3
text size, O4, the pace decision, merge authorization). Format follows
`.superpowers/sdd/planning-m4/2026-10-22-parity-m4-desktop.md`.

**iOS citations** are to `apple/` at `f970e8f` (the tip of `parity/m1-core-first-run`, which has the
M1.1 and M1.6 switch-overs). The iPad's `SplitShell` and `SidebarDestinations` are the model for a
sidebar; the phone's `PhoneShell` is cited only for shared rules. Desktop line numbers are at
`9d07444` (`parity/m3-desktop`); M4 moves them. Find code by the quoted text.

**Branch and worktree:** `parity/m5-desktop`, in `.worktrees/parity-desktop`, cut from the tip of
`parity/m4-desktop` after its Task 5.

**Preconditions (controller, before Task 1):**
- M2 desktop Tasks 1–15, M3 desktop Tasks 1–5 and M4 desktop Tasks 1–5 are complete. M5 reuses M4's
  `StatusPill.svelte`, `roster/RoomAvatar.svelte`, the two-line `roster/RoomRow.svelte`, and
  `liveStore`'s `inProgress`; M3's `resetSessionState`; M2's `SearchPanel` scope and
  `ConnectionLine`.
- The branch contains `origin/main` with the **M1.6 PR** merged. `grep -n 'firstRunScript\|spaceDisplayName\|displayName: string' src/lib/ipc.ts`
  finds all three, and `grep -n 'first_run_script\|space_display_name' src-tauri/src/lib.rs` finds
  both registered. Run `pnpm test` and `pnpm check` after the merge, before Task 1.

### Interfaces consumed (`src/lib/ipc.ts`; Step 0 of each task greps them)

| `ipc.ts` | From | May change? |
|---|---|---|
| `rosterNeedsYouInbox(rows, nowMs): Promise<NeedsYouInbox { decisions; invitations; count; badgeCount }>` | M1a PR3 | no (main) |
| `rosterAgentDirectory(rows, nowMs): Promise<RosterRow[]>` | M1a PR3 | no |
| `rosterFilterSections(sections, filter)`, `rosterFilterChips(sections): Promise<RosterFilterChip[]>`, `rosterFilterFromKey(key)`, `rosterFilterKey(filter)`, `rosterFilterEmptyState(filter): Promise<RosterEmptyState>`, `rosterHiddenInvitations(rows, showsInvitations)` | M1a PR3 | no |
| `roomStatus(state, describesAgent, turnInProgress)`, `roomStatusWord(status)` | M1a PR3 | no |
| `rosterSections(rows, view, showsInvitations, nowMs)` | main | no |
| `firstRunScript(): Promise<FirstRunScript>`, `firstRunAdvance(step)`, `firstRunAnswer(step)`, `FirstRunStep` | M1.6 | yes (unmerged) |
| `spaceDisplayName(name)`, `SpaceSummary.displayName` | M1.6 | yes |
| `StatusPill`, `RoomAvatar`, two-line `RoomRow`, `liveStore.inProgress` | M4 desktop Tasks 1, 4 | yes (in progress) |

## Global Constraints

**From the roadmap and AGENTS.md (binding):**
- Tokens only: no hex in views. Colours, radii, type and shadows are the generated Tailwind roles
  (`bg-surface-raised`, `rounded-pill`, `text-meta`, `text-ui`, …). A new role goes through
  `design/tokens.toml` and `python3 scripts/generate-tokens.py`; never edit `src/lib/tokens.css`.
- Amber (`signal`) means a pending decision and nothing else. **The Needs you badge is `accent`**
  (`SplitShell.swift:171-178`: `accentContent` on `accent`), never amber. Only a row's pending
  preview, its Needs you dot and the Needs you status pill are amber.
- The app parses nothing and decides nothing that the core already decides: what is in the inbox,
  what the badge counts, the directory, each status and word, what a chip admits and counts, a
  stored chip read back, an empty chip's words, a space's readable name, and every word and pause
  of the demo are the core's.
- Copy is iOS's, verbatim, with its `file:line` (O2). A string iOS lacks is marked
  *(no iOS copy)*.
- The platform track never edits `crates/` or the generated bindings, and this plan adds no Tauri
  command.
- Every new UI state ships with a story. Its baseline is recorded once, after inspection. Never
  re-record a frame to make the gate pass.
- Motion: animate only `opacity` and `transform`; reduced motion means none (`src/app.css` zeroes
  durations; the gate renders with `reducedMotion: 'reduce'`). List arrivals and removals reuse
  `animateList` (`src/lib/components/animateList.ts`), as `RoomList` does.
- **Falsification (AGENTS.md):** for every new test, mutate the implementation, watch the test fail,
  restore it, and record what you saw. Each task names its mutations. Ordering or timing tests: run
  the mutant three times.
- Story failure fixtures reject with a **`CoreError` shape** (`{ kind, message }`), never
  `new Error(...)`. Story interactions must actually run (the plain-DOM `settled` helper pattern of
  `DispatchCard.stories.svelte` / `LeaveRoomConfirm.stories.svelte`).
- Modal views are native `<dialog>`s opened with `showModal()`, in the `LeaveRoomConfirm.svelte`
  shape: Escape closes through the `cancel` event and focus returns to the opener.
- Async asks are **latest-only and room/roster-guarded** (`createLatestOnly` in
  `src/lib/components/searchView.ts`): an answer for an older roster never overwrites a newer one.

**The first-run demo never touches the homeserver.** `FirstRunDemo.svelte` and `firstRun.ts` import
no session store and no `ipc.ts` function other than the three `firstRun*` reads; the card's
`onDecide` only calls `firstRunAnswer`. Pinned in Task 4 by
`the demo invokes nothing but the first-run commands`.

**This machine (owner rules, 2026-10-22):**
- Every heavy command takes the shared lock:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock <command>`. Heavy: any `cargo build|test|clippy`,
  `./scripts/snapshot-stories.sh`. Light (no lock): `pnpm test`, `pnpm check`, git, grep.
  `export CARGO_BUILD_JOBS=8` and
  `export CARGO_TARGET_DIR=/home/rakeshgangwar/Projects/superjackfruit/supermessage/target`.
- While iterating, run the focused Vitest file. Run the full suites **once per task**, before the
  commit. `/tmp` is RAM: put nothing large there.

**Snapshot gate:**
- The 7 frames listed in `.superpowers/sdd/m2-desktop/env-desktop.md` already differ on this machine
  (renderer drift); never re-record them (M4 deleted the two `AgentReasoning` ones with their
  component).
- Never `--record`. Run the gate, inspect `.snapshots-web/<story-id>.png`, copy only that file into
  `src/lib/stories/baseline/`, run the gate again.

**Live account: READ-ONLY.** You may open the app, switch destinations, open rooms, menus, panels and
dialogs, search, take screenshots and read the DOM. You may **not** send, react, answer, edit,
delete, block, report, mute, pin, leave, accept, decline, create a room, **sign in or sign out**.
To see the welcome and the demo on the live app without signing out, Task 4 uses `localStorage`
keys and the `/login` route only (it calls no session command). Never invoke a write command against
the live session, even with an argument you expect to be refused (M3 ruling on Q14).

**Desktop verification for every task:**
- `pnpm test`, `pnpm check`, and the gate:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./scripts/snapshot-stories.sh`.
- No task touches `src-tauri/`, so the cargo suites are not required per task; the binary is built
  once (`cargo build -p supermessage`, under the lock) for the running-app checks.
- Running the app: `pnpm dev` (port 1420) in the background, then
  `$CARGO_TARGET_DIR/debug/supermessage` built from this worktree. The embedded WebDriver listens
  on `127.0.0.1:4445`. Synthetic hover does not register in WebKitGTK; reveal hover-only UI with
  focus. Never verify with a bare `cargo run`.
- Window widths for RF2: 1905px and 600px (the narrow, one-pane layout under 640px); text size
  Largest (150 %, O3's zoom).

**Serialize:** tasks marked **[page]** edit `src/routes/+page.svelte`; **[roster]** edit
`src/lib/components/RoomList.svelte`; **[login]** edit `src/routes/login/+page.svelte`. The tasks run
one after another in plan order.

**Commits** end with the attribution lines in the session's system reminder.

## Review Focus

1. **Sign out and back in as another account** (roadmap RF5). The destination returns to Chats; no
   badge, inbox row, agent row, chip count, status word or search term from the first account
   shows. The onboarding keys are per install and survive (iOS, `WelcomeView.swift:6-8`). Pinned in
   Task 2 by `reset returns to Chats and forgets the inbox` and in Task 3 by
   `the chip survives sign-out but its counts do not`.
2. **A narrow window and the largest text** (roadmap RF3): the four destinations and the badge, the
   chips, the inbox headers, "You're all caught up", the agent row, the welcome and the demo at
   600px and 150 % must not clip or overflow. Stories at that size per task; the running app
   (read-only) in every task.
3. **The selected room survives a destination change** (iPad rule, `SplitShell.swift:52-56`):
   choosing Needs you, Agents or Search never closes the room pane's room; under 640px, choosing a
   destination shows the sidebar and a chosen room shows the room pane, as Chats does today.
   Pinned in Task 2 by `a destination change keeps the selected room`.
4. **The inbox is finishable.** A decision answered anywhere leaves the inbox and the badge in the
   same roster update; the last leaves "You're all caught up"; an invitation alone never lights
   the badge. Pinned in Task 1 by `a row that stops needing you leaves` and in Task 2 by
   `the badge counts decisions only`.
5. **The demo never touches the homeserver.** Pinned in Task 4 by
   `the demo invokes nothing but the first-run commands`.

---

### Task 1: The Needs you inbox and the Agents view (N2 list and caught-up state, N3)

**iOS behaviour and copy:**
- **The inbox** (`Inbox/NeedsYouView.swift:11-57`): the core's `NeedsYouInbox`. Two sections, each
  only when it has rows: **"Waiting on you"** over `decisions`, then **"Invitations"** over
  `invitations` (`:22-35`). A header is the title (`text-meta text-content-muted`) then the count
  (`text-meta text-content-faint`), gap 6px, not upper-cased (`:74-80`). Rows are the roster's own
  row (`:59-72`) — M4's two-line `RoomRow` with `RoomAvatar`, `showsState` as the roster has it.
  A row leaving animates (`:48-49`). Title **"Needs you"** (`:50`).
- **Caught up** (`:45-47`, `CaughtUpView` `:87-119`), when `count == 0`: a seal-with-check glyph at
  56px in `text-ok`, `aria-hidden`; **"You're all caught up"** (title3 → `text-ui-lg font-semibold`,
  `text-content`); **"Decisions and invitations that need you land here."** (`text-ui
  text-content-muted`, centred); gap 12px, padding 32px, centred in the list area, one element for
  assistive tech (`role="status"`). One bounce of the glyph 250 ms after it appears; **none** under
  reduced motion. (The inbox's own copy; the core's `rosterFilterEmptyState("needsYou")` is the
  unoffered chip's, not this.)
- **Agents** (`Agents/AgentsView.swift:11-63`): the core's `rosterAgentDirectory(rows, nowMs)` —
  recency order, no invitations. Title **"Agents"** (`:49`). Empty: **"No agents yet"**, a sparkles
  glyph, **"Rooms with an agent in them appear here."** (`:42-48`).
- **Status** (`:25-27`, `:58-63`): `roomStatus(entry.state, true, roomId === selectedId &&
  liveStore.inProgress)`, then `roomStatusWord(status)`. Only the open room can say Working.
- **The agent row** (`:67-103`): `RoomAvatar` at **44px** (`describesAgent`, the entry's state), gap
  12px; a column (gap 2px): line 1 the name (name face, one line, truncated), then the relative time
  (`text-meta text-content-faint`) pushed to the end when not empty; line 2 (gap 6px) the role
  (`text-ui text-content-muted`, one line) when there is one, then `StatusPill` compact. Vertical
  padding 4px. The whole row is one button; it selects the room.
- Previews to mirror as stories: iOS "Needs you", "Needs you, dark", "All caught up", "Agents",
  "Agents, dark" (`NeedsYouView.swift:121-142`, `AgentsView.swift:105-119`) over
  `Previews/NavigationRevampFixtures.swift` `fleetSession()` (`:95`) and `caughtUpSession()` (`:109`);
  port their rows into `src/lib/fixtures/rooms.ts` verbatim.

**Files:**
- Create: `src/lib/components/inboxView.ts` (+ test), `src/lib/components/agentsView.ts` (+ test),
  `src/lib/components/NeedsYouList.svelte` (+ stories), `src/lib/components/AgentsList.svelte`
  (+ stories), `src/lib/components/CaughtUp.svelte`, `src/lib/components/layout/ContentUnavailable.svelte`
  (iOS `ContentUnavailableView`'s shape — glyph, title, description, optional action; Task 3 reuses
  it).
- Modify: `src/lib/fixtures/rooms.ts` (the iOS fleet and caught-up rows).
- Create: `docs/superpowers/plans/2026-10-22-parity-m5-desktop.md`, a copy of this plan (R10).

**Interfaces:**
- Produces (`inboxView.ts`): `inboxSections(inbox: NeedsYouInbox): { id: "decisions" | "invitations"; title: string; count: number; rows: RosterRow[] }[]`
  (empty sections dropped, order fixed); `isCaughtUp(inbox): boolean` (`count === 0`).
- Produces (`agentsView.ts`): `createAgentStatuses(deps: { roomStatus; roomStatusWord })` with
  `refresh(agents: RosterRow[], openRoomId: string | null, inProgress: boolean): Promise<void>`
  (latest-only), `statusOf(roomId): { status: RoomStatus; word: string } | null`.
- `NeedsYouList` props `{ inbox: NeedsYouInbox | null; nowMs: number; showsState: boolean; avatars; onSelect: (roomId: string) => void; onOpenInfo?: (roomId: string) => void }`;
  `AgentsList` props `{ agents: RosterRow[]; nowMs: number; statuses; avatars; onSelect }`.
  `data-testid`s: `needs-you-list`, `needs-you-count-decisions`, `needs-you-count-invitations`,
  `caught-up`, `agents-list`, `agent-row`, `no-agents`.

- [ ] **Step 0:** grep `ipc.ts` for `rosterNeedsYouInbox`, `rosterAgentDirectory`, `roomStatus`; grep
  `src/lib/components` for M4's `StatusPill.svelte`, `roster/RoomAvatar.svelte` and their props.
- [ ] **Step 1: Write the failing tests.**
  - `inboxView.test.ts`: `decisions then invitations, each counted` (2 + 1 → two sections, counts 2
    and 1, titles verbatim); `a section with no rows is left out`; `an empty inbox is caught up`;
    `a row that stops needing you leaves` (RF4: the next inbox without `!a` → `!a` absent from
    `inboxSections`, and with nothing left `isCaughtUp`).
  - `agentsView.test.ts`: `only the open room can say Working` (fake `roomStatus` records its
    arguments: `!a` open with `inProgress` → `turnInProgress: true` for `!a` only; every call has
    `describesAgent: true`); `an older refresh never overwrites a newer one` (resolve the first
    `roomStatus` after the second → the second's statuses stand; three runs).
  - Stories: `NeedsYouList` "Needs you", "Needs you, dark", "All caught up", "Largest text at 600px";
    `AgentsList` "Agents", "Agents, dark", "No agents yet", "Largest text at 600px"; `CaughtUp`
    "Reduced motion" (no animation class). Each story's interaction asserts the visible counts and
    titles from the DOM.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The bounce is a CSS `@keyframes` on `transform: scale()` inside
  `@media (prefers-reduced-motion: no-preference)`, with a 250 ms `animation-delay`, applied once on
  mount. The seal and sparkles are inline SVG in `currentColor`. The relative time is
  `relativeTime` from `roomIdentity.ts`, as `RoomRow` uses.
- [ ] **Step 4: Run the desktop verification.** Inspect and record each new frame.
  - **Falsify:** title the decisions section with `inbox.count` → `decisions then invitations…`
    fails; pass `inProgress` to every row → `only the open room can say Working` fails; drop the
    latest-only guard → `an older refresh…` fails (three runs); always animate the seal → the
    "Reduced motion" story's class assertion fails.
- [ ] **Step 5: Commit.** `desktop: the Needs you inbox and the Agents view, from the core`.

### Task 2: Sidebar destinations above the roster, with the Needs you count; Search in the sidebar (N1, G2) **[page]**

**iOS behaviour and copy:**
- **The four places** (`Navigation/AppDestination.swift:6-28`): **"Chats"**, **"Needs you"**,
  **"Agents"**, **"Search"**, in that order; icons two bubbles, tray, sparkles, magnifier.
- **The row** (`Navigation/SplitShell.swift:151-201`, `SidebarDestinations`): "a row of labelled
  buttons rather than a segmented control: four segments in a 288pt column truncate 'Needs you',
  and the inbox's count needs somewhere to go" (`:153-155`). Gap 4px, padding 10px horizontal and
  6px vertical; each button fills its share, a column (gap 3px) of the glyph at 17px (semibold when
  selected) over the title in caption2, one line (iOS shrinks it to 0.8 rather than truncating);
  `text-accent` on `bg-accent-soft` with an 8px corner when selected, `text-content-muted` on
  nothing otherwise; vertical padding 6px.
- **The count** (`:170-180`, `:45`): `badgeCount` — **decisions only** — over the Needs you glyph's
  top-right (offset 10px, −6px), caption2 semibold, tabular digits, `text-accent-content` on
  `bg-accent`, a capsule with 4px horizontal padding; nothing at 0. The button's accessible name is
  **"Needs you, \(count)"** when the count is above 0, else "Needs you" (`:192-193`); the selected
  one carries the selected state (`:194`).
- **The sidebar shows the chosen destination** (`:113-129`); the room pane keeps the selected room
  whatever the destination (`:52-56`, `:131-148`). **Search is the search panel in the sidebar**,
  titled "Search" (`Panels/SearchPanel.swift:75`), scoped to the selected room when there is one
  (`SplitShell.swift:121-127`), with no Done; choosing a result selects its room.
- The toolbar stays over every destination on the iPad (`:79-84`): the desktop keeps its title
  area, "New conversation" and "Account" for every destination.
- Titles: Chats keeps the roster title "Chats" and the connection line under it (O4); Needs you,
  Agents and Search show their own title in the same place (`NeedsYouView.swift:50`,
  `AgentsView.swift:49`, `SearchPanel.swift:75`). The connection line stays under whichever title
  shows *(desktop keeps one line, O4; iOS shows it under the Chats title only)*.
- A destination change is a selection haptic on iOS (`:199`); the desktop has no haptics (excluded).
- The desktop today reaches Search only from the room header (`+page.svelte:1081-1093`, a modal
  overlay, `:1271-1281`). G2 moves it to the sidebar; the header button goes (owner Q2).

**Files:**
- Create: `src/lib/stores/destinations.svelte.ts` (+ `destinations.test.ts`) — the destination, the
  inbox and its badge, the directory, the agent statuses (Task 1's `createAgentStatuses`), refreshed
  latest-only from `roomsStore.rooms`, `nowMs` and `liveStore.inProgress`; `reset()`.
- Create: `src/lib/components/SidebarDestinations.svelte` (+ stories).
- Modify: `src/routes/+page.svelte` — the row between the title block and "New conversation"
  (`:844-861`); the list area (`:862-874`) switches on the destination; the title follows it; the
  header's Search button and the `searchOpen` overlay go (`:228-229`, `:1081-1093`, `:1271-1281`);
  `resetSessionState` callers also call `destinations.reset()`.
- Modify: `src/lib/components/SearchPanel.svelte` (+ stories) — an `embedded` prop: no backdrop, no
  `role="dialog"`, no Close; the field takes focus when the destination is chosen; Escape clears
  the term instead of closing; `onClose` optional.
- Modify: `src/lib/stores/sessionReset.ts` (+ test) if `resetSessionState` is where stores are
  reset (M3), so one call covers the destination too.
- Test: `destinations.test.ts`, `searchView.test.ts` (embedded Escape), `sessionReset.test.ts`.

**Interfaces:**
- Produces (`destinations.svelte.ts`):
  ```ts
  export type Destination = "chats" | "needsYou" | "agents" | "search";
  export const DESTINATIONS: { id: Destination; title: string }[]; // iOS order and titles
  export function createDestinations(deps: { rosterNeedsYouInbox; rosterAgentDirectory; roomStatus; roomStatusWord }): {
    readonly selected: Destination; select(d: Destination): void;
    readonly inbox: NeedsYouInbox | null; readonly badgeCount: number;
    readonly agents: RosterRow[]; statusOf(roomId: string): { status: RoomStatus; word: string } | null;
    refresh(rows: RoomRow[], nowMs: number, openRoomId: string | null, inProgress: boolean): Promise<void>;
    reset(): void;
  };
  export function destinationLabel(d: Destination, badgeCount: number): string; // "Needs you, 2"
  export const destinations: ReturnType<typeof createDestinations>;
  ```
- `SidebarDestinations` props `{ selected: Destination; badgeCount: number; onSelect: (d) => void }`;
  `<nav aria-label="Destinations">` of `<button>`s with `aria-current="page"` on the selected one;
  `data-testid` `destination-<id>`, `needs-you-badge`.

- [ ] **Step 0:** grep `+page.svelte` for `searchOpen`, `SearchPanel`, `resetSessionState`; confirm
  M4's header still has the Search button (or note where M4 moved it).
- [ ] **Step 1: Write the failing tests.**
  - `destinations.test.ts`:
    - `the four, in iOS's order, with iOS's titles`.
    - `the badge counts decisions only` (RF4): fake inbox 2 decisions + 1 invitation → `badgeCount`
      2; `destinationLabel("needsYou", 2) === "Needs you, 2"`, `destinationLabel("needsYou", 0) ===
      "Needs you"`.
    - `an invitation alone lights no badge`.
    - `the badge goes when the decision is answered`: a second `refresh` whose inbox has no
      decisions → `badgeCount` 0 in that call.
    - `an older roster's inbox never overwrites a newer one` (three runs).
    - `reset returns to Chats and forgets the inbox` (RF1): select `needsYou`, refresh, `reset()`
      → `selected === "chats"`, `inbox === null`, `badgeCount === 0`, `agents` empty.
  - `searchView.test.ts` (or the `SearchPanel` story): `embedded, Escape clears the term and keeps
    the panel`.
  - `sessionReset.test.ts`: `one reset covers the destination` (if wired there).
  - Stories: `SidebarDestinations` "Chats selected", "Needs you with 3", "Needs you, nothing
    waiting", dark, "600px at Largest" (no label clipped: the story's interaction asserts each
    label's `scrollWidth <= clientWidth`); `SearchPanel` "Embedded, scoped to a room",
    "Embedded, everywhere".
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The page calls `destinations.refresh(roomsStore.rooms, now,
  roomsStore.selectedId, liveStore.inProgress)` from an `$effect`. Choosing a destination under
  640px shows the roster group (as the "‹ Chats" back does today). Choosing a result in embedded
  Search calls `roomsStore.select(roomId)` and, when narrow, shows the room pane. The inline glyphs
  are SVG in `currentColor`. The 600px label rule: `text-label` with `truncate` is not allowed to
  cut "Needs you" — if it does at 150 %, the row wraps to two lines of two before any label
  truncates (Decision 3).
- [ ] **Step 4: Run the desktop verification.** Inspect and record each new frame; the sidebar
  frames that gain the row change — record only those.
  - **Falsify:** badge from `inbox.count` → `an invitation alone lights no badge` fails; drop the
    latest-only guard → `an older roster's inbox…` fails (three runs); keep the destination in
    `reset()` → `reset returns to Chats…` fails.
  - **Running app (read-only)** at 1905px and 600px, 100 % and 150 %: the row and the badge; Needs
    you, Agents, Search (type a term, open a result: the room pane shows it; switch to Agents: the
    room stays); the header has no Search button. Read `aria-current` and the badge's text from the
    DOM.
- [ ] **Step 5: Commit.** `desktop: Chats, Needs you, Agents and Search above the roster`.

### Task 3: Chats — filter chips, empty states, readable space names (R3, M1.6) **[roster]**

**iOS behaviour and copy:**
- **The chips** (`Rooms/RosterFilterChips.swift:14-57`): **"All"**, **"Unread"** — the core's
  `rosterFilterChips(arranged)` (only these two are offered). A horizontally scrolling row, gap 8px,
  padding 16px/6px. A chip: the title, then — for a chip other than All whose count is above 0 — the
  count in tabular digits (`text-accent-content` selected, `text-content-muted` otherwise);
  footnote (`text-ui`), semibold when selected; text `accent-content` selected, `content` otherwise;
  padding 12px/6px; `rounded-pill`, `bg-accent` when selected, `bg-surface-raised` with a 1px
  `border-border` otherwise. Accessible name **"\(title), \(count)"**; `aria-pressed` for the
  selected one.
- **Where** (`Rooms/RoomListView.swift:101-120`): at the top of the list's scroll content, above
  the invitations, and only when the account has rooms (`:111-113`). The desktop's
  `ArrangementMenu` stays above them (owner Q3).
- **What it does** (`:32`, `:53-60`, `:82-91`): the chip in force is `rosterFilterFromKey(stored)`;
  stored as `rosterFilterKey(filter)` under `localStorage` key **`supermessage.roster.filter`** (the
  desktop's prefix, as `supermessage.roster.view`, `RoomList.svelte:77`; iOS's key is
  `roster.filter`); the list is `rosterFilterSections(arranged, filter)` — it narrows, never
  re-orders; the counts come from the unfiltered arrangement.
- **Empty states** (`:179-199`), in order: no rooms — **"No rooms yet"**, tray glyph, **"Rooms
  appear here as they sync."** (replaces the desktop's "No rooms yet.", `RoomList.svelte:128-129`);
  the chip left nothing — `rosterFilterEmptyState(filter)`'s title and description ("Nothing
  unread", "Every room is read.") and a **"Show all"** button; every room filtered away with
  invitations hidden — **"Nothing but invitations"**, envelope glyph, **"\(n) waiting — open
  Invitations above."** — reachable on desktop only if the arrangement hides invitations (the
  desktop passes `showsInvitations: true`, `RoomList.svelte:166`), so it is implemented over
  `rosterHiddenInvitations` and tested in a unit, not a story, if no layout can reach it.
- **Readable space names** (M1.6): the rail's labels use `space.displayName`
  (`Rooms/SpaceMenu.swift:55`; desktop `spacesRailView.ts` `spaceEntryLabel`, which reads
  `identity.name` today); every roster section title goes through `spaceDisplayName`
  (`RoomListView.swift:136-139`) — the Machine arrangement's `Rakeshs-MacBook-Pro.local` reads
  "Rakeshs-MacBook-Pro", a generated id "Runtime 9247e5".

**Files:**
- Create: `src/lib/components/roster/rosterFilter.ts` (+ test), `src/lib/components/roster/FilterChips.svelte`
  (+ stories).
- Modify: `src/lib/components/RoomList.svelte` (+ stories: chips, the filtered sections, the empty
  states through `ContentUnavailable`, readable section titles),
  `src/lib/components/spacesRailView.ts` (+ test: `displayName`).
- Modify: `src/lib/stores/sessionReset.ts` only if chip counts live in a store (they should not:
  they are derived from the roster).

**Interfaces:**
- Produces (`rosterFilter.ts`): `createRosterFilter(deps: { rosterFilterSections; rosterFilterChips; rosterFilterFromKey; rosterFilterKey; rosterFilterEmptyState; storage: Pick<Storage, "getItem" | "setItem"> })`
  with `load(): Promise<void>` (reads the stored key through `rosterFilterFromKey`), `selected`,
  `select(filter): Promise<void>` (stores through `rosterFilterKey`),
  `apply(arranged: RosterSection[]): Promise<{ sections; chips; empty: RosterEmptyState | null }>`
  (latest-only); `sectionTitles(sections, spaceDisplayName): Promise<Map<string, string>>`.
- `FilterChips` props `{ chips: RosterFilterChip[]; selected: RosterFilter; onSelect }`;
  `data-testid` `filter-chips`, `chip-<key>`, `chip-count-<key>`.

- [ ] **Step 0:** grep `ipc.ts` for the five `rosterFilter*` wrappers and `spaceDisplayName`.
- [ ] **Step 1: Write the failing tests.**
  - `rosterFilter.test.ts`: `the stored chip is read through the core` (stored "agents" → the fake
    `rosterFilterFromKey` is asked and its `"all"` is selected); `choosing a chip stores the core's
    key`; `counts come from the whole arrangement` (`rosterFilterChips` receives the unfiltered
    sections even with Unread selected); `a chip that leaves nothing has the core's words`;
    `an older apply never overwrites a newer one` (three runs); `the chip survives sign-out but its
    counts do not` (RF1: a new `apply` over another account's arrangement yields only its chips).
  - `sectionTitles`: `a .local host reads without it` (fake `spaceDisplayName` asked once per
    distinct title).
  - `spacesRailView.test.ts`: `the rail names a space readably` (`displayName` "Runtime 9247e5" in
    the label, not the raw id).
  - Stories: `FilterChips` "All and Unread" (iOS `RosterFilterChips.swift:60-71`: All 5, Unread 2;
    asserts "All" has no count and Unread's count reads 2), "Unread selected", "Unread with none",
    dark, "600px at Largest" (the row scrolls; no chip wraps); `RoomList` "No rooms yet",
    "Nothing unread with Show all", "Readable machine sections".
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** No amber in the chips. The chip row is the list's first child inside
  the scrolling `<nav>`, under the `ArrangementMenu`.
- [ ] **Step 4: Run the desktop verification.** Inspect and record each new or changed frame; the
  roster frames that gain the chip row change.
  - **Falsify:** count from the filtered sections → `counts come from the whole arrangement` fails;
    read the key without the core → `the stored chip is read through the core` fails; render chips
    with no rooms → the "No rooms yet" story's assertion fails; drop `spaceDisplayName` →
    `a .local host reads without it` fails.
  - **Running app (read-only)**: the chips over the live roster; Unread; "Show all"; the Machine
    arrangement's headings; the rail's tooltips (`title`), at 1905px and 600px, 150 %.
- [ ] **Step 5: Commit.** `desktop: All and Unread chips, the roster's empty states, readable space names`.

### Task 4: The welcome screen and the first-run demo (O1); README and the verification table **[login] [page]**

**iOS behaviour and copy:**
- **Keys, per install** (`Onboarding/WelcomeView.swift:4-17`): **`onboarding.welcomeSeen`**,
  **`onboarding.demoPending`**, **`onboarding.demoSeen`** — on desktop the same names in
  `localStorage` (Decision 4).
- **Signed out** (`:19-32`): the welcome until it is passed, then the sign-in form. On desktop the
  `/login` route shows `Welcome.svelte` while `welcomeSeen` is unset.
- **The welcome** (`:35-83`): spacer (min 24px); the mark at **132px** wide (`/favicon.svg`, the
  generated mark, `scripts/generate-tokens.py` writes `static/favicon.svg`), `alt="supermessage"`,
  scaling 0.94 → 1 on a 0.6 s spring as it appears — **scale only, never opacity**, none under
  reduced motion; **"supermessage"** (large title bold → `text-2xl font-bold`), top margin 20px;
  **"Chat with your team and your agents — and approve their work without leaving the
  conversation."** (`text-ui text-content-muted`, centred, top margin 10px, horizontal padding 8px);
  spacer (min 24px); **"Sign in"** — `bg-accent text-accent-content`, full width, large; padding
  28px, at most 460px wide, centred, on `bg-surface`.
- **Queued** (`LoginView.swift:115-126`): only after an **interactive** sign-in that succeeded — on
  desktop where `/login` calls `markFreshSignIn()` (`src/routes/login/+page.svelte:36-37`), it also
  sets `demoPending`. A restore never does.
- **Shown** (`RootView.swift:89-139`): over the signed-in app, full window, when `demoPending &&
  !demoSeen` and **no new recovery key is being shown** — on desktop, while `RecoveryPanel` is open
  with an `initialKey` from `ensureRecovery` (`+page.svelte:663-672`) the demo waits; it opens when
  that panel closes. Finishing or skipping sets `demoSeen`, clears `demoPending` (`:136-139`).
- **The demo** (`Onboarding/FirstRunDemoView.swift:17-147`), every word and pause from
  `firstRunScript()`:
  - a header with `agentName` ("Atlas") and `skipLabel` ("Skip") at the end on every step **except
    approved**;
  - a scrolling column, gap 16px, padding 16px, at most 560px, centred, on `bg-surface`;
  - `banner` ("A sample conversation — nothing here is sent anywhere.") `text-meta
    text-content-faint`, centred;
  - on **greeting** the typing bubble: `RoomAvatar` (32px, `describesAgent`, state `active`, the
    tile for `script.roomId`, initial `script.initial`), then an ellipsis (`text-content-muted`,
    animating unless reduced motion), padding 14px/10px, `bg-surface-raised`, 14px corner (the
    desktop's `rounded-card`), `aria-label` `typingLabel` ("Atlas is typing"); from **asking** on,
    the message: the avatar, then the name with `agentTag` ("Agent", `text-label font-semibold
    text-accent bg-accent-soft rounded-pill px-1.5`), and `greeting` in the body face, padding 12px,
    `bg-surface-raised`, `rounded-card`;
  - on **waiting** and **approved**: the real `DispatchCard`, given a `customEvent` view built from
    `script.card`, `script.cardLabel`, `script.cardEventType`, a local `TimelineItem` (no
    `eventId`, `timestampMs` now, sender `agentName`), and `onDecide = async (_e, _d, optionId) =>
    demo.answer(optionId)`; no `answeredHere`/`onAnswered`, so nothing reaches
    `answeredDecisions`;
  - on **approved**: a check-circle at 40px in `text-ok` (one bounce, `aria-hidden`),
    `approvedTitle` ("That's it — approvals from chat.") `text-ui-lg font-semibold`, centred,
    `approvedBody` ("When an agent needs you, it asks in its room, and the Needs you tab keeps the
    list.") `text-ui text-content-muted`, centred, and `finishLabel` ("Get started") — `bg-accent
    text-accent-content`, full width;
  - pacing: after each of `stepDelaysMs` (1100, then 900) the step advances through
    `firstRunAdvance`; it stops at the card; **under reduced motion the pace stays** (`:94-101`);
  - arrivals rise from below with a fade on a 0.45 s spring-like curve; reduced motion: none (the
    app's rule zeroes durations);
  - any answer advances (`firstRunAnswer("waiting") === "approved"`), as on iOS.

**Files:**
- Create: `src/lib/onboarding.ts` (+ test: the three keys over an injected `Storage`, defaults
  false), `src/lib/components/firstRun.ts` (+ test: the step machine over injected
  `firstRunAdvance`/`firstRunAnswer` and an injected `sleep`), `src/lib/components/Welcome.svelte`
  (+ stories), `src/lib/components/FirstRunDemo.svelte` (+ stories; a full-window `<dialog>` opened
  with `showModal()`; Escape is Skip).
- Modify: `src/routes/login/+page.svelte` — the welcome before the form; `demoPending` beside
  `markFreshSignIn()`.
- Modify: `src/routes/+page.svelte` — the demo's gate (flags, and the recovery panel not showing a
  new key).
- Modify: `README.md` (the desktop cells of N1, N2, N3, R3, O1; the deep-link row stays "n/a"),
  against the merged code.
- Create: `.superpowers/sdd/m5-desktop/m5-desktop-verification.md`.
- Test: `onboarding.test.ts`, `firstRun.test.ts`, `firstRunIsolation.test.ts` (new; see below).

**Interfaces:**
- Produces (`firstRun.ts`): `createFirstRunDemo(deps: { script: FirstRunScript; advance: typeof firstRunAdvance; answer: typeof firstRunAnswer; sleep: (ms: number) => Promise<void> })`
  with `step`, `chosen: string | null`, `run(): Promise<void>` (the paced advances; stops when
  disposed), `answer(optionId): Promise<boolean>`, `dispose()`.
- Produces (`onboarding.ts`): `onboarding(storage)` with `welcomeSeen`, `demoPending`, `demoSeen`
  getters and `passWelcome()`, `queueDemo()`, `finishDemo()`.
- `FirstRunDemo` props `{ onFinish: () => void }` — **no session or store prop**; `data-testid`
  `first-run`, `first-run-skip`, `first-run-finish`.

- [ ] **Step 0:** grep `ipc.ts` for `firstRunScript`, `FirstRunScript`'s fields and `FirstRunStep`.
- [ ] **Step 1: Write the failing tests.**
  - `firstRun.test.ts`: `it only gets past the card on the reader's answer` (answer on `greeting` →
    false; two advances → `waiting`; answer → `approved`, `chosen === "approve"`; a second answer →
    false); `the pace is the script's` (the injected `sleep` saw `[1100, 900]`, in order);
    `disposing stops the pace` (dispose after the first sleep → `advance` called once).
  - `onboarding.test.ts`: the three exact key names; `queueDemo` then `finishDemo` →
    `demoSeen` true, `demoPending` false.
  - `firstRunIsolation.test.ts` — **the demo invokes nothing but the first-run commands** (RF5):
    mock `@tauri-apps/api/core`'s `invoke` with a recorder; mount `FirstRunDemo` with an immediate
    `sleep`; advance to the card; click "Approve"; click "Get started" → every recorded command is in
    `{"first_run_script", "first_run_advance", "first_run_answer", "agent_avatar_style"}` (the last
    only if M4's `RoomAvatar` asks for the tile), and none is `send_*`, `*_decision*`,
    `send_permission_answer` or `mark_*`.
  - Stories: `Welcome` and dark (iOS `WelcomeView.swift:86-96`), "600px at Largest";
    `FirstRunDemo` "Greeting", "Waiting on the card", "Approved", dark (iOS
    `FirstRunDemoView.swift:150-160`), "600px at Largest" — each at a fixed step (`sleep` never
    resolves); the "Approved" story asserts "Skip" is absent.
  - A login-route test (or story with the route's markup extracted into `LoginGate.svelte`):
    `a first launch welcomes, then the form`, `passing the welcome is remembered`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement** as above. The page opens the demo from an `$effect` over the flags and
  `recoveryOpen && recoveryNewKey !== null`. The mark's arrival is `@keyframes` on `transform` only.
- [ ] **Step 4: Run the desktop verification, then the branch-end pass.**
  - **Falsify:** set `demoPending` on restore too → the login test's "restore never queues" case
    fails; open the demo while the new key shows → the gate test fails; skip the sleeps under
    reduced motion → `the pace is the script's` fails; hand the card the page's real decision
    sender → `firstRunIsolation.test.ts` fails; fade the mark → the Welcome story's
    `opacity === "1"` assertion on the first frame fails.
  - **Running app (read-only):** with WebDriver, `localStorage.removeItem("onboarding.welcomeSeen")`
    and navigate to `/login` → the welcome (no session command is called; navigate back to `/` to
    restore); `localStorage.setItem("onboarding.demoPending","true")`,
    `removeItem("onboarding.demoSeen")`, reload `/` → the demo over the live app; wait for the
    card; Approve; Get started; then restore the keys to how they were. Check the `pnpm dev`
    console and `webviewLog` show no command other than the first-run ones during the demo.
  - README: correct the desktop cells against the merged code.
  - Fill `m5-desktop-verification.md`: one line per exit row, ✅ only with a test name, a story
    frame or a screenshot.
  - **Owner checklist** in the verification file: on a fresh profile, the welcome then the form; an
    interactive sign-in → the recovery key, then the demo; skip or finish; relaunch → no demo.
- [ ] **Step 5: Commit.** `desktop: a welcome before sign-in, a first-run demo where nothing is sent; README after parity M5`.

---

## Excluded, and why

| Item | Why it is not in this plan | Where |
|---|---|---|
| Search scope (this room / everywhere) | M2 Task 12; Task 2 only moves the panel into the sidebar. | M2 |
| Deep links (`supermessage://`) | "n/a" in the matrix: the desktop routes matrix.to in-app and has no widgets that would open links. | — |
| Pull to refresh | Android-only in the roadmap; a desktop roster has no pull gesture and the core streams the roster live. | — |
| Destination haptics | The desktop has no haptics (M2/M4 precedent). | — |
| The Needs you count in the window title and the tray | G4. | M7 |
| Agents and Needs you as chips; a Mentions chip | Not offered by the core (`OFFERED_FILTERS`); iOS dropped them because they are destinations. | Core backlog |
| Moving the arrangement menu into Account (iOS/Android) | Owner Q3; the desktop's Machine arrangement is a desktop feature iOS lacks. | Owner Q3 |
| The space's name as the Chats title | iOS titles Chats with the selected space; the desktop names the space in the rail. Not in a matrix row. | — |
| A fleet-wide inbox ignoring the selected space | Owner Q1; a core change for all three hosts. | Owner Q1 |
| Keyboard shortcuts for the destinations | No iOS behaviour to match; Tab and Enter reach every button. | — |

## Exit: rows this plan turns ✅

| Matrix row | Task | Verified by |
|---|---|---|
| N1 destinations above the roster; Search reachable without a room | 2 | `destinations.test.ts`, `SidebarDestinations` stories; running app |
| N2 Needs you inbox, badge, "You're all caught up" | 1, 2 | `inboxView.test.ts`, `the badge counts decisions only`, `NeedsYouList` stories; running app |
| N3 Agents view with status and last activity | 1 | `agentsView.test.ts`, `AgentsList` stories; running app |
| R3 filters (All, Unread) | 3 | `rosterFilter.test.ts`, `FilterChips` and `RoomList` stories; running app |
| O1 welcome and local first-run demo | 4 | `firstRun.test.ts`, `firstRunIsolation.test.ts`, `Welcome`/`FirstRunDemo` stories; running app via `localStorage`; owner checklist |
| Readable space names | 3 | `spacesRailView.test.ts`, `a .local host reads without it` |
| Deep links | — | n/a (unchanged) |
| Review Focus 1–5 (this plan's) | 1–4 | the tests named in Review Focus |

## Desktop behaviour changes (visible)

1. A row of four destinations under the roster title; Needs you carries a count.
2. The sidebar shows the Needs you inbox, the Agents directory or the search panel when chosen; the
   room pane keeps its room.
3. The room header's "Search" button is gone; search lives in the sidebar, scoped to the open room.
4. Chips "All · Unread" above the roster; the roster's empty states use iOS's words.
5. Space names read as people say them, in the rail and in the Machine arrangement's headings.
6. A welcome before the first sign-in form; a one-time demo after the first interactive sign-in.

## Decisions taken in this plan (technical; the controller may overrule)

1. **One inbox source** for the badge and the list (`destinations.svelte.ts`), asked once per roster
   change, latest-only.
2. **Statuses for the Agents view are asked per row** through `roomStatus`/`roomStatusWord` (a few
   dozen IPC calls per roster change, as M4's header asks once per room). A batched command would be
   a new Tauri command over the same core functions; not needed at this size.
3. **Labels never truncate "Needs you".** iOS shrinks the caption to 0.8 rather than cut it; the
   desktop has no shrink-to-fit, so at 150 % in a narrow sidebar the row may wrap to two rows of
   two before any label truncates.
4. **Onboarding keys in `localStorage` under iOS's names** (`onboarding.*`), per install, not per
   account, as iOS's `@AppStorage`; they are conveniences, and a cleared store only shows the
   welcome again.
5. **The demo's card is the real `DispatchCard`** with a local `TimelineItem` and `onDecide`, no
   `answeredHere`/`onAnswered`, so the shared `answeredDecisions` store never sees the demo.
6. **Search in the sidebar keeps the M2 panel** through an `embedded` prop rather than a second
   component, so its scope, retry and keyboard rules stay one implementation.

Open questions for the owner are in `.superpowers/sdd/planning-m5/open-questions.md`.
