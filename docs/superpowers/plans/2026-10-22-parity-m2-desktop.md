# Parity M2 — Desktop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close the roadmap's "M2 — Desktop" rows (composer, room info, account and appearance, search scope, G1 decisions, timeline T2–T5/D7, motion) in `src/` and `src-tauri/`, with no new core function, plus the M0 carry-overs (clipped hover bar, shared error presenter, one connection line, desktop deferred minors).

**Architecture:**
- Logic goes in small `.ts` view modules with Vitest tests; components stay thin and get stories that the snapshot gate renders.
- No new Tauri command. One command is removed (`enable_recovery`, dead since M0), taking the
  registry from 46 back to 45.
- One command changes its return type: `attachment_send` reports whether the core re-staged the
  file. That change is made by **M1a PR2** (M1.7, branch `parity/m1-core-send-outcome`), not by
  this plan. Task 5a **waits for M1a PR2 to merge** and does not stub it.
- One capability is added: `core:webview:allow-set-webview-zoom`, for the text-size setting.
- Rows that turned out to need core work are listed under "Moved to core (M1/M3)". They are not stubbed here.

**Tech Stack:**
- Svelte 5 (runes), SvelteKit SPA, Tailwind v4 over the generated `src/lib/tokens.css`.
- Vitest 4, Storybook 10 stories with the snapshot gate (`./scripts/snapshot-stories.sh`).
- Tauri 2.11 (`@tauri-apps/api` `app.getVersion`, `webview.setZoom`; `@tauri-apps/plugin-opener` `openUrl`).

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` and `notes/desktop.md`. The binding umbrella is
`docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md` ("M2 — Desktop"). The house style is
`docs/superpowers/plans/2026-10-22-parity-m0-defects.md`. iOS paths below are relative to `apple/`.

**Branch and worktree:** `parity/m2-desktop-parity`, in worktree `.worktrees/parity-desktop`. Branch from
`main` once the M0 branches have merged; the current `parity/m0-defects` is main plus M0.

**Cross-plan dependencies and decisions (M1a plan `2026-10-22-parity-m1a-core-rules.md`):**
- **Task 5a waits for M1a PR2** (M1.7, `parity/m1-core-send-outcome`, tasks 3-4). Every other task
  can start on `main` now.
- Nothing here waits for M1.1 (M1a PR3, `parity/m1-core-roster-views`), M1.2 (M1a PR4,
  `parity/m1-core-cast`) or M1.4 (M1b PR2/PR3, `parity/m1-core-row-facts`,
  `parity/m1-core-timeline-grouping`). Rows that do are under "Moved to core". M1b PR3 replaces
  the desktop's grouping copy in `timelineGrouping.ts`; Tasks 8 and 9 touch other code in that
  area, so rebase over it if it has merged first.
- Owner decisions applied: O2 (Attach stays visible while editing; no "hide" item), O3 (text size
  100/115/130/150 %), O4 (one connection line under the roster title). Rulings applied: R1
  ("Not sent. Try again." stays), R2 ("Sign out?" until M3), R3 (Mark read removed; Core/M3),
  R4 ("⋯ More" pill), R5 (keep the version line), R6 (`roomChanged` wording stays iOS-verbatim).

## Global Constraints

**From the roadmap (binding):**
- Tokens only: no hex in views. A new type role goes through `design/tokens.toml` and then
  `python3 scripts/generate-tokens.py`. Never edit `src/lib/tokens.css` by hand.
- Amber (`signal`) means a pending decision and nothing else. It is never used for connection
  state, failures or receipts.
- The app parses nothing and decides nothing the core already decides. No client-private Matrix
  event types.
- Copy is iOS's, verbatim. Each task quotes it with its `file:line`.
- Every new UI state ships with a desktop story. Its baseline is recorded once, after inspection.
  Never re-record a frame to make a check pass.
- Desktop UI claims are verified in the running app: `pnpm dev` plus
  `$CARGO_TARGET_DIR/debug/supermessage`, with the embedded WebDriver on `127.0.0.1:4445`. Never
  verify with a bare `cargo run`.
- One heavy cargo build at a time. `/tmp` is RAM.

**This track:**
- Platform tracks never edit `crates/` or the generated bindings. This plan touches only `src/`,
  `src-tauri/src/lib.rs`, `src-tauri/src/commands.rs`, `src-tauri/capabilities/default.json` and
  `README.md`.
- Cargo: `export CARGO_TARGET_DIR=/home/rakeshgangwar/Projects/superjackfruit/supermessage/target`.
- **Snapshot gate on this machine:** these 7 frames already differ on `main` (renderer drift), and
  must never be re-recorded locally:
  - `composer-replybanner--long-excerpt`, `--no-excerpt`, `--replying`;
  - `live-agentreasoning--streaming-long`, `--streaming-short`;
  - `timeline-dispatchcard--resolved-in-the-room`;
  - `timeline-voicetranscript--hindi`.

  The gate passes when no *other* frame differs. Do not change the markup those stories render: no
  edits to `ReplyBanner.svelte`, and no change to the DispatchCard `outcome` branch.
- **Recording a new or changed frame:** never `--record`, which rewrites all 83+ frames, the 7
  above included. Run the gate, inspect `.snapshots-web/<story-id>.png`, copy only that file into
  `src/lib/stories/baseline/`, and then run the gate again.
- Story interactions must actually run (`docs/story-snapshots.md` §1, §3). Use the plain-DOM
  `settled` helper pattern from `DispatchCard.stories.svelte:19-34`. A `play` that throws is a
  failure, even if Storybook reports success.
- Story failure fixtures reject with a **`CoreError` shape** (`{ kind, message }`), never
  `new Error(...)`. Tauri rejects with the plain object, and an `Error` fixture hides
  `[object Object]` bugs (M0 final review, I1).
- **Falsification (AGENTS.md):** for every new test, mutate the implementation, watch the test fail,
  restore it, and record what you saw in the task report.
- Synthetic hover does not register in WebKitGTK over WebDriver. Reveal hover-only UI with focus
  (Tab, or `element.focus()` through `execute/sync`).
- The live account is read-only for verification. You may open the app, open rooms and menus, take
  screenshots and read the DOM. Do not send, edit, delete, react, leave, sign out or answer. List
  each skipped live step under "Live verification pending".
- **Desktop verification for every task:**
  - `pnpm test`, `pnpm check` and `./scripts/snapshot-stories.sh`;
  - plus, for a task that touches `src-tauri/`: `cargo test --workspace`,
    `cargo fmt --all --check` and
    `cargo clippy --workspace --all-targets --all-features -- -D warnings`.
- **Serialize:** tasks marked **[page]** edit `src/routes/+page.svelte`, and tasks marked
  **[timeline]** edit `src/lib/components/Timeline.svelte`. Run tasks with the same mark one after
  another, in plan order, never in parallel. Tasks marked **[composer]** share
  `Composer.svelte` and are serialized the same way.

## Review Focus

1. **The newest or oldest message's actions at a narrow width or the largest text size.** The
   action pill and its "More" menu must stay inside the scroller. Pinned in Task 3 by
   `rowEdgeClass reserves the first and last rows` and `menuOpensUp near the bottom edge`, and
   checked in the running app at 600px and at 150% zoom in Task 15.
2. **"Request changes" sent while offline.** The dialog keeps the typed reason and says so. The card
   stays answerable and shows no receipt. Pinned in Task 7 by the "Request changes fails" story and
   `answerDecision passes the comment and reports failure`.
3. **An edit that fails, or a room switch mid-edit.** The edited text stays in the composer and no
   edit body is ever sent as a new message. Leaving the room restores the pre-edit draft. Pinned in
   Task 4 by `EditStash restores the draft on a room switch` and `sendIntent never sends while
   editing`.
4. **Sign out and back in as another account.** Edit and reply targets from the first account must
   not show. Pinned in Task 14 by `resetComposeState clears edit and reply targets`.
5. **A burst of history (pagination or resync), or a message arriving while scrolled away.** Nothing
   animates in or moves under the reader. Pinned in Task 10 by `arrivedKeys is empty when not
   following`, `… for more than three` and `… on the first fill`.

---

### Task 1: One error presenter for every panel **[timeline]**

Several panels print the rejected `CoreError` with `String(err)` or
`err instanceof Error ? err.message : String(err)`. A real Tauri rejection is a plain object, so the
reader sees `[object Object]`. Three places also use the undefined utility `text-destructive`, which
renders the failure in the body colour.

**Files:**
- Modify:
  - `src/lib/components/SearchPanel.svelte:67`, `:121`;
  - `src/lib/components/NewRoomPanel.svelte:85`, `:179`;
  - `src/lib/components/InvitationPanel.svelte:62-63`;
  - `src/lib/components/SpaceInvitePanel.svelte:62-63`;
  - `src/lib/components/RecoveryPanel.svelte:75`, `:93`;
  - `src/lib/components/Timeline.svelte:885` (`saveMedia`) and `:1618`.
- Modify the stories: `SearchPanel.stories.svelte`, `NewRoomPanel.stories.svelte`,
  `InvitationPanel.stories.svelte:7`, `SpaceInvitePanel.stories.svelte:7`,
  `RecoveryPanel.stories.svelte`. Every `refuse` rejects with a `CoreError` object.
- Create: `src/lib/errorPresentation.test.ts`.
- Modify: `src/routes/login/loginMessage.test.ts`, for the D4 deferred minor.

**Interfaces:**
- Consumes: `errorMessage(err: unknown): string` (`src/lib/errorMessage.ts:16`). Do not change its
  strings; they are iOS `ErrorPresenter.swift`'s.

- [ ] **Step 1: Write the failing static test** in `errorPresentation.test.ts`.
  - Read every `src/**/*.svelte` file with `fs` and `import.meta.glob`, as
    `timelineActionAnchor.test.ts` does.
  - `noPanelStringifiesAnError`: no file matches `/String\(err\)|instanceof Error \? err\.message/`.
  - `noUndefinedDestructiveUtility`: no file contains `text-destructive`.
- [ ] **Step 2: Add the loginMessage cases** (D4 minor) to `loginMessage.test.ts`.

  | Test | Error | `loginMessage` returns |
  |---|---|---|
  | `networkWhitespaceIsGeneric` | `{kind: "network", message: "  "}` | `"Can't reach the homeserver."` |
  | `storeNamesTheLocalStore` | `{kind: "store", message: "disk full"}` | starts with `"Supermessage could not open what it saved on this computer"` and ends with `"disk full"` |
  | `otherKindsShowTheCoresMessage` | `{kind: "protocol", message: "M_UNKNOWN"}` | `"M_UNKNOWN"` |

- [ ] **Step 3: Run the tests and confirm they fail.**
  `pnpm vitest run src/lib/errorPresentation.test.ts src/routes/login` fails, listing the six
  files. The `loginMessage` rows may already pass; record that rather than forcing a failure.
- [ ] **Step 4: Implement.**
  - Replace each stringification with `errorMessage(err)`. Keep each panel's own prefix (for
    example `Could not accept: ${errorMessage(err)}`).
  - Replace `text-destructive` with `text-danger`.
  - In the five story files, change `refuse` to `() => Promise.reject({ kind: "refused", message:
    "M_FORBIDDEN: not invited" })` (keep each file's own wording). Search fails uses
    `{ kind: "network", message: "" }`, which must render "Can't reach the homeserver."
- [ ] **Step 5: Run the checks and confirm they pass.** `pnpm test`, `pnpm check` and the gate.
  - Expected changed frames: `rooms-searchpanel--search-fails` and `rooms-newroompanel--create-fails`
    (the colour is now danger, the words are the presenter's).
  - Inspect each frame and copy it in by hand.
  - Falsify: revert InvitationPanel to `String(err)` and check that its "Accept fails" frame shows
    `[object Object]`.
- [ ] **Step 6: Commit.** Message: `desktop: one error presenter for every panel`.

### Task 2: One connection line under the roster title, with iOS's words **[page]**

Decided: O4 — one line, like iOS, under the roster title ("Connecting…", "Offline",
"Reconnecting…", nothing when live). The duplicate goes: the full-width `ConnectionBanner` and the
header's dot-and-word are replaced by a single line.

**Files:**
- Create: `src/lib/connectionWords.ts`, `src/lib/connectionWords.test.ts`.
- Create: `src/lib/components/ConnectionLine.svelte` and `ConnectionLine.stories.svelte`.
- Delete: `src/lib/components/ConnectionBanner.svelte` and `ConnectionBanner.stories.svelte`
  (their frames `chrome-connectionbanner--*` are removed from the baselines; list them in the PR).
- Modify:
  - `src/routes/+page.svelte:236-254` (delete `connectionWord`), `:698` (remove the
    `ConnectionBanner`) and `:985-1007` (remove the header's dot and word);
  - the roster column in `+page.svelte`: render `ConnectionLine` directly under the roster's title.

**Interfaces:**
- Produces: `connectionSubtitle(state: ConnectionState): string | null`.
  - The copy is iOS `RootView.swift:165-173`: `syncing` → `"Connecting…"`, `offline` →
    `"Offline"`, `error` → `"Reconnecting…"`, `live` → `null`.
  - Desktop's `syncing` is iOS's `connecting`.
- Produces: `ConnectionLine` props `{ state: ConnectionState; message?: string | null }`. It
  renders nothing when `connectionSubtitle(state)` is `null`; otherwise one
  `role="status"` line in `text-meta text-content-muted` (never amber, no dot), with `message`
  (the error detail the banner used to show) only in its `title` attribute.

- [ ] **Step 1: Write the failing tests** in `connectionWords.test.ts`. Assert each of the four
  mappings, with the `…` character (U+2026), not three dots.
- [ ] **Step 2: Run the tests and confirm they fail.** `pnpm vitest run src/lib/connectionWords.test.ts`
  fails: no module.
- [ ] **Step 3: Implement.**
  - Add `ConnectionLine` under the roster title. When live it renders **nothing** ("nothing when
    live", `RootView.swift:160-163`).
  - The header no longer carries any connection indicator, and no banner remains: there is one
    line in the app, not two.
  - Stories: "Connecting", "Offline", "Reconnecting" and "Live (renders nothing)".
- [ ] **Step 4: Run the checks.**
  - Removed frames: `chrome-connectionbanner--*`. New frames: `connectionline--*`. Inspect, then
    record only those.
  - In the running app, read the `role="status"` text over WebDriver: it appears once, under the
    roster title, and is absent when live.
- [ ] **Step 5: Commit.** Message: `desktop: one connection line under the roster title (O4)`.

### Task 3: The message action pill — never clipped, with a More menu **[timeline]**

This is the M0 carry-over. On 2026-10-22 the newest message's hover bar was seen clipped at the
bottom of the list, despite `pb-8` (`Timeline.svelte:1363-1384`).

The likely mechanism: since M0 the bar holds Reply, 6 reactions, "+", Copy and Delete in a
`flex-wrap` row (`MessageActions.svelte:98`). On a narrow column it wraps to two lines (about 44px)
under a 32px reserve. This task adds Edit, which would make that worse.

The fix moves the bar off the gap below the block, which also frees the T2 rhythm in Task 8.

**Files:**
- Modify: `src/lib/components/timeline/MessageActions.svelte` (all of it).
- Modify: `src/lib/components/timeline/messageMenu.ts`, `messageMenu.test.ts`.
- Create: `src/lib/components/timeline/rowMeta.ts`, `rowMeta.test.ts`.
- Create: `src/lib/components/timeline/MessageActions.stories.svelte`.
- Modify:
  - `src/lib/components/Timeline.svelte:1210` and `:1735` (the call sites);
  - `Timeline.svelte:1374-1384` (row padding).
- Modify: `src/lib/components/timelineActionAnchor.test.ts`.

**Interfaces:**
- Produces:
  - `menuActions(row)` now returns `{ react, copy, edit, del, more, bar }`.
    - `edit = row.item.editable && row.item.eventId !== null`. The rule is iOS
      `TimelineCollectionView.swift:1110-1117`: `editable`, never `isOwn`.
    - `more = copy || edit || del`.
    - `bar = react || more`.
  - `menuOpensUp(anchor: { top: number; bottom: number }, viewport: { top: number; bottom: number }, menuHeight: number): boolean`.
    It is true when `anchor.bottom + menuHeight > viewport.bottom` and
    `anchor.top - menuHeight >= viewport.top`.
  - `rowEdgeClass(index: number, count: number): string` in `rowMeta.ts`. It returns `"pt-4"` for
    index 0, `"pb-4"` for the last index, and both when `count === 1`.
  - New `MessageActions` prop `onEdit: (row: ItemRow) => void`. The `alignEnd` prop is removed:
    the pill always sits at the block's top-right.

- [ ] **Step 1: Reproduce in the running app before touching code.**
  - Use a room whose newest row is your own message, at 1905px and at 600px window widths.
  - Over WebDriver `execute/sync`, focus the newest row's first action button.
  - Read the `getBoundingClientRect()` of the bar, of `.timeline-scroller`, and the bar's
    `offsetHeight`.
  - Record the numbers in the task report.
  - If the bar is a single line and still ends below the scroller's bottom, **stop and report
    BLOCKED** with the numbers: the cause is not the wrap, and this fix would not cover it.
- [ ] **Step 2: Write the failing tests.**
  - In `messageMenu.test.ts`:
    - `editOfferedWhenEditable`: `editable: true, eventId: "$e"` → `edit: true`.
    - `editNotOfferedWhenNotEditable`: an own message with `editable: false` → `edit: false`.
    - `editNotOfferedWithoutEventId` → `edit: false`.
    - `moreOfferedForCopyOnly`: body set, not own, not replyable → `more: true, react: false`.
    - `menuOpensUp near the bottom edge`: anchor `{top: 880, bottom: 906}`, viewport
      `{top: 60, bottom: 906}`, height 120 → `true`.
    - `menuOpensDown with room below`: anchor `{top: 100, bottom: 126}` → `false`.
    - `menuOpensDown when neither fits`: viewport `{top: 60, bottom: 200}`, anchor
      `{top: 100, bottom: 126}`, height 120 → `false`.
  - In `rowMeta.test.ts`: `rowEdgeClass reserves the first and last rows`, with
    `(0, 5) → "pt-4"`, `(4, 5) → "pb-4"`, `(2, 5) → ""` and `(0, 1) → "pt-4 pb-4"`.
  - In `timelineActionAnchor.test.ts`: assert that `MessageActions.svelte`'s bar class contains
    `top-0`, `-translate-y-1/2` and `flex-nowrap`, and contains neither `top-full` nor
    `flex-wrap`. Keep the existing "every `group` wrapper is `relative`" check.
- [ ] **Step 3: Run the tests and confirm they fail.**
  `pnpm vitest run src/lib/components/timeline src/lib/components/timelineActionAnchor.test.ts`.
- [ ] **Step 4: Implement.**
  - **The pill:** `absolute top-0 right-0 z-10 -translate-y-1/2 flex flex-nowrap items-center
    gap-0.5 rounded-control border border-border bg-surface-raised px-1 shadow-overlay`. It keeps
    the existing opacity, `group-hover` and `focus-within` reveal, and `pointer-events-none` until
    revealed.
  - **Pill contents, in order:** "Reply", the 6 `QUICK_REACTIONS`, "+" (all when `react`), then a
    "More" button when `more`. The More button shows `⋯` with `aria-hidden`,
    `aria-label="More actions"`, `aria-haspopup="menu"` and `aria-expanded`.
  - **The menu:** `role="menu"`. Items are `role="menuitem"`, in iOS order
    (`TimelineCollectionView.swift:1098-1127`): "Copy", "Edit", "Delete".
    - "Delete" uses `text-danger`.
    - On open, measure the More button and `.timeline-scroller`, and place the menu `bottom-full`
      when `menuOpensUp(...)`, otherwise `top-full`.
    - Escape and a click outside close it.
  - **Rows:** the row `div` at `Timeline.svelte:1374-1384` takes `rowEdgeClass(index,
    displayRows.length)` in place of the `pb-8` ternary. Rewrite the comment above it to explain
    the half-pill overhang at both ends.
  - **For now** `onEdit` is wired to a no-op in `Timeline.svelte`. Task 4 connects it.
  - **Stories** (`MessageActions.stories.svelte`), inside a `relative group` frame with a fixed
    width:
    - "Own message, focused": the play focuses Reply.
    - "More menu open": the play clicks More and waits for `[role=menu]`.
    - "Copy only (sending)": a `notSentYet` own message; the play focuses More.
- [ ] **Step 5: Run the checks.**
  - Unit tests and the gate pass. Record the 3 new frames after inspection.
  - Repeat the Step 1 measurement at both widths. The bar's bottom must be at or above the
    scroller's bottom, and its top at or below the scroller's top for the oldest loaded row.
  - Open More on the newest row and check that the menu opens upward.
- [ ] **Step 6: Commit.** Message: `desktop: message actions sit on the block, never clipped`.

### Task 4: Composer edit mode **[timeline] [composer]**

**iOS behaviour:**
- `ComposerView.swift:147` — the placeholder reads "Edit message".
- `:199-203, :236` — a tick control labelled "Save edit".
- `:270-282` — on failure, "Could not save the edit." and the text stays.
- `:320-328` — cancelling restores the draft.
- `:557-581` — `EditStrip`: "Editing message", with a "Cancel" ✕.
- `:155-161` — the draft is not written while editing.
- `EditTarget.swift:38-44` — an edit starts only on an `editable` row with an event id, seeded
  with `body ?? ""`.

**Files:**
- Create: `src/lib/stores/editTarget.svelte.ts`, `src/lib/stores/editTarget.test.ts`.
- Create: `src/lib/components/composer/editMode.ts`, `editMode.test.ts`.
- Create: `src/lib/components/composer/EditBanner.svelte` and `EditBanner.stories.svelte`. Its
  shape copies `ReplyBanner.svelte`; do not edit `ReplyBanner.svelte` (drift frames).
- Modify: `src/lib/components/Composer.svelte`:
  - the room-switch `$effect`;
  - `send()` (`:506-567`);
  - the placeholder (`:762`);
  - the Send label (`:805-820`);
  - the banner slot (`:629`).
- Modify: `src/lib/components/Timeline.svelte`. Add `startEdit` beside `startReply` (`:974`) and
  wire `MessageActions.onEdit`.

**Interfaces:**
- Consumes: `editMessage(roomId: string, eventId: string, body: string): Promise<void>`
  (`src/lib/ipc.ts:1480`); `menuActions(row).edit` (Task 3).
- Produces:
  - `interface PendingEdit { eventId: string; body: string }`.
  - `editTargetStore`: `get(roomId): PendingEdit | null`, `set(roomId, edit)`, `clear(roomId)`,
    `clearAll()`, and `fromRow(row: { item: { editable: boolean; eventId: string | null; body: string | null } }): PendingEdit | null`.
    It has the same shape as `replyTargetStore` (`src/lib/stores/replyTarget.svelte.ts`).
  - `class EditStash` in `editMode.ts`:
    - `begin(draft: string, edit: PendingEdit): string` returns the text to show (the body) and
      keeps the draft.
    - `end(): string` returns the kept draft and clears the stash.
    - `get editing(): PendingEdit | null`.
  - `sendIntent(edit: PendingEdit | null, staged: boolean, text: string): "edit" | "attachment" | "text" | "nothing"`.
    - When `edit` is set, the result is `"edit"` if `text.trim() !== ""`, otherwise `"nothing"`.
    - Otherwise: `"attachment"` when staged, `"text"` when trimmed text, and `"nothing"`.
  - `composerPlaceholder(edit: boolean, replySender: string | null): string` returns
    `"Edit message"` when editing, otherwise the existing `` `Reply to ${sender}…` `` /
    `"Message…"`.
  - `sendLabel(edit: boolean, staged: boolean): string` returns `"Save edit"`, `"Send file"` or
    `"Send"`.

- [ ] **Step 1: Write the failing tests.**
  - `editTarget.test.ts`:
    - `fromRow refuses a non-editable row` → `null`.
    - `fromRow refuses a row without an event id` → `null`.
    - `fromRow seeds an empty body` with `body: null` → `{ eventId, body: "" }`.
    - `clearAll empties every room`.
  - `editMode.test.ts`:
    - `begin shows the body and keeps the draft`: `begin("half typed", {eventId: "$e", body: "old"})`
      returns `"old"`, and `end()` returns `"half typed"`.
    - `EditStash restores the draft on a room switch`: after `begin`, `end()` returns the draft
      and `editing` is `null`.
    - `sendIntent never sends while editing`: with `edit` set and `staged: true`, the result is
      `"edit"`, never `"attachment"` or `"text"`. With whitespace text the result is `"nothing"`.
    - `placeholder and label`: assert the exact strings above.
- [ ] **Step 2: Run the tests and confirm they fail.**
  `pnpm vitest run src/lib/stores/editTarget.test.ts src/lib/components/composer`.
- [ ] **Step 3: Implement.**
  - **Starting an edit:** `Timeline.startEdit(row)` does
    `const e = editTargetStore.fromRow(row); if (e) editTargetStore.set(roomId, e)`.
  - **Composer pick-up:** the Composer watches `editTargetStore.get(roomId)`. On a new edit it calls
    `value = stash.begin(value, edit)`, clears any reply target for the room (iOS shows one strip,
    edit first, `ComposerView.swift:70-74`), and focuses the textarea.
  - **Drafts:** while `stash.editing` is set, the draft tracker is not written.
  - **Room switch:** in the room-switch `$effect`, before `drafts.switchTo`, if editing:
    `editTargetStore.clear(previousRoomId); value = stash.end()`.
  - **Sending:** `send()` branches on `sendIntent`. `"edit"` calls
    `editMessage(roomId, edit.eventId, value)`.
    - On success: `editTargetStore.clear(roomId); value = stash.end()`.
    - On failure: set `failure = { label: "Edit failed", message: "Could not save the edit." }`
      and keep the text and edit mode. The message is iOS's; the label is the existing eyebrow
      slot.
  - **Banner and controls:** `EditBanner` renders in the banner slot ("Editing message", and a
    Cancel ✕ with `aria-label="Cancel"`). Cancel calls `editTargetStore.clear(roomId); value =
    stash.end()`. The attach button stays visible and enabled while editing (Decided: O2, iOS
    `ComposerView.swift:137-163`); a file attached mid-edit waits on the chip, and Save edit
    saves the text only (`sendIntent` returns `"edit"`).
  - **Stories:** "Editing" (`EditBanner`), and "Edit, Cancel focused" (the play focuses Cancel).
- [ ] **Step 4: Run the checks.**
  - All pass; record the 2 new frames after inspection.
  - In the running app (read-only): open the More menu on your own message and choose Edit. The
    composer shows "Editing message" and the old text, and the placeholder becomes "Edit message"
    when the field is cleared. Press Cancel and check that the earlier draft returns.
  - Do **not** save. List "save an edit; edit failure offline" as live verification pending.
- [ ] **Step 5: Commit.** Message: `desktop: edit your own messages from the composer`.

### Task 5: Composer capsule (C1) and failures on the chip (C3) **[composer]**

**iOS behaviour:**
- `ComposerView.swift:16-22` and `:62-81` — one capsule holds the strip, the chip and the field
  row. "+" is inside, at the leading edge.
- `:24-29` and `:626-675` — an attachment's failure is drawn **on its chip** (filename, the message
  in danger, a danger border), never as a loose line above.
- `:633` — the filename falls back to `"Attachment"`.
- `:240-247` and `StagedAttachment.swift:90-92` — a failed chip with no file blocks sending the
  text alone (`blocksSend`).
- **The thumbnail half of C3 is moved to core.** See the section at the end.

**Files:**
- Create: `src/lib/components/composer/ComposerCapsule.svelte` and
  `ComposerCapsule.stories.svelte`.
- Modify:
  - `src/lib/components/composer/StagedAttachmentChip.svelte`;
  - `src/lib/components/composer/StagedAttachmentChip.stories.svelte`;
  - `src/lib/components/stagedAttachment.ts` and `stagedAttachment.test.ts`.
- Modify: `src/lib/components/Composer.svelte`:
  - the failure block (`:636-642`);
  - the staged chip (`:673-690`);
  - the field row (`:704-830`);
  - `attach` (`:330-353`), `sendStaged` (`:380-411`) and `canSend` (`:499`).

**Interfaces:**
- Consumes: `AttachmentFailure`, `attachmentFailure(err, phase)` and `stagedStripView`
  (`stagedAttachment.ts:250, :294, :193`).
- Produces:
  - `interface FailedChip { filename: string; failure: AttachmentFailure }` and
    `blocksSend(staged: StagedAttachment | null, failed: FailedChip | null): boolean`, which is
    `staged === null && failed !== null`. Both live in `stagedAttachment.ts`.
  - `StagedAttachmentChip` prop `failure?: AttachmentFailure | null` (default `null`). When it is
    set:
    - the eyebrow shows `failure.label` in `text-danger`;
    - the rail is `border-l-danger`;
    - `failure.message` renders with `role="alert"` in `text-danger`, in place of the summary.
  - `ComposerCapsule` props: `{ top?: Snippet; field: Snippet; onAttach: () => void; attachDisabled: boolean /* true only while sending; never while editing, O2 */; sendLabel: string; canSend: boolean; onSend: () => void }`.
    It renders a `rounded-card border border-border bg-surface` capsule: `top` (strips and chip)
    above a row of "+" (the existing `aria-label="Attach a file"` and `title`), `field`, and the
    Send button.

- [ ] **Step 1: Write the failing tests** in `stagedAttachment.test.ts`.
  - `blocksSend when only a failed chip remains`: `(null, chip)` → `true`.
  - `does not block with a staged file`: `(staged, chip)` → `false`.
  - `does not block with neither`: `(null, null)` → `false`.
- [ ] **Step 2: Run the tests and confirm they fail.**
  `pnpm vitest run src/lib/components/stagedAttachment.test.ts`.
- [ ] **Step 3: Implement.**
  - The Composer holds `failedChip = $state<FailedChip | null>(null)`.
  - **Send failure:** `sendStaged`'s `catch` sets `failedChip = { filename:
    stagedStripView(attachment).filename, failure: attachmentFailure(err, "send") }` instead of
    `failure`. The draft stays.
  - **Attach failure:** `attach`'s `catch` sets `failedChip = { filename: "Attachment", failure:
    attachmentFailure(err, "attach") }`.
  - **Remove** clears `failedChip`, and so does a room switch.
  - `canSend` gains `&& !blocksSend(staged, failedChip)`.
  - The loose failure block keeps only text-send failures (the `roomChanged` text case, and Task
    4's edit failure).
  - The chip renders from `staged` (no failure) or from `failedChip` (with failure).
  - Wrap the strips, chip and field in `ComposerCapsule`. The tray keeps its sunken ground and
    `72ch` column.
  - **Stories:**
    - chip "Send failed on the chip": `attachmentFailure({kind: "attachmentTooLarge", message:
      "that file is 200.0 MiB, but this homeserver accepts at most 50.0 MiB"}, "send")`;
    - chip "Refused when attaching": `{kind: "unknownAttachment"}`, filename "Attachment";
    - capsule "Empty", "With a staged file" and "With a failed file".

    The capsule stories pass a plain `<textarea>` snippet.
- [ ] **Step 4: Run the checks.**
  - All pass. The existing three chip frames must be unchanged. Record the 5 new frames after
    inspection.
  - In the running app (read-only): check that "+" sits inside the capsule at 1905px and at 600px.
  - List "send failure keeps the chip" as live verification pending (it needs a send).
- [ ] **Step 5: Commit.** Message: `desktop: composer capsule, with failures on the chip`.

### Task 5a: A failed attachment stays on the chip, ready to retry (D12) **[composer]**

**This corrects an M0 ruling.** M0 decided that dropping a failed attachment was parity. It is
not. iOS re-stages after a failed send (`apple/SupermessageKit/Stores/StagedAttachment.swift:143-178`):
- it discards the old token, which is a no-op if the send consumed it;
- it stages the **same path** again (`attachmentStagePath`);
- it re-applies the voice mark, if there was one;
- the chip keeps the file and shows the failure. The fallback copy is "Couldn't send that file."
  (`:155`).

A retry then sends the file. The draft stays throughout.

**Why this waits for a Core task:**
- Desktop cannot copy the iOS mechanism in the webview. The webview never holds a path: that is
  the attachments design's §3, stated in `src-tauri/capabilities/default.json` and in
  `ipc.ts:700-745, :1879-1900`. A Tauri command that stages a path supplied by the webview would
  undo that design, so this plan does not add one.
- `src-tauri` cannot do it alone either:
  - `attachments::send_staged` takes the path out of the map before reading
    (`crates/supermessage-core/src/attachments.rs:703`, `take_for_send`, which is private);
  - on failure it returns only the `CoreError`;
  - the picker and the drop handler stage inside the core, so the host never sees the path.
- The re-stage therefore has to happen in the core. That is also where it belongs: it is the rule
  iOS keeps in Swift today.

**Core-track prerequisite: M1a PR2 (M1.7, `parity/m1-core-send-outcome`), already planned in
`2026-10-22-parity-m1a-core-rules.md` Tasks 3-4. This task waits for it to merge.** Its contract
(ruling 11):
- **Core:** `attachments::send_staged` returns
  `CoreResult<SendOutcome>`, where `SendOutcome` is `Sent` or
  `Failed { error: CoreError, restaged: Option<StagedAttachment> }`.
- **On any failure after `take_for_send`,** the core:
  - stages the same path again through `stage_path`, getting a fresh token for the same room;
  - re-applies the entry's `VoiceNote`;
  - returns `restaged: None` when the file can no longer be staged (it is gone, or now too large).
- **On a refusal before the token was taken** (`roomChanged`, `unknownAttachment`), the core
  returns the error as today.
- **FFI:** the same outcome is exposed, so iOS can delete its Swift `restage` and use the core's.
  Both sets of bindings are regenerated in the same PR.
- **Tauri:** `attachment_send` returns `SendOutcome`, serialized as
  `{ "outcome": "sent" } | { "outcome": "failed", "error": CoreError, "restaged": StagedAttachment | null }`.
  This changes the shape of an existing command; it adds no new one.

**Files (desktop, once M1a PR2 has merged):**
- Verify, do not redo: `src/lib/ipc.ts` `attachmentSend` already returns
  `Promise<AttachmentSendOutcome>` and `ipc.test.ts` already has `attachmentSend returns the
  core's outcome` (both from M1a PR2). Update only the doc comment if it still says the token is
  simply consumed.
