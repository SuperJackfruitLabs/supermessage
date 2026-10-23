# Parity M7 (Desktop): system surfaces. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each task is a bundle: one implementer dispatch, targeted
> tests while iterating, the full suites once before the commit, one review. **An implementer reads
> only its own task, plus Global Constraints and Review Focus.**

**Goal:** finish gate G4's desktop equivalents for the iOS widgets and Live Activity, so P3 and P4
are "n/a, with an agreed desktop equivalent" in the matrix:
- a **tray icon** carrying the Needs you count and the running-agent count;
- an opt-in **keep running in the tray** (owner question Q6);
- a **desktop notification standing in for the Live Activity** (Q7).

M6 already added the window-title count.

**Architecture:**
- **No core change on this track.** It consumes CP5 (`widget::needs_you_line` and `widget::pulse`
  made `pub`) and CP6 (`core::turn_card`). Both are filed to the Core track
  (`.superpowers/sdd/planning-m6-m7/open-questions.md`, Part 2).
- **The tray is Rust.** `tauri` gains the features `tray-icon` (`tray-icon 0.24.2`,
  `libappindicator 0.9.0` and `muda 0.19.3` are already in `Cargo.lock`) and `image-png`.
  `src-tauri/src/tray.rs` builds it in `setup`. M6's `set_attention` command (`attention.rs`)
  grows to update the tray as well as the title.
