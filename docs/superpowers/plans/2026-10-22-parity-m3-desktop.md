# Parity M3 — Desktop Implementation Plan: reaching the core's existing capabilities

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each task is a bundle: one implementer dispatch, targeted
> tests while iterating, the full suites once before the commit, one review.

**Goal:** turn every desktop safety, recovery and room-settings row of the parity matrix ✅ by
reaching core functions that already exist: block/unblock and the blocked list, report
(message, room, user) with iOS's reason sheet, mute/pin/notification mode in room info and on a
row menu, the room-addressed permission answer, the known-people new conversation with DM reuse,
the inviter on invitations, and the signed-in identity in Account.

**Architecture:**
- **No core change and no binding change.** Every function called here is already `pub` in
  `crates/supermessage-core`. The desktop adds Tauri commands over them in a **new file**,
  `src-tauri/src/commands_session.rs` (commands that take the `Session`, not the focused
  timeline), registered in `src-tauri/src/lib.rs`. A separate file keeps this track out of
  `commands.rs`, which every Core-track PR edits (ruling 9: PRs merge one at a time).
- Logic lives in small `.ts` modules with Vitest tests; components stay thin and get stories that
  the snapshot gate renders.
- One new event listener: `sm://ignored-users` (`core::safety::IGNORED_USERS_EVENT`), which
  `src-tauri/src/host.rs:51` already emits.
- What a reader reported is hidden **on this device**, exactly as iOS's `SafetyStore` does
  (`apple/SupermessageKit/Stores/SafetyStore.swift:50-55`): there is no Matrix protocol for
  hiding one event for one account.

**Tech stack:** Svelte 5 (runes), SvelteKit SPA, Tailwind v4 over the generated
`src/lib/tokens.css`, Vitest 4, Storybook 10 with the snapshot gate, Tauri 2.11.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` (rows "Room info: … mute/pin, Leave
with confirm, block/report", R2, "Invitations as a row, with inviter", "New room / DM with known
people first", "Permission requests", "Block / unblock, blocked list", "Report message / room /
user", "Recovery", "Account panel") and `notes/desktop.md`. Umbrella:
`docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md`, "M3". Format and conventions follow
`.superpowers/sdd/planning-m1-m2/2026-10-22-parity-m2-desktop.md`. iOS paths below are relative to
`apple/`. Core paths are `C=crates/supermessage-core/src`.

**Branch and worktree:** `parity/m3-desktop`, in `.worktrees/parity-desktop`, cut from the tip of
`parity/m2-desktop` after its Task 15 (M2 may not have merged yet; rebase on `main` when it does).

**Preconditions (controller, before Task 1):**
- The branch contains `origin/main` at or after `2f7ecbe` (core PRs #128-#131: `PersonDto` and
  `RoomCast` in `ipc.ts`, the `next_notification_mode` command). M2 desktop's ledger already
  plans this merge before its final PR; if it has not happened, merge `origin/main` and run
  `pnpm test` and `pnpm check` before dispatching Task 1.
- M2 desktop Tasks 13-15 are complete: `AccountPanel.svelte`, `SignOutConfirm.svelte`,
  `accountView.ts` (`signOutTitle`, `ACCOUNT_LINKS`, `versionLine`), `stores/sessionReset.ts`
  (`resetComposeState`) and `appVersion()` exist, and `enable_recovery` is gone.

**Line numbers.** The roadmap cites `session.rs:1799-1836` for the safety block. That is correct on
`main` (`1ff3b95`) and on `parity/m2-desktop`. On `origin/main` (`2f7ecbe`) the same block is
`C/session.rs:1835-1888`: `ignore_user` `:1847`, `unignore_user` `:1853`, `blocked_users` `:1860`,
`report_event` `:1867`, `report_room` `:1878`, `report_user` `:1884`. After the precondition merge,
the `origin/main` numbers apply. Other core functions this plan wraps, on `origin/main`:
`send_permission_answer` `C/session.rs:435`, `set_room_notification_mode` `:1228`,
`set_room_pinned` `:1503`, `known_people` `:1561`, `direct_room_with` `:1609`, `account` `:1639`,
`room_inviter` `:1660`; `people::matching` `C/people.rs:108`; `display_name::people_label`
`C/display_name.rs:260`; `safety::report_reason_remaining` `C/safety.rs:104`.

## Global Constraints

**From the roadmap and AGENTS.md (binding):**
- Tokens only: no hex in views. A new type role goes through `design/tokens.toml` and then
  `python3 scripts/generate-tokens.py`. Never edit `src/lib/tokens.css` by hand.
- Amber (`signal`) means a pending decision and nothing else. Nothing in this plan is amber:
  blocked, reported, muted and failure states use `danger`, `content-muted` or `ok`.
- The app parses nothing and decides nothing that the core already decides. No client-private
  Matrix event types. A Matrix id is never split in TypeScript (use `people_label`).
- Copy is iOS's, verbatim, with its `file:line`. A string iOS lacks is marked *(no iOS copy)*.
- The platform track never edits `crates/` or the generated bindings. A needed core change is
  filed to the Core track, never worked around in UI code.
- Every new UI state ships with a story. Its baseline is recorded once, after inspection. Never
  re-record a frame to make the gate pass.
- **Falsification (AGENTS.md):** for every new test, mutate the implementation, watch the test
  fail, restore it, and record what you saw in the task report. Each task names its mutations.
- Story failure fixtures reject with a **`CoreError` shape** (`{ kind, message }`), never
  `new Error(...)`.
- Story interactions must actually run. Use the plain-DOM `settled` helper pattern of
  `src/lib/components/LeaveRoomConfirm.stories.svelte`.
- Modal confirms and sheets are native `<dialog>`s opened with `showModal()`, in the
  `LeaveRoomConfirm.svelte` shape: focus opens on Cancel (never on a destructive button), Escape
  cancels through the `cancel` event, and focus returns to the opener. `escapeClosesPanel`
  (`roomInfoView.ts`) already skips the room-info panel while any `dialog:modal` is open, so no
  extra Escape plumbing is needed.

**This machine (owner rules, 2026-10-22):**
- Every heavy command takes the shared lock:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock <command>`. Heavy: any `cargo build|test|clippy`,
  `./scripts/snapshot-stories.sh`. Light (no lock): `pnpm test`, `pnpm check`, `cargo fmt`, git,
  grep. Also `export CARGO_BUILD_JOBS=8` and
  `export CARGO_TARGET_DIR=/home/rakeshgangwar/Projects/superjackfruit/supermessage/target`.
- While iterating, run the focused Vitest file. Run the full suites **once per task**, before the
  commit.
- `/tmp` is RAM: put nothing large there.

**Snapshot gate:**
- The 7 frames listed in `.superpowers/sdd/m2-desktop/env-desktop.md` already differ on this
  machine (renderer drift). Never re-record them. The gate passes when no other frame differs.
- Never `--record` (it rewrites every frame). Run the gate, inspect
  `.snapshots-web/<story-id>.png`, copy only that file into `src/lib/stories/baseline/`, run the
  gate again.

**Live account: READ-ONLY.** You may open the app, open rooms, menus, panels, dialogs and sheets,
type into a field without submitting, take screenshots and read the DOM. You may **not** block,
unblock, report, mute, unmute, pin, unpin, change a notification mode, leave, decline, accept,
answer, send, create a room or open a person from the new-conversation list (that can create a
room). List every skipped write under "Live verification pending" in the task report.
- **Registration probe (allowed, read-only by construction):** a write command can be proven
  registered without side effects by invoking it with an argument the core refuses while parsing,
  before any request leaves the process. Task 1 lists the exact calls; every one must reject with
  `kind: "protocol"`. If any of them resolves, **stop and report BLOCKED**.

**Desktop verification for every task:**
- `pnpm test`, `pnpm check`, and the gate:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./scripts/snapshot-stories.sh`.
- For a task that touches `src-tauri/` (Task 1 only), also, each under the lock:
  `cargo test --workspace`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`;
  and `cargo fmt --all --check` (light).
- Running the app: `pnpm dev` (port 1420) in the background, then
  `$CARGO_TARGET_DIR/debug/supermessage` built from this worktree (`cargo build -p supermessage`
  under the lock). The embedded WebDriver listens on `127.0.0.1:4445`. Synthetic hover does not
  register in WebKitGTK; reveal hover-only UI with focus. Never verify with a bare `cargo run`.