- Modify: `src/lib/components/Composer.svelte` `sendStaged` — M1a PR2 left a minimal adaptation
  (discard `restaged.token`, throw `error`); this task replaces it.
- Modify: `src/lib/components/stagedAttachment.ts` and `stagedAttachment.test.ts`.
- Modify: `src/lib/components/composer/StagedAttachmentChip.stories.svelte`.

**Interfaces:**
- Consumes:
  - the Core task's `attachment_send` outcome;
  - Task 5's `FailedChip`, `blocksSend` and the chip's `failure` prop.
- Produces:
  - (Consumed, from M1a PR2: `type AttachmentSendOutcome = { outcome: "sent" } | { outcome: "failed"; error: CoreError; restaged: StagedAttachment | null }`.)
  - `afterFailedSend(outcome: Extract<AttachmentSendOutcome, { outcome: "failed" }>, filename: string): { staged: StagedAttachment | null; failed: FailedChip }`
    in `stagedAttachment.ts`.
    - `staged` is `outcome.restaged`.
    - `failed` is `{ filename, failure }`, built from `attachmentFailure(outcome.error, "send")`
      with one change. When the file **was** re-staged and the core gave no message, the message
      is iOS's fallback, "Couldn't send that file.". The default wording, "That file wasn't sent.
      Attach it again." (`stagedAttachment.ts:326-334`), would then be false, because there is
      nothing to attach again.
    - When nothing was re-staged, the default wording stays.
  - The chip renders the failure **and** stays sendable when both `staged` and `failed` are set.
    `blocksSend` is unchanged: it blocks only when there is no file behind the failure.
  - A successful send clears `failed`.