- **Linux caveats, designed around rather than discovered:**
  - Tauri emits **no tray click events on Linux**. The icon shows its menu, and everything is in
    the menu (v2.tauri.app/learn/system-tray: the event "is not emitted even though the icon is
    shown").
  - The **tooltip is unsupported on Linux**, so the counts are menu items, plus an amber dot on
    the icon while something needs the reader.
  - **KDE Plasma** shows StatusNotifierItems natively.
  - **GNOME** shows nothing without the AppIndicator/KStatusNotifierItem extension (Ubuntu ships
    it, Fedora does not). That is why Q6 keeps close-to-tray opt-in.
  - CI already installs `libappindicator3-dev` (`.github/workflows/ci.yml:280`). This machine has
    `libayatana-appindicator 0.6.0` and `libappindicator 12.10.1`.
- **Words** come from the core: `needs_you_line` gives "Nothing needs you", "1 needs you" or
  "3 need you", and `pulse` gives "All quiet", "2 working" or "2 working · 1 needs you"
  (`C/widget.rs:455-461, 487-495`). The turn notification's words come from CP6. The menu's verbs
  have no iOS copy.

**Tech stack:** as M6 desktop. Tauri 2.11.5, Svelte 5, Vitest 4, Storybook 10 with the snapshot
gate.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` rows P3 and P4 (`:164-165`). Umbrella:
`docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md` "M7" and gate G4. Questions:
`open-questions.md` Q5, Q6 and Q7. Format follows
`.superpowers/sdd/planning-m3/2026-10-22-parity-m3-desktop.md`. iOS paths are relative to
`apple/` at `origin/main` `2f7ecbe`. `C=crates/supermessage-core/src`.

**Branch and worktree:** `parity/m7-desktop`, in `.worktrees/parity-desktop`, cut from the tip of
`parity/m6-desktop` after its Task 5.

**Preconditions (controller):**
- M6 desktop is complete:
  - `notify.rs` (`Poster`), `commands_notify.rs` (`notification_post`);
  - `attention.rs` (`set_attention`, `title_for`) and `stores/attention.svelte.ts`;
  - `stores/windowFocus.svelte.ts`;
  - M5's sidebar destinations, with a way to navigate to Needs you.
- **Before Task 2:** CP5 has merged. **Before Task 4:** CP6 has merged, and M1b's `sm://turn`
  (`TurnUpdate`) is consumed by the desktop live store (M4).

## Global Constraints

The M6 desktop plan's Global Constraints apply unchanged:
- tokens only;
- amber only for a pending decision;
- the app decides nothing the core decides;
- iOS copy verbatim with `file:line`, and *(no iOS copy)* marked;
- no `crates/` edits;
- a story per new UI state, with its baseline recorded once after inspection;
- every test falsified;
- `CoreError`-shaped story failures;
- the heavy-command lock, `CARGO_BUILD_JOBS=8` and `CARGO_TARGET_DIR`, and `/tmp` is RAM;
- the snapshot-gate rules (7 known-drifting frames, never `--record`);
- verifying in the running app (`pnpm dev` plus `$CARGO_TARGET_DIR/debug/supermessage`, WebDriver
  on `127.0.0.1:4445`), at 1905px and 600px, and at Largest.

Tray-specific:
- **Tray icons are generated, not hand-coloured.** The amber dot's colour comes from a new Rust
  emitter in `scripts/generate-tokens.py`, which writes `src-tauri/src/tokens.rs` from
  `design/tokens.toml`'s `signal`. Never edit `tokens.rs` by hand. Amber is allowed here because the
  dot means "a decision is waiting" and nothing else.
- **Native surfaces have no story.** The tray menu and the notification are proven by Rust unit
  tests over traits, and checked read-only in the running app on KDE by screenshot.

**Live account: READ-ONLY.** You may run the app, open the tray menu, use "Open Needs you" and
"Open supermessage", toggle the Account setting (it is local), close the window with the setting
on and off, and quit from the tray. You may not send, answer or register anything.

**Verification for every task:** `pnpm test`, `pnpm check`, and the snapshot gate. Under the lock,
`cargo test --workspace` and
`cargo clippy --workspace --all-targets --all-features -- -D warnings`. Also
`cargo fmt --all --check`.

**Serialize:** **[page]** marks a task that edits `src/routes/+page.svelte`. The tasks run in plan
order.

**Commits** end with the attribution lines in the session's system reminder.

## Review Focus

1. **One number everywhere** (Q5). The window title, the tray's Needs you line, the icon's dot and
   the M5 sidebar badge all come from `NeedsYouInbox.badge_count` (`C/roster.rs:441-444`), so they
   cannot disagree. Pinned in Task 2.
2. **A dot that lies.** The amber dot shows only while `badge_count > 0`, and it leaves the moment
   it is 0, including at sign-out (RF5). Pinned in Tasks 1–2.
3. **An invisible process.** Closing the window never hides the app unless the reader turned the
   setting on (Q6). Quit always exits. Pinned in Task 3.
4. **The same news twice.** The Live Activity stand-in never fires for a turn that ended with a
   reply, because the reply notifies, and never for the room on screen in a focused window. Pinned
   in Task 4.
5. **Sign out and back in as another account** (RF5). The tray shows "Nothing needs you" and
   "All quiet", the dot goes, and no stand-in notification from the first account survives. Pinned
   in Tasks 2 and 4.

---

### Task 1: The tray icon and its menu

No core prerequisite.

**Behaviour:**
- The icon is the app's 32×32 icon (`src-tauri/icons/32x32.png`). A second variant has a filled
  `signal` dot in the top-right corner (radius 5 px, with a 1 px ring in the icon's own transparent
  margin), drawn at start-up from the PNG's RGBA. It is never hand-drawn.
- The menu, top to bottom:
  - a disabled line, "Nothing needs you" (Task 2 fills the core's words);
  - a disabled line, "All quiet";
  - a separator;
  - "Open Needs you": show, unminimise and focus the window, then emit `sm://navigate`
    `{ to: "needsYou" }`;
  - "Open supermessage": show, unminimise and focus;
  - a separator;
  - "Quit supermessage": `app.exit(0)`.

  The menu verbs are *(no iOS copy; iOS has no tray)*. The two lines start as the core's
  zero-state words, which Task 2 wires.
- `show_menu_on_left_click(true)`, so the menu opens on both buttons on every platform.
- The tooltip is set on macOS and Windows only (Task 2 gives it the pulse). Linux does not support
  it.

**Files:**
- Modify: `src-tauri/Cargo.toml`, with `tauri = { version = "2", features = ["tray-icon", "image-png"] }`.
- Modify: `scripts/generate-tokens.py`. Add a Rust emitter for `src-tauri/src/tokens.rs` holding
  `SIGNAL_LIGHT_RGBA` and `SIGNAL_DARK_RGBA` as `[u8; 4]`. Run it and commit the output.
  The tray uses the **dark-theme** signal (`#E8A33D` today, read from tokens), because a panel's
  theme is unknown and the brighter amber reads on both. Say so in a comment.
- Create: `src-tauri/src/tray.rs`, containing:
  - `pub fn dot_icon(rgba: &[u8], width: u32, height: u32, colour: [u8; 4]) -> Vec<u8>`;
  - `pub trait TrayView { fn set_lines(&self, needs_you: &str, working: &str); fn set_dot(&self, on: bool); fn set_tooltip(&self, text: &str); }`;
  - `TauriTray`, implementing it over `TrayIcon` and the two `MenuItem` handles;
  - `pub fn build(app: &AppHandle) -> tauri::Result<TauriTray>`.
- Modify: `src-tauri/src/lib.rs` (build it in `setup`, manage it, and handle the menu event ids).
- Modify: `src/lib/ipc.ts` and `ipc.test.ts` (`onNavigate(handler)` on `"sm://navigate"`).
- Modify: `src/routes/+page.svelte` **[page]** (route `needsYou` to M5's Needs you destination).
- Create: `docs/superpowers/plans/2026-10-22-parity-m7-desktop.md`, a copy of this plan (R10).

- [ ] **Step 0:** confirm the `tauri` feature names against `tauri 2.11.5`'s `Cargo.toml` in
  `~/.cargo/registry` (grep only). Confirm how M5 navigates to Needs you.
- [ ] **Step 1: Write the failing tests.**
  - Rust:
    - `dot_icon_paints_the_signal_colour_in_the_top_right` (the centre pixel of the dot equals the
      colour);
    - `dot_icon_leaves_the_rest_of_the_icon_untouched` (the pixels outside the dot's bounding box
      are equal);
    - `the_menu_has_its_items_in_order` (ids and labels, read from a menu built by a
      `#[cfg(test)]` helper with `tauri::test::mock_app()`);
    - `open_needs_you_emits_navigate` (a fake emitter).
  - Vitest: `onNavigate listens on sm://navigate`, and a page-level test that
    `a needsYou navigation selects the Needs you destination`.
  - Token generator: `python3 scripts/generate-tokens.py` followed by `git diff --exit-code` is
    clean after the commit (the existing generator check, if CI has one; otherwise state that you
    ran it).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - paint the dot top-left → the first test fails;
    - drop the emit → `open_needs_you_emits_navigate` fails.
  - Live (read-only, KDE): the icon appears in the system tray. Right-click and left-click both
    open the menu. Screenshot it. "Open Needs you" shows the window on Needs you. "Quit
    supermessage" exits the process (`pgrep supermessage` is empty). Record that GNOME was not
    checked here.
- [ ] **Step 5: Commit.** `desktop: tray icon and menu`.

### Task 2: The tray's counts and words **[page]**

Needs CP5 and Task 1.

**Behaviour:**
- **Needs you** is the M5 badge count (`badge_count`), the same value M6 Task 4 puts in the title
  (Q5). Line 1 is `needs_you_line(badge_count, false)`. The dot is on exactly when
  `badge_count > 0`.
- **Working** counts the agent directory's rows (`roster_agent_directory`) whose `room_status`
  (`C/roster.rs:496-520`; the M4 header pill's own call, with `turnInProgress` from the M1b turn
  record) is `Working`. Line 2 is `pulse(working, 0, false)`, giving "All quiet" or "2 working".
- **Tooltip** (macOS and Windows): `pulse(working, badge_count, false)`, for example
  "2 working · 1 needs you".
- It is recomputed on every roster change, every `sm://turn` update, and a **60 s** tick, because
  statuses age with time (`C/roster.rs`; widgets do the same with frames, `C/widget.rs:33-41`).
- At sign-out: 0 and 0.

**Files:**
- Modify: `src-tauri/src/attention.rs`. It becomes
  `set_attention(needs_you: u32, working: u32)`, which sets the title (unchanged) and calls
  `TrayView::set_lines(needs_you_line(n, false), pulse(w, 0, false))`, `set_dot(n > 0)` and
  `set_tooltip(pulse(w, n, false))`. Factor the words into a pure
  `pub fn tray_words(needs_you: u32, working: u32) -> TrayWords`.
- Modify: `src/lib/stores/attention.svelte.ts` and `attention.test.ts` (the working count, the
  60 s tick, and its teardown).
- Modify: `src/lib/ipc.ts` and `ipc.test.ts` (`setAttention(needsYou, working)`), and
  `stores/sessionReset.ts` (`setAttention(0, 0)`).
- Modify: `src/routes/+page.svelte` (pass the directory rows and the turn state).

- [ ] **Step 0:** grep for CP5's `pub fn needs_you_line` and `pub fn pulse` in `C/widget.rs`. Find
  how M4's header pill calls `room_status` and where the desktop holds `turnInProgress` per room.
- [ ] **Step 1: Write the failing tests.**
  - Rust:
    - `tray_words_at_zero_are_nothing_needs_you_and_all_quiet`;
    - `tray_words_name_one_and_many` ("1 needs you", "3 need you", "2 working");
    - `the_tooltip_joins_both` ("2 working · 1 needs you");
    - `set_attention_turns_the_dot_on_only_above_zero` (a fake `TrayView`);
    - `set_attention_sets_the_same_count_in_the_title_and_the_tray`.
  - Vitest:
    - `working counts only agents whose status is Working`;
    - `a turn in progress makes its agent working`;
    - `an invitation never lights the dot` (the badge count excludes it);
    - `the counts are re-read every minute`;
    - `the tick stops when the page is torn down`;
    - `signing out sends zero and zero`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - use `count` for the dot → the invitation test fails;
    - count `Active` as working → the first Vitest fails;
    - drop the tick → the minute test fails;
    - set the dot at `>= 0` → the dot test fails.
  - Live (read-only, KDE): with the live account's real state, the menu's lines match the sidebar
    badge and the Agents view. The dot is present if and only if the badge is. Screenshot it.
- [ ] **Step 5: Commit.** `desktop: Needs you and working counts in the tray`.

### Task 3: Keep running in the tray (opt-in) **[page]**

Q6's default: an Account setting, off by default.

**Behaviour:**
- Account → Notifications gets the toggle "Keep running in the tray when the window is closed",
  with the footnote "You'll still get notifications. Quit from the tray icon's menu."
  *(no iOS copy; iOS has no such setting)*.
- With the toggle on, `WindowEvent::CloseRequested` becomes `prevent_close()` plus `hide()`. With
  it off, the behaviour is today's: the window closes and the app exits.
- On macOS, `RunEvent::Reopen` (a Dock click) shows the window again.
- The setting persists **on this device**, in `localStorage` (the appearance store's pattern),
  and is sent to Rust on start and on change through `window_close_behaviour(keep_running: bool)`,
  held in an `AtomicBool`.
- Sign-out leaves the setting alone. It belongs to the device, not the account, like the theme.

**Files:**
- Modify: `src-tauri/src/lib.rs` (`on_window_event`, already at `lib.rs:175-180`, gains the
  `CloseRequested` branch) and `src-tauri/src/tray.rs`
  (`pub fn close_action(keep_running: bool) -> CloseAction`, which is `Hide` or `Close`).
- Create: `src/lib/stores/closeBehaviour.svelte.ts` and its test.
- Modify: `src/lib/components/AccountPanel.svelte` (the row) and its stories (off; on; at 600px;
  at Largest).
- Modify: `src/lib/ipc.ts` and `ipc.test.ts`, and `+page.svelte` (send the setting at start).

- [ ] **Step 1: Write the failing tests.**
  - Rust: `close_hides_only_when_keep_running_is_on`.
  - Vitest:
    - `the setting defaults to off`;
    - `the setting survives a reload` (a fake `Storage`);
    - `a storage that throws reads as off`;
    - `the setting is sent to Rust on start and on change`.
  - Stories: the row off and on. The interaction toggles it and asserts the switch's
    `aria-checked`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - default to on → its test fails;
    - hide regardless → the Rust test fails.
  - Live (read-only, KDE):
    1. With the toggle off, closing the window ends the process.
    2. With it on, closing hides the window. The tray's "Open supermessage" brings it back, and
       "Quit supermessage" ends the process.
    3. At 600px and Largest, the Account row does not clip. Screenshot each.
- [ ] **Step 5: Commit.** `desktop: optional keep running in the tray`.

### Task 4: The Live Activity stand-in notification, and the verification table **[page]**

Needs CP6 and M6's `notification_post`. Q7's default rule.

**iOS behaviour this stands in for:**
- `Supermessage/Platform/FleetActivityController.swift:6-29`: the hub's Lock Screen card shows an
  agent's turn and how it ended. On iOS, an answer's text notifies as a message, and the turn card
  itself is quiet (`C/notification.rs:310-330`, `C/push.rs:107-125`).
- Words: CP6 `turn_card` (`SupermessageKit/Notifications/FleetCard.swift:183-230`):
  - "{name} finished" with "Done in {elapsed} · {n} steps";
  - "{name} failed" with "At step {c} of {t} · after {elapsed}".

**Rule (Q7):**
- Post when a room's turn **ends**, meaning `TurnSummary.in_progress` goes from true to false, and
  either `ended_by == Done` (finished with no reply) or `failed_step` is set.
- Only when **not** (window focused **and** that room selected), using M6's `windowFocus` and the
  selection.
- Never for `Reply` without a failed step, because the reply notifies, and never for `TimedOut`
  (`turn_card` returns `None`).
- Each turn posts at most once: the id is `"turn:{roomId}:{turnId}"`.
- It is a MESSAGE-category note, so it has no buttons, and its click opens the room (M6 Task 3).
- The name is `TurnSummary.agent_id` (P2) through the room cast's display name, falling back to
  the room name. This is RF2.

**Files:**
- Modify: `src-tauri/src/commands_notify.rs`, adding
  `turn_card(summary: TurnSummary, agent_name: String) -> Option<TurnCard>`, a thin wrapper over
  CP6.
- Create: `src/lib/notifier/turnEnded.ts` and `turnEnded.test.ts`. `createTurnEndedNotifier(deps)`
  takes `TurnUpdate`s and the focus and selection state, keeps the last `in_progress` per room and
  the posted ids, and calls `turnCard`, then `notificationPost`.
- Modify: `src/lib/ipc.ts` and `ipc.test.ts`, and `+page.svelte` (subscribe it to the live
  store's `sm://turn` updates; reset it in `resetSessionState`).
- Create: `.superpowers/sdd/m7-desktop/m7-desktop-verification.md`.

- [ ] **Step 0:** grep CP6 (`C/turn_card.rs` or wherever it merged), the desktop live store's
  `TurnUpdate` subscription, and `TurnSummary.agentId` in `ipc.ts`.
- [ ] **Step 1: Write the failing tests** (Vitest, with fake `turnCard` and `notificationPost`
  that record):
  - `a turn that finishes without a reply posts finished`;
  - `a turn that ends by reply posts nothing`;
  - `a failed step posts failed even after a reply`;
  - `a timed-out turn posts nothing`;
  - `the room on screen in a focused window posts nothing`;
  - `the same room in an unfocused window posts`;
  - `a turn posts at most once` (the end update delivered twice gives one post);
  - `the card names the agent doing the turn` (RF2);
  - `an edited-progress turn ends like any other` (RF1: the summaries the core emits for an
    edit-progress turn end in `Reply` → nothing posted, and no hang in the state map);
  - `signing out forgets every room's state`.
  - Rust: `turn_card_command_returns_the_cores_words` (a fixture `TurnSummary`).
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - post on `Reply` → its test fails;
    - drop the focus check → the on-screen test fails;
    - drop the posted-id set → the at-most-once test fails;
    - name the room → the RF2 test fails.
  - Live (read-only): if a real agent turn finishes with no reply while the window is unfocused,
    screenshot the notification. Otherwise mark it pending. Live verification pending (owner,
    with the M4 RF1/RF2 script): a ✅-only finish and a failed step, each seen as one notification.
  - Write `m7-desktop-verification.md`:

    | Row | Desktop equivalent (G4) | Evidence | Live |
    |---|---|---|---|
    | P4 | Window-title count (M6 T4); tray Needs you line and dot; working line | M6 T4, M7 T1–T2 tests; KDE screenshots | ✅ read-only (KDE); GNOME unverified |
    | P4 | Keep running in the tray (opt-in) | T3 tests; KDE check | ✅ |
    | P3 | Turn-ended notification (Done or failed, not Reply or TimedOut) | T4 tests | pending (owner script) |
    | — | macOS/Windows tray and notifications | CI build only (G5) | — |
- [ ] **Step 5: Commit.** `desktop: notify when an agent's turn ends without a reply; M7 verification table`.

**Excluded from M7 desktop** (`open-questions.md` Part 3): the macOS dock badge, the Windows
taskbar overlay, the Unity launcher count, Linux tray click handling (unsupported), a tray tooltip
on Linux (unsupported), and a progress notification for a running turn. A running turn is shown
by the tray's working line instead, and XDG has no reliable in-place progress update across
servers.
