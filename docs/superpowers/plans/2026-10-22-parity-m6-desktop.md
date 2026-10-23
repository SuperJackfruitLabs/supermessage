# Parity M6 (Desktop): notifications, the window-title count, and voice. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each task is a bundle: one implementer dispatch, targeted
> tests while iterating, the full suites once before the commit, one review. **An implementer reads
> only its own task, plus Global Constraints and Review Focus.**

**Goal:** turn these desktop rows of the parity matrix ✅ (or "n/a" with an agreed equivalent):
- **P2:** desktop notifications worded by the core. On Linux they answer permissions and gates
  from the notification (owner question Q4's default); on macOS and Windows they are
  informational.
- **P4, partly:** the window-title count (gate G4).
- **C2:** voice message recording.

P1 (background push) is n/a on desktop: there is no push, so notifications exist while the
process runs.

**Architecture:**
- **No core change on this track.** It consumes prerequisite CP1 (`core::notification_policy`),
  CP2 (`core::voice_recording`) and, only if Task 5's probe says so, CP4 (the WebM remux). Those
  are filed to the Core track: `.superpowers/sdd/planning-m6-m7/open-questions.md`, Part 2.
- **The Tauri side (`src-tauri/`) is this track's.** It adds new files, `notify.rs`,
  `commands_notify.rs`, `attention.rs` and `commands_voice.rs`, registered in `lib.rs`. It stays
  out of `commands.rs`, which Core-track PRs edit (the M3 ruling).
- **Posting stays in Rust.** The webview gets no `notification:*` capability, just as it gets no
  `dialog:*` (`src-tauri/capabilities/default.json`). It asks Rust to post a note the core
  planned. Answering is handled entirely in Rust, because a notification action arrives in Rust
  (D-Bus) and the session is there. The core decides what the action means; Rust sends; Rust
  posts the failure.
- **Plugins:**
  - `tauri-plugin-notification = "2.7.0"` (crates.io, 2026-10-14; rust-version 1.90) posts on
    macOS and Windows and asks for permission. Its Actions API is mobile-only, and it has no
    desktop click handler (v2.tauri.app/plugin/notification).
  - On Linux the app uses `notify-rust` 4 directly, the crate the plugin itself uses there, so
    there is no second copy. XDG notifications carry actions when the server advertises the
    `actions` capability, and both KDE Plasma and GNOME Shell do.
  - **Fallback when there is no `actions` capability, and on macOS and Windows:** the notification
    is informational. A decision's body says what is waiting, and the reader answers it in the
    app (Needs you, M5).
- Logic lives in small `.ts` modules with Vitest tests. Components stay thin and get stories.

**Tech stack:** Svelte 5 (runes), SvelteKit SPA, Tailwind v4, Vitest 4, Storybook 10 with the
snapshot gate, Tauri 2.11.5 (`Cargo.lock`), webkit2gtk-4.1 2.54 on this machine.

**Spec:** `docs/research/2026-10-22-ios-parity/README.md` rows P2, P4 and C2 (`:151`, `:162`,
`:165`), and `notes/desktop.md`. Umbrella: `docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md`
"M6", G4 and Review Focus 3–5. Owner decisions: `.superpowers/sdd/planning-m1-m2/owner-decisions.md`.
Questions: `open-questions.md` Q4, Q5 and Q9. Format follows
`.superpowers/sdd/planning-m3/2026-10-22-parity-m3-desktop.md`. iOS paths are relative to
`apple/` at `origin/main` `2f7ecbe`. `C=crates/supermessage-core/src`.

**Branch and worktree:** `parity/m6-desktop`, in `.worktrees/parity-desktop`, cut from the tip of
`parity/m5-desktop` after its last task.

**Preconditions (controller):**
- M2–M5 desktop are complete:
  - M3's `commands_session.rs`, with `send_permission_answer` at `:144`, and
    `stores/sessionReset.ts` (`resetSessionState`);
  - M5's sidebar destinations and its Needs you badge, computed from `roster_needs_you_inbox`'s
    `badgeCount`.
- **Before Task 2:** CP1 has merged into `origin/main` and been merged into the branch.
- **Before Task 5:** CP2 has merged, and the owner has installed `gst-plugins-base` and
  `gst-plugins-good` locally (Q9). Only `gstreamer` and `gst-plugin-pipewire` are present
  today.

## Global Constraints

**From the roadmap and AGENTS.md (binding):**
- **Tokens only:** no hex in views. A new type role goes through `design/tokens.toml`, then
  `python3 scripts/generate-tokens.py`. Never edit `src/lib/tokens.css` by hand.
- **Amber (`signal`) means a pending decision and nothing else.** The recording dot and the mic
  while recording are `danger`. Nothing in this plan is amber except what M5 already draws.
- **The app parses nothing and decides nothing that the core already decides.** The core decides a
  notification's words, category, actions, action copy and answer, and whether a row or roster
  change is news. It also decides the waveform and the recorder's numbers. The desktop's only
  decisions are the platform's: which OS surface, whether the XDG server supports actions, and
  window focus.
- **Copy** is iOS's, verbatim, with its `file:line`. A string iOS lacks is marked
  *(no iOS copy)*.
- **The platform track never edits `crates/` or the generated bindings.** A needed core change is
  filed to the Core track, never worked around in UI or Tauri code.
- **Every new UI state ships with a story.** Its baseline is recorded once, after inspection, and
  never re-recorded to make the gate pass. Native notifications and the window title have no
  story; their tests are Rust unit tests and Vitest over the command wrappers.
- **Falsification (AGENTS.md):** for every new test, mutate the implementation, watch the test
  fail, restore it, and record what you saw. Each task names its mutations.
- Story failure fixtures reject with a `CoreError` shape (`{ kind, message }`). Story
  interactions use the plain-DOM `settled` helper pattern of `LeaveRoomConfirm.stories.svelte`.

**Live account: READ-ONLY.** You may run the app, post a **local** notification (a fixture
`notification_post` call, or a real one from roster activity), click a notification's body to open
a room, record audio, and stage, mark and discard a recording. You may **not** press an answer
button on a real decision notification, send an answer, or send a voice note or any message. Prove
each of those with fakes. List every skipped write under "Live verification pending".

**This machine (owner rules, 2026-10-22):**
- Every heavy command takes the shared lock:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock <command>`. Heavy means any
  `cargo build|test|clippy` and `./scripts/snapshot-stories.sh`. Light (no lock) means
  `pnpm test`, `pnpm check`, `cargo fmt`, git and grep. Also
  `export CARGO_BUILD_JOBS=8` and
  `export CARGO_TARGET_DIR=/home/rakeshgangwar/Projects/superjackfruit/supermessage/target`.
- `/tmp` is RAM, so put nothing large there.
- Snapshot gate: the 7 frames listed in `.superpowers/sdd/m2-desktop/env-desktop.md` already
  differ on this machine. Never re-record them and never `--record`. Copy only the inspected
  `.snapshots-web/<story-id>.png` into `src/lib/stories/baseline/`.

**Desktop verification for every task:**
- `pnpm test`, `pnpm check`, and the gate:
  `flock -w 7200 $HOME/.cache/supermessage-heavy.lock ./scripts/snapshot-stories.sh`.
- Each task touches `src-tauri/`, so also run, each under the lock,
  `cargo test --workspace` and
  `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and run
  `cargo fmt --all --check`.
- Running the app: `pnpm dev` (port 1420) in the background, then
  `$CARGO_TARGET_DIR/debug/supermessage` built from this worktree (`cargo build -p supermessage`
  under the lock). The embedded WebDriver listens on `127.0.0.1:4445`. Never verify with a bare
  `cargo run`. Window widths for RF3 are 1905px and 600px, with text size Largest (150%).
- The desktop session here is **KDE Plasma** (`XDG_CURRENT_DESKTOP=KDE`). GNOME behaviour is
  checked by the owner, or noted as unverified.

**Serialize:** tasks marked **[page]** edit `src/routes/+page.svelte`, and **[composer]** edit
`Composer.svelte`/`ComposerCapsule.svelte`. Tasks run in plan order.

**Commits** end with the attribution lines in the session's system reminder.

## Review Focus

1. **An answer whose send fails** (roadmap RF4). The reader gets "Your answer wasn't sent" /
   "Open the conversation to answer it there." (`Supermessage/Platform/LocalNotifier.swift:93-99`).
   Nothing says it was sent, and the card in the room stays answerable. Pinned in Task 3.
2. **A button that does something other than its label.** "Allow always" is never "Allow once",
   and an option the gate does not offer is never sent (`SupermessageKit/Notifications/NotificationRouter.swift:113-117`).
   "Request changes" has no button on desktop, because XDG has no text input, so the reader types
   it in the app. Pinned in Task 3.
3. **Sign out and back in as another account** (RF5). Every notification is withdrawn. The notified
   set, roster baseline and mode cache are forgotten. Linux action waiters are closed. The title
   count goes back to "supermessage". Pinned in Tasks 1, 2 and 4.
4. **The room on screen.** A focused window showing a room never notifies for it
   (`NotificationComposer.swift:164-166`), and an unfocused one does. Pinned in Task 2.
5. **A narrow window and the largest text** (RF3): the recording pill at 600px and 150%. Pinned by
   stories in Task 5.

---

### Task 1: Notification plumbing: the plugin, the Linux poster, permission, and the room-addressed gate sender

No prerequisite. It adds the posting surface, the permission ask, and `send_gate_decision_to`.

**iOS behaviour this ports:**
- `Supermessage/Platform/PlatformCoordinator.swift:124-131, 161-173`: the permission is asked
  after a fresh sign-in, never at launch or on a restore.
- `Supermessage/Platform/LocalNotifier.swift:65-85`: a notification has a title, an optional
  subtitle, a body and its category, and the request identifier is the note's id, so the same id
  replaces.
- `LocalNotifier.swift:101-107`: on sign-out every delivered notification is removed.
- `C/session.rs:451` `send_gate_decision_to`: the same content as the card's, sent to a room that
  need not be open.

**Files:**
- Modify: `src-tauri/Cargo.toml`. Add `tauri-plugin-notification = "2.7.0"`, and under
  `[target.'cfg(target_os = "linux")'.dependencies]` add `notify-rust = "4"` with a comment: the
  plugin's own Linux backend, used directly for actions (Q4). Run `cargo deny check licenses`
  (`src-tauri/deny.toml`) and record new crates and licences.
- Create: `src-tauri/src/notify.rs`, containing:
  - `pub trait Poster: Send + Sync { fn post(&self, note: &PostedNote) -> Result<(), String>; fn withdraw_all(&self); }`;
  - `pub struct PostedNote { id, room_id, title, body, actions: Vec<PostedAction> }`;
  - `XdgPoster` (Linux, `notify-rust`);
  - `PluginPoster` (macOS/Windows, `NotificationExt`);
  - `fn body_with_subtitle(subtitle: Option<&str>, body: &str) -> String`. A desktop
    notification has a summary and a body only, so the subtitle leads the body on its own line
    *(no iOS copy; layout)*.
- Create: `src-tauri/src/commands_notify.rs`, with:
  - `notification_permission(prompt: bool) -> String` (`granted`/`denied`/`prompt`);
  - `notification_clear_all()`;
  - `send_gate_decision_to(room_id, gate_id, option_id, comment: Option<String>, in_reply_to, prompt) -> ()`.
- Modify: `src-tauri/src/lib.rs`. Register the plugin and the commands, and manage
  `Arc<dyn Poster>`. On Linux choose `XdgPoster`, otherwise `PluginPoster`.
- Modify: `src/lib/ipc.ts` and `ipc.test.ts`, with the wrappers `notificationPermission(prompt)`,
  `notificationClearAll()` and `sendGateDecisionTo(…)`.
- Modify: `src/lib/stores/sessionReset.ts` and its test (call `notificationClearAll`).
- Modify: `src/routes/+page.svelte` **[page]**. `consumeFreshSignIn()` (`stores/freshSignIn.ts`)
  clears the mark as it reads it, so read it **once** into a local, then pass it to both the
  recovery set-up and `notificationPermission(fresh)`.
- Create: `docs/superpowers/plans/2026-10-22-parity-m6-desktop.md`, a copy of this plan (R10).

**Interfaces:**
- `XdgPoster`:
  - It reads `notify_rust::get_capabilities()` once and keeps `actions: bool`.
  - `post` builds a `notify_rust::Notification` with appname "supermessage", summary = title,
    body = `body_with_subtitle`, and category hint `im.received`.
  - When `actions` is true, it adds `"default"` labelled "Open"
    (`LocalNotifier.swift:44-45`), plus each `PostedAction` whose spec has no text input.
  - It keeps a map `note.id → u32` so a repost replaces (`.id(existing)`). It keeps at most **16**
    live action waiters (one `spawn_blocking` each, `handle.wait_for_action`); the 17th closes the
    oldest notification rather than leave dead buttons on screen.
  - Each action is sent on an `mpsc` channel as `(action_id, user_info)`. Task 3 consumes it.
- `withdraw_all` closes every handle it knows, and the plugin's equivalent on other OSes.

- [ ] **Step 0:** confirm `Session::send_gate_decision_to`'s signature on the base, and that
  `commands_session.rs:144` is `send_permission_answer`. Confirm `notify-rust`'s resolved version
  after the plugin is added (`cargo tree -i notify-rust`, light), and use the same major.
- [ ] **Step 1: Write the failing tests.**
  - Rust (`notify.rs` `#[cfg(test)]`, with a fake XDG backend trait behind `XdgPoster`):
    - `a_repost_with_the_same_id_replaces`;
    - `without_the_actions_capability_no_buttons_are_added`;
    - `a_text_input_action_is_never_a_button` ("Request changes");
    - `the_seventeenth_waiter_closes_the_oldest`;
    - `withdraw_all_closes_every_handle`;
    - `body_with_subtitle_leads_with_the_subtitle`.
  - Rust (`commands_notify.rs`): `send_gate_decision_to_refuses_a_bad_room_id_at_parsing`. It
    expects `kind: protocol` and nothing to reach the network (the M3 registration-probe rule).
  - Vitest: `ipc.test.ts`, one test per wrapper, with exact command names and camelCase args.
    `sessionReset.test.ts`: `resetSessionState withdraws every notification`.
    `freshSignIn` use: `a fresh sign-in asks for notifications once and still sets up recovery`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the checks** (the full desktop set, including the cargo trio under the lock).
  - **Falsify:**
    - drop the id map → `a_repost_with_the_same_id_replaces` fails;
    - add text-input actions → `a_text_input_action_is_never_a_button` fails;
    - consume the fresh mark twice → the fresh-sign-in test fails;
    - skip `notificationClearAll` in the reset → its test fails.
  - Live (read-only), in the running app: over WebDriver (`window.__TAURI_INTERNALS__.invoke`),
    call `notification_permission {prompt: false}` and record the result (expect `granted` on
    Linux). Registration probe: `send_gate_decision_to {roomId: "not-a-room-id", gateId: "g",
    optionId: "approve", comment: null, inReplyTo: "$e", prompt: "p"}` must reject with
    `kind: "protocol"`. If it resolves, **stop and report BLOCKED**. Nothing is posted in this
    task; Task 2 adds `notification_post`.
- [ ] **Step 5: Commit.** `desktop: notification plumbing, permission, room-addressed gate sender`.

### Task 2: Local notifications, worded by the core **[page]**

Needs CP1 and Task 1.

**iOS behaviour this ports** (the rules are CP1's; the lifecycle stays host):
- `Supermessage/Platform/PlatformCoordinator.swift:93-117`: becoming active starts a 5-second
  catch-up (`:95`) during which roster changes are not news (`:226`) and the timeline's "since"
  moves to now (`:239`).
- `:214-220`: the context. On desktop, the open room and the subscribed timeline room are the same
  room (the selected one), "active" is window focus, and `remotePush` is always false.
- `:222-250`: roster → `notifications_for_roster`. The focused timeline →
  `notifications_for_focused`, with "since" reset on a room change (`:235-238`). Every returned
  event id is recorded as notified whether or not it is delivered (`:245-248`).
- `:252-269`: each note passes `notification_delivers(note, mode)`, with the room's mode from
  `room_info` cached for 60 s (`:264-265`).
- `SupermessageKit/Notifications/NotificationComposer.swift:94-104`: a note's id is the event id,
  so the same event never stacks.

**Files:**
- Modify: `src-tauri/src/commands_notify.rs`. Add:
  - `notifications_for_roster(previous: Vec<RoomRow>, next: Vec<RoomRow>, context: NotificationContext) -> Vec<PlannedNotification>`;
  - `notifications_for_focused(room_id, room_name, since_ms: u64, already_notified: Vec<String>, context) -> Result<Vec<PlannedNotification>, CoreError>`.
    It reads `FocusedTimeline::snapshot()` exactly as `acknowledgement::for_focused` does
    (`C/acknowledgement.rs:161-182`). `TimelineRow` is `Serialize`-only (`C/dto.rs:847`), so rows
    never come back from the webview. A focused room other than `room_id` is `RoomChanged`.
  - `notification_delivers(note, mode: Option<NotificationMode>) -> bool`;
  - `notification_post(note: PlannedNotification) -> Result<(), String>`. It maps the note to
    `PostedNote`, taking actions from `notification_actions(note.category)` and putting
    `notification_user_info(note)` in each action.
- Create: `src/lib/stores/windowFocus.svelte.ts`. It holds `focused`, from `document.hasFocus()`
  plus `focus`, `blur` and `visibilitychange`, the pattern of `Timeline.svelte:675-694`, and
  `onChange`. Leave `Timeline.svelte`'s own copy alone.
- Create: `src/lib/notifier/notifier.svelte.ts` and `notifier.test.ts`. The coordinator takes
  injected deps (the commands, `roomInfo` for the mode, and a clock).
- Modify: `src/lib/ipc.ts` and `ipc.test.ts`, adding the four wrappers and the types
  `NotificationContext`, `PlannedNotification`, `NotificationCategory`, `PermissionAnswers` and
  `GateAnswers`.
- Modify: `src/routes/+page.svelte`. Start the notifier after the session is live. Feed it
  `roomsStore`'s rows, the timeline store's revision for the selected room, `selectedId`, and
  `windowFocus`. Reset it in `resetSessionState`.

**Interfaces (`notifier.svelte.ts`):**
- `createNotifier(deps)` returns `{ roomsChanged(rows), timelineChanged(roomId, roomName), focusChanged(focused), selectedChanged(roomId), reset(), get notified(): ReadonlySet<string> }`.
- `CATCH_UP_MS = 5000` and `MODE_CACHE_MS = 60_000`, each with its iOS line in a comment.
- Timeline calls are **trailing-throttled to one per 250 ms**. A streaming turn re-emits the
  timeline several times a second, and iOS reads in-process rows where this is an IPC call
  *(desktop-only rule)*.

- [ ] **Step 0:** grep the CP1 names in the core (`C/notification_policy.rs`). Confirm the
  timeline store exposes a revision or an equivalent change signal for the selected room.
- [ ] **Step 1: Write the failing tests.**
  - Vitest `notifier.test.ts`, with fake commands that record and a fake clock:
    - `the first roster is a baseline`;
    - `coming back to the window is not a burst`: within 5 s nothing is posted, after 5 s one note;
    - `the mode is asked once a minute`;
    - `a held-back row is still recorded as notified`;
    - `a room switch starts a new since`;
    - `the focused room in a focused window is passed as open and active`, asserting the
      `context` sent;
    - `an unfocused window passes appActive false`;
    - `a burst of timeline changes is one call per 250 ms`;
    - `a RoomChanged refusal is ignored`;
    - `reset forgets the baseline, the notified set and the modes` (RF5).
  - Rust:
    - `notifications_for_focused_reads_the_cores_rows` (a seeded `FocusedTimeline` fixture, as
      the acknowledgement tests seed one);
    - `notifications_for_focused_refuses_another_room`;
    - `notification_post_carries_the_cores_actions_and_user_info`, with a recording `Poster`: a
      PERMISSION note has the actions `sm.permission.allowOnce` and `sm.permission.reject`, and
      the user info has `sm.allowOptionId`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - set `CATCH_UP_MS = 0` → the burst test fails;
    - record notified only when delivered → its test fails;
    - remove the throttle → the 250 ms test fails;
    - send `appActive: true` always → the unfocused test fails.
  - Live (read-only): with the window unfocused for a minute on the roster, real local
    notifications appear with the core's words. Click one: the window comes forward (Task 3 wires
    the click; until then, note that it does not) and screenshot it. Check RF4 of this plan: with
    a room selected and the window focused, a new message there posts nothing.
- [ ] **Step 5: Commit.** `desktop: local notifications worded by the core`.

### Task 3: Answer from a Linux notification; click to open

Needs CP1 and Tasks 1–2. **It is proven with fakes:** pressing a real decision's button on the live
account is a write.

**iOS behaviour and copy:**
- `Supermessage/Platform/LocalNotifier.swift:23-61` (now `notification_actions`): "Allow once",
  "Reject", "Approve", "Request changes", "Reject" and "Open". Desktop drops "Request changes",
  because XDG has no text input (Q4). GATE's "Open" and the body click (`"default"`) both open.
- `SupermessageKit/Notifications/NotificationRouter.swift:113-160` (now `notification_response`):
  anything that cannot be answered exactly as labelled opens instead.
- `SupermessageKit/Notifications/NotificationAnswerer.swift:19-51`: the answer is sent straight to
  its room and bounded. The desktop session is already live, so there is no quiet restore. The
  budget is 15 s, which is iOS's (`:22`). A desktop has no system limit, but past that time the
  reader is better told to look.
- `Supermessage/Platform/AppDelegate.swift:172-180` and `LocalNotifier.swift:87-99`: failure posts
  "Your answer wasn't sent" / "Open the conversation to answer it there." (now
  `answer_failed_notification`).
- `NotificationRouter.swift:4-31`: opening means the window comes forward and the room is selected.
  Nothing else moves the selection.

**Device unlock:** iOS requires unlock to answer (`LocalNotifier.swift:18-21`). A desktop
notification server shows actions only in an unlocked session. Whether a lock screen exposes them
is checked by the owner on KDE and GNOME. It is not claimed here.

**Files:**
- Create: `src-tauri/src/notify_actions.rs`, containing:
  - `pub trait Answerer { async fn permission(&self, room_id: &str, option_id: &str) -> CoreResult<()>; async fn gate(&self, room_id: &str, gate_id: &str, option_id: &str, comment: Option<&str>, in_reply_to: &str, prompt: &str) -> CoreResult<()>; }`,
    implemented for `Session`;
  - `pub trait Opener { fn open_room(&self, room_id: &str); }`, implemented over the main window:
    `unminimize`, `show`, `set_focus`, then emit `sm://notification/open` `{ roomId }`;
  - `pub async fn handle(action_id: String, user_info: HashMap<String, String>, answerer: &impl Answerer, opener: &impl Opener, poster: &dyn Poster)`.
- Modify: `src-tauri/src/lib.rs`. Spawn the consumer of Task 1's channel in `setup`.
- Modify: `src/lib/ipc.ts`. Add `onNotificationOpen(handler)` listening on
  `"sm://notification/open"`.
- Modify: `src/routes/+page.svelte` **[page]**. On `onNotificationOpen`, select the room through
  the existing selection path.
- Tests: Rust in `notify_actions.rs`, Vitest in `ipc.test.ts`, and a page-level test if the
  selection path has one.

- [ ] **Step 1: Write the failing tests** (Rust, with a recording `Answerer`, `Opener` and
  `Poster`):
  - `allow_once_sends_the_permission_option_to_its_room`;
  - `approve_sends_the_gate_decision_referencing_the_gate_event`: `in_reply_to` is the gate's
    event id, and the prompt is passed through;
  - `reject_sends_the_gate_reject_option`;
  - `an_option_the_gate_does_not_offer_sends_nothing_and_opens`;
  - `the_body_click_opens_the_room`;
  - `a_refused_answer_posts_your_answer_wasnt_sent`: the id is `"{room}#answer-failed"`, the
    title and body are verbatim, and nothing else is posted (RF4);
  - `a_send_that_hangs_is_given_up_on_within_the_budget`: a fake that never resolves, a budget
    shortened to 100 ms for the test → the failure note is posted;
  - `a_dismissal_does_nothing`.
  - Vitest: `onNotificationOpen listens on sm://notification/open`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.** The budget is `tokio::time::timeout(Duration::from_secs(15), …)`. The
  send itself is not cancelled. Its later success is harmless, which is the honest side to err on
  (`NotificationAnswerer.swift:13-18`).
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - remove the failure post → `a_refused_answer_posts_your_answer_wasnt_sent` fails;
    - in the handler, send a hard-coded `"approve"` instead of the answer's `option_id` →
      `approve_sends…` still passes, but `allow_once_sends…` and the reject case fail (add
      `reject_sends_the_gate_reject_option` so this mutation is caught);
    - drop the timeout → the hang test fails (three runs).
  - Live (read-only): wait for or produce a **message** notification (not a decision) and click its
    body. The window comes forward on that room. Screenshot it. Then check with
    `busctl --user call org.freedesktop.Notifications /org/freedesktop/Notifications org.freedesktop.Notifications GetCapabilities`
    that KDE lists `actions`, and record it.
  - Live verification pending (owner): press Approve on a real gate notification on KDE and on
    GNOME, then press Approve offline and see "Your answer wasn't sent".
- [ ] **Step 5: Commit.** `desktop: answer from a Linux notification; click opens the room`.

### Task 4: The window-title count (G4) **[page]**

No core prerequisite. Q5's default is the Needs you badge count.

**Behaviour:**
- The count is `NeedsYouInbox.badge_count` (`C/roster.rs:441-444`): decisions only, because "an
  invitation alone must not light the badge". It is the same number the M5 sidebar badge shows.
  Read it from M5's inbox state, never a second `roster_needs_you_inbox` call (Step 0).
- The title is "(3) supermessage" when the count is positive and "supermessage" at zero
  *(no iOS copy; iOS's equivalent is the app badge)*.
- Rust only sets the title when it changes.
- Sign-out returns the title to "supermessage".

**Files:**
- Create: `src-tauri/src/attention.rs`. `pub fn title_for(needs_you: u32) -> String`, and a command
  `set_attention(needs_you: u32)` that sets the main window's title. M7 adds the tray to this same
  command.
- Modify: `src-tauri/src/lib.rs` (registration).
- Create: `src/lib/stores/attention.svelte.ts` and `attention.test.ts`.
- Modify: `src/lib/ipc.ts` and `ipc.test.ts` (`setAttention`), `stores/sessionReset.ts`
  (`setAttention(0)`), and `+page.svelte` (wiring the M5 badge count into the store).

- [ ] **Step 0:** find M5's badge count source (`grep -rn "badgeCount" src/lib`).
- [ ] **Step 1: Write the failing tests.**
  - Rust: `title_for_zero_is_the_app_name`; `title_for_three_leads_with_the_count`.
  - Vitest `attention.test.ts`:
    - `the title counts decisions, not invitations` (an inbox with 2 decisions and 1 invitation
      gives `setAttention(2)`);
    - `the title is set only when the count changes`;
    - `signing out clears the title`.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - use `count` instead of `badgeCount` → the invitations test fails;
    - drop the change check → the second test fails.
  - Live (read-only): with the live account's real pending decisions, read the window title over
    WebDriver with `window.__TAURI_INTERNALS__.invoke("plugin:window|title", {label: "main"})`. If
    the capability refuses it, screenshot the taskbar instead. Compare it with the sidebar badge.
- [ ] **Step 5: Commit.** `desktop: Needs you count in the window title`.

### Task 5: C2 voice recording, and the verification table **[composer]**

Needs CP2, and CP4 only if Step 0 says so. Recording, staging and marking are local. Sending is a
write, proven with fakes.

**iOS behaviour and copy:**
- `SupermessageKit/VoiceRecordingFormat.swift:4-27` (now `voice_recording_spec`): Opus, 48 kHz
  mono, 24 kbit/s. The core sends a marked Ogg/Opus as it is, reads its length from the file, and
  sends it as `audio/ogg` (`C/attachments.rs:910-922`).
- `Supermessage/Composer/VoiceRecorder.swift`:
  - `:78-81`: "Microphone access is off. Turn it on in Settings to record.";
  - `:100`, `:116`: "Couldn't start recording.";
  - `:46-47`: sample every 100 ms, with at most 60 levels (now `voice_waveform`);
  - `:130-133`: under 600 ms is thrown away (now `voice_long_enough`);
  - `:173-182`: a start abandoned during the permission prompt, and cancel.
- `Supermessage/Composer/ComposerView.swift`:
  - `:189-197`: the mic shows when the field is empty and nothing is staged;
  - `:199-216`: a tap records. A pointer hold of 300 ms (`:422`) records held, release sends, and
    a drag 80px left (`:401`) cancels. Playing audio stops first (`:204`, `:207`);
  - `:236`: "Send voice message"; `:441`: "Record voice message";
  - `:448-500`: the pill. A pulsing `danger` dot (none under reduced motion), the elapsed time
    (core `elapsed_label`, `ElapsedTime` in M1b C1), "Slide left to cancel" or "Recording"
    (`:477`), and "Discard recording" (`:490`);
  - `:304-317`: stage → mark voice → send with no text.
- macOS microphone usage string, verbatim from iOS: "Supermessage records voice messages you
  choose to send." (`apple/project.yml:186`).

**Files:**
- Modify: `src-tauri/Cargo.toml`. Add `webkit2gtk = "2.0"` (Linux target; `2.0.2` is already in
  `Cargo.lock` through wry).
- Modify: `src-tauri/src/lib.rs`. In `setup`, on Linux, through `window.with_webview`:
  - set `enable-media-stream` true;
  - connect `permission-request`. Allow **only** a `UserMediaPermissionRequest` that is audio-only,
    and only while the view's URI passes `is_app_origin` (`lib.rs:87`). Deny everything else.
- Create: `src-tauri/Info.plist` (merged by Tauri into the macOS bundle) with
  `NSMicrophoneUsageDescription`. Modify `src-tauri/tauri.conf.json` so `bundle.linux.deb.depends`
  and `bundle.linux.rpm.depends` include the GStreamer base and good plugin packages (Q9).
- Create: `src-tauri/src/commands_voice.rs`, with:
  - `attachment_stage_recording(request: tauri::ipc::Request)`: a **raw body**, with the room id
    and mime in the `x-room-id` and `x-mime` headers;
  - `attachment_mark_voice(room_id, token, duration_ms: u64, waveform: Vec<f32>)` over
    `StagedAttachments::mark_voice` (`C/attachments.rs:202`);
  - `voice_recording_spec()`;
  - `voice_waveform(decibels: Vec<f32>, bins: u32)`.

  Staging writes `app_cache_dir/recordings/<uuid>/Voice message.<ogg|webm>`, refuses a body over
  25 MB, then calls `focused.verify_focus(room_id)` and `attachments::stage_path`. Sweep
  `recordings/` at startup and in the logout command.
- Create: `src/lib/voice/voiceRecorder.ts` and `.test.ts`. It is the state machine, a port of
  `VoiceRecorder.swift:18-190`, over an injected
  `Capture { start(): Promise<void>; stop(): Promise<Blob>; levelDb(): number }`.
- Create: `src/lib/voice/micCapture.ts`. `getUserMedia({audio: true})`, then `MediaRecorder` with
  the type Step 0 chose, plus an `AnalyserNode` for RMS → dBFS. **Silence is −160, never
  `-Infinity`**, because JSON turns `-Infinity` into `null`, which `Vec<f32>` refuses.
- Create: `src/lib/components/composer/MicButton.svelte`, `RecordingPill.svelte`, and their
  stories: idle, recording by tap, held, discard focused, the failure line, at 600px, and at
  Largest.
- Modify: `src/lib/components/Composer.svelte` and `ComposerCapsule.svelte` **[composer]**, plus
  `src/lib/ipc.ts` and `ipc.test.ts`.
- Create: `.superpowers/sdd/m6-desktop/m6-desktop-verification.md`.

- [ ] **Step 0: Probe in the running app (read-only),** over WebDriver:
  `typeof MediaRecorder`, `!!navigator.mediaDevices?.getUserMedia`, and
  `MediaRecorder.isTypeSupported(t)` for `audio/ogg;codecs=opus`, `audio/webm;codecs=opus`,
  `audio/mp4;codecs=opus` and `audio/mp4`. Record all of them.
  - Ogg/Opus supported: no core change.
  - WebM/Opus only: **stop and report**, so the controller files CP4. Continue once it merges.
  - Neither, or no `MediaRecorder`: **stop and report BLOCKED** for Q9.
- [ ] **Step 1: Write the failing tests.**
  - Vitest `voiceRecorder.test.ts`:
    - `a slip of the thumb is thrown away`;
    - `a recording carries its length and the core's waveform` (the fake `voiceWaveform` receives
      the sampled dB);
    - `silence is sent as -160, never -Infinity`;
    - `a refused microphone says so` (copy verbatim);
    - `a hold released before recording starts is abandoned`;
    - `discard throws it away and stages nothing`;
    - `send stages, marks, then sends with no text`: the order is
      `attachmentStageRecording` → `attachmentMarkVoice` → `attachmentSend`;
    - `recording stops playback first`.
  - Vitest `ipc.test.ts`: one test per new wrapper, and that the raw body sends the room id and
    mime as headers.
  - Rust:
    - `stage_recording_refuses_another_room` (`RoomChanged`);
    - `stage_recording_refuses_an_oversized_body`;
    - `stage_recording_names_the_file_voice_message`;
    - `the_permission_handler_allows_only_audio_for_the_app_origin`, with the predicate factored
      as `fn allows_user_media(audio: bool, video: bool, uri: &Url) -> bool`.
  - Stories: the pill and mic states above, with interactions for tap → pill, the discard button,
    and the hold timer under a fake clock.
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the checks.**
  - **Falsify:**
    - allow video in `allows_user_media` → its test fails;
    - send `-Infinity` → the silence test fails;
    - skip the mark → the order test fails;
    - skip the 600 ms rule → the slip test fails.
  - Live (read-only), Linux:
    1. Grant nothing by hand. The handler must allow the microphone with no prompt.
    2. Record 3 s in a real room by tap and see the pill tick.
    3. Discard. No file stays under `<app_cache_dir>/recordings/` after the next start's sweep.
    4. Repeat at 600px and at Largest, and screenshot the pill.

    Staging calls the homeserver only to read its upload limit, which is a read, so steps 2–3 may
    stage. Never press send. Live verification pending: sending a voice note, and Element X
    playing it.
  - Write `m6-desktop-verification.md` with the rows P2 (Linux answer, macOS/Windows info-only,
    CI), P4 title, C2, and the owner checklist:
    - an Approve press on KDE and on GNOME;
    - the failure path offline;
    - whether the lock screen exposes actions;
    - a sent voice note playing in Element.
- [ ] **Step 5: Commit.** `desktop: record voice messages (C2); M6 verification table`.