- [ ] **Step 1: Write the failing tests.**
  - `stagedAttachment.test.ts`:
    - `afterFailedSend keeps the re-staged file`: `restaged` is set → `staged` is that file and
      `failed.filename` is the original name.
    - `afterFailedSend without a re-stage blocks the text`: `restaged: null` → `staged: null`, and
      `blocksSend(staged, failed)` is `true`.
    - `afterFailedSend uses iOS's fallback once re-staged`: `error: {kind: "network", message: ""}`
      with a re-staged file → the message is `"Couldn't send that file."`.
    - `afterFailedSend keeps "Attach it again" without a re-stage`: the same error with
      `restaged: null` → `"That file wasn't sent. Attach it again."`.
    - `afterFailedSend shows the core's words`: `error: {kind: "network", message: "connection
      reset"}` → `"Connection reset"`.
    - `a retry is allowed`: `blocksSend(restagedFile, failed)` is `false`.
- [ ] **Step 2: Run the tests and confirm they fail.**
  `pnpm vitest run src/lib/components/stagedAttachment.test.ts`.
- [ ] **Step 3: Implement.**
  - In `sendStaged`, branch on the outcome.
    - **`sent`:** what happens today, plus clearing `failedChip`.
    - **`failed`:** `const next = afterFailedSend(outcome, filename)`. If `next.staged`, call
      `adoptStaged(sentRoomId, next.staged)`. Set `failedChip = next.failed`. Leave the draft and
      the caption text untouched.
  - The `catch` stays for refusals that reject before the token was taken (`roomChanged`,
    `unknownAttachment`). Those keep Task 5's behaviour.
  - Send, with the failed chip and its re-staged file, retries the same file with the current
    text as its caption.
  - **Story:** "Failed, ready to retry". The chip has a staged file **and** the failure
    `attachmentFailure({kind: "network", message: "connection reset"}, "send")`; it shows the
    filename, the summary line hidden, the message as an alert, and "Remove".