- Window widths for RF3: 1905px and 600px; text size Largest (150%, M2 Task 13's zoom).

**Serialize:** tasks marked **[page]** edit `src/routes/+page.svelte`, **[timeline]** edit
`src/lib/components/Timeline.svelte`, **[roominfo]** edit `src/lib/components/RoomInfoPanel.svelte`.
The tasks run one after another in plan order; none run in parallel on this track.

**Commits** end with the attribution lines in the session's system reminder.

## Review Focus

1. **A report that does not land** (offline, 5xx, a homeserver that refuses person reports). The
   sheet stays open with the reason still in the field and the refusal under it. Nothing is
   hidden and nobody is blocked on the strength of a report that did not go (iOS
   `ReportSheet.swift:12-18`, `Session.swift:352-380`). Pinned in Task 1 by
   `aFailedReportHidesNothingAndBlocksNoOne` and in Task 2 by the "Report a person, refused" story.
2. **Sign out and back in as another account** (roadmap RF5). The first account's blocked list,
   hidden messages, identity and name must not show. Pinned in Task 1 by `persistsUntilSignOut`
   and in Task 5 by `resetSessionState forgets the account and its safety state`.
3. **A permission answer whose send fails** (roadmap RF4). Today the desktop sends it through
   the focused timeline's send queue, which resolves when the message is *queued*, so the card
   can show "Allowed once by you" for an answer that never lands. `send_permission_answer` awaits
   the homeserver (`C/session.rs:428-441`; iOS `Session.swift:625-650` records the same
   reasoning). Pinned in Task 1 by `a permission answer goes through send_permission_answer` and
   `a refused permission answer reports false`.
4. **Two devices, or an echo that lags.** A block from another device, or the homeserver's echo
   of this one, must win over anything optimistic, and a slow seed must not overwrite a newer
   event. Pinned in Task 1 by `theCoresListWins` and `aSeedNeverOverwritesALaterEvent`.
5. **A narrow window and the largest text** (roadmap RF3): the report sheet, the member actions,
   the row menu and the new-conversation list at 600px and at 150% must not clip or overflow.
   Checked in the running app in Tasks 2-5 (read-only: open and cancel), and pinned for the row
   menu by `menuPosition keeps the menu inside the window`.
6. **A room toggle when the core cannot answer.** Nothing flips and no label lies (iOS
   `RoomListView.swift:280-301`). Pinned in Task 3 by `toggleMute does nothing when roomInfo fails`.

---

### Task 1: The session commands, the safety store, and the room-addressed permission answer **[page]**

Every later task consumes what this one adds. It also moves the desktop permission answer onto
the core's room-addressed sender, which is one function and one test.

**iOS behaviour this ports:**
- `SupermessageKit/Stores/SafetyStore.swift:56-115`:
  - `blocked` is replaced wholesale by the core's list (`apply`, `:78-80`);
  - `noteBlocked`/`noteUnblocked` are the only local writes, made after a call succeeds (`:84-88`);
  - `hide(eventId:)` persists the hidden set under the key `safety.hiddenEvents` (`:69`, `:91-94`);
  - `visible(_:)` drops rows whose **event** id was hidden; a local echo (no event id) always
    passes (`:96-106`);
  - `clear()` forgets everything, including the stored key (`:108-114`), and runs at sign-out
    (`Session.swift:764`).
- `SupermessageKit/Stores/SafetyStore.swift:11-37` — `ReportSubject`: `message(roomId, eventId,
  senderId, senderName, isAgent)`, `room(roomId, name)`, `user(userId, name, isAgent)`; `blockable`
  is the sender for a message, nobody for a room, the person for a user.
- `SupermessageKit/Session.swift:332-392`:
  - `block`/`unblock` return a refusal message or `nil`, and note the change only when it landed;
  - `blockedUsers` returns `[]` when the list cannot be read (`:346-350`);
  - `report` sends the report **first and alone decides success**; only then is a message hidden;
    then, if asked and the person is not already blocked, it blocks. A failed block after a
    landed report returns `"Reported, but blocking didn't work: \(blockRefused)"` (`:376`);
  - a non-core error reads `"That didn't work."` (`:389`).
- `SupermessageKit/Session.swift:625-650` — a permission answer goes through
  `sendPermissionAnswer(roomId:optionId:)`, which awaits the homeserver.
- `SupermessageKit/ErrorPresenter.swift:49-56` — `ReasonTooLong` and `Refused` wording.

**Files:**
- Create: `src-tauri/src/commands_session.rs`.
- Modify: `src-tauri/src/lib.rs` (`mod commands_session;`, the `use`, and the
  `generate_handler!` list).
- Modify: `src/lib/ipc.ts`, `src/lib/ipc.test.ts`.
- Create: `src/lib/stores/safety.svelte.ts`, `src/lib/stores/safety.test.ts`.
- Create: `src/lib/safety.ts`, `src/lib/safety.test.ts`.
- Create: `src/lib/stores/account.svelte.ts`, `src/lib/stores/account.test.ts`.
- Modify: `src/lib/components/decisionReply.ts`, `decisionReply.test.ts`.
- Modify: `src/lib/errorMessage.ts`, `src/lib/errorMessage.test.ts`.
- Modify: `src/routes/+page.svelte` — the session `onMount` (the one that calls
  `roomsStore.restoreSession()`, about `:579`): install the listener and seed the stores.
- Modify: `AGENTS.md` ("Current status" paragraph: "ordinary AgentPod permission replies remain
  plain text on desktop") and `README.md:38` (the desktop cell "permission answers sent as plain
  messages").
- Create: `docs/superpowers/plans/2026-10-22-parity-m3-desktop.md`, a copy of this plan (R10:
  plans travel with the work).

**Interfaces:**
- Produces (Rust, `commands_session.rs`; each a thin wrapper, `State<'_, Session>`, returning
  `Result<_, CoreError>` unless noted):
  - `ignore_user(user_id: String) -> ()`; `unignore_user(user_id: String) -> ()`;
  - `blocked_users() -> Vec<RoomMemberDto>` (over `Session::blocked_users`);
  - `report_event(room_id, event_id, reason: String) -> ()`; `report_room(room_id, reason) -> ()`;
    `report_user(user_id, reason) -> ()`;
  - `report_reason_remaining(reason: String) -> i64` — sync, no state, never fails;
  - `set_room_notification_mode(room_id: String, mode: NotificationMode) -> ()`;
  - `set_room_pinned(room_id: String, pinned: bool) -> ()`;
  - `known_people(query: Option<String>) -> Vec<PersonDto>`. It is `Session::known_people`, then,
    when `query` is `Some`, `supermessage_core::people::matching(&all, &q)`. **Why one command
    does both:** `PersonDto` derives `Serialize` but not `Deserialize` (`C/people.rs:18-19`), so a
    `people_matching(people, query)` command cannot take the list back from the webview, and a
    TypeScript filter would be a second copy of `people::matching`. Composing two core calls is
    still a thin wrapper; no rule is added here. (Open question Q12.)
  - `direct_room_with(user_id: String) -> Option<String>`;
  - `room_inviter(room_id: String) -> Option<String>`;
  - `account() -> AccountDto`;
  - `people_label(user_ids: Vec<String>) -> String` — sync, no state;
  - `send_permission_answer(room_id: String, option_id: String) -> ()`.
- Produces (TypeScript, `ipc.ts`), each with a doc comment naming its core function:
  - `interface AccountDto { userId: string; homeserver: string }`;
  - `ignoreUser(userId)`, `unignoreUser(userId)`: `Promise<void>`;
  - `blockedUsers(): Promise<RoomMember[]>`;
  - `reportEvent(roomId, eventId, reason)`, `reportRoom(roomId, reason)`,
    `reportUser(userId, reason)`: `Promise<void>`;
  - `reportReasonRemaining(reason: string): Promise<number>`;
  - `setRoomNotificationMode(roomId, mode: NotificationMode)`, `setRoomPinned(roomId, pinned)`:
    `Promise<void>`;
  - `knownPeople(query: string | null = null): Promise<PersonDto[]>`;
  - `directRoomWith(userId): Promise<string | null>`; `roomInviter(roomId): Promise<string | null>`;
  - `account(): Promise<AccountDto>`; `peopleLabel(userIds: string[]): Promise<string>`;
  - `sendPermissionAnswer(roomId, optionId): Promise<void>`;
  - `onIgnoredUsers(handler: (userIds: string[]) => void): Promise<UnlistenFn>`, listening on
    `"sm://ignored-users"`.
- Produces (`src/lib/stores/safety.svelte.ts`):
  - `createSafetyStore(storage: Pick<Storage, "getItem" | "setItem" | "removeItem"> | null)`,
    and the app's instance `safetyStore = createSafetyStore(<localStorage, or null when it throws>)`.
  - `get blocked(): ReadonlySet<string>`; `get hidden(): ReadonlySet<string>`. Both are `$state`
    holding a **new** `Set` on every change (never mutated in place), so `$derived` readers update.
  - `apply(userIds: string[]): void` — replaces `blocked`, and bumps an internal generation.
  - `isBlocked(userId): boolean`; `noteBlocked(userId)`; `noteUnblocked(userId)`.
  - `hide(eventId: string): void` — adds it and writes `JSON.stringify([...hidden])` to
    `safety.hiddenEvents` inside `try/catch`.
  - `visible<T extends { eventId: string | null }>(items: readonly T[]): T[]`.
  - `seed(load: () => Promise<string[]>): Promise<void>` — applies the loaded ids **only if** no
    `apply` happened while it was loading (generation check). Failures are swallowed (iOS's
    `try?`); the next event fills the list.
  - `clear(): void` — empties both sets and removes `safety.hiddenEvents`.
  - On construction it reads `safety.hiddenEvents`; anything that is not a JSON array of strings
    reads as empty.
- Produces (`src/lib/safety.ts`, pure over injected deps, so it is testable without Tauri):
  - `type ReportSubject = { kind: "message"; roomId: string; eventId: string; senderId: string; senderName: string; isAgent: boolean } | { kind: "room"; roomId: string; name: string } | { kind: "user"; userId: string; name: string; isAgent: boolean }`;
  - `blockable(subject): { userId: string; name: string; isAgent: boolean } | null`;
  - `interface SafetyDeps { ignoreUser; unignoreUser; reportEvent; reportRoom; reportUser; store: Pick<SafetyStore, "isBlocked" | "noteBlocked" | "noteUnblocked" | "hide"> }`, defaulting to
    the real wrappers and `safetyStore`;
  - `block(userId, deps?): Promise<string | null>`; `unblock(userId, deps?): Promise<string | null>`
    — the refusal text from `errorMessage(err)`, or `null`; note the change only on success;
  - `report(subject, reason, alsoBlock, deps?): Promise<string | null>` — the iOS order above;
  - `reportSubjectFor(row: TimelineRow, roomId: string, isAgent: (senderId: string) => Promise<boolean>): Promise<ReportSubject | null>`
    — `null` when the row has no `eventId` or no `sender`; the name is `row.senderShort` (iOS
    `TimelineCollectionView.swift:1000-1007`).
  - `memberIsAgent(roomId: string, senderId: string, load = roomInfo): Promise<boolean>` — the
    member's `isAgent` from `room_info` (the core's `agent_name_from_id` rule,
    `C/room_info.rs:149`); `false` when the room cannot be read or the sender is not a member.
    **Step 0 below may replace this.**
- Produces (`src/lib/stores/account.svelte.ts`): `accountStore` with `get account(): AccountDto | null`,
  `get name(): string | null`, `load(): Promise<void>` (calls `account()`, then
  `peopleLabel([userId])` for the name; failures leave both `null`), and `clear()`.
- Changes: `sendDecisionReply` sends a permission answer with
  `sendPermissionAnswer(roomId, optionId)` instead of `sendMessage(roomId, optionId)`.
- Changes: `errorMessage` gains `case "reasonTooLong"`: the core's message with its first letter
  upper-cased. iOS's sentence needs the two numbers, which arrive only inside the message string;
  the desktop does not parse it. The sheet disables Send before the limit (Task 2), so this is a
  backstop (open question Q15).

- [ ] **Step 0: Check what the base already carries.**
  - `grep -n "isAgent" src/lib/ipc.ts` inside `interface TimelineRow`. If the Core track's M1.4
    row facts (M1b Task C6, `TimelineRow.is_agent`) have merged, `reportSubjectFor` reads
    `row.isAgent` and `memberIsAgent` is not created. Otherwise create `memberIsAgent` as above.
    Record which in the report.
  - Confirm the line numbers above against `crates/supermessage-core/src/session.rs` on the base.
- [ ] **Step 1: Write the failing tests.**
  - `ipc.test.ts`, one `it` per wrapper, each asserting the exact command name and camelCase
    arguments (the existing pattern): `ignoreUser invokes ignore_user with userId`, …,
    `knownPeople passes an explicit null query`, `knownPeople passes the query`,
    `peopleLabel passes userIds`, `sendPermissionAnswer passes roomId and optionId`, and
    `onIgnoredUsers listens on sm://ignored-users`.
  - `safety.test.ts` (store), ported from iOS `SupermessageKitTests/SafetyStoreTests.swift`:
    - `hidesWhatWasReported` (`:24`): hide `$b`; `visible([a, b, c])` is `[a, c]`.
    - `aLocalEchoPasses` (`:32`): an item with `eventId: null` survives any hidden set.
    - `theCoresListWins` (`:41`): `noteBlocked("@x")`, then `apply(["@y"])`: `isBlocked("@x")` is
      false and `isBlocked("@y")` true.
    - `persistsUntilSignOut` (`:51`): with a fake `Storage`, hide `$b`; a new store over the same
      storage still hides `$b`; `clear()`; a third store hides nothing and the key is removed.
    - `whoCanBeBlocked` (`:70`): `blockable(message)` is the sender, `blockable(room)` is `null`,
      `blockable(user)` is the user.
    - `aSeedNeverOverwritesALaterEvent`: start `seed` with a load that resolves later to
      `["@old"]`; meanwhile `apply(["@new"])`; after the seed resolves, `blocked` is `{"@new"}`.
    - `aStoreWhoseStorageThrowsStillHides`: a `Storage` whose methods throw; `hide` works in memory.
    - `corruptStorageReadsAsEmpty`: `safety.hiddenEvents` holds `"{"`; nothing is hidden.
  - `safety.test.ts` (orchestration), with recording fakes:
    - `reportHidesOnlyAfterTheHomeserverHasIt`: `reportEvent` is a deferred promise; before it
      resolves, `store.hide` has not been called; after, it has been called with the event id.
    - `aFailedReportHidesNothingAndBlocksNoOne` (RF1): `reportEvent` rejects
      `{kind: "network", message: ""}`; the result is `"Can't reach the homeserver."`, `hide` and
      `ignoreUser` were never called.
    - `aBlockThatFailsAfterAReportSaysWhichHalf`: report resolves, `ignoreUser` rejects
      `{kind: "refused", message: "You can't block yourself."}` → exactly
      `"Reported, but blocking didn't work: You can't block yourself."`, and `hide` was called.
    - `anAlreadyBlockedSenderIsNotBlockedAgain`: `isBlocked` true, `alsoBlock` true → `ignoreUser`
      never called, result `null`.
    - `aRoomReportNeverBlocks`: `alsoBlock: true` on a room subject → `ignoreUser` never called.
    - `blockNotesOnlyWhatLanded`: a rejected `ignoreUser` leaves `noteBlocked` uncalled and returns
      the presenter's text; a resolved one calls it once.
    - `reportSubjectFor needs an event and a sender`: no `eventId` → `null`; no `sender` → `null`;
      otherwise the subject carries `senderShort` and the `isAgent` the lookup returned.
    - If `memberIsAgent` exists: `memberIsAgent reads the core's flag` (member present with
      `isAgent: true` → true; absent → false; `roomInfo` rejects → false).
  - `account.test.ts`: `load names the account through people_label` (the fake `peopleLabel`
    receives `["@rakesh:id.agentpod.dev"]` and its answer is `name`); `a failed load leaves
    nothing`; `clear forgets both`.
  - `decisionReply.test.ts`:
    - `a permission answer goes through send_permission_answer`: a decision with
      `subject: null` → `sendPermissionAnswer(roomId, "Allow once")` called once, and
      `sendMessage` never called.
    - `a refused permission answer reports false`: `sendPermissionAnswer` rejects →
      `answerDecision` resolves `false` (RF4).
  - `errorMessage.test.ts`: `reasonTooLong shows the core's words, capitalised`:
    `{kind: "reasonTooLong", message: "that reason is 2105 characters; reports allow at most 2000"}`
    → `"That reason is 2105 characters; reports allow at most 2000"`.
- [ ] **Step 2: Run them and confirm they fail.**
  `pnpm vitest run src/lib/ipc.test.ts src/lib/stores/safety.test.ts src/lib/safety.test.ts src/lib/stores/account.test.ts src/lib/components/decisionReply.test.ts src/lib/errorMessage.test.ts`.
- [ ] **Step 3: Implement.**
  - The Rust file opens with a module doc in the style of `commands.rs:1-5`: thin wrappers over
    `Session`; no logic. Each command has a one-line doc naming the core function. `lib.rs`
    registers all 15. No change to `commands.rs`.
  - `+page.svelte`, in the session `onMount`: install `onIgnoredUsers((ids) =>
    safetyStore.apply(ids))` once (unlisten in `onDestroy`). After `restoreSession()` resolves
    `true`, call `void safetyStore.seed(async () => (await blockedUsers()).map((m) => m.userId))`
    and `void accountStore.load()`. The seed exists because the core emits the list "once when
    the session starts" (`C/event.rs:62-65`), which can be before the webview's listener exists.
  - `decisionReply.ts`: replace the `sendMessage` branch and rewrite its comment: the content is
    still the option's name as a plain message (`C/timeline.rs:1011-1022`,
    `permission_answer_content`), but it is room-addressed and awaited, so a failure keeps the
    card answerable.
  - `AGENTS.md` and `README.md:38`: say the desktop answers permission requests through the
    core's room-addressed sender (`Session::send_permission_answer`), as iOS does. Keep the
    statement that the content is ordinary chat text (`README.md:78`).
