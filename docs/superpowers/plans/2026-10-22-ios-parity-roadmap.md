# iOS Parity Roadmap — Android and Desktop

> **For agentic workers:** this is the umbrella plan. It is not executed task by task. Each
> milestone below gets its own task-level plan
> (`docs/superpowers/plans/YYYY-MM-DD-parity-mN-<name>.md`), written just before that milestone
> starts, because it consumes interfaces the earlier milestones create. Execute those with
> superpowers:subagent-driven-development or superpowers:executing-plans. The M0 plan is
> `2026-10-22-parity-m0-defects.md`.

**Goal:** every row of the parity matrix in the spec is either ✅ or "n/a" with an agreed
desktop or Android equivalent. Each row is verified in the running app, on Android and on
desktop, with the work done on the two platforms in parallel.

**Architecture:** there are three tracks:
- **Core (C)** owns `crates/supermessage-core`, `crates/supermessage-ffi`, both sets of
  generated bindings, and the iOS switch-over to core-owned rules.
- **Android (A)** owns `android/`.
- **Desktop (D)** owns `src/` and `src-tauri/`.

Rules a platform decides today in Swift-only code move into the core once (M1). Both
platform tracks then build UI on the same core outputs. Work that needs no new core runs on
the platform tracks from the start, in parallel with M1.