- [ ] **Step 4: Run the checks.**
  - All pass. Record the new frame after inspection.
  - **Falsify:** make `afterFailedSend` drop `restaged`, and check that `a retry is allowed`
    fails.
  - **Live check** (pending, needs a send): stage a file, take the network down, send, and check
    the chip and the draft stay. Bring the network back, press Send, and check the file lands
    once.
- [ ] **Step 5: Commit.** Message: `desktop: a failed attachment stays on the chip for a retry (D12)`.

### Task 6: Agents are marked in the `@` menu **[composer]**

**iOS behaviour:** `ComposerView.swift:513-553` puts an "Agent" tag (`FloatingSurface.swift:54-63`,
accent text on `accentSoft`) on each agent, with the accessibility label `"{name}, agent"`.

The core already decides who is an agent: `RoomMemberDto.is_agent`
(`crates/supermessage-core/src/room_info.rs:45-50`), serialized camelCase through `room_info`.
The TS type just omits it.

**Files:**
- Modify: `src/lib/ipc.ts:159-169` (`RoomMember`) and `:1175-1178` (`Mentionable`).
- Modify: `src/lib/components/composer/MentionMenu.svelte` and `MentionMenu.stories.svelte`.
- Modify: `src/lib/components/mentions.test.ts`.

**Interfaces:**
- Produces:
  - `RoomMember` gains `isAgent: boolean` and `isIgnored: boolean`.
  - `Mentionable` gains `isAgent?: boolean`. `collect_mentions` ignores unknown fields, because
    `core::mentions::Mentionable` has no `deny_unknown_fields`.

- [ ] **Step 1: Write the failing test** in `mentions.test.ts`:
  `matchMentions keeps isAgent on the match`. Given members `[{userId: "@agent_x:h", displayName:
  "Hermes", isAgent: true}]`, `matchMentions("her", members)[0].isAgent` is `true`. This fails on
  `svelte-check`/`tsc` until the type has the field. Run `pnpm check` as the red step.
- [ ] **Step 2: Implement.**
  - For `isAgent`, the option renders a trailing `<span class="rounded-pill bg-accent-soft px-1.5
    text-label text-accent">Agent</span>` and `aria-label="{mentionLabel(member)}, agent"`.
  - Update the fixtures that build `RoomMember` (`src/lib/fixtures/*`) with `isAgent: false,
    isIgnored: false`.
  - Add the story "An agent among people".
- [ ] **Step 3: Run the checks.** `pnpm check` is clean and the tests pass. The existing four
  MentionMenu frames are unchanged. Record the new frame after inspection.
- [ ] **Step 4: Commit.** Message: `desktop: mark agents in the mention menu`.

### Task 7: Decision cards — hierarchy, typed feedback, receipt (G1) **[timeline]**

**iOS behaviour and copy:**
- `DecisionCard.swift:495-497` — one answer leads, full width, filled amber with a `signal-soft`
  label. The others are bordered secondaries, and a rejection comes last as danger text with no
  border.
- `:522-530` — the roles come from the option **id**, lower-cased: `approve`, `request_changes`
  and `reject`. Everything else is `other` (`DecisionReceipt.swift` `DecisionOptionRole`).
- `:568-578`, the Request changes dialog:
  - title "Request changes";
  - field placeholder "What needs changing?";
  - buttons "Cancel" and "Send";
  - message "This goes back to the agent as the reason, so it can pick the work up again.".
- `:616-625` — the comment is trimmed, and empty becomes `null`.
- `:607-614` — the dialog is only for `request_changes`.
- `DecisionReceipt.swift:20-27`, the outcomes:
  - "Approved by you";
  - "Changes requested by you";
  - "Rejected by you";
  - otherwise "You chose “{label}”".
- `DecisionReceipt.swift:83-89`, the time: "just now" under 60s, then the system's relative
  phrasing.
