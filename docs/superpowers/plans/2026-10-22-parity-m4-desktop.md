# Parity M4 — Desktop Implementation Plan: the agent experience

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each task is a bundle: one implementer dispatch, targeted
> tests while iterating, the full suites once before the commit, one review. **An implementer reads
> only its own task, plus Global Constraints and Review Focus.**

**Goal:** turn the desktop agent-experience rows of the parity matrix ✅ — A1 (a header status
pill), T1 (agent message cards and the serif long read), A2 (**one live card inside the timeline**,
replacing the three strips outside the list), A3 ("Sent to" → "is on it"), T6 (the turn record and
"What I did" in the answer row), D6 (the quote hidden under its parent), D11 (one name per person on
the typing line and the live card) and R1 (agent tiles, the dot on the avatar, two-line rows) — plus
the two desktop deferrals that belong here (M3 Q9: the `@` menu from the room's cast, agents first
with "{harness} on {host}"; M3 Q10: "Read by {names}" on reader faces), and pass roadmap Review
Focus 1 and 2 in the running app (M4's exit).

**Core prerequisites.** `.superpowers/sdd/planning-m4/core-prereqs.md`. The desktop needs **neither
P1** (the Tauri `acknowledgement` command already reads the core's rows,
`src-tauri/src/commands.rs` → `acknowledgement::for_focused`) **nor anything else from the Core
track** beyond M1b. **P2** (`TurnSummary.agentId`, owner Q1) is wired in Task 5 only if it merged.

**Architecture:**
- **No core change and no binding change.** One new Tauri command, `room_mention_candidates`, in
  `src-tauri/src/commands_session.rs` (M3's file for commands over `Session`), composing two
  existing core functions (Task 5). Everything else is already a command: `turn`, `onTurn`,
  `elapsedLabel` (M1b C4), `roomCast`, `typingView`, `acknowledgement`, `roomStatus`,
  `roomStatusWord`, `rosterRow` (M1a), `peopleLabel` (M3 desktop Task 1).
- Logic lives in small `.ts` modules with Vitest tests; components stay thin and get stories the
  snapshot gate renders.
- `liveStore` becomes a mirror of the core's turn record for the focused room, as iOS's `LiveStore`
  does after M1b C5. The live card is a row of the virtualized list, so `LiveTurn.svelte`,
  `LiveActivity.svelte`, `AgentReasoning.svelte` and `reasoningLabel.ts` go.
- **M1b names may still change.** This plan uses the interfaces as written in
  `.superpowers/sdd/planning-m1-m2/2026-10-22-parity-m1b-turns-timeline.md` (only C1 is committed at
  planning time). Each task's Step 0 greps `src/lib/ipc.ts` for the names in "Interfaces consumed";
  if one differs, use the merged name and say so in the report.

**Tech stack:** Svelte 5 (runes), SvelteKit SPA, Tailwind v4 over the generated
`src/lib/tokens.css`, Vitest 4, Storybook 10 with the snapshot gate, virtua `VList`, Tauri 2.11.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` rows A1, T1, A2, A3, T6, D5, D6, D11, R1
and `notes/desktop.md` (same IDs). Umbrella: `docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md`
"M4" and "Review Focus" 1–2 (the exit), 3 and 5. Format follows
`.superpowers/sdd/planning-m3/2026-10-22-parity-m3-desktop.md`.

**iOS citations** are to `apple/` at `e6d934b` (`parity/m1-core-first-run` = `origin/main` + M1a
PR5 + M1b C1). **M1b C5 rewrites `LiveTurnView.swift:55-211` and `WhatIDidFooter.swift:24-60` to
read `TurnSummary`; C7/C10 move `TimelineRowView.swift`/`TimelineCollectionView.swift` call sites.**
The copy, sizes and timings quoted here do not change; find a moved line by the quoted text.
Desktop line numbers are at `a1ee3c6` (`parity/m2-desktop`); M2 Tasks 14–15 and M3 move them.

**Branch and worktree:** `parity/m4-desktop`, in `.worktrees/parity-desktop`, cut from the tip of
`parity/m3-desktop` after its Task 5.

**Preconditions (controller, before Task 1):**
- M2 desktop Tasks 1–15 and M3 desktop Tasks 1–5 are complete (`commands_session.rs`,
  `peopleLabel`, `resetSessionState`, the safety store, the `RowMenu`).
- The branch contains `origin/main` with **M1b Batch A (C1–C5) and Batch B (C6–C12)** merged:
  `onTurn`/`turn`/`elapsedLabel` in `ipc.ts`, `toolSeen` gone from `live.svelte.ts` (C4), the
  timeline grouping read from the core with the membership stretch (C12). Run `pnpm test` and
  `pnpm check` after the merge, before Task 1.

### Interfaces consumed (names as planned in M1b; Step 0 checks them)

| `ipc.ts` | From | May change? |
|---|---|---|
| `onTurn(handler: (u: TurnUpdate) => void)`, `turn(roomId): Promise<TurnSnapshot \| null>` | C4 | yes |
| `TurnUpdate { summary; step?: TurnStepChange }`, `TurnStepChange { index; step }` | C2/C4 | yes |
| `TurnSummary { roomId; turnId; revision; inProgress; endedBy?; startedAtMs; endedAtMs?; lastActivityMs; answering; hasThought; totalSteps; completedSteps; failedStep?; currentStepTitle; stepIsTool; recordLabel? }` | C2/C4 | yes |
| `TurnSnapshot { summary; steps: TurnStep[]; thought? }`, `TurnStep { id; title; kind?; status; phase; statusLabel; locations; input?; output?; seq; hasDetail }` | C2/C4 | yes |
| `elapsedLabel(elapsedMs: number): Promise<string>` | C4 | yes |
| `TimelineRow.isAgent?`, `.isLongRead?`, `.hidesQuote?`, `.showsRuntime?`, `.continuesRun?`, `.endsRun?` | C6, C9 | yes |
| `roomStatus(state, describesAgent, turnInProgress)`, `roomStatusWord(status)`, `rosterRow(rows, roomId, nowMs)`, `RosterRow.avatar?: AgentAvatarStyle`, `AvatarRole` | M1a | no |
| `roomCast(roomId): Promise<RoomCast>`, `typingView(typers, agentIds, counterpart)`, `acknowledgement(roomId, addressee, agentIds, typingAgents, turnInProgress)` | M1a | no |
| `peopleLabel(userIds): Promise<string>` | M3 desktop Task 1 | no |
| `TurnSummary.agentId?` | P2 (owner Q1) | new, optional |

## Global Constraints

**From the roadmap and AGENTS.md (binding):**
- Tokens only: no hex in views. Colours, radii, type and shadows are the generated Tailwind roles
  (`bg-surface-raised`, `rounded-card`, `text-meta`, `shadow-overlay`, `font-serif`, …). A new role
  goes through `design/tokens.toml` and `python3 scripts/generate-tokens.py`; never edit
  `src/lib/tokens.css`.
- Amber (`signal`) means a pending decision and nothing else. In this plan only "Needs you" (the
  pill's word and ground, the roster dot) and a pending preview are amber. Working, Done, failures,
  the dock and the Agent label never are.
- The app parses nothing and decides nothing that the core already decides. Status, words, step
  titles, counts, the record label, elapsed text, who is an agent, what is a long read, which quote
  hides, the typing line, the dock text and mention matching are the core's. A Matrix id is never
  split in TypeScript.
- Copy is iOS's, verbatim, with its `file:line`. A string iOS lacks is marked *(no iOS copy)*.
- The platform track never edits `crates/` or the generated bindings. A needed core change is filed
  to the Core track, never worked around in UI code.
- Every new UI state ships with a story. Its baseline is recorded once, after inspection. Never
  re-record a frame to make the gate pass.
- **Streaming rules (AGENTS.md):** never animate the whole list; a streaming cell's resize must not
  animate; animate only `opacity` and `transform` — a height change inside `VList` breaks virtua's
  measurements (`src/lib/components/animateList.ts:24-32`). No `transition` on `height`,
  `max-height`, `top` or `margin` anywhere inside the timeline. Reduced motion means none
  (`src/app.css` zeroes durations; the gate renders with `reducedMotion: 'reduce'`).
- **Falsification (AGENTS.md):** for every new test, mutate the implementation, watch the test fail,
  restore it, and record what you saw. Each task names its mutations. Ordering or timing tests: run
  the mutant three times.
- Story failure fixtures reject with a **`CoreError` shape** (`{ kind, message }`), never
  `new Error(...)`. Story interactions must actually run (the plain-DOM `settled` helper pattern of
  `DispatchCard.stories.svelte` / `LeaveRoomConfirm.stories.svelte`).
- Modal views are native `<dialog>`s opened with `showModal()`, in the `LeaveRoomConfirm.svelte`
  shape: Escape closes through the `cancel` event and focus returns to the opener.

**This machine (owner rules, 2026-10-22):**
- Every heavy command takes the shared lock:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock <command>`. Heavy: any `cargo build|test|clippy`,
  `./scripts/snapshot-stories.sh`. Light (no lock): `pnpm test`, `pnpm check`, `cargo fmt`, git,
  grep. `export CARGO_BUILD_JOBS=8` and
  `export CARGO_TARGET_DIR=/home/rakeshgangwar/Projects/superjackfruit/supermessage/target`.
- While iterating, run the focused Vitest file. Run the full suites **once per task**, before the
  commit. `/tmp` is RAM: put nothing large there.

**Snapshot gate:**
- The 7 frames listed in `.superpowers/sdd/m2-desktop/env-desktop.md` already differ on this machine
  (renderer drift); never re-record them. Two of them (`live-agentreasoning--streaming-long`,
  `--streaming-short`) belong to `AgentReasoning.stories.svelte`, which Task 2 deletes with its
  component — deleting a story is not re-recording it; say so in the report.
- Never `--record`. Run the gate, inspect `.snapshots-web/<story-id>.png`, copy only that file into
  `src/lib/stories/baseline/`, run the gate again.

**Live account: READ-ONLY.** You may open the app, open rooms, menus, panels and dialogs, take
screenshots and read the DOM. You may **not** send, react, answer, edit, delete, block, report,
mute, pin, leave, accept or create a room. Anything that needs a write goes under "Live
verification pending"; the owner's live pass (Q2) covers it. Never invoke a write command against
the live session, even with an argument you expect to be refused (M3 ruling on Q14).

**Desktop verification for every task:**
- `pnpm test`, `pnpm check`, and the gate:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./scripts/snapshot-stories.sh`.
- For a task that touches `src-tauri/` (Task 5 only), also, each under the lock:
  `cargo test --workspace`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`;
  and `cargo fmt --all --check` (light).
- Running the app: `pnpm dev` (port 1420) in the background, then `$CARGO_TARGET_DIR/debug/supermessage`
  built from this worktree (`cargo build -p supermessage` under the lock). The embedded WebDriver
  listens on `127.0.0.1:4445`. Synthetic hover does not register in WebKitGTK; reveal hover-only UI
  with focus. Never verify with a bare `cargo run`.
- Window widths for RF3: 1905px and 600px; text size Largest (150 %, M2 Task 13's zoom).

**Serialize:** tasks marked **[page]** edit `src/routes/+page.svelte`, **[timeline]** edit
`src/lib/components/Timeline.svelte`, **[composer]** edit `src/lib/components/Composer.svelte`. The
tasks run one after another in plan order; none run in parallel on this track.

**Commits** end with the attribution lines in the session's system reminder.

## Review Focus

1. **A bridge agent that answers by editing a progress message** (roadmap RF1, M4 exit). No deltas
   → the core starts no turn → no card, no "Working" pill; a turn that did start ends when the
   agent's new or edited message lands (M1b C2/C3) and the card gives way to "What I did"; the dock
   goes when the agent's message lands, edited or not. Pinned in Task 2 by
   `a bridge that only edits its progress message shows no card` and
   `a turn the core ended by a reply leaves its record`, and in Task 5 by
   `a progress message the agent edits ends the dock`.
2. **A room with several agents and several humans** (roadmap RF2, M4 exit). No "Sent to" without
   one addressee; every typing agent named the core's way; a human typing stays on the people's
   line; the card names the writer (P2, or iOS's rule); runtime suffixes show once two senders
   speak (`showsRuntime`). Pinned in Task 1 by `the typing line is asked with the room's cast`, in
   Task 3 by `attribution follows showsRuntime`, and in Task 5 by
   `several agents typing are named and nobody is "Sent to"`.
3. **A narrow window and the largest text** (roadmap RF3): the pill, the live card's header and
   step lines, the Agent label beside a long name, the Read button, the two-line row, the dock and
   the `@` menu at 600px and 150 % must not clip or overflow. Stories at that size per task, and the
   running app (read-only) in Tasks 2–5.
4. **Sign out and back in as another account** (roadmap RF5). No turn, record, cast, typing agent,
   dock or reader label from the first account. Pinned in Task 1 by
   `resetSessionState forgets the turn and the cast` and in Task 5 by `reset forgets the dock`.
5. **The live card growing inside the virtual list.** History does not slide, gap or overlap; the
   reader at the newest end stays there; a reader scrolled up is not moved. Pinned in Task 2 by the
   "Timeline — a streaming turn" probe story and `the live row is the last row only while in
   progress`.

---

### Task 1: The turn mirror, the room's cast, and one name per person on the typing line **[page]**

**iOS behaviour this ports** (iOS after M1b C5 and M1a Task 13; plan text in
`2026-10-22-parity-m1b-turns-timeline.md` C5 "Interfaces"):
- `LiveStore` mirrors the focused room: `answer`, `thought`, `steps`, `summary`; `finished =
  summary.endedBy != null`, `inProgress = summary.inProgress`, `isLive = summary != null || answer
  != null`; `apply(update)` for the focused room only, dropping an older `revision`; a new `turnId`
  drops the last turn's steps, reasoning and answer; a step change lands at the core's index (index
  == length appends); `focus(roomId)` resets and restores from `turn(roomId)`; the answer and
  thought text from `onLive`/`onThought`, the answer going on `done`; an agent name per room.
- `TimelineView.swift:188-195` (`loadCast`): the room's cast loads per room; the typing line and
  the live card's name use it (D11).
- `TypingStore` (M1a Task 13): one `typingView(typers, cast.agentIds, cast.counterpart)` call gives
  both the people's line and the typing **agents** (the dock's input, Task 5).

**Files:**
- Modify: `src/lib/stores/live.svelte.ts` (rewrite) and `live.test.ts` (rewrite).
- Create: `src/lib/stores/liveTurn.ts` and `liveTurn.test.ts` (the pure reducer).
- Create: `src/lib/stores/cast.svelte.ts` and `cast.test.ts`.
- Modify: `src/routes/+page.svelte` — focus the live store and the cast on room change; the typing
  `$effect` (M1a Task 12's `typingView(boundTypers(typingStore.users), [], null)`) passes the cast;
  keep `typingAgents` from the result for Task 5.
- Modify: `src/lib/stores/sessionReset.ts` (`resetSessionState`, M3) and its test.
- Modify (compile only): `LiveTurn.svelte`, `LiveActivity.svelte`, `AgentReasoning.svelte` read the
  new store's shape until Task 2 deletes them.
- Create: `docs/superpowers/plans/2026-10-22-parity-m4-desktop.md`, a copy of this plan (R10).

**Interfaces:**
- Produces (`liveTurn.ts`, pure):
  ```ts
  export interface LiveTurnState { roomId: string | null; summary: TurnSummary | null; steps: TurnStep[]; thought: string | null; answer: string | null }
  export function emptyTurn(roomId: string | null): LiveTurnState;
  export function applyTurnUpdate(s: LiveTurnState, u: TurnUpdate): LiveTurnState;   // other room or older revision → s unchanged
  export function restoreTurn(s: LiveTurnState, snap: TurnSnapshot | null): LiveTurnState; // drops a snapshot older than s.summary.revision
  ```
- Produces (`liveStore`): `focus(roomId: string | null): Promise<void>`; getters `summary`,
  `steps`, `thought`, `answer`, `inProgress`, `finished`, `isLive`;
  `setAgentName(name: string | null, roomId: string)`, `agentName(roomId): string | null`;
  `clear()`. Subscribes to `onTurn`, `onLive`, `onThought`; **no `onTool`** (steps arrive on
  `sm://turn`; the core drops stale ones).
- Produces (`castStore`): `load(roomId: string): Promise<void>` (stale-guarded: a load for a room
  left meanwhile is discarded), `cast: RoomCast | null`, `castFor(roomId): RoomCast | null` (null
  unless `cast.roomId === roomId`), `clear()`. A failed `roomCast` leaves `null`.
- `+page.svelte`: on `roomsStore.selectedId` change → `liveStore.focus(id)`, `castStore.load(id)`,
  then `liveStore.setAgentName(cast?.counterpart?.name ?? null, id)`. Typing:
  `typingView(boundTypers(users), castFor(id)?.agentIds ?? [], castFor(id)?.counterpart ?? null)` →
  `typingLine = view.line`, `typingAgents = view.agents`.

- [ ] **Step 0:** `grep -n 'export function onTurn\|export function turn\|export interface TurnSummary\|export function elapsedLabel' src/lib/ipc.ts`; record the names.
- [ ] **Step 1: Write the failing tests.**
  - `liveTurn.test.ts` (ported from the iOS C5 list): `an update for another room changes nothing`;
    `an older revision does not rewind the card`; `a new turn drops the last one's steps, reasoning
    and answer`; `a step change lands at the index the core gave` (replace at 0, append at length);
    `a restore older than what is held is dropped`; `a restore brings back a finished turn's steps
    and thought, and no answer`.
  - `live.test.ts` (fake `onTurn`/`onLive`/`onThought`/`turn`): `focus restores the room's turn from
    the core`; `a restore for a room left meanwhile is dropped`; `the streamed answer comes from the
    live channel and goes on done`; `the header's name is kept per room`; `it never subscribes to
    tool updates`.
  - `cast.test.ts`: `loads the cast for the room`; `a load for a room left meanwhile is dropped`;
    `a failed load is no cast`; `castFor another room is null`.
  - `+page` typing wiring through a helper `typingArgs(users, cast, roomId)` in
    `src/lib/components/typingView.ts`: `the typing line is asked with the room's cast` (RF2:
    `agentIds` and `counterpart` passed; another room's cast → `[]`, `null`).
  - `sessionReset.test.ts`: `resetSessionState forgets the turn and the cast` (RF5).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** Keep `pacer.ts`. The three old components keep rendering from the new
  getters until Task 2 (no frame may change in this task).
- [ ] **Step 4: Run the desktop verification.** Falsify: drop the revision check → `an older
  revision…` fails; drop the room guard in `focus` → `a restore for a room left meanwhile…` fails
  (three runs); pass `[]` for `agentIds` → `the typing line is asked with the room's cast` fails;
  drop the cast from `resetSessionState` → its test fails.
- [ ] **Step 5: Commit.** `desktop: the turn is the core's; the typing line knows the room's cast`.

### Task 2: One live card inside the timeline, and "What I did" in the answer row (A2, T6, D11) **[timeline] [page]**

**iOS behaviour and copy:**
- **Where it goes** (`Timeline/TimelineCollectionView.swift:886-913`): a turn **in progress**
  (`isLive && !isFinished`) is one row at the newest end of the list. A **finished** turn has **no
  row**: its record is drawn inside the answer's own row, between the sender's header and the text
  (`:612-614`). The answer row (`recordAnchorId`, `:812-824`) is the **newest non-own row whose view
  is a bubble**. The desktop today draws three strips **outside** the list
  (`src/routes/+page.svelte:1030-1055`: `AgentReasoning`, `LiveTurn`, `LiveActivity`), labels the
  answer with the room's name (`:1044-1047`, D11) and has no elapsed time; they all go.
- **The card** (`Timeline/LiveTurnView.swift:57-138`): spacing 8; padding 12; `surface-raised`,
  a 1px `border`; outer vertical padding 6; opacity 0.85 when finished (stories only). Corner: iOS
  14 → desktop `rounded-card` (Decision 3).
- **Header** (`:141-154`): the name (one line, truncated), then `"Done"` in `ok` when finished or
  `"Working"` in `accent`, then `"· " + elapsedLabel(…)` in `text-meta text-content-muted
  tabular-nums`.
- **Elapsed** (`:156-174`): ticks once a second from `startedAtMs` while running; still (stories)
  or finished, measured to `lastActivityMs`. Text is the core's `elapsedLabel` ("8s", "1m 04s",
  "1h 02m") — one IPC call a second while a turn runs.
- **Current step** (`:66-68`, `:177-199`): shown when not finished and (`summary.stepIsTool` or no
  answer text showing yet): a small spinner in `accent` (a static dotted ring under reduced motion
  or in a story), then `summary.currentStepTitle` (`text-ui text-content`, at most 2 lines).
- **Step summary** (`:204-217`), when there are steps: `"1 step done"` / `"\(done) steps done"`
  (`completedSteps`) with a check glyph, `text-meta text-content-muted`; if `failedStep`,
  `"\(failed) failed"` with an x glyph, `text-danger`, at most 2 lines.
- **Reasoning** (`:74-84`): a `<details>` whose summary is `"Reasoning"`, collapsed; open, the
  thought (`text-ui text-content-muted`, selectable).
- **Steps** (`:86-99`): a `<details>` labelled `"1 step"` or `"All \(n) steps"`; open, one row per
  step: glyph by phase (done ✓, failed ✕ in `danger`, running ↻, queued/unknown clock), title
  (`text-meta`, one line, truncated), the core's `statusLabel` (`text-meta`, never squeezed;
  `danger` when failed, else `content-faint`). A step with `hasDetail` is its own `<details>`
  opening onto `"Touched"` (locations, one per line), `"Input"`, `"Output"` — label `text-meta
  text-content-faint`, text `font-mono` small, `text-content-muted`, selectable (`:229-318`).
- **The answer** (`:101-113`): the paced, markdown-rendered answer the desktop already has
  (`pacer.ts`; `richBlocksFromMarkdown` every 60 ms, `LiveTurn.svelte` "PARSE_INTERVAL_MS") moves
  into the card unchanged. The old 33vh cap and inner scroll go: inside the list the card scrolls
  with the history.
- **The name** (`:53-55`, D11): `liveStore.agentName(roomId) ?? writerName`, where `writerName` is
  the newest non-own row's `senderName`, else `"Agent"` (`TimelineCollectionView.swift:524,739`).
- **"What I did"** (`Timeline/WhatIDidFooter.swift:17-93`): shown when `summary.recordLabel` is set;
  a `<details>` whose summary is that label (`"Thought for 12s · 2 steps"`, `"3 steps · 1m 5s"`) in
  `text-ui text-content-muted`; it fades open (opacity only) unless reduced motion; open, the thought
  then each step (glyph `content-faint`/`danger`, title `text-ui text-content-muted` up to 2 lines,
  `statusLabel` `text-meta`). Padding 12/6; `bg-surface-sunken rounded-card`; at most 600px wide.
- **Streaming must not move the history** (`TimelineCollectionView.swift:161-229`): layout is
  applied without animation while a turn streams. iOS then glides the visible cells (0.32 s); the
  desktop does not (Decision 2). The tail-follow `ResizeObserver` in `Timeline.svelte`
  (`:789-889`, `shouldRepin`) already re-pins a reader at the tail on content growth by assigning
  `scrollTop` — instantly — and leaves a scrolled-up reader alone.

**Files:**
- Create: `src/lib/components/live/LiveCard.svelte` and `LiveCard.stories.svelte`.
- Create: `src/lib/components/live/liveCardView.ts` and `liveCardView.test.ts`.
- Create: `src/lib/components/live/WhatIDid.svelte` and `WhatIDid.stories.svelte`.
- Create: `src/lib/components/timeline/recordAnchor.ts` and `recordAnchor.test.ts`.
- Modify: `src/lib/components/timelineGrouping.ts` (`TimelineDisplayRow` gains
  `{ type: "liveTurn"; key: "live-turn" }`) and its test; `Timeline.svelte` (append the live row to
  `displayRows` while in progress; render it; the anchor row's prelude); `Timeline.stories.svelte`
  (the probe story).
- Modify: `src/routes/+page.svelte` (delete the three strips and their imports).
- Delete: `src/lib/components/LiveTurn.svelte`, `LiveActivity.svelte` (+ stories),
  `AgentReasoning.svelte` (+ stories), `reasoningLabel.ts` (+ test), and
  `live/LiveTurnBubble.svelte` (+ stories) once nothing imports it.

**Interfaces:**
- Produces (`liveCardView.ts`): `showsCurrentStep(summary, answerShown: boolean): boolean`;
  `stepsDoneLabel(n: number): string` ("1 step done" / "N steps done"); `failedLabel(title): string`;
  `stepsToggleLabel(n): string` ("1 step" / "All N steps"); `stateWord(summary): "Working" | "Done"`;
  `toolGlyph(phase): "done" | "failed" | "running" | "waiting"`;
  `elapsedMs(summary, nowMs, still: boolean): number`; `writerName(agentName, rows): string`.
- Produces (`recordAnchor.ts`): `recordAnchorKey(rows: TimelineDisplayRow[]): string | null` — the
  newest `item` row with `!isOwn` and a `bubble` render.
- `LiveCard` props: `{ summary; steps; thought; answer; name; still?: boolean }`; `data-testid`
  `live-card`. `WhatIDid` props `{ label; thought; steps }`.

- [ ] **Step 0:** confirm Task 1's store shape; `grep -n "liveTurn\|live-turn" src/lib/components/*.ts`.
- [ ] **Step 1: Write the failing tests.**
  - `liveCardView.test.ts`: every label above, exactly; `showsCurrentStep` (a tool step → true even
    with text; `stepIsTool` false with text → false; finished → false); `elapsedMs` (running →
    `now − started`; still or finished → `lastActivity − started`); `writerName` (`"Atlas"` wins;
    `null` → newest peer `senderName`; no rows → `"Agent"`).
  - `recordAnchor.test.ts`: `the newest agent bubble carries the record`, `an own message never
    does`, `an image or a card from the agent does not`.
  - `timelineGrouping.test.ts`: `the live row is the last row only while in progress`
    (in progress → last row is `liveTurn`; finished → no `liveTurn` row; absent turn → none).
  - Stories (each with a played assertion):
    - `LiveCard`: "Mid-turn", "Thinking only", "Answering only", "Activity card" (1m 04s, 2 done, 1
      failed, 1 running — iOS `ComposerRevampFixtures.longTurn()`), "Activity card, finished",
      "Largest text at 600px".
    - `WhatIDid`: "Collapsed", "Open".
    - `Timeline`: "A finished turn's record in its answer" (asserts `live-card` absent and the
      record inside the newest peer row), "A bridge that only edits its progress message" (RF1: no
      summary, an edited agent row "🔎 Searching files…" → no card, no record), "A turn the core
      ended by a reply" (RF1: from in progress to `endedBy: "reply"` → card gone, record shown).
    - **"Timeline — a streaming turn" (the probe, Review Focus 5):** a fixture room at the newest
      end; the play grows the answer every 450 ms for 3 s (iOS `-fixtureStreaming`'s pace) and, on
      every animation frame, records `getBoundingClientRect()` of the newest three history rows and
      of `live-card`. It asserts: (a) on every frame the newest history row's bottom equals the
      card's top within 1px — no gap, no overlap; (b) `document.getAnimations()` over the timeline
      contains no animation of anything but `opacity` or `transform`; (c) the scroller stays at
      the tail (`scrollHeight − scrollTop − clientHeight ≤ 1`). A second play scrolls up 400px
      first and asserts the first visible row's `top` does not change while the card grows.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - `displayRows` appends `{ type: "liveTurn", key: "live-turn" }` when `liveStore.isLive &&
    !liveStore.finished`. It is never an arrival (`arrivedKeys`' `isMessage` is false for it) and
    an end change never shifts (`shouldShift`).
  - The card renders inside the same column measure as messages. The elapsed tick is an `$effect`
    with a 1 000 ms interval while in progress and not `still`, calling `elapsedLabel` and keeping
    the last answer for the current second.
  - Glyphs are inline SVG in `currentColor` (no icon font, no hex).
  - The anchor row passes `prelude` to `messageBlock`, drawn after the sender line and before
    `ReplyQuote`.
  - `+page.svelte`: only `<Timeline>` remains between the header and the typing line.
- [ ] **Step 4: Run the desktop verification.** Every new frame inspected and recorded; the three
  deleted components' frames disappear (list them).
  - **Falsify:** add `transition: height 300ms` to `live-card` → the probe's (b) fails; disable the
    tail repin (`shouldRepin` → `false`) → (c) fails (three runs); keep the card when finished →
    `the live row is the last row only while in progress` fails; anchor on any newest row → the
    record story fails.
  - **Running app (read-only):** open an agent room at 1905px and 600px and 150 %: no strips under
    the list; if the account has a finished turn in that room since the app started, the record
    is on the answer. A streaming answer needs a send — "Live verification pending".
- [ ] **Step 5: Commit.** `desktop: one live card in the timeline; the turn's record in its answer`.

### Task 3: Agent cards, the long read, the quote under its parent, the runtime rule, "Read by" (T1, D6, R8, Q10) **[timeline]**

**iOS behaviour and copy:**
- **Every peer message is a card** (`Timeline/TimelineRowView.swift:263-275`, `:408-465`): padding
  12/8; `surface-raised` for a peer (your own stays `bg-accent-soft`); at most 600 wide for a peer,
  520 for your own (`WhatIDidFooter.swift:96-102`). Desktop today puts a peer's text on the page
  (`Timeline.svelte` `messageBlock`, `max-w-[68ch] font-sans text-body`) and keeps the `ch`
  measure (Decision 4). Decided by owner O2 (follow iOS): the card is for every peer; **Agent**
  marks an agent.
- **The header** (`:381-406`): the sender's face at 22 (`row.senderInitial`, the member's avatar
  when cached — `memberAvatars`), the name (one line, peer colour), **"Agent"** when `row.isAgent`
  (C6), the time (`text-meta text-content-faint`), `"edited"`. The same header heads an audio row and
  an agent's failed turn (`AudioRow` `:794-808`, `TurnErrorRow` `:213-248`).
- **`AgentLabel`** (`Timeline/TimelineChrome.swift:177-190`): `"Agent"`, `text-label font-semibold
  text-accent bg-accent-soft rounded-pill px-1.5`, never shrinks.
- **Attribution (R8, waited for M4):** `row.showsRuntime ? row.senderName : row.senderShort`
  (`TimelineCollectionView.swift:599`, now the core's `shows_runtime`). Desktop today always shows
  `senderShort` with the runtime in a tooltip.
- **The long read** (`TimelineRowView.swift:423-457`), when `row.isLongRead`: the text is cut at
  320px with a fade (`mask-image: linear-gradient(to bottom, black 72%, transparent)`); under it a
  `"Read"` button with a book glyph, `text-ui font-semibold text-accent`, described as
  `"Opens the whole message in a reading view"`. It opens the reading view
  (`TimelineChrome.swift:192-225`): titled with the attribution, the whole message through the same
  renderer in `font-serif text-longread`, line height +4px, at most 640px wide, padding 20/16, on
  `bg-surface`, with `"Done"`.
- **D6** (`TimelineRowView.swift:410`): the quote is drawn only when `!row.hidesQuote` (C9).
- **"Read by" (M3 Q10, `TimelineChrome.swift:172-173`):** the reader faces' label is
  `"Read by " + peopleLabel(readerIds)`. Desktop says `"Seen by N"`
  (`src/lib/components/timeline/ReaderFaces.svelte:25`).

**Files:**
- Modify: `src/lib/components/Timeline.svelte` (`messageBlock`, the audio and turn-error headers).
- Create: `src/lib/components/timeline/SenderHeader.svelte`, `AgentLabel.svelte`,
  `LongReadDialog.svelte` (+ stories), `messageView.ts` (+ test).
- Modify: `src/lib/components/timeline/ReaderFaces.svelte` (+ stories), and create
  `readerLabel.ts` (+ test).
- Modify: `src/lib/components/Timeline.stories.svelte`.

**Interfaces:**
- Produces (`messageView.ts`): `attributionFor(row): string`; `showsAgentLabel(row): boolean`
  (`!isOwn && row.isAgent === true`); `quoteFor(row): ReplyQuote | null`
  (`row.hidesQuote ? null : row.replyQuote`); `isLongRead(row): boolean` (`row.isLongRead === true`).
- Produces (`readerLabel.ts`): `createReaderLabels(peopleLabel)` with `label(ids): string | null`
  (cached by the joined ids, `null` while loading) and `clear()`; `ReaderFaces` shows
  `"Read by …"` once known (until then `"Read by"` plus the count, *(no iOS copy)*, never the old
  "Seen by N").
- `LongReadDialog` props `{ title; blocks; onClose }`; focus starts on "Done"; Escape closes.

- [ ] **Step 0:** `grep -n 'isAgent\|isLongRead\|hidesQuote\|showsRuntime' src/lib/ipc.ts`.
- [ ] **Step 1: Write the failing tests.**
  - `messageView.test.ts`: `an agent's message says Agent and a person's does not`; `your own
    message never does`; `attribution follows showsRuntime` (RF2: true → `senderName`, absent →
    `senderShort`); `a reply under its parent drops the quote`; `isLongRead reads the row`.
  - `readerLabel.test.ts`: `names the readers the core's way` (fake `peopleLabel` gets the ids in
    order); `asks once per set of readers`; `clear forgets` (RF5).
  - Stories: Timeline "An agent and a person, as cards", "A long read" (play: "Read" opens the
    dialog, its last paragraph is in it, Escape closes it, focus returns to "Read"), "A reply under
    its parent", "Two agents speaking (runtime shown)", "Largest text at 600px" (a long agent name:
    the name truncates, "Agent" stays whole); `ReaderFaces` "Read by three of five".
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The card's padding changes every peer row's height; virtua measures
  them (no animation). `SenderHeader` is shared by message, audio and turn-error rows.
- [ ] **Step 4: Run the desktop verification.** Most timeline frames change (the peer card);
  inspect and record each, and list them.
  - **Falsify:** label every peer → `an agent's message says Agent…` fails; ignore `hidesQuote` →
    its test fails; always `senderShort` → `attribution follows showsRuntime` fails; ask
    `peopleLabel` on every render → `asks once per set of readers` fails.
  - **Running app (read-only):** an agent room: cards, "Agent", a long report cut with "Read" and
    the serif dialog; a reply under its parent; at 600px and 150 %.
- [ ] **Step 5: Commit.** `desktop: every peer message is a card; agents say so; long reads open to read`.

### Task 4: The header status pill and the roster's agent tiles (A1, R1) **[page]**

**iOS behaviour and copy:**
- **Status** (`Navigation/RoomScreen.swift:74-84`): `roomStatus(state, describesAgent,
  turnInProgress: liveStore.inProgress)` with `state` and `describesAgent` from the core's
  `rosterRow(rows, roomId, nowMs)`. The header's clock moves every 60 s while the reader stays
  (`:40-48`); on a room change it resets (`:35-39`).
- **Header** (`:194-220`, owner Q3 default): a second line under the name — the **status pill**
  when there is a status, else `"1 member"` / `"\(n) members"` (`text-meta text-content-muted`;
  only for a room of people that is not an invitation, `:116-123`, from `roomInfo(roomId)
  .activeMemberCount`). The desktop keeps its avatar, role chip, Search and Info (Q3).
- **Status pill, compact** (`:222-266`): a 6px dot, then `roomStatusWord(status)` ("Working",
  "Needs you", "Active", "Idle", "Quiet") in `text-meta`, one line; gap 4px; padding 7px/1px;
  `rounded-pill`, `bg-signal-soft` for Needs you else `bg-surface-raised`; the word `text-signal`
  for Needs you else `text-content-muted`. Dot: Working and Active `bg-ok`, Needs you `bg-signal`,
  Idle `bg-content-faint`, Quiet `bg-border`. **Working pulses** (opacity) unless reduced motion.
  Accessible name: the word.
- **The roster's face** (`Rooms/RoomAvatar.swift:12-118`), 40px: an agent's room is a rounded
  square (corner 28 % of the size) in the core's tile colours (`RosterRow.avatar`: `ground`/`ink`
  roles), a 1px `border` when the ground is `surface-raised`, the initial at 44 % semibold; a
  people room a circle on `surface-raised` with a 1px `border`, the initial at 42 % medium
  `text-content-muted`; a picture fills the shape. The **agent badge** top-right, offset (3, −3):
  36 % circle, `bg-accent`, a sparkle in `text-accent-content`, a 1.5px `surface` ring. The **state
  dot** bottom-right, offset (2, 2): 30 %, a 2px `surface` ring; Needs you `signal`, Active `ok`,
  Idle `content-faint`, **Quiet nothing**. Accessible text `"Agent"` and the state word joined by
  `", "`.
- **The row** (`Rooms/RoomRowView.swift:27-136`): **two lines** — line 1: the name, `"Invitation"`
  (outlined, `accent`) for an invitation, the time (`accent` when unread, else `content-faint`);
  line 2: the preview (`signal` when pending, else `content-muted`), or the role when it has said
  nothing, or a blank line; the unread badge (`"99+"` over 99). The dot moves from beside the name
  to the avatar; the role-and-time line goes. Dot drawn when
  `showsState && (describesAgent || state == "needsYou")` (`:99-101`).

**Files:**
- Create: `src/lib/components/StatusPill.svelte` (+ stories), `src/lib/components/roomHeaderView.ts`
  (+ test), `src/lib/components/roster/RoomAvatar.svelte` (+ stories),
  `src/lib/components/roster/avatarView.ts` (+ test).
- Modify: `src/routes/+page.svelte` (the header's second line), `src/lib/components/roster/RoomRow.svelte`
  (+ stories), `src/lib/components/RoomList.svelte` (pass `entry.avatar`).

**Interfaces:**
- Produces (`roomHeaderView.ts`): `createHeaderStatus(deps)` with
  `refresh(roomId, rows, nowMs, inProgress): Promise<void>` (stale-guarded), `status`, `word`;
  `memberCountLabel(n): string | null` ("1 member", "N members", null for 0 or unknown).
- Produces (`avatarView.ts`): `avatarRoleClass(role: AvatarRole, use: "bg" | "text"): string` (the
  seven roles → token classes); `dotClass(state): string | null`; `avatarLabel(describesAgent,
  state, shown): string`.
- `StatusPill` props `{ status: RoomStatus; word: string; compact?: boolean }`; `RoomAvatar` props
  `{ initial; avatarUrl; describesAgent; style: AgentAvatarStyle | null; state: AgentState | null; size?: number }`.

- [ ] **Step 1: Write the failing tests.**
  - `roomHeaderView.test.ts`: `an agent's room with a live turn is Working` (fake `rosterRow`,
    `roomStatus`; asserts `turnInProgress: true` reached it); `a room left while asking keeps the
    new room's status`; `memberCountLabel`.
  - `avatarView.test.ts`: every `AvatarRole` maps to a token class and none is `signal` or `danger`;
    `quiet draws no dot`; `the label says Agent and the state`.
  - Stories: `StatusPill` "Header statuses" and dark (the five, iOS `RoomScreen.swift:268-290`),
    "Largest text at 600px"; header stories "An agent's room, working" and "A room of people";
    `RoomAvatar` "Avatars" and dark (over `agentAvatarVariants()` output fixed in a fixture, iOS
    `RoomAvatar.swift:120-154`); `RoomRow` "Every state" and dark (iOS `RoomRowView.swift`
    previews), "State dot hidden", "Two lines at 600px and Largest".
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The pulse is a CSS `@keyframes` on `opacity` inside
  `@media (prefers-reduced-motion: no-preference)`, applied only for `working`. The sparkle is an
  inline SVG in `currentColor`.
- [ ] **Step 4: Run the desktop verification.** Roster and header frames change; inspect and
  record each.
  - **Falsify:** pass `false` for `turnInProgress` → the Working test fails; map a role to
    `bg-signal` → the palette test fails; draw a Quiet dot → `quiet draws no dot` fails; refresh
    without the room guard → `a room left while asking…` fails (three runs).
  - **Running app (read-only):** the roster at 1905px/600px and 150 %: tiles, badges, dots, two
    lines; an agent room's pill; a people room's count.
- [ ] **Step 5: Commit.** `desktop: the room says what its agent is doing; agents have their own faces`.

### Task 5: "Sent to" → "is on it", the `@` menu from the room's cast, and the verification pass (A3, M3 Q9, RF1, RF2) **[page] [composer]**

**iOS behaviour and copy:**
- **Placement** (`Timeline/TimelineView.swift:123-146`): above the composer, leading-aligned, gap
  4px, horizontal padding 12px (bottom 4px only when something shows): the **dock**, then the
  **typing pill**. The dock enters moving up from below with a fade, the pill with a fade, 220 ms,
  none under reduced motion (`transform` and `opacity` only).
- **The facts** (`:177-195`): the focused room; the cast from Task 1 — `addressee` and `agentIds`
  only when the cast is this room's; `typingAgents` from Task 1's `typingView` result;
  `turnInProgress = liveStore.inProgress`. The core's `acknowledgement` command decides the rest,
  including "sent this session".
- **The dock** (`:198-236`): gap 7px; `Sent` → a check in `text-ok`; `OnIt` → an ellipsis in
  `text-accent` that animates (opacity steps) unless reduced motion; the core's text —
  `"Sent to \(name)"`, `"\(name) is on it…"`, `"\(names) are on it…"`, `"On it…"` — `text-meta
  text-content`, one line, truncated; padding 12px/7px; a floating capsule (`bg-surface-raised`,
  1px `border`, `shadow-overlay`, `rounded-pill`). `role="status"`, `aria-live="polite"`.
- **The typing pill** (`:238-257`): a speech-bubble glyph in `text-content-faint`, the core's line
  (`text-meta text-content-muted`, one line), gap 6px, padding 10px/5px, `bg-surface-sunken
  rounded-pill`. `TypingIndicator.svelte` (M1a Task 12's `text` prop) is restyled into it.
- **The `@` menu** (`Composer/ComposerView.swift:59-63`, `:503-549`; M3 Q9): the people come from
  the **room's cast**, matched by the core (`people::matching`), agents first (the cast keeps the
  core's `arrange` order), at most 6 (`RoomCastCandidates.swift`); each row: the initial in a 30px
  `bg-accent-soft` circle in `text-accent`, the name (`text-ui font-medium`), for an agent
  `"\(harness) on \(host)"` (`text-meta text-content-muted`), and the `"Agent"` tag
  (`FloatingSurface.swift:54-63`; M2 Task 6 drew it); minimum height 44px; accessible name
  `"\(name), agent"` for an agent. The send passes `cast.mentionables` (`:282-286`).
- **RF2:** several agents and no single addressee → no "Sent to"; working agents named the core's
  way ("Atlas, Quill and 1 other are on it…").

**Files:**
- Modify: `src-tauri/src/commands_session.rs` and `src-tauri/src/lib.rs` (one command, registered).
- Modify: `src/lib/ipc.ts` (+ `ipc.test.ts`): `roomMentionCandidates(roomId, query)`.
- Create: `src/lib/components/composer/AcknowledgementDock.svelte` (+ stories),
  `src/lib/components/composer/ackView.ts` (+ test).
- Modify: `src/lib/components/TypingIndicator.svelte` (+ stories), `src/routes/+page.svelte`
  (facts, the ask, placement), `src/lib/components/Composer.svelte` (candidates and mentionables
  from the cast), `src/lib/components/composer/MentionMenu.svelte` (+ stories),
  `src/lib/components/mentions.ts` (+ test: `matchMentions` deleted — the core matches now;
  `findMentionQuery`, `applyMention`, `mentionLabel` stay).
- Modify (only if P2 merged): `liveCardView.ts` `writerName` takes `agentId` and the cast.
- Modify: `README.md` (desktop cells of the rows this plan turns ✅).
- Create: `.superpowers/sdd/m4-desktop/m4-desktop-verification.md`.

**Interfaces:**
- Produces (Rust): 
  ```rust
  /// The people an `@` query offers in a room: its cast, agents first, matched by the core
  /// (`Session::room_cast`, then `people::matching`), at most `CANDIDATES` (6, iOS
  /// `RoomCastCandidates.swift`). One call per keystroke over a few dozen people (M3 Q12).
  #[tauri::command]
  pub async fn room_mention_candidates(room_id: String, query: String, session: State<'_, Session>) -> Result<Vec<PersonDto>, CoreError>
  ```
- Produces (`ackView.ts`): `createAcknowledgement(deps)` with
  `ask(facts: { roomId; cast: RoomCast | null; typingAgents: string[]; inProgress: boolean }): Promise<void>`
  (stale-guarded by room and a request counter; any rejection → `null`), `current:
  Acknowledgement | null`, `clear()`.
- If P2 merged: `writerName(agentId, cast, agentName, rows)` — the rule written in
  `core-prereqs.md` P2, identically.

- [ ] **Step 0:** `grep -n 'agentId' src/lib/ipc.ts` (P2).
- [ ] **Step 1: Write the failing tests.**
  - Rust (`commands_session.rs` tests, beside M3's): `room_mention_candidates_without_a_session_is_not_ready`.
  - `ipc.test.ts`: `roomMentionCandidates passes roomId and query`.
  - `ackView.test.ts` (fake `acknowledgement` command):
    - `asks with the room's facts` (another room's cast → `addressee: null`, `agentIds: []`);
    - `a progress message the agent edits ends the dock` (RF1: the fake answers `Sent` while the
      newest row is own and `null` once an agent row follows; asking again after the timeline's
      `items` change — including a `Set` with `edited` — clears the dock);
    - `several agents typing are named and nobody is "Sent to"` (RF2: no counterpart →
      `addressee: null`, `typingAgents` passed through, the core's text shown);
    - `an answer for the room just left is dropped` (three runs);
    - `a refusal is no dock` (`{ kind: "roomChanged", … }` → `null`);
    - `reset forgets the dock` (RF5; wired into `resetSessionState`).
  - Stories: `AcknowledgementDock` "Sent", "On it", "Several on it", "Largest text at 600px";
    `TypingIndicator` "A person typing" (the pill); `MentionMenu` "From the room's cast" (two agents
    with "{harness} on {host}" and the tag, then a person), "Largest text at 600px".
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** `+page.svelte` asks on any change of `roomsStore.selectedId`,
  `timelineStore.items`, `typingAgents`, `liveStore.inProgress` or the cast. The dock uses Svelte
  `in:fly={{ y: 8 }}` + fade (transform and opacity), duration 0 under reduced motion. The Composer
  asks `roomMentionCandidates(roomId, query)` per query change (stale-guarded) and sends with
  `castFor(roomId)?.mentionables ?? []`.
- [ ] **Step 4: Run the desktop verification (with the Rust checks), then the branch-end pass.**
  - **Falsify:** ask only on room change → `a progress message the agent edits ends the dock`
    fails; drop the room guard → `an answer for the room just left is dropped` fails; pass
    `cast.addressee` for another room → `asks with the room's facts` fails.
  - README: correct the desktop cells for A1, T1, A2, A3, T6, D5, D6, D11, R1 against the merged
    code.
  - Fill `m4-desktop-verification.md` with the exit table below; ✅ only with a test, a story or a
    screenshot; "live pending (owner)" for anything that needs a send.
  - **Running app (read-only), 1905px then 600px, Default then Largest text:** the roster's tiles,
    dots and two lines; an agent room's pill; a people room's count; an agent card with "Agent"; a
    long read; "Read by …" on your own message (read the `aria-label` from the DOM); the `@` menu
    (type `@` in the composer without sending, then clear the field); no strips under the timeline.
  - **Owner live pass (Q2)**, written into the verification file as a checklist: send one message
    to a bridge agent that edits its progress message (RF1); one in a room with two agents and a
    person (RF2). The implementer does not send.
- [ ] **Step 5: Commit.** `desktop: "Sent to" then "is on it"; the @ menu from the room's cast; README after parity M4`.

---

## Excluded, and why

| Item | Why it is not in this plan | Where |
|---|---|---|
| D5 — churn across verbs, expandable | Done by M1b C12 (`groupTimelineItems` over the core's `membership`, `MembershipStretch.svelte`). Checked in the exit table only. | M1b PR3 |
| `continuesRun` / run window | M1b C12. | M1b PR3 |
| Removing the duplicate stale-tool guard | M1b C4 deleted `toolSeen`. | M1b PR1 |
| Desktop typing line from the core | M1a Task 12; Task 1 only adds the cast. | M1a PR4 |
| Agents marked in the `@` menu (the tag itself) | M2 Task 6. Task 5 moves the source to the cast and adds the runtime line and order. | M2 |
| Decision cards (G1), arrival motion, receipts | M2 Tasks 7 and 10. | M2 |
| An `agent_avatar` command | M3 Q1: no desktop caller; roster tiles come from `RosterRow.avatar`. | M6/M7 if ever |
| A finished-turn haptic | No desktop haptics (roadmap M2 lists it for Android only). | — |
| iOS's 0.32 s glide of the history | Not ported (Decision 2). | Owner Q5 |
| `StreamingMarkdown.split` into the core | The desktop parses the whole revealed answer through the core; no split to share. | Core backlog |
| A4 Stop | Blocked on AgentPod's cancel event. | — |
| N1–N3, R3, O1 | M5 (the Agents view will reuse `RoomAvatar`). | M5 |

## Exit: rows this plan turns ✅

| Matrix row | Task | Verified by |
|---|---|---|
| A1 header status pill | 4 | `roomHeaderView.test.ts`, `StatusPill` stories; running app |
| T1 agent card, long read | 3 | `messageView.test.ts`, Timeline and `LongReadDialog` stories; running app |
| A2 one live card in the timeline | 1, 2 | `liveTurn.test.ts`, `live.test.ts`, `liveCardView.test.ts`, the probe story; live pending (owner) |
| A3 "Sent to" → "is on it" | 5 | `ackView.test.ts`, `AcknowledgementDock` stories; live pending (owner) |
| T6 "What I did" | 2 | `recordAnchor.test.ts`, `WhatIDid` and Timeline stories |
| D5 | (M1b C12) | `timelineGrouping.test.ts`, `MembershipStretch` stories |
| D6 | 3 | `a reply under its parent drops the quote` |
| D11 | 1, 2 | `the typing line is asked with the room's cast`, `writerName` tests |
| R1 | 4 | `avatarView.test.ts`, `RoomAvatar`/`RoomRow` stories; running app |
| "Read by {names}" (M3 Q10) | 3 | `readerLabel.test.ts`; running app (DOM label) |
| `@` menu from the cast (M3 Q9) | 5 | `ipc.test.ts`, the Rust command test, `MentionMenu` stories; running app (no send) |
| Review Focus 1 | 2, 5 | the RF1 stories, `a progress message the agent edits ends the dock`; owner live pass |
| Review Focus 2 | 1, 3, 5 | `the typing line is asked with the room's cast`, `attribution follows showsRuntime`, `several agents typing are named…`; owner live pass |

## Desktop behaviour changes (visible)

1. The three strips under the timeline (reasoning, the answer, the tool ticker) are gone; one card
   sits at the newest end of the timeline while a turn runs, with the elapsed time, the current
   step, the counts and the failure; a finished turn leaves "What I did" in its answer.
2. The answer is no longer capped at 33vh with its own scroll; it scrolls with the timeline.
3. Every peer message is on a card with the sender's face; agents say "Agent"; runtime suffixes
   show when two or more others speak (was: never, tooltip only).
4. Reader faces say "Read by {names}" (was "Seen by N").
5. Roster rows are two lines with the dot on a 40px avatar (was 32px, three lines, dot by the name).
6. The header gains a second line: the status pill, or the member count.
7. The `@` menu lists the room's cast, agents first with their runtime, at most 6 (was 8 room
   members matched in TypeScript).
8. The typing line becomes a pill.

## Decisions taken in this plan (technical; the controller may overrule)

1. The live store mirrors only the focused room, as iOS; other rooms' turns live in the core
   (`turn(roomId)` on focus).
2. No glide: the tail-follow observer re-pins instantly as the card grows (owner Q5 can ask for a
   transform glide later).
3. Corners use the desktop's tokens: `rounded-card` for the live card, the peer card and "What I
   did"; your own bubble keeps `rounded-control`. iOS's literal 14 is not added as a token.
4. The peer card keeps the desktop's reading measure, `68ch` (≈ 600px at the body size), and your
   own `52ch`, rather than pixel widths.
5. The `@` menu's people come from a new `room_mention_candidates` command composing
   `Session::room_cast` and `people::matching` (M3 Q12's pattern), because `PersonDto` cannot be
   sent back to the core and a TypeScript filter would copy `people::matching`.
6. **The live card is a row of `VList`** (the roadmap's A2), gated by the probe story. If the probe
   cannot be made to pass after one fix round, stop and escalate (open question Q6) rather than
   moving the card back outside the list.

Open questions for the owner are in `.superpowers/sdd/planning-m4/open-questions.md`.