**Tech Stack:**
- Rust: matrix-sdk 0.18 and UniFFI 0.28.
- Kotlin/Compose: AGP 9.3.1, Kotlin 2.4.0, compileSdk 36, minSdk 31.
- Tauri 2 with Svelte 5, Tailwind v4 and Vitest.
- SwiftUI, verified on CI only.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md`, with the evidence in `notes/android.md`
and `notes/desktop.md`. The iOS behaviour being matched is
`docs/superpowers/specs/2026-09-23-ios-revamp.md` plus the iOS code it cites.

## Global Constraints

These come from `AGENTS.md` and the revamp spec, and bind every milestone plan.

**Rules:**
- Tokens only: no hex in views. New type roles go through `design/tokens.toml`, then
  `python3 scripts/generate-tokens.py`. Never edit a generated file.
- Amber (`signal`) means a pending decision and nothing else.
- The app parses nothing and decides nothing that the core already decides.
- No client-private Matrix event types. A4 (Stop) stays blocked until AgentPod defines a
  cancel event.
- Matching iOS means matching its **behaviour and copy**, not its widgets. Copy strings are
  taken verbatim from the iOS file the milestone plan cites (for example "Sent to {name}",
  "{name} is on it", "Thought for {n}s · {k} steps", "You're all caught up", "What needs
  changing?").

**Bindings:**
- Any change to `supermessage-ffi` regenerates **both** the Kotlin bindings
  (`./scripts/build-android-libs.sh`) and the Swift bindings (the Linux recipe in
  `AGENTS.md`).
- Both sets are committed in the same PR. Only the Core track does this.

**Testing:**
- Android instrumented tests run after `scripts/android-ci-parity.sh`, at pixel_6 height
  **and** at `wm size 1080x1280`.
- Use no single-character substring matchers, and set the required **width** in test
  shells, not the height.
- Every new UI state ships with an Android `@Preview` (in `android/app/src/debug`) and a
  desktop story. Its baseline is recorded once, after inspection: never re-record to make a
  check pass.

**Verifying desktop changes:**
- Desktop UI claims are verified in the running app: `pnpm dev` plus
  `target/debug/supermessage`, with the embedded WebDriver on `127.0.0.1:4445`.
- Never verify with a bare `cargo run`.

**This machine:**
- It has 31 GB of RAM, and `/tmp` is RAM.
- Run one emulator and one heavy cargo build at a time. Put emulator logs in
  `TMPDIR=~/Android/dl`.

## Review Focus

These are the conditions most likely to break for a real user, which no single task owns.
Each milestone plan adds a test for the lines its code touches.

1. **A bridge agent that answers by editing a progress message** ("🔎 Searching files…" and
   then the final text) instead of streaming over the live channel. The "is on it" pill,
   the live card and the turn record must still resolve, and not hang in "Sent to".
2. **A room with several agents and several humans.** Status, acknowledgement and "one
   name per person" must name the right participant, not the room.
3. **Accessibility font sizes and narrow widths:** Android font scale 2.0 at 411dp, and a
   desktop window under 640px. New cards, pills and menus must not clip or overlap.
4. **Answering a gate or permission whose send fails** (offline, 5xx). The card must stay
   answerable and say so. It must never show a receipt for an answer that did not land.
5. **Sign out and back in as another account.** Caches, drafts, turn records, notification
   state and blocked lists from the first account must not show.

---

## Parallel workflow

- **One worktree per track**, created with superpowers:using-git-worktrees under
  `.worktrees/` (or the workspace's `.audit-worktrees/` convention if that is still in use):
  `parity-core`, `parity-android` and `parity-desktop`.
- **One branch per milestone per track:** `parity/m<N>-<track>-<slug>`, for example
  `parity/m0-android-defects`.
- **One PR per task group**, merged to `main` in dependency order. Rebase each track on
  `main` after every Core PR merges.
- **Ownership rules:**
  - The platform tracks never edit `crates/` or the generated bindings.
  - When the Desktop track needs a Tauri command over an existing core function, it edits
    only `src-tauri/src/commands*.rs` and `lib.rs`. That is not core work.
  - When a platform track needs a core change, it files it to the Core track as a task in
    the next C milestone and does not work around it in UI code.
- **The core contract comes first.** Each M1 task lands its Rust API, FFI export, Tauri
  command and both bindings before either platform track consumes it. Platform work that
  depends on an unmerged core task waits. It does not stub the API.
- **iOS switch-over:** each M1 task deletes the Swift Kit copy and points iOS at the core
  version, verified by the `ios` job on `macos-15` in CI (it cannot be built here). If that
  job is red for reasons outside the task, the switch-over goes into a follow-up PR. The
  core API still merges.

## Decision gates

These are decided before the milestone that needs them. The recommendation is the default
if the owner does not decide otherwise.

| Gate | Needed by | Question | Recommendation |
|---|---|---|---|
| G1 | M5 | Android phone navigation | Material 3 `NavigationSuiteScaffold`: a bottom bar on phones, a rail on tablets. It has the same four destinations as iOS (Chats, Needs you, Agents, Search), with the same badge rule. |
| G2 | M5 | Desktop "Needs you" and "Agents" | Sidebar destinations above the roster, with a count badge on Needs you. Search moves there too, so it can be reached without a room open. |
| G3 | M6 | Android background push | Decide after checking the AgentPod hub's gateway for FCM. If the hub has no FCM, use UnifiedPush, and track hub FCM as a charter item in AgentPod. The `event_id_only` pusher and `Core::notification_for` are reused either way. |
| G4 | M7 | Desktop equivalents for widgets and Live Activity | A window-title count, plus a tray icon with the Needs you count and the running-agent count. A desktop notification replaces the Live Activity. |
| G5 | M8 | What counts as verified | Run each matrix row once in the running app on the phone AVD, the tablet AVD and desktop Linux. Windows and macOS desktop builds are checked on CI only. |

## Milestones

Each item ID is from the spec's matrix. "Plumbing" says which track writes the API; the
platform tracks always write their own UI.

### M0 — Defects and unreachable plumbing (A ∥ D, about a week)
Detailed plan: `2026-10-22-parity-m0-defects.md`.

- **A:**
  - B1, permission answers.
  - B2, sending an attachment without text.
  - B3, images render.
  - Sender faces.
  - B4, lifecycle.
  - Connection status.
- **D:**
  - B5, `ensure_recovery` at sign-in.
  - B6, decision and attachment send failures.
  - Edit and delete wrappers, plus Copy and Delete in the message menu.
  - Login errors in the homeserver's words.
- **Docs:** README capability rows (B7).

**Exit:** all of the above are tested and merged, and the README table matches the code.

### M1 — Shared presentation rules into the core (C, in parallel with M2 and M3)
Each task: Rust module and tests, an FFI export, a Tauri command, both bindings, and the iOS
switch-over.

1. **Room status and roster views.** Covers A1, N2, N3 and R3.
   - Move `RoomStatus` (Working, Needs you, Active, Idle, Quiet), `NeedsYouInbox`,
     `AgentDirectory` and `RosterFilter` (All, Unread) from `RosterViews.swift` into
     `core::roster`.
   - Expose them on `RosterSection` and `RoomRow`, or as new `roster_*` functions.
2. **Room cast and acknowledgement.** Covers D11 and A3.
   - Move `RoomCast` (one name per participant) and `Acknowledgement` ("Sent to X" → "X is
     on it", including the edited-progress-message case from Review Focus 1).
3. **Turn record.** Covers T6 and A2.
   - Move `TurnRecordLabel`, the 20-room turn-record retention, the elapsed time and the
     current-step summary into `core::live` (or the existing live module).
   - Finished turns keep their tool steps for "What I did".
4. **Timeline rules.** Covers D5, D6 and T1.
   - Cross-verb membership churn with an expandable stretch, hiding the reply quote when the
     parent is directly above or across the new-messages line, and `is_agent` and
     `is_long_read` on the row.
   - This replaces the logic in `TimelineGrouping.swift`, `kit/TimelineGrouping.kt` and
     `timelineGrouping.ts`, so there is one implementation.
5. **Agent avatar style.** Covers R1: the generated agent face shape, colour and badge
   (`AgentAvatarStyle.swift`).
6. **First-run demo script and readable space names.** Covers O1: `FirstRunDemo.swift` and
   `SpaceNames.swift`.

**Exit:** iOS on CI uses only the core versions. The three platform grouping copies are
deleted or reduced to rendering.

### M2 — Platform parity that needs no new core (A ∥ D, starting alongside M1)

**Android:**
- A room header: name, avatar, a tap opens room info, and no status pill yet.
- The message menu:
  - react (with "+" opening on tap), reply, copy, edit, and delete with a confirm;
  - swipe to reply;
  - a who-reacted popover.
- Room list:
  - the spaces menu;
  - R2 swipe actions (read, mute, pin, decline);
  - Account → Chats (arrangement and the agent-state toggle);
  - an expandable invitations row.
- Timeline:
  - a labelled new-messages line, and the undecryptable recovery hint;
  - markdown while streaming;
  - T2 rhythm (2/12), T3 reactions on the corner with receipt faces, T4 swipe-to-reveal
    times, and T5 quote jump with a highlight;
  - D7 quiet metadata, with no raw event type.
- G1 decision layout: full-width Approve, destructive Reject last, a digest with Details,
  and failure feedback.
- Composer:
  - C1 capsule layout with attach inside;
  - any-file attach;
  - C3 thumbnail with errors on the chip;
  - the C4 `@` picker over `peopleMatching` and `knownPeople`.
- Account and settings:
  - the account panel (appearance, chats, help, terms and privacy links);
  - a theme chooser (System/Light/Dark, Tinted/Black, accents);
  - search scope (this room / everywhere).
- M2 haptics; M1 motion (cards expand, decision → receipt), with every animation off when
  the system's animator duration scale is 0 (Android's Reduce Motion).

**Desktop:**
- Composer: edit mode, C3 thumbnail with errors on the chip, agents marked in the `@`
  menu, and the C1 layout (attach inside).
- Room info: Leave with a confirm, and the runtime row.
- Account and settings:
  - an account panel with identity, version and help links;
  - the theme and accent chooser (writing `data-appearance` and `data-accent`);
  - a sign-out confirm;
  - a text-size setting (Dynamic Type's equivalent), checked at the largest size against
    Review Focus 3.
- Search scope.
- G1: typed feedback for Request changes, the Approve/Reject hierarchy, and sent/failed
  states.
- Timeline: T2 rhythm, T3, T4 (hover time), T5 and D7.
- Motion: messages animate in, cards expand, decision → receipt.
- Pull the "Mark read" row action forward, since it needs no new command.

**Exit:** every listed row is ✅ in its milestone plan's verification table.

### M3 — Reaching the core's existing capabilities (D adds commands; A adds wrappers)
- **Safety on both platforms:**
  - block and unblock, and the blocked list in Account;
  - report message, room and user, with a reason sheet, hiding the reported item (iOS
    `ReportSheet`).
  - Android adds `CoreClient` wrappers and handles `FfiEvent.IgnoredUsers`. Desktop adds
    commands over `session.rs:1799-1836`.
- **Desktop only:**
  - commands and UI for mute, pin and notification mode (room info and row context menu);
  - `send_permission_answer`, replacing the plain-message reply;
  - `known_people` and `direct_room_with` for new room / DM;
  - `room_inviter` on invitations;
  - `account`;
  - `agent_avatar`.

**Exit:** every safety, recovery and room-settings row in the spec is ✅ on both platforms.

### M4 — The agent experience (A ∥ D, after M1.1–M1.5)
- A1: a header status pill on both platforms.
- T1: agent message cards (avatar, name, Agent badge, soft surface) and a serif long-read
  view (`ThemeType.longread`).
- A2: one live card inside the timeline, showing the current step, elapsed time, step count
  and streamed markdown. On desktop it replaces the three strips outside the list.
- A3: "Sent to" → "is on it".
- T6: the turn record and "What I did" in the answer row.
- D5 and D6 rendered from the core, and D11 names from the cast.
- R1: generated agent avatars, with the status dot on the avatar and two-line rows.
  Android moves runtime out of the row.

**Exit:** both platforms pass Review Focus 1 and 2 in the running app.

### M5 — Navigation and first run (A ∥ D, after M1.1, M1.6, G1 and G2)
- N1: four destinations.
- N2: the Needs you inbox with a badge and the "You're all caught up" state.
- N3: the Agents view.
- R3: filter chips.
- O1: the welcome screen and the local first-run demo room. Nothing is sent anywhere.
- Android: deep links (`supermessage://open?room=`) and pull to refresh.