- [ ] **Step 4: Run the checks.**
  - The full desktop set, including the cargo trio under the lock. No frame changes.
  - **Registration probe in the running app** (read-only by construction; see Global
    Constraints). Over WebDriver `execute/async`, call
    `window.__TAURI_INTERNALS__.invoke(name, args)` and record each result:
    - read-only, expected to resolve: `account` `{}`; `people_label` `{userIds: ["@x:y"]}`;
      `report_reason_remaining` `{reason: "  hi  "}` (expect `1998`); `blocked_users` `{}`;
      `known_people` `{query: null}`; `known_people` `{query: "zzzz-no-match"}` (expect `[]`);
      `direct_room_with` `{userId: "@nobody:invalid.example"}` (expect `null`);
      `room_inviter` `{roomId: "!nope:invalid.example"}` (expect `null`).
    - writes, expected to reject with `kind: "protocol"` at parsing:
      `ignore_user` and `unignore_user` `{userId: "not-a-user-id"}`;
      `report_user` `{userId: "not-a-user-id", reason: ""}`;
      `report_room` `{roomId: "not-a-room-id", reason: ""}`;
      `report_event` `{roomId: "not-a-room-id", eventId: "$e", reason: ""}`;
      `set_room_notification_mode` `{roomId: "not-a-room-id", mode: "muted"}`;
      `set_room_pinned` `{roomId: "not-a-room-id", pinned: true}`;
      `send_permission_answer` `{roomId: "not-a-room-id", optionId: "x"}`.
    - Any write that resolves: **stop, report BLOCKED**, do nothing else.
  - **Falsify:**
    - rename `"report_event"` in the wrapper → its ipc test fails;
    - in `report`, call `hide` before awaiting the report → `reportHidesOnlyAfterTheHomeserverHasIt`
      fails;
    - drop the generation check in `seed` → `aSeedNeverOverwritesALaterEvent` fails;
    - restore `sendMessage` for permissions → `a permission answer goes through send_permission_answer`
      fails;
    - make `visible` filter on `id` instead of `eventId` → `aLocalEchoPasses` fails.
  - Live verification pending: a real permission answer (the user).