- `DecisionCard.swift:287-293` — "Waiting for the board to confirm".
- The receipt is set **only** after the send landed (`:79-90`, `:255-257`).

**Files:**
- Create: `src/lib/components/timeline/decisionView.ts` and `decisionView.test.ts`.
- Create: `src/lib/components/timeline/RequestChangesDialog.svelte`.
- Modify: `src/lib/components/decisionReply.ts` and `decisionReply.test.ts`.
- Modify: `src/lib/components/timeline/DispatchCard.svelte:58-75, :225-276` and
  `DispatchCard.stories.svelte`.
- Modify: `src/lib/fixtures/customEvents.ts`. Add a gate fixture with options `approve` /
  "Approve", `request_changes` / "Request changes" and `reject` / "Reject".
- Modify: `src/lib/components/Timeline.svelte:1006-1008` (`onDecide` gains `comment`).

**Interfaces:**
- Produces:
  - `sendDecisionReply(roomId, eventId, decision, optionId, comment: string | null = null)` and
    `answerDecision(roomId, eventId, decision, optionId, comment: string | null = null):
    Promise<boolean>`.
    - A gate sends `sendGateDecision(roomId, subject, optionId, comment, eventId, prompt)`.
    - A permission request ignores `comment`.
  - `DispatchCard` prop `onDecide: (eventId: string | null, decision: CustomEventDecision,
    optionId: string, comment: string | null) => Promise<boolean>`.
  - In `decisionView.ts`:
    - `type OptionRole = "approve" | "requestChanges" | "reject" | "other"` and
      `optionRole(option: CustomEventDecisionOption): OptionRole`;
    - `arrangeOptions(options): { primary: CustomEventDecisionOption | null; secondaries: CustomEventDecisionOption[]; rejections: CustomEventDecisionOption[] }`.
      The primary is the first non-reject option (iOS `:512-520`);
    - `receiptOutcome(option): string`;
    - `answeredWhen(atMs: number, nowMs: number): string`. It returns `"just now"` under 60 000
      ms, otherwise `Intl.RelativeTimeFormat(undefined, { numeric: "auto" })` on whole minutes,
      then hours.
  - `RequestChangesDialog` props `{ onSend: (comment: string | null) => Promise<boolean>; onCancel: () => void }`.
    It stays open with an `InlineFailure` "Not sent. Try again." when `onSend` resolves `false`,
    and keeps the typed text.

- [ ] **Step 1: Write the failing tests.**
  - `decisionView.test.ts`:
    - `optionRole is by id, case-insensitive`: `"Reject"` → `reject`, `"approve"` → `approve`,
      `"request_changes"` → `requestChanges`, `"decline"` → `other`.
    - `arrangeOptions puts reject last`: `[reject, approve, request_changes]` → primary approve,
      secondaries `[request_changes]`, rejections `[reject]`.
    - `arrangeOptions with only rejects` → primary `null`.
    - `receiptOutcome`: assert the four strings above, including `You chose “Allow once”` with
      U+201C and U+201D.
    - `answeredWhen`: `(0, 59_000)` → `"just now"`, and `(0, 120_000)` →
      `new Intl.RelativeTimeFormat(undefined, {numeric: "auto"}).format(-2, "minute")`.
  - `decisionReply.test.ts`, `answerDecision passes the comment and reports failure`:
    - with the gate fixture and comment `"tests fail"`, assert `sendGateDecision` was called with
      `(roomId, subject, "request_changes", "tests fail", eventId, prompt)` and the result is
      `true`;
    - then the mock rejects `{kind: "network", message: ""}` and the result is `false`.
- [ ] **Step 2: Run the tests and confirm they fail.**
  `pnpm vitest run src/lib/components/timeline/decisionView.test.ts src/lib/components/decisionReply.test.ts`.
- [ ] **Step 3: Implement.**
  - **Pending card:** after "Awaiting your decision", lay out the options with `arrangeOptions`:
    - **primary:** `w-full rounded-control bg-signal px-3 py-2 text-ui font-medium text-signal-soft`;
    - **secondaries:** `rounded-control border border-border-strong px-3 py-1.5 text-ui
      text-content`, in a `flex flex-wrap gap-2` row;
    - **rejections:** `px-3 py-1.5 text-ui text-danger`, with no border, after the secondaries.
  - **Disabled state:** `disabled:opacity-50 disabled:cursor-not-allowed`, and no hover fill when
    disabled. This closes the D2 deferred minor: use `enabled:hover:` variants.
  - **The `request_changes` option** opens `RequestChangesDialog` (a scrim and dialog in the
    `DeleteConfirm.svelte` shape, with a `<textarea>`). Its `onSend` calls
    `decide(option, comment)`.
  - **On a `true` result**, the card holds `answered = { option, atMs: Date.now() }` and renders the
    receipt in place of the decision block:
    - the prompt, muted;
    - `✓ {receiptOutcome} · {answeredWhen}` in `text-ok`;
    - "Waiting for the board to confirm" in `text-meta text-content-muted`;
    - no amber: drop `dispatch-card-pending` while answered.
  - **On `false`**, show "Not sent. Try again." (existing), and the buttons stay live.
  - Leave the `outcome` branch (`:206-224`) untouched; its frame is one of the 7 drift frames.
  - **Stories:**
    - "Gate, three answers";
    - "Request changes dialog": the play clicks Request changes;
    - "Request changes fails": the play clicks it, types "tests fail", clicks Send with
      `onDecide → false`, and waits for `[role=alert]`. The textarea still holds "tests fail";
    - "Answered on this device": the play clicks Approve with `onDecide → true` and waits for the
      receipt.
- [ ] **Step 4: Run the checks.**
  - All pass.
  - Expected changed frames: `timeline-dispatchcard--pending-decision`, `--sending`, `--send-failed`
    (button hierarchy). `--resolved-in-the-room` must not be among the *new* differences.
  - Record the 4 new frames, and copy the 3 changed ones after inspection.
  - List the live answer as pending.
- [ ] **Step 5: Commit.** Message: `desktop: decision cards lead with one answer and ask what needs changing`.

### Task 8: Timeline rhythm and quiet metadata (T2, T3, T4, D7) **[timeline]**

**iOS behaviour:**
- **T2** (`TimelineRowView.swift:373-375`): 2pt inside a run and 12pt between turns.
- **T3** (`:316-325`, `:555-620`):
  - reactions are pulled up over the bubble's bottom edge (`spacing: -8`, inset 10);
  - a chip shows its count only when `count > 1`;
  - your own chip is accent-tinted and the others sit on `surfaceRaised`.
- **D7** (`:334-371`, `TimelineChrome.swift:134-175`):
  - your own meta sits **under** the bubble, in faint: `Sending…` / `Not sent`, then "edited",
    then the time, then reader faces;
  - the time shows only on the last message of a run, or when the send state speaks;
  - readers appear on any own message whose `readBy` is non-empty, at most 3 faces of 14px with a
    ring, then `+N`.
- **D7, edited on a peer continuation** (`:327-332`): a continued peer row shows "edited" under its
  text.
- **T4** (`TimelineCollectionView.swift:664-669`): every message's time can be revealed, except
  date dividers, unread markers, system lines and `none` rows. On desktop the gesture is hover or
  focus.

**Files:**
- Modify: `src/lib/components/timeline/rowMeta.ts` and `rowMeta.test.ts` (created in Task 3).
- Create: `src/lib/components/timeline/ReaderFaces.svelte` and `ReaderFaces.stories.svelte`.
- Delete: `src/lib/components/timeline/SeenMarker.svelte`.
- Modify: `src/lib/components/timeline/ReactionsRow.svelte` (spacing, count and ground).
- Modify: `src/lib/components/Timeline.svelte`:
  - `:482-494` (delete `lastOwnMessageId`);
  - `:1042-1212` (`messageBlock`);
  - `:1734-1736` (the dispatch card's call sites).

**Interfaces:**
- Consumes:
  - `TimelineDisplayRow` (`timelineGrouping.ts:85`). `ItemRow` is
    `Extract<TimelineDisplayRow, { type: "item" }>`; export it from `rowMeta.ts` and import it in
    `messageMenu.ts` and `MessageActions.svelte` instead of their local copies;
  - `createMemberAvatarCache()` (`src/lib/stores/memberAvatarCache.svelte.ts`), keyed by `mxc:`.
- Produces, in `rowMeta.ts`:
  - `endsRun(rows: readonly TimelineDisplayRow[], index: number): boolean`. It is false only when
    `rows[index + 1]` is an `item` row with `continuesRun`.
  - `interface OwnMeta { state: "Sending…" | "Not sent" | null; edited: boolean; showTime: boolean }`
    and `ownMeta(row: ItemRow, endsRun: boolean): OwnMeta`.
    - `notSentYet` → "Sending…" and `sendingFailed` → "Not sent" (iOS `SendState.swift`).
    - `showTime = endsRun || state !== null`.
  - `revealsTime(row: ItemRow, endsRun: boolean): boolean`. It is true for `bubble`, `image`,
    `mediaFile` and `audio` renders with a timestamp, **unless** the time is already on screen. A
    `customEvent` card is excluded: its header always shows the time (`DispatchCard.svelte:100`).
    The cases where the time is already on screen:
    - a peer row that does not continue a run, which has the header time;
    - an own row where `ownMeta(...).showTime`.
  - `interface ReaderFace { userId: string; mxcUri: string | null; initial: string | null }`.
  - `readerFaces(rows: readonly TimelineDisplayRow[]): Map<string, ReaderFace>`. For each sender,
    use the first `kind === "message"` row's `senderAvatar` and `senderInitial` (iOS
    `TimelineCollectionView.swift:747-757`).
  - `readersFor(item: TimelineItem, faces: Map<string, ReaderFace>): ReaderFace[]`. It returns an
    empty array unless `item.isOwn`. An unknown reader gets `{userId, mxcUri: null, initial: null}`.
  - `ReaderFaces` props `{ readers: ReaderFace[]; faceFor: (mxc: string) => string | null }`.

- [ ] **Step 1: Write the failing tests** in `rowMeta.test.ts`. Use the `senderRun`,
  `ownMessageSending` and `ownMessageFailed` fixtures.
  - `endsRun is false when the next row continues`, and true for the last row.
  - `ownMeta shows the time only at the end of a run`: a mid-run own row that was sent →
    `showTime: false`.
  - `ownMeta always speaks for a failed send`: `sendingFailed` mid-run → `{state: "Not sent",
    showTime: true}`.
  - `revealsTime skips a peer run start` and `revealsTime offers a peer continuation`.
  - `revealsTime skips a date divider`.
  - `readersFor ignores peers' messages`.
  - `readersFor falls back for an unseen reader`.