### M6 — Notifications and voice (A ∥ D, after G3)
- **Android:**
  - `POST_NOTIFICATIONS` and local notifications worded by `notificationForRow`;
  - action buttons for Allow once / Reject and Approve / Request changes (with text) /
    Reject, sent through `sendGateDecisionTo` and `sendPermissionAnswer` with no room open,
    and requiring device unlock as iOS does;
  - background push per G3, reusing `registerPusher` and `notificationFor`;
  - C2 voice recording (`RECORD_AUDIO`, `attachmentMarkVoice`).
- **Desktop:**
  - `tauri-plugin-notification` with commands over `notification_for_row` and
    `notification_gate_answers`;
  - a window-title count;
  - C2 voice recording (a new command over `attachment_mark_voice`, plus staging from
    recorded bytes).

### M7 — System surfaces (A ∥ D, after G4)
- **Android:** Glance widgets for the Needs you count and agent status, over the core's
  `widget*` functions. Also an ongoing notification for a running agent turn, standing in
  for P3.
- **Desktop:** a tray icon with counts.

### M8 — Parity verification
- Re-run the spec's audit method against `main`.
- Do the G5 runtime pass for every row.
- Update the README capability table and `docs/platform-parity.md`.
- Write a dated follow-up record in `docs/research/` that supersedes the 2026-10-22
  matrix. Do not edit the matrix itself.

## Dependency summary

```
M0(A,D) ──► M2(A,D) ──────────────► M4(A,D) ─► M5(A,D) ─► M6 ─► M7 ─► M8
            M3(D cmds, A wraps) ──►   ▲            ▲
M1(C) ── M1.1–M1.5 ─────────────────┘   M1.1, M1.6 ┘
```

M0, M1 and M2 start the same week. M3 can interleave with M2 on each track. Every item in
the spec's matrix is assigned above. The only exception is A4, which stays blocked on
AgentPod.