- [ ] **Step 5: Commit.** Message: `desktop: session commands, block/report plumbing, room-addressed permission answers`.

### Task 2: Report a message, a room or an invitation, with iOS's reason sheet; the inviter **[timeline]**

**iOS behaviour and copy** (`Supermessage/Panels/ReportSheet.swift`):
- `:205-215` — the explanation:
  - message: `"Your report goes to your homeserver's administrators, not to \(senderName). The message is hidden from you once the report is sent."`;
  - room: `"Your report about \(name) goes to your homeserver's administrators, not to the people in it."`;
  - user: `"Your report goes to your homeserver's administrators, not to \(name)."`, plus
    `" Reporting an agent doesn't stop it running."` for an agent.
- `:184-190` — titles: `"Report message"`, `"Report room"`, `"Report"`.
- `:74-94` — the reason field: header `"Reason"`, placeholder `"What's wrong? (optional)"`,
  accessibility label `"Reason for the report"`, hint
  `"Optional. Seen only by the homeserver's administrators."`, 4 to 10 lines. The counter shows
  only when `remaining < 200`: `"\(remaining) characters left"`, or
  `"\(-remaining) characters over the limit"` in danger. `remaining` is the core's
  `reportReasonRemaining` (`:60-61`).
- `:96-107` — when there is someone to block: `"Also block \(target.name)"` as a toggle, **on by
  default** (`:39-45`), or `"\(target.name) is blocked"` (muted) when they already are; footer
  `blockConsequence(isAgent:)` (`:178-182`):
  - agent: `"Its messages are hidden everywhere, on all your devices. The agent keeps running and isn't told."`;
  - person: `"You won't see their messages in any room, on any of your devices. They aren't told."`.
- `:109-115` — a refusal is shown under the form in danger.
- `:122-138` — `"Cancel"` and `"Send"`; Send is disabled while too long, hint
  `"Asks before sending"`; while sending, a progress indicator replaces Send and the sheet cannot
  be dismissed (`:149`).
- `:139-148` — the confirmation: title `"Report this message?"` / `"Report \(name)?"`
  (`:217-223`); message `"The homeserver's administrators will see it. \(subjectName) won't be told."`;
  actions `sendLabel` (`"Report and block"` when blocking, otherwise `"Report"`, `:225-227`,
  destructive) and `"Cancel"`.
- `:158-171` — on success the sheet closes; on a refusal it stays, with the reason kept.
- `Timeline/TimelineCollectionView.swift:1128-1145` — `"Report…"` is offered on **someone else's**
  message the server has acknowledged (`!isOwn && canReplyOrReact`, with a sender), last, in danger,
  in its own group.
- `Panels/InvitationView.swift:50-59` — `"You have been invited to \(roomName)."` and, when known,
  `"from \(inviter)"` in meta, muted. `:62-64` — a failure in danger. `:77-87` — a quiet
  `"Report room…"` in danger beneath Accept/Decline, hint
  `"Reports this invitation to your homeserver's administrators"`.

**Files:**
- Create: `src/lib/components/safety/reportView.ts`, `reportView.test.ts`.
- Create: `src/lib/components/safety/ReportSheet.svelte`, `ReportSheet.stories.svelte`.
- Modify: `src/lib/components/timeline/messageMenu.ts`, `messageMenu.test.ts`.
- Modify: `src/lib/components/timeline/MessageActions.svelte` (the More menu), and
  `MessageActions.stories.svelte`.
- Modify: `src/lib/components/Timeline.svelte`:
  - the `view` derivation, where `groupTimelineItems(timelineStore.items)` is called (about `:461`):
    it becomes `groupTimelineItems(safetyStore.visible(timelineStore.items))`;
  - both `MessageActions` call sites (about `:1275` and `:1804`) gain `onReport`;
  - host the sheet beside the delete confirm (`deleting` state).
- Create: `src/lib/components/timelineHidesReported.test.ts`.
- Modify: `src/lib/components/InvitationPanel.svelte`, `InvitationPanel.stories.svelte`,
  `src/lib/components/invitationView.ts`, `invitationView.test.ts`.
- Modify: `src/routes/+page.svelte` where `InvitationPanel` mounts (about `:1059`): pass the inviter
  loader and the report hook. **[page]** too.

**Interfaces:**
- Consumes: Task 1's `ReportSubject`, `blockable`, `report`, `reportSubjectFor`,
  `memberIsAgent` (or `row.isAgent`), `safetyStore`, `reportReasonRemaining`, `roomInviter`.
- Produces (`reportView.ts`, every string iOS-verbatim as quoted above):
  - `reportTitle(subject)`, `reportExplanation(subject)`, `confirmationTitle(subject)`,
    `confirmationMessage(subject)`, `subjectName(subject)`,
    `sendLabel(subject, alsoBlock: boolean)`, `blockConsequence(isAgent: boolean)`;
  - `counterLine(remaining: number): { text: string; over: boolean } | null` — `null` at
    `remaining >= 200`;
  - `canSend(remaining: number): boolean` — `remaining >= 0`.
- Produces: `ReportSheet` props `{ subject: ReportSubject; isBlocked: (userId: string) => boolean; remaining?: (reason: string) => Promise<number>; onSend: (reason: string, alsoBlock: boolean) => Promise<string | null>; onClose: () => void }`.
  - `remaining` defaults to `reportReasonRemaining`. Each keystroke asks it; a sequence number
    drops answers older than the newest question.
  - A native modal `<dialog>` with `aria-label={reportTitle(subject)}`, `max-h-[90vh]
    overflow-y-auto`, `w-full max-w-md`.
  - Two stages in the same dialog: the form, and the confirmation. Choosing Send moves to the
    confirmation; its Cancel returns to the form with the reason intact. Focus opens on the
    reason field in the form and on Cancel in the confirmation.
  - The confirmation's action calls `onSend(reason, alsoBlock && blockable(subject) !== null)`.
    `null` closes the sheet (`onClose`). A string returns to the form and shows it in a
    `role="alert"` line in `text-danger`; the reason stays.
  - While sending: buttons disabled, Escape and backdrop clicks do nothing.