- [ ] **Step 2: Run the tests and confirm they fail.**
  `pnpm vitest run src/lib/components/timeline/rowMeta.test.ts`.
- [ ] **Step 3: Implement.**
  - **Rhythm:** the row wrapper becomes `pt-0.5` when `continuesRun`, and `pt-3` otherwise. Rewrite
    the comment at `:1114-1127`: the 22px reason no longer applies, because Task 3's pill no
    longer sits in that gap.
  - **Own meta line:** move it out of the bubble and below it, `justify-end`, in
    `font-sans text-meta tabular-nums text-content-faint`. When failed, use `text-danger` with a
    leading `!` in `aria-hidden` and the words "Not sent".
    - Its order is: state, "edited", time when `showTime`, then `<ReaderFaces>`.
    - `ReaderFaces` takes `aria-label={`Seen by ${readers.length}`}`, the existing desktop words,
      until M3 brings `people_label` (see the moved-to-core section).
  - **Peer "edited":** a peer continuation that was edited renders "edited" under its text, and no
    header line.
  - **Hover time:** when `revealsTime`, render `<time datetime={iso}>` in `absolute top-0
    text-meta tabular-nums text-content-faint opacity-0 group-hover:opacity-100
    group-focus-within:opacity-100`.
    - Peer rows put it at `right-0`; own rows at `left-0`.
    - It is `aria-hidden`, because the row's own label carries the time; the `title` carries it
      too.
  - **Reactions:** under an own bubble only, `ReactionsRow` takes `-mt-2 px-2.5 relative`. Peer
    blocks and cards keep `mt-1.5`; the reason is in `ReactionsRow.svelte:37-48`.
    - Not-mine chips use `bg-surface-raised`.
    - The count renders only when `reaction.count > 1`. The `aria-label` keeps the count.
  - Delete `SeenMarker.svelte` and `lastOwnMessageId`.
  - **Stories:** `ReaderFaces` "One reader", "Three readers" and "Five readers (+2)", all with
    initials only.
- [ ] **Step 4: Run the checks.**
  - All pass. Record the 3 new frames after inspection.
  - In the running app, compare a room at 1905px and 600px with the pre-task screenshot. Report
    any row whose height changed unexpectedly. Focus a continuation row and check that its time
    appears.
- [ ] **Step 5: Commit.** Message: `desktop: timeline rhythm, reactions on the corner, quiet meta`.

### Task 9: Jump to the quoted message (T5) **[timeline]**

**iOS behaviour** (`TimelineCollectionView.swift:921-950`, `TimelineRowView.swift:80-89, :540-548`):
- Tapping a quote whose parent is loaded scrolls the parent to the centre (animated unless Reduce
  Motion is on).
- The parent is washed in `accentSoft`, which fades after 1.4s.
- The quote has the hint "Shows the original message".
- A quote whose parent is not loaded is inert.

**Files:**
- Modify: `src/lib/components/timeline/ReplyQuote.svelte`.
- Create: `src/lib/components/timeline/ReplyQuote.stories.svelte`.
- Modify: `src/lib/components/timeline/rowMeta.ts` and `rowMeta.test.ts`.
- Modify: `src/lib/components/Timeline.svelte:1170` and the row `div` (`:1374`, the highlight).

**Interfaces:**
- Produces:
  - `parentIndex(rows: readonly TimelineDisplayRow[], eventId: string | null): number`. It returns
    -1 when `eventId` is null or absent.
  - `ReplyQuote` prop `onJump?: (() => void) | null`. When set, the quote is a `<button
    type="button">` with `aria-description="Shows the original message"`. When it is unset,
    the quote renders exactly as today.
  - `HIGHLIGHT_MS = 1400`, exported from `rowMeta.ts`.

- [ ] **Step 1: Write the failing tests.**
  - `parentIndex finds the parent by event id`.
  - `parentIndex is -1 for an unloaded parent`.
  - `parentIndex is -1 for null`.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement.**
  - In `Timeline`, `jumpToParent(row)` computes the index with `parentIndex` and calls
    `vlist?.scrollToIndex(i, { align: "center", smooth: !reducedMotion })`. Read `reducedMotion`
    from `matchMedia("(prefers-reduced-motion: reduce)")`.
  - It sets `highlightedKey`, and clears it after `HIGHLIGHT_MS`, cancelling any earlier timer.
  - The highlighted row's sheet `div` adds `bg-accent-soft transition-colors duration-700`. This
    is a colour only; no size changes, so virtua's measurements hold.
  - Pass `onJump` only when `parentIndex(...) >= 0`.
  - **Stories:** "Jumpable quote" (the play focuses the button) and "Unloaded parent".
- [ ] **Step 4: Run the checks.**
  - All pass. Record the 2 new frames after inspection.
  - In the running app, click a reply's quote. The parent scrolls into view, washes, and fades.
- [ ] **Step 5: Commit.** Message: `desktop: a quote jumps to the message it quotes`.

### Task 10: Motion — arrivals, expanding cards, decision to receipt (M1) **[timeline]**

**iOS behaviour:**
- `TimelineCollectionView.swift:855-884` — only messages animate in. Nothing animates while the
  reader is away from the newest messages, on the first fill, or for more than 3 arrivals at once.
- `:1180-1201` — an arrival fades in from 16pt below, 0.38s, damping 0.78.
- `DecisionCard.swift:191-194` — the receipt arrives with opacity plus a 0.97 scale anchored at
  the top.
- `WhatIDidFooter.swift:25-26` — a disclosure animates open (snappy, 0.25s).
- Reduce Motion is honoured: `app.css:69-77` already zeroes every duration, so on desktop reduced
  motion means none.

Animate only `opacity` and `transform`. A height change inside `VList` would break virtua's
measurements (`animateList.ts:24-32`).

**Files:**
- Create: `src/lib/components/timeline/arrivals.ts` and `arrivals.test.ts`.
- Modify: `src/lib/components/Timeline.svelte` (`view` derivation `:395-405`, the row `div`, and
  `<style>`).
- Modify: `src/lib/components/timeline/DispatchCard.svelte` (the receipt transition and the
  `<details>` content).

**Interfaces:**
- Produces: `arrivedKeys(previous: readonly string[], next: readonly string[], following: boolean, isMessage: (key: string) => boolean): Set<string>`.
  - It returns the message keys in `next` that are not in `previous`.
  - It returns an empty set when `previous.length === 0`, when `!following`, or when more than 3
    keys are new.

- [ ] **Step 1: Write the failing tests** in `arrivals.test.ts`.
  - `one new message animates`: `(["a", "b"], ["a", "b", "c"], true, all)` → `{"c"}`.
  - `arrivedKeys is empty when not following`.
  - `arrivedKeys is empty for more than three`: four new keys → empty.
  - `arrivedKeys is empty on the first fill`: `previous` is `[]`.
  - `a date divider never animates`: `isMessage` false for `"d"` → `{"c"}` only.
  - `pagination prepends do not animate`: new keys at the head → empty, because `following` is
    false while paginating. Assert it with `following: false`.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement.**
  - **Arrivals:** in the `view` derivation, compute `arriving = arrivedKeys(previousRowKeys, keys,
    followBottom, isMessageKey)` before `previousRowKeys = keys`. `isMessageKey` is true for
    `item` rows with `bubble`, `image`, `mediaFile`, `audio` or `customEvent` renders.
  - A row whose key is in `arriving` gets the class `row-arrive`, and removes the key from the set
    `onanimationend`, so a virtua remount does not replay it.
  - The keyframes go in `<style>`: `@keyframes row-arrive { from { opacity: 0; transform:
    translateY(16px) } }` at `380ms cubic-bezier(0.2, 0.9, 0.3, 1.1)`, applied only inside
    `@media (prefers-reduced-motion: no-preference)`.
  - **DispatchCard receipt:** a Svelte `in:` transition, opacity 0 → 1 plus scale 0.97 → 1 with
    `transform-origin: top`, 250ms. Its duration is 0 when reduced motion is on.
  - **DispatchCard reasoning:** the content under `<summary>` fades in over 200ms inside the same
    media query.
- [ ] **Step 4: Run the checks.**
  - All pass. The gate renders with `reducedMotion: 'reduce'`, so no frame may change; any
    difference is a bug.
  - In the running app, watch a new message arrive while at the bottom (another device or an
    agent): it rises in. Scroll up, let one arrive, and check that nothing moves.
- [ ] **Step 5: Commit.** Message: `desktop: messages arrive, cards open and settle`.

### Task 11: Room info — runtime, and Leave with a confirm

**iOS behaviour and copy:**
- `RoomInfoPanel.swift:108-117` — runtime rows "Harness" and "Machine".
- `:174-196` — the button "Leave room" (danger). The confirm title is
  `"Leave \(info.name ?? "this room")?"` and its message "You'll stop receiving its messages.".
  The actions are "Leave" (destructive) and Cancel. After leaving, the panel closes.

**Files:**
- Modify: `src/lib/components/RoomInfoPanel.svelte` (after the identity header at `:205`, and at
  the foot after the member list).
- Create: `src/lib/components/LeaveRoomConfirm.svelte` and `LeaveRoomConfirm.stories.svelte`.
- Create: `src/lib/components/roster/RuntimeRows.svelte` and `RuntimeRows.stories.svelte`.
- Modify: `src/lib/components/roomInfoView.ts` and `roomInfoView.test.ts`.
- Modify: `src/lib/stores/rooms.svelte.ts` (beside `declineInvitation`, `:250`) and
  `rooms.test.ts`.

**Interfaces:**
- Consumes: `RoomInfo.runtime: { harness: string; host: string } | null` (`ipc.ts:177-213`);
  `leaveRoom(roomId)` (`ipc.ts:1588`).
- Produces:
  - `leaveTitle(name: string | null): string`, which returns `` `Leave ${name ?? "this room"}?` ``.
  - `roomsStore.leave(id: string): Promise<void>`. It calls `deps.leaveRoom(id)` and then, when
    `selectedId === id`, sets `selectedId` and `selectedName` to `null`. It rethrows on failure.
  - `LeaveRoomConfirm` props `{ roomName: string | null; onLeave: () => Promise<void>; onCancel: () => void }`,
    in the `DeleteConfirm.svelte` shape. A failure shows `InlineFailure` with `errorMessage(err)`.
  - `RuntimeRows` props `{ runtime: { harness: string; host: string } }`. It is a `<dl>` with "Harness" and
    "Machine".

- [ ] **Step 1: Write the failing tests.**
  - `roomInfoView.test.ts`:
    - `leaveTitle names the room`: `"Ops"` → `"Leave Ops?"`.
    - `leaveTitle falls back`: `null` → `"Leave this room?"`.
  - `rooms.test.ts`:
    - `leave deselects the room it left`;
    - `leave keeps another selection`;
    - `leave rethrows and keeps the selection on failure`.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement.**
  - The panel renders `RuntimeRows` when `info.runtime`.
  - At the foot, a "Leave room" `text-danger` button opens `LeaveRoomConfirm`, with
    `roomName={info.name}`.
  - `onLeave` is `() => roomsStore.leave(roomId).then(onClose)`. The `{#if roomsStore.selectedId}`
    in `+page.svelte` then shows the empty state with no page edit.
  - **Stories:** `RuntimeRows` "Agent room"; `LeaveRoomConfirm` "Leave confirmation" and "Leave
    fails" (`onLeave` rejects `{kind: "network", message: ""}`, and the play clicks Leave).
- [ ] **Step 4: Run the checks.**
  - All pass. Record the 3 new frames after inspection.
  - In the running app, open an agent room's info: Harness and Machine show. Open the confirm and
    Cancel it. Do not leave.
- [ ] **Step 5: Commit.** Message: `desktop: room info shows the runtime and can leave`.

### Task 12: Search this room or everywhere **[page]**

**iOS behaviour and copy** (`SearchPanel.swift:33-59, :90-99, :143-146`):
- When opened from a room, a two-way control offers `{room name}` and "All rooms". It starts
  narrowed to the room.
- Changing it re-runs a search already made.
- The idle hint reads "Find a message in {name}." when narrowed, otherwise "Find a message across
  your rooms.".

**Files:**
- Modify: `src/lib/components/searchView.ts` and `searchView.test.ts`.
- Modify: `src/lib/components/SearchPanel.svelte` and `SearchPanel.stories.svelte`.
- Modify: `src/routes/+page.svelte:1191-1198`.

**Interfaces:**
- Consumes: `searchMessages(term: string, roomId?: string): Promise<SearchResult[]>` (`ipc.ts:1745`).
- Produces:
  - `interface SearchScope { roomId: string; name: string }`.
  - `searchHint(scope: SearchScope | null, narrowed: boolean): string`.
  - `scopedRoomId(scope: SearchScope | null, narrowed: boolean): string | undefined`.
  - `SearchPanel` props: `scope?: SearchScope | null`, and `onSearch: (term: string, roomId?: string) => Promise<SearchResult[]>`.

- [ ] **Step 1: Write the failing tests.**
  - `searchHint`: assert both strings exactly, and that a `null` scope gives the "across" one.
  - `scopedRoomId`: narrowed gives the room id; widened or no scope gives `undefined`.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement.**
  - The control is a `role="radiogroup"` with `aria-label="Search in"` and two `role="radio"`
    buttons, shown only when `scope`.
  - The hint is shown before the first search.
  - `run()` passes `scopedRoomId(...)`.
  - The page passes `scope={roomsStore.selectedId ? { roomId: roomsStore.selectedId, name:
    selectedIdentity.name } : null}` and `onSearch={searchMessages}`.
  - **Stories:** "Scoped to this room" (the hint only), and "Scoped, with hits" (the play submits,
    and `onSearch` asserts it received a room id by returning hits only then).
- [ ] **Step 4: Run the checks.**
  - All pass. `rooms-searchpanel--before-searching` gains the hint; inspect it and copy it in.
    Record the 2 new frames.
  - In the running app (searching is read-only), search in a room, then switch to All rooms and
    check that the results change.
- [ ] **Step 5: Commit.** Message: `desktop: search this room or everywhere`.

### Task 13: Appearance — theme, accent and text size

**iOS behaviour and copy:**
- `Appearance.swift:11-45` and `:96-120`:
  - mode "System" / "Light" / "Dark";
  - dark style "Tinted" / "Black";
  - accents: the house accent (label "Violet") plus `blue`, `graphite`, `green`, `pink`, `teal`,
    each labelled with its capitalised name;
  - storage keys `appearance.mode`, `appearance.darkStyle` and `appearance.accent`.
- `AccountPanel.swift:83-95` — the footer: "Black is true black in dark mode, for OLED screens.
  The accent colours buttons, links and your own highlights."
- **Text size:** iOS uses Dynamic Type and has no copy of its own. Decided: O3 — "Text size" in
  Account → Appearance: Default / Large / Larger / Largest = 100 / 115 / 130 / 150 % webview zoom,
  with the `core:webview:allow-set-webview-zoom` capability.

The desktop palettes already exist: `tokens.css:127-286` (`light`, `dark`, `black`) and
`:311-666` (`[data-accent]`).

**Files:**
- Create: `src/lib/appearance.ts` and `src/lib/appearance.test.ts`.
- Create: `src/lib/stores/appearance.svelte.ts`.
- Modify: `src/routes/+layout.svelte` (install the store).
- Modify: `src/lib/ipc.ts`. Add `setTextZoom` beside the other host wrappers; this is not a
  command.
- Modify: `src-tauri/capabilities/default.json`. Add `"core:webview:allow-set-webview-zoom"`, and
  extend the description with one sentence on why.

**Interfaces:**
- Produces:
  - `type AppearanceMode = "system" | "light" | "dark"`, `type DarkStyle = "tinted" | "black"`,
    `type Accent = "" | "blue" | "graphite" | "green" | "pink" | "teal"`, and
    `type TextSize = "default" | "large" | "larger" | "largest"`.
  - `interface AppearancePrefs { mode: AppearanceMode; darkStyle: DarkStyle; accent: Accent; textSize: TextSize }`
    and `DEFAULT_PREFS = { mode: "system", darkStyle: "tinted", accent: "", textSize: "default" }`.
  - `appearanceAttributes(prefs: AppearancePrefs, systemDark: boolean): { appearance: "light" | "dark" | "black" | null; accent: Exclude<Accent, ""> | null }`.
    - `light` → `"light"`.
    - `dark` → `"black"` if the dark style is black, otherwise `"dark"`.
    - `system` → `"black"` only when `systemDark` and the style is black, otherwise `null` (the
      `prefers-color-scheme` rules apply).
    - An empty accent → `null`.
  - `zoomFor(size: TextSize): number`, mapping default/large/larger/largest to 1, 1.15, 1.3 and
    1.5.
  - `readPrefs(storage: Pick<Storage, "getItem"> | null): AppearancePrefs`. An unknown or missing
    value falls back to the default for that field. The keys are `appearance.mode`,
    `appearance.darkStyle`, `appearance.accent` and `appearance.textSize`.
  - `ACCENT_LABELS: Record<Accent, string>`, which is `"Violet"` for `""` and the capitalised
    name for the rest.
  - `appearanceStore`: `get prefs(): AppearancePrefs`, `update(patch: Partial<AppearancePrefs>):
    void` (writes storage inside try/catch, then applies), and `install(): () => void`.
    - `install` applies once and listens to `matchMedia("(prefers-color-scheme: dark)")`.
    - Applying sets or removes `data-appearance` and `data-accent` on `document.documentElement`,
      and calls `setTextZoom(zoomFor(...))`. It swallows the call outside Tauri.
  - `setTextZoom(factor: number): Promise<void>` uses `getCurrentWebview().setZoom(factor)`.

**Why zoom, not root font size:** the panel breakpoints are px tokens computed from rem-wide
columns (`+page.svelte:63-128`). Scaling the root font would widen the columns and leave the px
breakpoints behind, so it would reintroduce the 232px-pane bug that comment records. Webview zoom
scales both together.

- [ ] **Step 1: Write the failing tests** in `appearance.test.ts`.
  - `appearanceAttributes`: test all 3 modes × 2 dark styles × `systemDark` true and false.
    Notably `(system, black, true)` → `"black"` and `(system, black, false)` → `null`.
  - The accent `"teal"` passes through, and `""` → `null`.
  - `zoomFor`: test the four values.
  - `readPrefs`: `null` storage → defaults; `appearance.accent = "magenta"` → `""`; a valid set
    round-trips.
- [ ] **Step 2: Run the tests and confirm they fail.** `pnpm vitest run src/lib/appearance.test.ts`.
- [ ] **Step 3: Implement** the module, the store, the `+layout.svelte` `onMount(() =>
  appearanceStore.install())`, the wrapper and the capability. The UI is Task 14.
- [ ] **Step 4: Run the checks.**
  - `pnpm test`, `pnpm check`, the gate (unchanged), and the cargo trio (the capability changed).
  - In the running app, set `localStorage["appearance.mode"] = "dark"` over `execute/sync` and
    reload: `data-appearance="dark"` appears. Set `"appearance.textSize" = "largest"` and reload:
    `document.documentElement.clientWidth` is the window's width divided by 1.5, within a pixel.
- [ ] **Step 5: Commit.** Message: `desktop: appearance and text size settings`.

### Task 14: Account panel and sign-out confirm **[page]**

**iOS behaviour and copy** (`AccountPanel.swift`):
- `:83-95` — the "Appearance" section.
- `:125-138` — the links "Help & support" (`https://supermessage.dev/support/`), "Terms of use"
  (`https://supermessage.dev/terms/`) and "Privacy" (`https://supermessage.dev/privacy/`).
- `:140-146` — "Encryption recovery".
- `:148-157` — "Sign out" (danger), with the footer "Signing out removes this account and its
  messages from this device.".
- `:178-187` — the confirm title `"Sign out of \(name)?"` with actions "Sign out" (destructive)
  and "Cancel".
- **Not here:** the identity block ("Signed in as", name, user id, homeserver) needs the `account`
  command, so it is M3. So does the name in the confirm title.
- **Version:** desktop adds the app version, which iOS does not show.

**Files:**
- Create: `src/lib/components/AccountPanel.svelte` and `AccountPanel.stories.svelte`.
- Create: `src/lib/components/SignOutConfirm.svelte` and `SignOutConfirm.stories.svelte`.
- Create: `src/lib/components/accountView.ts` and `accountView.test.ts`.
- Create: `src/lib/stores/sessionReset.ts` and `sessionReset.test.ts`.
- Modify: `src/lib/stores/replyTarget.svelte.ts` (add `clearAll`) and `replyTarget.test.ts`.
- Modify: `src/routes/+page.svelte`:
  - `:770-809`: the sidebar foot becomes one "Account" button;
  - `:655-668`: `signOut` calls `resetComposeState()`;
  - add the panel mount.