- Produces: `menuActions(row)` gains `report: boolean = !isOwn && canReplyOrReact && eventId !== null && sender !== null`;
  `more` becomes `copy || edit || del || report`.
- Produces: `MessageActions` prop `onReport: (row: ItemRow) => void`. "Report…" is the last item
  of the More menu, after a `role="separator"`, in `text-danger`.
- Produces: `inviterLine(inviter: string | null): string | null` — `` `from ${inviter}` `` or `null`.
- Produces: `InvitationPanel` props gain `loadInviter: () => Promise<string | null>` and
  `onReport: () => void`; its failure line moves to `text-danger` (iOS `:63`; the M2 Task 1
  deferred minor "invitation panels' failure line not in the danger role").

- [ ] **Step 1: Write the failing tests.**
  - `reportView.test.ts`: every string above, exactly, for each subject kind, including the agent
    sentence on a user subject and its absence on a person; `sendLabel(message, true)` is
    `"Report and block"`, `sendLabel(room, true)` is `"Report"` (a room has nobody to block);
    `counterLine(200)` is `null`, `counterLine(199)` is `{text: "199 characters left", over:
    false}`, `counterLine(-5)` is `{text: "5 characters over the limit", over: true}`;
    `canSend(0)` true, `canSend(-1)` false.
  - `messageMenu.test.ts`: `reportOfferedOnSomeoneElsesSentMessage`;
    `reportNotOfferedOnYourOwn` (`isOwn: true`); `reportNotOfferedWhilePending`
    (`canReplyOrReact: false`); `reportNotOfferedWithoutASender`; `reportAloneOpensMore` (no body,
    not editable, not own, sent → `more: true`).
  - `invitationView.test.ts`: `inviterLine("Krishna")` is `"from Krishna"`; `inviterLine(null)` is
    `null`.
  - `timelineHidesReported.test.ts` (static, in the `timelineActionAnchor.test.ts` style): read
    `Timeline.svelte`; assert it contains `groupTimelineItems(safetyStore.visible(` and that every
    `<MessageActions` tag carries `onReport=`.
- [ ] **Step 2: Run them and confirm they fail.**
  `pnpm vitest run src/lib/components/safety src/lib/components/timeline src/lib/components/invitationView.test.ts src/lib/components/timelineHidesReported.test.ts`.