- Modify: `src/lib/ipc.ts`. Add `appVersion`; delete `enableRecovery` (`:1275-1281`).
- Modify: `src-tauri/src/commands.rs:62-69` (delete `enable_recovery`) and
  `src-tauri/src/lib.rs:24, :222`. This closes the D1 deferred minor: the command has had no
  caller since M0, and removing it takes the registry from 46 to 45.
- Modify: `src/lib/components/RecoveryPanel.svelte`. Move the `// svelte-ignore
  state_referenced_locally` from between the doc comment and its `let` to directly above the
  `let`, under the doc comment (D1 deferred minor).

**Interfaces:**
- Consumes: `appearanceStore`, `ACCENT_LABELS` and `AppearancePrefs` (Task 13);
  `editTargetStore.clearAll()` (Task 4); `openUrl` from `@tauri-apps/plugin-opener`.
- Produces:
  - `appVersion(): Promise<string>`, via `getVersion()` from `@tauri-apps/api/app`
    (`core:default` already allows `app:allow-version`).
  - `signOutTitle(name: string | null): string`. It returns `` `Sign out of ${name}?` `` when a
    name is known, otherwise `"Sign out?"` (Decided: R2 — until M3 brings `account`).
  - `ACCOUNT_LINKS: readonly { label: string; url: string }[]`, with the three iOS entries in
    order.
  - `versionLine(version: string | null): string | null`, which returns `` `Version ${version}` ``
    or `null`.
  - `replyTargetStore.clearAll(): void`.
  - `resetComposeState(stores = { editTargetStore, replyTargetStore }): void`, which calls
    `clearAll()` on both.
  - `AccountPanel` props: `{ version: string | null; prefs: AppearancePrefs; onChange: (patch: Partial<AppearancePrefs>) => void; onOpenRecovery: () => void; onSignOut: () => Promise<void>; onOpenLink: (url: string) => void; onClose: () => void }`.
  - `SignOutConfirm` props: `{ accountName: string | null; onConfirm: () => Promise<void>; onCancel: () => void }`.

- [ ] **Step 1: Write the failing tests.**
  - `accountView.test.ts`:
    - `signOutTitle`: `"rakesh"` → `"Sign out of rakesh?"`, and `null` → `"Sign out?"`.
    - `ACCOUNT_LINKS`: the labels and URLs, exactly.
    - `versionLine`: `"0.0.15"` → `"Version 0.0.15"`, and `null` → `null`.
  - `sessionReset.test.ts`: `resetComposeState clears edit and reply targets`. Set a target in
    each store for two rooms, call it, and assert that `get` returns `null` for all four.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement.**
  - **The panel** is a right-side dialog in the `SearchPanel` scrim shape, with
    `aria-label="Account"`, closed by Escape and ✕. Its sections, in iOS order:
    - **Appearance:** a `radiogroup` "Appearance" (System, Light, Dark), a `radiogroup` "Dark
      style" (Tinted, Black), and the accent swatches. Each swatch is a `role="radio"` with
      `aria-label={ACCENT_LABELS[a]}`, and carries `data-appearance={resolved}` and
      `data-accent={a || undefined}`, so that `bg-accent` resolves to that accent's own token in
      the current appearance (`tokens.css:339-378` matches any element). The section footer is
      the iOS copy.
    - **Text size:** a `radiogroup` with "Default", "Large", "Larger" and "Largest" (O3).
    - **Links:** the three links, each a button calling `onOpenLink(url)`.
    - **Recovery:** "Encryption recovery".
    - **Sign out:** "Sign out" in `text-danger`, with the iOS footer.
    - **Version:** `versionLine(version)` in `text-meta text-content-faint`.
  - **Sign out** opens `SignOutConfirm`, showing `signOutTitle(null)`, the footer sentence as its
    message, and "Cancel" / "Sign out".
  - **The page:** the sidebar foot has a single "Account" button (it replaces "Encryption
    recovery" and "Sign out" at `:793-807`). `signOut()` calls `resetComposeState()` after
    `roomsStore.logout()`. The page passes `onOpenLink={(u) => void openUrl(u)}`.
  - Delete `enable_recovery` and `enableRecovery`, and their mention in `ipc.ts:1283`.
  - **Stories:** `AccountPanel` "Account" and "Dark, black, teal" (the latter sets prefs; the
    decorator still sets the page's appearance). `SignOutConfirm` "Sign out confirmation".
- [ ] **Step 4: Run the checks.**
  - The full desktop set passes, cargo trio included. Record the 3 new frames after inspection.
  - In the running app, open Account and switch to Dark, Black and Teal. The window repaints
    with no reload. Choose Largest text and check the app at a 900px window against Review Focus
    1 and the roadmap's Review Focus 3.
  - Open the sign-out confirm and Cancel it. Do not sign out; list it as live verification
    pending.
- [ ] **Step 5: Commit.** Message: `desktop: account panel, appearance, sign-out confirm`.

### Task 15: README and the M2 verification table

**Files:**
- Modify: `README.md:34` and any other desktop cell this milestone changed. Desktop edit becomes
  "Implemented".
- Create: `.superpowers/sdd/<this-run>/m2-desktop-verification.md`. This is the run's evidence
  record, not a repo doc.

- [ ] **Step 1: Correct the README cells** against the merged code, not against this plan.
- [ ] **Step 2: Run each M2 row in the running app at 1905px, at 600px and at Largest text.**
  Mark each row ✅ only with a screenshot or DOM read. A row that needs a write action is marked
  "live pending (user)".

  The rows: edit mode, C1, C3 on the chip, agents in `@`, Leave, runtime, the account panel,
  theme and accent, sign-out confirm, text size, search scope, G1, T2, T3, T4, T5, D7, motion,
  the connection line, the action pill at both ends, and the error presenter.
- [ ] **Step 3: Commit.** Message: `docs: README desktop rows after parity M2`.

---

## Moved to core (M1/M3)

| Row | Why it is not M2 desktop | Where it goes |
|---|---|---|
| **"Mark read" row action** (R2) — **removed from M2 desktop (R3)** | `mark_room_read` reaches only the *focused* timeline (`commands.rs:350-355` → `FocusedTimeline::mark_read`, `timeline.rs:3009-3015`, which refuses any other room). A roster row is not focused. The FFI's `mark_room_read` (`supermessage-ffi/src/lib.rs:254-257`) is the same, so **iOS's own swipe "Mark read" (`RoomListView.swift:260-268`) cannot work on an unfocused room either.** | **Core track, M3:** a `Session::mark_room_read(room_id)` that reads the room's latest event without focusing it, with FFI, Tauri and both bindings. It also fixes iOS's swipe. The desktop row action follows then. |
| **Re-stage after a failed attachment send** (D12; corrects the M0 ruling) | The webview never holds a path (design §3), and the core consumes the path on send and returns only the error (`attachments.rs:703`). iOS re-stages in Swift (`StagedAttachment.swift:143-178`). | **Core, M1a PR2 (M1.7, `parity/m1-core-send-outcome`):** `send_staged` returns `Sent | Failed { error, restaged }`; FFI as well, so iOS drops its Swift copy. Desktop Task 5a consumes it and waits for it. |
| **C3 thumbnail** in the composer | iOS decodes the staged file from its path (`ComposerView.swift:706-730`). Desktop never holds a path, by design (`ipc.ts:700-745`), and `StagedAttachments` has no accessor for preview bytes (`attachments.rs`). | **Core (C):** `StagedAttachments::thumbnail(token) -> Option<data URI>`, then a desktop command in M3. |
| **Account identity** (name, user id, homeserver) and "Sign out of {name}?" | Needs the `account` command. | **M3** (already listed). Task 14 leaves `accountName: null` and the slot ready. |
| **"Read by {names}"** accessible label on reader faces | iOS words it with the core's `people_label` (`TimelineChrome.swift:172`). Desktop has no command for it (`display_name.rs:260`). | **M3:** a command over `people_label`. Until then the label reads "Seen by N". |
| **Agents first, and "{harness} on {host}"** in the `@` menu | iOS uses `PersonDto` from `people`/`known_people` (`ComposerView.swift:425-431, :533-539`). Desktop has only `room_info` members, which carry `isAgent` but no runtime or ordering. | **M3** (`known_people` is listed; add `people_matching`). |

## Decisions (formerly open questions)

0. The attachment re-stage (Task 5a). Decided: ruling 11 / R10 — the core does it in M1a PR2
   (M1.7); Task 5a waits for that PR and consumes the outcome; no webview-supplied path.
1. The text-size scale and its words. Decided: O3 — Default / Large / Larger / Largest =
   100 / 115 / 130 / 150 % webview zoom, in Account → Appearance, with
   `core:webview:allow-set-webview-zoom`.
2. Sign-out title before M3. Decided: R2 — "Sign out?" until M3 brings the `account` command.
3. Mark read. Decided: R3 — needs a new core function (Core track, M3; also fixes iOS's swipe);
   removed from M2 desktop.
4. Message actions behind "⋯ More". Decided: R4 — accepted (top-edge action pill).
5. The version line. Decided: R5 — keep it in Account (desktop-only, harmless).
6. `roomChanged` copy for a failed delete. Decided: R6 — stays iOS-verbatim for now.
7. Connection banner vs header word. Decided: O4 — one line under the roster title; Task 2.

## Deferred minors from M0 (triage)

| Minor | Decision |
|---|---|
| D1: misplaced `svelte-ignore` in `RecoveryPanel.svelte` | Task 14 |
| D1: `enableRecovery` / `enable_recovery` have no caller | Task 14 (removed; 46 → 45 commands) |
| D1: `+page.svelte` `freshSignIn` wiring has no test | Stays deferred. It is page wiring with no harness; the user's live check covers it. |
| D1: a late `ensureRecovery` key is dropped | Done in the M0 final wave (I2) |
| D2: disabled decision buttons get a hover fill | Task 7 |
| D2: no component-level test of the sending/failed card | Task 7 (the `decisionView` tests plus three played stories) |
| D3: `confirmDelete` silently closes on a null event id | Stays deferred (unreachable through `menuActions`) |
| D4: untested `loginMessage` branches | Task 1 |
| Final: I2 effect wiring proven only by the helper | Stays parked (the user's live check) |
| Final: `roomChanged` copy for a delete | Decided: R6 (stays iOS-verbatim) |
| **M0 ruling (B6/D12): "a failed attachment being dropped is parity"** | **Wrong** (controller correction, 2026-10-22). iOS re-stages and keeps the chip (`StagedAttachment.swift:143-178`). Task 5a, after M1a PR2. The research README's D12 row ("✅ (corrected during M0)") and B6 note get their dated superseding note in M1a PR1 Task 1 Step 0 (R10); the matrix values are not edited. |