- [ ] **Step 3: Implement.**
  - **Message report:** `Timeline.svelte` holds `reporting = $state<ReportSubject | null>(null)`.
    `onReport(row)` builds the subject with `reportSubjectFor(row, roomId, …)` and sets it.
    `onSend` is `(reason, alsoBlock) => report(subject, reason, alsoBlock)`; `isBlocked` is
    `safetyStore.isBlocked`. On success the hidden row leaves the timeline through
    `safetyStore.visible`; nothing else needs to change. Rewrite the comment above the `view`
    derivation to say rows reported on this device are dropped before grouping (iOS
    `TimelineCollectionView.swift:417`).
  - **Invitation:** `InvitationPanel` loads the inviter once on mount (`loadInviter`, failure →
    no line) and shows `inviterLine` under the prompt in `text-meta text-content-muted`.
    `"Report room…"` is a `text-meta text-danger` button under the two answers, with
    `title`/`aria-description` set to the iOS hint, opening the sheet with
    `{kind: "room", roomId, name: roomName}`. The page passes
    `loadInviter={() => roomInviter(selectedId)}`; the panel hosts its own `ReportSheet`.
  - **Stories** (`ReportSheet.stories.svelte`, a fixed `remaining` fake per story):
    - "Report a message" (an agent sender, reason typed, toggle on — the agent footer shows);
    - "Report a person, refused" (`onSend` resolves
      `"This homeserver doesn't accept person reports. Nothing was sent."`; the play opens the
      confirmation, confirms, and waits for `[role=alert]`; the reason is still in the field);
    - "Report a room" (no toggle);
    - "Already blocked" (`isBlocked` true: the "is blocked" line, no toggle; the confirmation
      action reads "Report");
    - "Over the limit" (`remaining` → `-5`: the danger counter, Send disabled);
    - "Confirm" (the play clicks Send and waits for "Report and block").
    - `MessageActions`: "More menu with Report" (someone else's sent message; the play opens More).
    - `InvitationPanel`: "With inviter" and "Report room opens the sheet" (the play clicks it and
      waits for the dialog).
- [ ] **Step 4: Run the checks.**
  - Full desktop set. Record the new frames after inspection. Expected changed frames: the
    existing `InvitationPanel` "Accept fails" frame (failure now in danger) — inspect and copy.
    No other existing frame may change.
  - Running app (read-only): open the More menu on someone else's message: "Report…" is last and
    red. Choose it; the sheet opens; type a reason; open the confirmation; **Cancel**; Cancel the
    sheet. At 600px and at Largest text, the sheet and its buttons stay inside the window. Open an
    invitation (if one exists): "from …" shows, and "Report room…" opens and cancels.
  - **Falsify:**
    - drop the `!isOwn` clause → `reportNotOfferedOnYourOwn` fails;
    - remove `safetyStore.visible(` from `Timeline.svelte` → `timelineHidesReported.test.ts` fails;
    - make `counterLine` show from 2000 → `counterLine(200)` fails;
    - default the toggle to off → the "Confirm" story times out waiting for "Report and block".
  - Live verification pending: sending a report; reporting and blocking; a report refused by the
    homeserver; a reported message disappearing.
- [ ] **Step 5: Commit.** Message: `desktop: report a message, a room or an invitation, with the inviter shown`.

### Task 3: Room settings and members — notifications, pin, block, report; the row menu **[roominfo]**

**iOS behaviour and copy:**
- `Panels/RoomInfoPanel.swift:121-138` — a "Notifications" section above the members:
  - `"Mute"` as a switch; on means `.muted`, off restores `.default` (`:359-373`);
  - `"Notify me about"`, a picker with `"Everything"` (allMessages), `"Mentions only"`,
    `"Account default"` (default) and `"Nothing"` (muted), in that order;
  - `"Pin to top"` as a switch.
  - Each control writes optimistically, then reloads the room's info (`:363-397`), so a refused
    write snaps back.
- `:176-208` — `"Report room…"` (danger) beside `"Leave room"`, hint
  `"Reports this room to your homeserver's administrators"`.
- `:286-308` — every member except this account opens the member actions; your own row is not a
  control. Hint `"Block or report"` / `"Unblock or report"`.
- `:238-259` — member actions, titled with the member's name (`displayName ?? userId`):
  - `"Unblock \(name)"` when blocked, otherwise `"Block \(name)"` (destructive);
  - `"Report \(name)…"` (destructive), opening the report sheet with a user subject;
  - `"Cancel"`;
  - message: blocked → `"Unblocking shows their messages again, including ones sent while they were blocked."`,
    otherwise `blockConsequence(isAgent:)` (Task 2).
- `:310-323` — a block or unblock that fails is said under the member list in danger. "Blocked"
  is the core's list when it has spoken for this member, otherwise the member's own `isIgnored`,
  less whatever this panel has just unblocked (the flag lags the echo).
- `:400-436` — the member row shows `AgentTag` ("Agent", accent on accent-soft,
  `Composer/FloatingSurface.swift:54-63`) for an agent, and `"Blocked"` in danger when blocked;
  a blocked name is muted.
- `Rooms/RoomListView.swift:244-301` — the row actions, which on desktop are a row menu:
  - an invitation: `"Decline"` (destructive), which leaves;
  - a joined room: `"Mute"`/`"Unmute"` and `"Pin"`/`"Unpin"`;
  - a toggle **asks the core first** (`roomInfo`), then `nextNotificationMode` (the core's
    `C/room_info.rs` `next_notification_mode`, `muted → default`, otherwise `muted`) or `!pinned`;
    when `roomInfo` fails, nothing happens (`:285-301`).
  - "Mark read" is **not** here: see "Excluded".

**Files:**
- Create: `src/lib/components/roomSettings.ts`, `roomSettings.test.ts`.
- Create: `src/lib/components/RoomNotificationsSection.svelte`,
  `RoomNotificationsSection.stories.svelte`.
- Create: `src/lib/components/AgentTag.svelte` — the span `MentionMenu.svelte:56` draws today;
  `MentionMenu` switches to it with an identical result (its frames must not change).
- Modify: `src/lib/components/roster/MemberRow.svelte`, `MemberRow.stories.svelte`.
- Create: `src/lib/components/safety/memberActions.ts`, `memberActions.test.ts`.
- Create: `src/lib/components/safety/MemberActions.svelte`, `MemberActions.stories.svelte`.
- Modify: `src/lib/components/RoomInfoPanel.svelte`: the notifications section after the topic;
  member rows with actions; `"Report room…"` beside Leave; the hosted `ReportSheet`.
- Create: `src/lib/components/roster/rowMenu.ts`, `rowMenu.test.ts`.
- Create: `src/lib/components/roster/RowMenu.svelte`, `RowMenu.stories.svelte`.
- Modify: `src/lib/components/roster/RoomRow.svelte` (an optional `onMenu` prop) and
  `src/lib/components/RoomList.svelte` (holds the open menu).

**Interfaces:**
- Consumes: Task 1's wrappers, `block`, `unblock`, `safetyStore`, `accountStore`; Task 2's
  `ReportSheet` and `blockConsequence`; `nextNotificationMode` (`ipc.ts`, from `origin/main`);
  `roomsStore.declineInvitation` (`rooms.svelte.ts:250`).
- Produces (`roomSettings.ts`):
  - `NOTIFY_OPTIONS: readonly { mode: NotificationMode; label: string }[]` — the four, in iOS order;
  - `muteTarget(on: boolean): NotificationMode` — `"muted"` or `"default"`;
  - `interface ToggleDeps { roomInfo; nextNotificationMode; setRoomNotificationMode; setRoomPinned }`;
  - `toggleMute(roomId, deps?): Promise<{ muted: boolean } | null>` and
    `togglePin(roomId, deps?): Promise<{ pinned: boolean } | null>` — ask `roomInfo` first; `null`
    when it, or the write, fails.
- Produces: `RoomNotificationsSection` props `{ mode: NotificationMode; pinned: boolean; onMode: (m: NotificationMode) => void; onPinned: (p: boolean) => void }`.
  Mute and Pin are `<input type="checkbox" role="switch">` with visible labels; "Notify me about"
  is a native `<select>` (a native popup, per the AGENTS.md native-feel budget).
- Produces (`memberActions.ts`): `memberName(member)` (`displayName ?? userId`);
  `memberIsBlocked(member, blocked: ReadonlySet<string>, unblockedHere: ReadonlySet<string>)`
  (iOS `:320-323`); `memberActionsMessage(blocked: boolean, isAgent: boolean)`;
  `blockLabel(name)`, `unblockLabel(name)`, `reportLabel(name)`.
- Produces: `MemberActions` props `{ member: RoomMember; blocked: boolean; onBlock: () => Promise<void>; onUnblock: () => Promise<void>; onReport: () => void; onCancel: () => void }` — a
  native modal `<dialog>`, title the name, the message, then the actions in iOS order; focus on
  Cancel.
- Produces: `MemberRow` props gain `blocked?: boolean` (default `false`) and
  `onActions?: () => void`. When `onActions` is set, a trailing `⋯` button
  (`aria-label="Block or report {name}"`, or `"Unblock or report {name}"` when blocked,
  `aria-haspopup="dialog"`) opens it; the name and id stay `.selectable` text (open question Q5).
- Produces (`rowMenu.ts`):
  - `type RowMenuItem = { id: "mute" | "pin" | "decline"; label: string; destructive: boolean }`;
  - `rowMenuItems(affordance: RoomAffordance, info: { notifications: NotificationMode; pinned: boolean } | null): RowMenuItem[]`
    — invitation → `[Decline]`; compose → `[Mute|Unmute, Pin|Unpin]`, labels from `info` (`null`
    → `"Mute"`, `"Pin"`); `nothing` → `[]` (no menu);
  - `menuPosition(point: {x: number; y: number}, size: {width: number; height: number}, viewport: {width: number; height: number}): {left: number; top: number}`
    — opens at the point, flipped left/up when it would leave the window, never negative.
- Produces: `RowMenu` props `{ items: RowMenuItem[]; at: {x: number; y: number}; onChoose: (id: RowMenuItem["id"]) => void; onClose: () => void }`
  — `role="menu"`, `role="menuitem"` items, focus on the first, Escape and an outside click close.
- Produces: `RoomRow` prop `onMenu?: (roomId: string, at: {x: number; y: number}) => void`, raised
  by `contextmenu` (with `preventDefault`) and by Shift+F10 or the ContextMenu key on the row
  button (then `at` is the row's bottom-left). Rows render unchanged when it is absent, so the
  existing `RoomRow` frames must not change.

- [ ] **Step 1: Write the failing tests.**
  - `roomSettings.test.ts`:
    - `NOTIFY_OPTIONS are iOS's four, in order` (labels and modes exactly);
    - `muteTarget` both ways;
    - `toggleMute asks the core first`: `roomInfo` → `{notifications: "allMessages"}`,
      `nextNotificationMode` → `"muted"`; `setRoomNotificationMode("!a", "muted")` ran after both,
      result `{muted: true}`;
    - `toggleMute does nothing when roomInfo fails` (RF6): `roomInfo` rejects; no write, `null`;
    - `togglePin flips what the core says`: `pinned: true` → `setRoomPinned("!a", false)`;
    - `a refused write reports null`.
  - `memberActions.test.ts`: `memberIsBlocked` — the store says so → true; `isIgnored` true and
    not unblocked here → true; `isIgnored` true but unblocked here → false; the three labels and
    both messages exactly (reusing `blockConsequence`).
  - `rowMenu.test.ts`: invitation → one destructive "Decline"; compose with `{muted, pinned}` →
    "Unmute", "Unpin"; compose with `null` → "Mute", "Pin"; `nothing` → `[]`;
    `menuPosition keeps the menu inside the window`: a point 10px from the right and bottom edges
    flips both ways; a point at `(0, 0)` stays at `(0, 0)`.
- [ ] **Step 2: Run them and confirm they fail.**
  `pnpm vitest run src/lib/components/roomSettings.test.ts src/lib/components/safety src/lib/components/roster`.
- [ ] **Step 3: Implement.**
  - **Room info.** After the topic block, `RoomNotificationsSection` with `info.notifications`
    and `info.pinned`. `onMode`/`onPinned` set `info` optimistically, call the write, then reload
    `roomInfo(roomId)` whatever the outcome (iOS `:391-397`).
  - Member rows: `blocked={memberIsBlocked(member, safetyStore.blocked, unblockedHere)}`;
    `onActions` on every row whose `userId !== accountStore.account?.userId` (no actions while the
    account is unknown). Block/unblock go through Task 1's `block`/`unblock`; a refusal sets
    `safetyFailure`, shown under the list in `role="alert" text-danger`; a success adds to or
    removes from `unblockedHere`. "Report {name}…" closes the actions and opens `ReportSheet` with
    `{kind: "user", userId, name, isAgent: member.isAgent}`.
  - `"Report room…"` (danger, iOS hint as `title`) beside `"Leave room"`, opening the sheet with
    `{kind: "room", roomId, name: info.identity.name}`.
  - **Row menu.** `RoomList` passes `onMenu` to every row. Opening fetches
    `roomInfo(roomId).catch(() => null)` for a compose row (so the labels say what the room is set
    to, open question Q4), then shows `RowMenu` with `rowMenuItems(...)`. Choosing Mute or Pin calls
    `toggleMute`/`togglePin` (which ask again before writing); Decline calls
    `roomsStore.declineInvitation(roomId)`. The menu closes on choice. No menu for `nothing` rows.
  - **Stories:**
    - `RoomNotificationsSection`: "Account default", "Muted and pinned";
    - `MemberRow`: "Agent", "Blocked", "With actions" (the play focuses `⋯`);
    - `MemberActions`: "Block an agent" (agent message), "Unblock", "Block fails" (`onBlock`
      rejects a `CoreError`; the play clicks Block; the parent's alert is out of scope, so this
      story asserts the dialog stays open and enabled);
    - `RowMenu`: "Joined room, muted", "Invitation".
- [ ] **Step 4: Run the checks.**
  - Full desktop set. Record the new frames. Existing `MemberRow` frames change only where a
    fixture member is an agent (the tag); inspect and copy those. `RoomRow` and `MentionMenu`
    frames must not change.
  - Running app (read-only): open room info in an agent room and in a group room. The section
    reads the room's real mode and pin; **do not change them**. Open a member's actions and
    Cancel; open "Report {name}…" and Cancel. Your own row has no `⋯`. Right-click a roster row
    and press Escape; focus a row and press Shift+F10, then Escape. At 600px and at Largest
    text, the menu and dialogs stay inside the window.
  - **Falsify:**
    - write before asking in `toggleMute` → `toggleMute does nothing when roomInfo fails` fails;
    - drop the `unblockedHere` clause → the third `memberIsBlocked` case fails;
    - return `rowMenuItems` labels without `info` → the "Unmute" case fails;
    - remove the flip in `menuPosition` → the edge case fails.
  - Live verification pending: mute, unmute, notification mode, pin, unpin (room info and row
    menu); block and unblock from the member list; decline from the row menu.
- [ ] **Step 5: Commit.** Message: `desktop: room notifications and pin, member block and report, a row menu`.

### Task 4: New conversation — the people you know first, reusing a DM **[page]**

**iOS behaviour and copy** (`Panels/NewRoomPanel.swift`):
- `:106` — title `"New conversation"`; `:109` — `"Cancel"`.
- `:112` — a search field, prompt `"Name, machine, or @user:server"`, filtering with the core's
  `peopleMatching` (`:55-57`).
- `:69-91`:
  - while loading, `"Looking for who you know"`;
  - empty: title `"Nobody yet"` (no query) or `"No one matching \(query)"`, description
    `"Agents and people you share a room with appear here."` or
    `"Try a name, a machine, or a full address."`;
  - otherwise a section `"Who you know"` (no query) or `"Matches"`, one row per person, in the
    core's order (agents first, `C/people.rs:89-104`).
- `:141-205` — a row: the person's `initial` (the glyph when it is one) in a 30px disc, the name,
  and a subtitle: `"\(runtime.harness) on \(runtime.host)"` for an agent, otherwise the user id,
  one line, truncated in the middle. A busy row shows progress; while one is busy, the others are
  disabled. A failure shows at the top in danger.
- `Session.swift:549-574` — opening a person: `directRoomWith(userId)` (any error → treated as
  none), otherwise `createRoom(name: person.name, invite: [userId], isDirect: true)`; then open the
  room and close.
- `:93-101`, `:207-283` — `"Join by address"` opens `"Join a room"`: header `"Room address"`,
  placeholder `"#general:supermessage.dev"`, footer
  `"An alias like #general:supermessage.dev, or a room id starting with !."`, a `"Join"` button
  disabled while empty or busy, `"Cancel"`.

**Files:**
- Modify: `src/lib/components/NewRoomPanel.svelte` (most of it) and `NewRoomPanel.stories.svelte`.
- Modify: `src/lib/components/roomCreation.ts`, `roomCreation.test.ts`.
- Modify: `src/routes/+page.svelte` where `NewRoomPanel` mounts (about `:1197`).

**Interfaces:**
- Consumes: `knownPeople(query)`, `directRoomWith`, `createRoom`, `joinRoomByAlias` (`ipc.ts`).
- Produces (`roomCreation.ts`):
  - `personSubtitle(person: PersonDto): string`;
  - `peopleHeading(query: string): "Who you know" | "Matches"`;
  - `emptyState(query: string): { title: string; description: string }`;
  - `openConversation(person: PersonDto, deps = { directRoomWith, createRoom }): Promise<string>`
    — the room id; a failed lookup falls through to create; a failed create rejects with the
    `CoreError` (the panel words it with `errorMessage`).
- Produces: `NewRoomPanel` props: the existing `onCreate`, `onJoin`, `onOpened`, `onClose`, plus
  `loadPeople: (query: string | null) => Promise<PersonDto[]>` and
  `onOpenPerson: (person: PersonDto) => Promise<string>`.
- The panel has three views in one dialog (`aria-label` follows the view):
  - **people** (default, iOS): the search field, the list, then two quiet rows: `"Join by address"`
    and `"New room"` *(no iOS copy: the desktop's existing named-room form, kept, open question Q6)*;
  - **join**: iOS's "Join a room" form (the existing `isRoomTarget` check and its message stay);
  - **create**: the existing form, unchanged in behaviour.
  - Each sub-view has a `"Cancel"` that returns to the people view.
- Each keystroke calls `loadPeople(query)` with a sequence guard (a late answer to an older query
  is dropped); the empty query loads with `null`.

- [ ] **Step 1: Write the failing tests** in `roomCreation.test.ts`:
  - `openConversation reuses an existing DM`: `directRoomWith` → `"!dm"`; `createRoom` never
    called; result `"!dm"`.
  - `openConversation creates a DM named for the person`: `directRoomWith` → `null`;
    `createRoom("Atlas", ["@atlas:x"], true)`.
  - `openConversation creates when the lookup fails`: `directRoomWith` rejects; `createRoom` called.
  - `personSubtitle`: runtime `{harness: "Claude", host: "Ci"}` → `"Claude on Ci"`; none → the id.
  - `peopleHeading` and `emptyState`: all four strings exactly, with the query interpolated.
- [ ] **Step 2: Run them and confirm they fail.** `pnpm vitest run src/lib/components/roomCreation.test.ts`.
- [ ] **Step 3: Implement.**
  - The page passes `loadPeople={(q) => knownPeople(q)}` and
    `onOpenPerson={(p) => openConversation(p)}`; `onOpened` is the existing rail-refresh-and-select.
  - Row disc: `person.initial` in `bg-surface-raised`; a `text-meta text-content-muted` subtitle
    with `truncate` (middle truncation has no CSS equivalent; end truncation is the desktop's
    choice, noted in the report). Busy: the row shows `Working…` *(no iOS copy; iOS shows a
    spinner)* with `aria-busy="true"`.
  - **Stories:** "Known people" (an agent with a runtime, a person, and a person with no display
    name whose subtitle is the raw id), "Nobody yet", "No one matching" (the play types `zz`),
    "Opening a person" (`onOpenPerson` never resolves; the play clicks a row), "Join by address"
    (the play clicks it), "Open fails" (`onOpenPerson` rejects a `CoreError`; the play clicks a row
    and waits for `[role=alert]`). Keep the existing "Create fails" story on the create view (its
    frame changes: the play must first click "New room").
- [ ] **Step 4: Run the checks.**
  - Full desktop set. Existing `rooms-newroompanel--*` frames change by design; inspect each and
    copy. Record the new ones.
  - Running app (read-only): open the panel; the people you share rooms with load, agents first;
    type a host name and see the list narrow; open "Join by address" and Cancel. **Do not click a
    person** (it may create a room). Check 600px and Largest text.
  - **Falsify:**
    - make `openConversation` always create → `reuses an existing DM` fails;
    - drop the sequence guard and swap two answers in a story fixture → note what shows (report);
    - swap the two headings → `peopleHeading` fails.
  - Live verification pending: opening an existing DM from the list; creating a new DM.
- [ ] **Step 5: Commit.** Message: `desktop: new conversation with the people you know first`.

### Task 5: Account identity and the blocked list; README and the M3 verification table **[page]**

**iOS behaviour and copy** (`Panels/AccountPanel.swift`):
- `:55-80` — "Signed in as": a 44px disc with the initial, the name (headline), the user id (meta,
  muted, one line, truncated in the middle), and `"Homeserver"` with its URL.
- `:112-130` — `"Blocked"`, **only when someone is in it**: each row is the member row with an
  `"Unblock"` button labelled `"Unblock \(name)"` (`RoomInfoPanel.swift:438-455`); footer
  `"Their messages are hidden in every room, on all your devices. A blocked agent keeps running. Block someone from a room's member list."`,
  replaced by the refusal in danger when an unblock fails (`:204-212`). Loaded once when the
  panel opens (`:177-180`; failure → no section).
- `:142-144` — the footer under the links:
  `"To report abuse, long-press a message or open a room's info. Reports go to your homeserver's administrators."`
  — desktop wording, see below.
- `:182-192` — the confirm title `"Sign out of \(name)?"`.
- **Name:** iOS uses `AccountLabel.name` (the localpart, `SupermessageKit/AccountLabel.swift`).
  The desktop names the account with the core's `people_label` (`user_label`), as Android does
  (`android/app/src/main/kotlin/dev/supermessage/Account.kt:221-244`). Open question Q7.

**Files:**
- Modify: `src/lib/components/AccountPanel.svelte`, `AccountPanel.stories.svelte` (M2 Task 14).
- Modify: `src/lib/components/accountView.ts`, `accountView.test.ts`.
- Modify: `src/lib/stores/sessionReset.ts`, `sessionReset.test.ts`.
- Modify: `src/routes/+page.svelte`: pass the account, name and blocked list; sign-out resets the
  session state.
- Modify: `README.md` (rows 38 and 39 for desktop, and any other cell this milestone changed).
- Create: `.superpowers/sdd/m3-desktop/m3-desktop-verification.md` (the run's evidence record, not
  a repo doc).

**Interfaces:**
- Consumes: `accountStore` (Task 1), `blockedUsers`, `unblock`, `MemberRow`, `initial`
  (`roomInfoView.ts:84`), `signOutTitle` (M2).
- Produces:
  - `AccountPanel` props gain `account: AccountDto | null`, `accountName: string | null`,
    `blocked: RoomMember[] | null`, `onUnblock: (userId: string) => Promise<string | null>`.
  - `REPORT_FOOTER` in `accountView.ts`:
    `"To report abuse, open a message's More menu or a room's info. Reports go to your homeserver's administrators."`
    *(iOS copy adapted: "long-press" is not a desktop gesture; open question Q16)*.
  - `BLOCKED_FOOTER` — the iOS sentence verbatim.
  - `blockedSectionShown(blocked: RoomMember[] | null): boolean` — non-null and non-empty.
  - `resetSessionState(stores = { editTargetStore, replyTargetStore, safetyStore, accountStore }): void`
    — `resetComposeState` plus `safetyStore.clear()` and `accountStore.clear()`.
- The confirm title is `signOutTitle(accountName)`; R2's `"Sign out?"` remains only while the name
  is unknown.

- [ ] **Step 1: Write the failing tests.**
  - `accountView.test.ts`: `REPORT_FOOTER` and `BLOCKED_FOOTER` exactly; `blockedSectionShown(null)`
    and `([])` false, one member true; `signOutTitle("Rakesh")` → `"Sign out of Rakesh?"`.
  - `sessionReset.test.ts`: `resetSessionState forgets the account and its safety state` (RF5):
    with real stores over a fake `Storage`, set a reply and an edit target, `apply(["@x"])`,
    `hide("$e")`, and a loaded account; after the call, `isBlocked("@x")` is false, `visible` hides
    nothing, `accountStore.account` is `null`, and the storage key is gone.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
  - Identity first, in iOS order; the disc shows `initial(accountName ?? "?")`; while the account
    is unknown, the name reads `"Signed in"` (iOS `AccountLabel.swift`, `userId ?? "Signed in"`).
  - The page loads `blockedUsers()` each time the panel opens (failure → `null`), passes
    `accountStore.account` and `accountStore.name`, and wires `onUnblock` to Task 1's `unblock`,
    removing the row on success. `signOut()` calls `resetSessionState()` in place of
    `resetComposeState()`.
  - The Blocked section sits after Chats/appearance and before the links (iOS order); the report
    footer under the links.
  - **Stories:** "Account" (identity filled; inspect and copy the changed frame), "Blocked users"
    (two members, one an agent), "Unblock fails" (`onUnblock` resolves a refusal string; the play
    clicks Unblock and waits for the danger footer). `SignOutConfirm` "Sign out of a named
    account".
- [ ] **Step 4: Run the checks.**
  - Full desktop set. Record new frames; copy changed `AccountPanel` frames after inspection.
  - Running app (read-only): open Account: your name, id and homeserver show; the Blocked
    section shows only if the account has blocked someone. Open the sign-out confirm: it names
    the account; **Cancel**.
  - **Falsify:** drop `safetyStore.clear()` from `resetSessionState` → the RF5 test fails; make
    `blockedSectionShown([])` true → its case fails.
- [ ] **Step 5: README and verification.**
  - Correct the README desktop cells against the merged code (not against this plan): row 39
    "Implemented" for desktop; row 38 as Task 1 worded it.
  - Fill `m3-desktop-verification.md`: one line per row in the exit table below, at 1905px,
    600px and Largest text, each marked ✅ only with a screenshot or DOM read, or
    "live pending (user)" for a write.
- [ ] **Step 6: Commit.** Message: `desktop: account identity and blocked list; README after parity M3`.

---

## Excluded, and why

| Item | Why it is not in this plan | Where it goes |
|---|---|---|
| **`agent_avatar` command** (roadmap M3 desktop list) | iOS calls `Core::agent_avatar` only to fill the App Group cache that the fleet Live Activity and the widgets read (`SupermessageKit/Notifications/AgentAvatarCache.swift:29-79`, `Platform/PlatformCoordinator.swift:140`). The desktop has neither, so the command would have no caller (ruling 9). It also cannot be added from this track: `widget::AgentAvatar` derives no `Serialize` (`C/widget.rs:575`), so a Tauri command returning it needs a core change. Room and member faces already come from `room_avatar`/`member_avatar`. | Revisit at M6/M7 if desktop notifications or the tray want agent faces; a Core-track item then (Serialize, or a data-URI variant). Open question Q1. |
| **"Mark read" in the row menu** (R2) | Ruling R3: `mark_room_read` reaches only the focused timeline (`commands.rs` `mark_room_read` → `FocusedTimeline::mark_read`, `C/timeline.rs:3011-3017`, which refuses any other room). No `Session::mark_room_read(room_id)` exists on `origin/main`. | Core track: the M3 core task R3 names. Then one `RowMenu` item here. Open question Q2. |
| **C3 composer thumbnail command** | The M2 plan's "Moved to core" table needs `StagedAttachments::thumbnail(token)` first; it is not in the core. | Core track, then a desktop command. |
| **Agents first, and "{harness} on {host}", in the `@` menu** (M2 desktop deferral) | The composer should take its people from the room's cast (`room_cast`, already a command since M1.2), not from `known_people`; that switch is M4's D11 "names from the cast". | M4 (open question Q9). |
| **"Read by {names}" on reader faces** (M2 desktop deferral) | A T3/D7 polish item, not a safety, recovery or room-settings row. Task 1 adds `people_label`, so it becomes a small wiring change. | M4 polish (open question Q10). |
| Room info runtime rows and "Leave room" with its confirm | Done in M2 desktop Task 11. | — |
| The Account panel shell (appearance, text size, links, version, sign-out confirm) | Done in M2 desktop Tasks 13-14. | — |
| Recovery: `ensure_recovery` at sign-in and the new key | Done in M0 (B5); `enable_recovery` removed in M2 Task 14. The recovery row is re-checked in Task 5's verification table only. | — |
| Edit and delete of own messages | Done in M0/M2. | — |
| `notification_for(_row)`, `notification_gate_answers`, `attachment_mark_voice`, `attachment_stage_path` | Notifications and voice. | M6. |
| Reporting a space invitation from the rail | iOS has no space invitations in a rail. | — |

## Exit: rows this plan turns ✅

| Matrix row | Task | Verified by |
|---|---|---|
| Block / unblock, blocked list | 1, 3, 5 | `safety.test.ts`, `memberActions.test.ts`, `MemberActions`/`AccountPanel` stories; live pending (user) |
| Report message / room / user (reason sheet, hides the reported message) | 1, 2, 3 | `safety.test.ts`, `reportView.test.ts`, `timelineHidesReported.test.ts`, `ReportSheet` stories; live pending (user) |
| Room info: members, runtime, mute/pin, Leave with confirm, block/report | 3 (runtime and Leave from M2) | `roomSettings.test.ts`, `RoomNotificationsSection` stories, running app (read-only) |
| R2 mute / pin from the row (Mark read excluded) | 3 | `rowMenu.test.ts`, `roomSettings.test.ts`, `RowMenu` stories |
| Invitations with inviter (and Report room) | 2 | `invitationView.test.ts`, `InvitationPanel` stories, running app |
| New room / DM with known people first | 4 | `roomCreation.test.ts`, `NewRoomPanel` stories, running app (read-only) |
| Permission requests (shared, room-addressed sender) | 1 | `decisionReply.test.ts`; live pending (user) |
| Account panel: identity and blocked list | 5 | `accountView.test.ts`, `AccountPanel` stories, running app |
| Recovery | (M0) | Task 5 verification table |
| Review Focus 5 (sign out, sign in as another) | 1, 5 | `persistsUntilSignOut`, `resetSessionState forgets the account and its safety state` |

## Decisions taken in this plan (technical; the controller may overrule)

1. Commands go in `src-tauri/src/commands_session.rs`, not `commands.rs`, to stay clear of
   Core-track merges.
2. `known_people` takes an optional query and composes `people::matching` (Q12).
3. The desktop seeds the blocked list from `blocked_users` after the session restores, because an
   `sm://` event emitted before the listener exists is lost; the event still wins (generation
   check).
4. A message report's `isAgent` comes from `TimelineRow.isAgent` if M1.4 has landed, otherwise
   from the room's member list (`room_info`, the same core rule `agent_name_from_id`). A sender who
   has left the room reads as a person.
5. `reasonTooLong` shows the core's message capitalised (Q15).
6. Native `<select>` for "Notify me about"; HTML `role="menu"` for the row menu (Q3).

Open questions for the owner are in `.superpowers/sdd/planning-m3/open-questions.md`.
