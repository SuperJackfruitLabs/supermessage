# Parity M1b — Turn Record and Timeline Rules into the Core: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** move the iOS turn record (M1.3: T6, A2) and the iOS timeline grouping rules
(M1.4: D5, D6, T1) into `supermessage-core`. iOS switches to the core version and its Swift
copies are deleted. The Android and desktop copies of the grouping rules are replaced in the
same PR.

**Execution:** Core track, worktree `parity-core`. There are three PRs, developed in parallel
and **merged one at a time, in this order** (ruling 9 and ruling 10). Each PR rebases on
`main`, regenerates both bindings and re-runs the iOS CI job before it merges.

| PR | Branch | Tasks | Merges |
|---|---|---|---|
| 1 — M1.3 turn record | `parity/m1-core-turns` | C1–C5 | after M1a PR1–PR5 (`parity/m1-core-avatar`, `…-send-outcome`, `…-roster-views`, `…-cast`, `…-first-run`) |
| 2 — M1.4a row facts | `parity/m1-core-row-facts` | C6–C7 | after PR 1 |
| 3 — M1.4b grouping pass | `parity/m1-core-timeline-grouping` | C8–C12 | last PR of M1 |

**Architecture:**
- **M1.3.** A session-owned `core::turns::TurnBook` keeps one turn per room for every room.
  It is fed by `core::live`'s handlers, by the focused timeline's diffs (an agent's reply ends
  a turn) and by a 30-second sweeper (a time limit). Finished records are kept for 20 rooms,
  and the book is cleared on logout. Hosts get a small `CoreEvent::Turn(TurnUpdate)` (a
  summary plus at most one step change) and query `Session::turn(room_id)` for a full
  snapshot when a room opens. The labels are pure free functions.
- **M1.4.** Per-row facts (`is_agent`, `is_long_read`) are computed once, in
  `TimelineRow::with_view`. Neighbour-dependent facts (sender runs, reply-quote hiding, runtime
  suffix, membership runs and stretches) are computed by a settle pass that runs last in
  `timeline.rs` `emit_ops`. Each host keeps only the set of stretches the reader has opened,
  plus a mechanical walk that turns row fields into display entries.

**Tech Stack:** Rust (matrix-sdk 0.18, UniFFI 0.28, `unicode-segmentation` 1.13), Tauri 2
with Svelte 5 and Vitest, Kotlin/Compose, and SwiftUI, which is verified on CI only.

**Spec:** the roadmap, `docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md` §M1.3–M1.4;
the controller's rulings, `.superpowers/sdd/planning-m1-m2/m1-rulings.md` (binding, 1–12); the owner decisions and controller rulings, `.superpowers/sdd/planning-m1-m2/owner-decisions.md` (O1, R7, R8, R9, R10); and the
inventory, `.superpowers/sdd/planning-m1-m2/m1-inventory.md` §0, §3 and §4. Line numbers in
this plan were checked against `parity/m0-defects`.

Paths: `C=crates/supermessage-core/src`, `F=crates/supermessage-ffi/src`, `K=apple/SupermessageKit`,
`KT=apple/SupermessageKitTests`, `APP=apple/Supermessage`,
`AK=android/kit/src/main/kotlin/dev/supermessage/kit`, `AKT=android/kit/src/test/kotlin/dev/supermessage/kit`,
`AA=android/app/src/main/kotlin/dev/supermessage`, `AAT=android/app/src/androidTest/kotlin/dev/supermessage`.

## Global Constraints

- Everything in the roadmap's Global Constraints applies. That includes tokens only, amber
  only for decisions, and no client-private event types. A new Android UI state ships with a
  `@Preview`, and a new desktop UI state ships with a story whose baseline is recorded once,
  after inspection.
- **Ruling 1:** the target is iOS behaviour and copy, verbatim, except where the core already
  decides the same thing. Agent detection on a row is `item_view::agent_name_from_id`, not
  iOS's `hasPrefix("@agent_")`.
- **Ruling 2:** the turn's current step, completed count and step chain are the ones
  `LiveTurnView` uses (`APP/Timeline/LiveTurnView.swift:194-207`). The two duration formats
  stay two functions: `elapsed_label` (from `ElapsedTime.label`) and `turn_duration_label`
  (from `TurnRecordLabel.duration`).
- **Ruling 3:** the visible changes on Android and desktop are accepted. They are listed under
  "Android and desktop behaviour changes", and their tests and fixtures are updated in PR 3.
- **Ruling 4:** `unicode-segmentation = "1.13"` becomes a direct core dependency in PR 2.
  The licence gate must pass.
- **Ruling 8 (amended by R9):** every PR includes the following. The fixture/test helpers
  `group_timeline_rows` and `long_read_graphemes` are UniFFI-only; no Tauri command is added
  without a desktop caller.
  - A Rust module, with each iOS test ported under a snake_case version of its name.
  - A `#[uniffi::export]`, a Tauri command registered in `src-tauri/src/lib.rs`, and an
    `ipc.ts` wrapper (except the two UniFFI-only helpers above).
  - Both bindings regenerated and committed: Swift with the Linux recipe in `AGENTS.md`
    ("Changing the FFI surface…"), and Kotlin with `./scripts/build-android-libs.sh`.
  - iOS switched to the core version, with the Swift copy deleted.
  - Android and desktop are touched only where they hold a copy of the rule.
- **Copy, verbatim from iOS:**
  - Record label: `"Thought for 12s · 2 steps"`, `"Thought for 12s"`, `"Thought"`,
    `"3 steps · 1m 5s"`, `"1 step"`, `"2 steps"`. The separator is `" · "` (U+00B7).
  - Step titles: `"Writing the answer"`, `"Thinking"`, `"Starting"`.
  - Membership: `"Someone"`, `"updated their membership"`, `" and "`,
    `"{n0}, {n1} and {k} other|others {verb}"`, `"{name} {v0}, then {v1}"`,
    `"{name} made {n} membership changes"` and `"{n} membership changes · {named}"`.
- **Constants:**

  | Constant | Value |
  |---|---|
  | `KEPT_RECORDS` | `20` |
  | `GIVE_UP_AFTER_MS` | `30 * 60 * 1000` (O1: decided, no iOS counterpart) |
  | `REPLY_SKEW_MS` | `60_000` |
  | `SWEEP_EVERY` | 30 s |
  | `RUN_WINDOW_MS` | `5 * 60 * 1000` |
  | `MAX_NAMED` | `2` |
  | `STRETCH_COLLAPSE_THRESHOLD` | `3` |
  | `LONG_READ_GRAPHEMES` | `600` |

- **Wire format:** every new `TimelineRow` field has a default. A `bool` field carries
  `#[uniffi(default = false)]` and `#[serde(skip_serializing_if = "std::ops::Not::not")]`.
  An `Option` field carries `#[uniffi(default = None)]` and
  `#[serde(skip_serializing_if = "Option::is_none")]`. This is the `voice_reply` precedent in
  `C/dto.rs`. Existing Swift, Kotlin and TypeScript fixtures compile unchanged, and the
  desktop JSON only gains a key when it is true or present. The TypeScript mirror declares
  each new field optional.
- **Falsification:** for every ported or new test, mutate the line it pins, watch the test
  fail, restore it, and record what you saw in the commit body. For ordering and boundary
  tests (tool seq, time limit, skew, grapheme bound), run the mutated version three times.
- **Core verification:** run these from the repo root.
  - `cargo test -p supermessage-core`;
  - `cargo test --workspace`;
  - `cargo fmt --all --check`;
  - `cargo clippy --workspace --all-targets --all-features -- -D warnings`.
- **Bindings:**
  - Run the Swift recipe from `AGENTS.md`.
  - `export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/29.0.14206865" && ./scripts/build-android-libs.sh`
    takes about 15 minutes. Run it alone: one heavy build at a time.
  - Then `git status` must show changes under both `apple/Generated/` and
    `android/core/src/main/kotlin/uniffi/`.
- **Licence gate (PR 2):**
  `cargo deny --manifest-path src-tauri/Cargo.toml check licenses bans sources` passes. This
  is the same command CI's `licences` job runs.
- **Desktop verification:** `pnpm test`, `pnpm check` and `./scripts/snapshot-stories.sh`.
  PR 3 also checks the running app (`pnpm dev` plus `target/debug/supermessage`, WebDriver
  on `127.0.0.1:4445`): a room with membership churn shows the stretch line and opens it.
- **Android verification:**
  - First, `cargo build -p supermessage-ffi`. Then, from `android/`, run
    `./gradlew test :app:testDebugUnitTest`.
  - PR 3 also runs `scripts/android-ci-parity.sh`, then
    `./gradlew :app:connectedDebugAndroidTest` twice: once at pixel_6 height and once after
    `adb shell wm size 1080x1280`.
- **iOS verification:** the only iOS build is CI's `ios` job (`.github/workflows/ci.yml:335`,
  `runs-on: macos-15`). Its steps are:
  - the XCFramework build;
  - "The checked-in bindings match the Rust";
  - "Kit tests";
  - "The app and its UI tests compile";
  - "The previews still look like the baseline".

  Push the branch, then run `gh pr checks <n> --watch`, and read failures with
  `gh run view <run> --log-failed`. Never re-record a preview baseline to turn the job green.
  If the job is red for reasons outside the task, the switch-over goes to a follow-up PR (the
  roadmap's rule) and the core API still merges.

## Review Focus

1. **A bridge agent that answers by editing a progress message** (roadmap Review Focus 1).
   With no live deltas, no turn starts and nothing hangs. A turn that did start ends when the
   agent's edited or new message lands. Pinned in C2 by
   `a_bridge_that_only_edits_its_progress_message_starts_no_turn` and
   `an_agent_reply_ends_a_turn_that_never_sent_done`. Pinned in C3 by
   `an_edited_agent_message_ends_the_rooms_turn` and
   `a_tool_only_turn_ends_when_its_turn_card_lands`.
2. **A turn that never sends `done`**: the agent crashed, or the hub skipped `done` on a
   tool-only turn (`outbound.ts:508`). It must stop saying "Working". Pinned in C2 by
   `a_turn_silent_for_thirty_minutes_ends_at_its_last_activity`.
3. **Sign out, then sign in as another account** (roadmap Review Focus 5). No turn record
   from the first account may show. Pinned in C3 by `logout_forgets_every_turn`, and in C5
   by the Swift test "signing out forgets the turn on screen".
4. **A room with several agents and several humans** (roadmap Review Focus 2).
   - A human speaking mid-turn does not end the agent's turn. Pinned in C3 by
     `a_human_speaking_mid_turn_does_not_end_the_agents_turn`.
   - Runtime suffixes show once a second agent speaks. Pinned in C8 by
     `a_room_where_several_speak_keeps_it`.
5. **Back-paginating hundreds of rows into a long room.** The grouping pass must not double
   the IPC by re-sending every row it touched. Pinned in C9 by
   `a_back_paginated_batch_carries_its_grouping_in_its_own_ops` and
   `appending_a_message_sets_only_the_row_whose_run_it_ends`.

---

## M1.3 Design — turn record

**Data model:**
- `core::turns` (new file `C/turns.rs`) holds a `TurnBook`, a
  `Mutex<HashMap<String, TurnState>>` with one entry per room. A `TurnState` holds:
  - `turn_id`, `started_at_ms`, `ended_at_ms`, `last_activity_ms`, `ended_by`;
  - `answering`, the thought text and its seq;
  - the steps, merged by tool call id, each with its own last seq.
- `TurnBook` also keeps a `finished_order: VecDeque<String>` for retention.
- Every input is a `TurnInput` with an explicit `at_ms`, so the rules are pure and testable.
  The wiring supplies `turns::now_ms()`.

**Rules:**
- **Starting a turn:**
  - A non-done `Answer`, `Thought` or `Tool` for a room with no turn, or with a finished one,
    starts a new turn (`LiveStore.beginTurnIfFinished`). It gets a new `turn_id`, and the
    old record is dropped.
  - `Reply`, and a `done` with no turn, start nothing.
- **Activity:** `started_at_ms` is the first input's `at_ms`. `last_activity_ms` is the
  `at_ms` of every non-done input (`noteActivity`).
- **Ending a turn** sets `ended_by` and `ended_at_ms`, the first time only:
  - `Answer{done}` or `Thought{done}` ends it with `Done` at `at_ms` (iOS).
  - `Reply` ends it with `Reply`, unless `event_ms + REPLY_SKEW_MS < started_at_ms`.
  - `expire(now)` ends every in-progress turn with
    `now - last_activity_ms >= GIVE_UP_AFTER_MS` with `TimedOut`, at `last_activity_ms`.
- **Answer and thought:** `Answer{done}` sets `answering = false`. Thought text applies when
  `seq >= thought_seq`; its done keeps the text.
- **Tools:**
  - A tool is dropped (`accepted: false`) when `seq <= ` that tool id's last seq in this turn.
    This is the desktop rule from `src/lib/stores/live.svelte.ts:122-125`, and it fixes the
    "completed overtaken by in_progress" bug iOS has.
  - Otherwise the tool replaces the step at its index, or is appended.
- **Retention:**
  - When a turn finishes, its room id goes to the back of `finished_order`. While
    `finished_order.len() > KEPT_RECORDS`, the front room's record is removed.
  - In-progress turns are never evicted by count. The time limit bounds them.
- **Derived fields** (ruling 2, from `LiveTurnView`):
  - `current_step_title` follows this chain: the last `Running` step title, else the last
    `Queued` step title, else `"Writing the answer"` if answering, else `"Thinking"` if there
    is a thought, else `"Starting"`. `step_is_tool` is true for the first two.
  - `completed_steps` counts `Done` only. `failed_step` is the last `Failed` title.
    `total_steps` is the number of steps.
  - `record_label` is `Some(turn_record_label(has_thought, total_steps, Some(ended - started)))`
    once the turn has ended and has a thought or a step. Otherwise it is `None`. This is the
    `WhatIDidFooter` condition.

**Where it is fed:**
1. **`live::listen` handlers.** These see every room. After `LiveState.accept`, they call
   `turns.apply`. They emit the `Turn` update **before** the raw `Live`, `Thought` or `Tool`
   event, so a host that resets on a new `turn_id` does not wipe the text that started the
   turn. A tool the book refuses is not emitted at all. This replaces the "deliberately not
   de-duplicated" note at `C/live.rs:375-386`: the book now knows where a turn ends.
2. **`turn_watch::TurnSink`.** This wraps the focused timeline's sink in
   `Session::subscribe_timeline` (`C/session.rs:932`). After forwarding a `TimelineDiff`, it
   applies `Reply` for each row in `PushBack`, `Append` or `Insert`, or in a `Set` with
   `item.edited`, that passes `answers_a_turn`:
   - the row is not own, and either
   - it is a turn card (`item.detail == custom_events::TURN_ACTIVITY_EVENT_TYPE`), or
   - it is a `kind == "message"` row whose sender is agent-like:
     `agent_name_from_id(sender).is_some() || sender_name != sender_short`, where a differing
     short name means the bridge's runtime suffix.

   `Reset` and `PushFront` (history) never end a turn. `timeline.rs` is untouched by PR 1.
3. **`turn_watch::spawn_sweeper`.** Every `SWEEP_EVERY` it calls `turns.expire(now_ms())`
   and emits each update. It is started in `start_streams` and stopped in `logout`, like
   `ignore_watch`.

**Sign-out:** `Session::logout` calls `turns.clear()` first, beside `staged.clear()`, and
stops the sweeper. The book is session-owned (not a client handler context), so it survives
client rebuilds and must be cleared explicitly.

**Wire:**
- New `CoreEvent::Turn(TurnUpdate)`, which maps to `FfiEvent::Turn { update }` and to the
  Tauri channel `"sm://turn"` (`turns::TURN_EVENT`).
- A `TurnUpdate` carries no thought or answer text. Text keeps travelling on `Live` and
  `Thought`. The book emits an update only when the summary (excluding `revision`) changes or
  a step changes, so a growing thought or answer sends nothing new.
- `Session::turn(room_id) -> Option<TurnSnapshot>` returns the full record (summary, steps,
  thought) for a host opening a room.
- `revision` (monotonic per book) lets a host discard a snapshot or event that is older than
  what it holds.

**IPC cost:** about one ~200-byte update per tool report, plus three per turn (start,
answering, end). Today's `Tool` events still flow; stale ones are now dropped before IPC.
Tool input and output (up to 4,000 chars each, `C/live.rs:206`) cross once per change, in
`step`, never as the whole list.

**Host migration:**
- **iOS.** `LiveStore` becomes a mirror:
  - `apply(update)` for the focused room;
  - `restore(snapshot)` on focus;
  - answer and thought text from the `live` and `thought` events.

  `TurnRecordLabel.swift`, `ElapsedTime.swift` and `Notifications/LiveTurnSummary.swift` are
  deleted.
- **Desktop.** It has no copy of the record, retention, times or step rules
  (`reasoningLabel.ts` is the reasoning disclosure's own copy, so it is M4 UI). It does hold a
  copy of the tool-seq rule, which is deleted from `live.svelte.ts`. It gains the commands,
  `onTurn` and the types.
- **Android.** It has no copy. It gets an exhaustive-`when` branch for `FfiEvent.Turn` that
  does nothing until M4.
- **M1.1 follow-up (ruling 7):** `TurnBook::in_progress(room_id)` is provided for
  `room_status`. Wiring it into the roster is not in this plan.

### Interfaces produced by PR 1 (used by C1–C5)

```rust
// C/turn_label.rs
pub fn turn_record_label(thought: bool, steps: u32, elapsed_ms: Option<u64>) -> String;
pub fn turn_duration_label(elapsed_ms: u64) -> String; // TurnRecordLabel.duration: round half away, min 1s, "2m 5s"
pub fn elapsed_label(elapsed_ms: i64) -> String;       // ElapsedTime.label: floor, clamp ≥0, "1m 04s", "1h 02m"

// C/turns.rs  — all records: #[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
//                #[serde(rename_all = "camelCase")]; enums likewise with uniffi::Enum
pub struct TurnStep { pub id: String, pub title: String, pub kind: Option<String>, pub status: String,
    pub phase: crate::live::ToolPhase, pub status_label: String, pub locations: Vec<String>,
    pub input: Option<String>, pub output: Option<String>, pub seq: u64, pub has_detail: bool }
impl TurnStep { pub fn from_payload(p: &crate::live::ToolPayload) -> Self }
#[serde(tag = "kind")] pub enum TurnInput {
    Answer { seq: u64, done: bool, at_ms: u64 },
    Thought { seq: u64, text: String, done: bool, at_ms: u64 },
    Tool { step: TurnStep, at_ms: u64 },
    Reply { event_ms: Option<u64>, at_ms: u64 } }
pub enum TurnEnding { Done, Reply, TimedOut }
pub struct TurnSummary { pub room_id: String, pub turn_id: u64, pub revision: u64,
    pub in_progress: bool, pub ended_by: Option<TurnEnding>, pub started_at_ms: u64,
    pub ended_at_ms: Option<u64>, pub last_activity_ms: u64, pub answering: bool,
    pub has_thought: bool, pub total_steps: u32, pub completed_steps: u32,
    pub failed_step: Option<String>, pub current_step_title: String, pub step_is_tool: bool,
    pub record_label: Option<String> }
pub struct TurnStepChange { pub index: u32, pub step: TurnStep }   // index == len → append
pub struct TurnUpdate { pub summary: TurnSummary, pub step: Option<TurnStepChange> }
pub struct TurnSnapshot { pub summary: TurnSummary, pub steps: Vec<TurnStep>, pub thought: Option<String> }
pub struct TurnOutcome { pub accepted: bool, pub update: Option<TurnUpdate> } // core-internal, not FFI
#[derive(Default)] pub struct TurnBook { /* Mutex<…> */ }
impl TurnBook {
    pub fn apply(&self, room_id: &str, input: TurnInput) -> TurnOutcome;
    pub fn expire(&self, now_ms: u64) -> Vec<TurnUpdate>;
    pub fn snapshot(&self, room_id: &str) -> Option<TurnSnapshot>;
    pub fn in_progress(&self, room_id: &str) -> bool;
    pub fn clear(&self);
}
pub fn fold_turn(room_id: &str, inputs: Vec<TurnInput>) -> Option<TurnSnapshot>; // a fresh book; fixtures/previews
pub fn now_ms() -> u64;
pub const TURN_EVENT: &str = "sm://turn";

// C/turn_watch.rs
pub fn answers_a_turn(row: &crate::dto::TimelineRow) -> bool;
pub fn replies_in(ops: &[crate::dto::DiffOp<crate::dto::TimelineRow>]) -> Vec<Option<u64>>; // event_ms per answering row
pub struct TurnSink { /* inner: Arc<dyn EventSink>, room_id: String, turns: Arc<TurnBook> */ }
impl TurnSink { pub fn wrap(inner: Arc<dyn EventSink>, room_id: &str, turns: Arc<TurnBook>) -> Arc<dyn EventSink> }
pub fn spawn_sweeper(turns: Arc<TurnBook>, sink: Arc<dyn EventSink>) -> tokio::task::JoinHandle<()>;

// C/live.rs
pub fn listen(client: &Client, sink: Arc<dyn EventSink>, turns: Arc<TurnBook>) -> EventHandlerHandle;
// C/event.rs:  CoreEvent::Turn(crate::turns::TurnUpdate)   — `sm://turn`
// C/session.rs: pub fn turn(&self, room_id: &str) -> Option<TurnSnapshot>
```

FFI (`F/lib.rs`) has these free functions: `turn_record_label`, `turn_duration_label`,
`elapsed_label` and `fold_turn(room_id: String, inputs: Vec<TurnInput>)`. It adds
`Core::turn(&self, room_id: String) -> Option<TurnSnapshot>` and
`FfiEvent::Turn { update: TurnUpdate }` (`F/events.rs`). Tauri gets the same five commands.
`ipc.ts` gets `turnRecordLabel`, `turnDurationLabel`, `elapsedLabel`, `foldTurn`,
`turn(roomId)`, `onTurn(handler)`, and the types `TurnStep`, `TurnInput`, `TurnEnding`,
`TurnSummary`, `TurnStepChange`, `TurnUpdate` and `TurnSnapshot`. `ToolPhase` gains
`Deserialize` (for Tauri `fold_turn`).

---

## PR 1 — M1.3 turn record (`parity/m1-core-turns`)

### Task C1: Turn labels

**Files:**
- Create: `C/turn_label.rs`
- Modify: `C/lib.rs:32-73` (`pub mod turn_label;`)

**Interfaces:**
- Produces: `turn_record_label`, `turn_duration_label` and `elapsed_label`, as above.

- [ ] **Step 1: Port the failing tests** into `turn_label.rs` `#[cfg(test)]`.
  - `reasoning_leads_then_the_steps`:
    - `(true,2,Some(12_000))` → `"Thought for 12s · 2 steps"`;
    - `(true,1,Some(12_000))` → `"Thought for 12s · 1 step"`;
    - `(true,0,Some(12_000))` → `"Thought for 12s"`.
  - `a_turn_that_only_used_tools_says_how_many_and_how_long`: `(false,3,Some(65_000))` →
    `"3 steps · 1m 5s"`.
  - `no_time_known_leaves_the_time_out_rather_than_inventing_one`: `(true,0,None)` →
    `"Thought"`; `(false,2,None)` → `"2 steps"`.
  - `durations` (`turn_duration_label`): 200 → `"1s"`, 59_400 → `"59s"`, 125_000 → `"2m 5s"`.
    Add 1_500 → `"2s"` and 89_500 → `"1m 30s"` to pin half-away rounding.
  - `seconds_then_minutes_with_padded_seconds_then_hours` (`elapsed_label`): 8_900 → `"8s"`,
    64_000 → `"1m 04s"`, 600_000 → `"10m 00s"`, 3_725_000 → `"1h 02m"`, -3_000 → `"0s"`.

  The sources are `KT/TurnRecordLabelTests.swift:6-30` and
  `KT/ComposerRevampTests.swift:377-386`.
- [ ] **Step 2: Run them and confirm they fail.**
  `cargo test -p supermessage-core turn_label` fails with unresolved functions.
- [ ] **Step 3: Implement the three functions.** Round with `(ms as f64 / 1000.0).round()`,
  which is half away from zero, like Swift's `.rounded()`. Floor with `ms.div_euclid(1000)`
  clamped to 0.
- [ ] **Step 4: Run the tests and confirm they pass.** Same command, PASS. Then mutate
  `max(1, …)` and the padding, watch the tests fail, and restore.
- [ ] **Step 5: Commit** `feat(core): turn record and elapsed labels, from iOS`.

### Task C2: The turn book

**Files:**
- Create: `C/turns.rs`
- Modify: `C/lib.rs` (`pub mod turns;`) and `C/live.rs:138` (`ToolPhase` derives `Deserialize`)

**Interfaces:**
- Consumes: `turn_record_label` (C1); `ToolPhase` and `ToolPayload` (`C/live.rs:140`, `:179`).
- Produces: everything under `C/turns.rs` above.

- [ ] **Step 1: Port the failing tests** into `turns.rs`. Use a helper
  `book_with(room, inputs) -> TurnBook`, the room `"!r:x.org"`, and `t = 1_000_000`.
  - Timing (`KT/ComposerRevampTests.swift:319-363`):
    - `elapsed_runs_from_the_first_delta_and_stops_at_the_end`: Thought at t, Answer at
      t+30s. Then `last_activity_ms == t+30_000`. Answer done at t+42s gives
      `ended_at_ms == Some(t+42_000)` and `record_label == Some("Thought for 42s")`.
    - `the_next_turn_starts_its_own_clock`: after done, a new Answer at +100s gives a new
      `turn_id` and `started_at_ms` equal to that delta's `at_ms`.
    - `steps_the_running_one_how_many_are_done_the_failed_one`: tools 1 Done, 2 Running,
      3 Failed, 4 Done give `current_step_title "2"`, `step_is_tool`, `completed_steps 2`,
      `failed_step Some("3")` and `in_progress`.
  - Store (`KT/LiveStoreTests.swift:23-195`):
    - `reasoning_outlives_the_turn_that_produced_it`
    - `the_streamed_answer_goes_because_the_real_message_says_it_better`, which asserts
      `answering == false` after done
    - `a_thoughts_own_done_does_not_hide_it_either`
    - `tool_calls_outlive_the_turn_too`
    - `the_next_turn_replaces_the_last_ones_record`: new `turn_id`, `steps` empty,
      `thought` None
    - `a_tool_row_with_nothing_behind_it_does_not_pretend_otherwise`: `has_detail` false,
      then true with a location
    - `a_later_report_on_the_same_call_replaces_it_rather_than_stacking`: both updates carry
      `index 0`, and the snapshot has 1 step with `status "completed"` and
      `output Some("3 passed")`
    - `a_finished_turns_record_survives_other_rooms_turns`
    - `the_next_turn_replaces_the_kept_record`
    - `signing_out_forgets_every_kept_record` (`clear()`)
    - `only_so_many_rooms_are_remembered`: 21 rooms finish in order; room 1 is `None` and
      room 21 is `Some`
  - Inverted on purpose (an iOS behaviour change):
    `a_turn_left_mid_way_is_kept_and_keeps_counting`. An unfinished turn's snapshot is
    `Some`, with `in_progress`.
  - Summary (`KT/NotificationComposerTests.swift:501-531`, values per ruling 2):
    - `the_running_tool_is_the_current_step_and_only_done_tools_are_counted`: tools Done,
      "Run tests" Running, Failed, with answering and a thought, give
      `current_step_title "Run tests"`, `completed_steps 1` and `total_steps 3`.
    - `with_no_tool_running_the_phase_of_the_turn_is_the_step`: answering gives
      `"Writing the answer"`; a thought only gives `"Thinking"`; a queued tool plus answering
      gives the queued title; only Done tools gives `"Starting"`.
  - New:
    - `a_tool_report_the_network_delivered_late_is_dropped`: seq 5 Done, then seq 4 Running.
      The second returns `accepted: false, update: None`, and the step stays `Done`.
    - `a_done_with_no_turn_starts_nothing`
    - `a_growing_thought_sends_no_update`: the second Thought delta returns `update: None`.
    - `a_bridge_that_only_edits_its_progress_message_starts_no_turn`: `Reply` with no turn
      gives `update: None` and `snapshot == None` (Review Focus 1).
    - `an_agent_reply_ends_a_turn_that_never_sent_done`: a Tool at t, then
      `Reply{event_ms: Some(t), at_ms: t+5_000}`. This gives `ended_by Some(Reply)` and
      `ended_at_ms Some(t+5_000)`.
    - `a_reply_from_before_the_turn_does_not_end_it`: `event_ms = t - 60_001` is ignored,
      and `t - 60_000` ends the turn.
    - `a_turn_silent_for_thirty_minutes_ends_at_its_last_activity`:
      `expire(last + GIVE_UP_AFTER_MS - 1)` is empty. `expire(last + GIVE_UP_AFTER_MS)` gives
      one update with `TimedOut` and `ended_at_ms == Some(last)` (Review Focus 2).
    - `fold_turn_matches_the_book`
    - `revisions_only_move_forward`
- [ ] **Step 2: Run them and confirm they fail.** `cargo test -p supermessage-core turns::`
  fails with unresolved items.
- [ ] **Step 3: Implement the types, `TurnBook` and `fold_turn`** as the M1.3 Design states.
  - Keep `finished_order` in a `VecDeque`.
  - `revision` is a book-wide counter, bumped on every emitted update. `turn_id` is a
    book-wide counter, bumped on every new turn.
  - The doc comment on `GIVE_UP_AFTER_MS` says the 30-minute value is the owner's
    decision (O1) and has no iOS counterpart.
- [ ] **Step 4: Run the tests and confirm they pass.** Then mutate each of:
  - the seq comparison (`<=` to `<`);
  - `>=` to `>` in `expire`;
  - the skew;
  - the `KEPT_RECORDS` comparison;
  - `completed_steps` counting `Failed`.

  Run each mutant three times, watch it fail, restore, and record the results.
- [ ] **Step 5: Commit** `feat(core): core-owned turn state for every room`.

### Task C3: Feed the book, emit it, clear it

**Files:**
- Create: `C/turn_watch.rs`
- Modify:
  - `C/lib.rs`
  - `C/event.rs:44-66` (`CoreEvent::Turn`)
  - `C/live.rs:312-402` (`listen` signature, handlers, and the doc at `:375-386`)
  - `C/session.rs`: fields, `for_process` `:130-145`, `start_streams` `:757-788`,
    `logout` `:253+`, `subscribe_timeline` `:932-942`, and a new `turn`
  - `docs/agentpod-events.md:43-45`: one sentence saying turn state is `core::turns`

**Interfaces:**
- Consumes: `TurnBook`, `TurnInput`, `TurnStep::from_payload` and `now_ms` (C2).
- Produces: `CoreEvent::Turn`, `TurnSink::wrap`, `spawn_sweeper`, `answers_a_turn`,
  `replies_in`, `live::listen(client, sink, turns)` and `Session::turn`.

- [ ] **Step 1: Write the failing tests.**
  - In `turn_watch.rs`, with a recording sink like `C/timeline.rs:6620`'s `Envelopes`, and
    rows from `TimelineRow::new(project_item_parts(..))` as at `C/timeline.rs:7109`:
    - `an_agent_message_appended_ends_the_rooms_turn`: a `PushBack` of a non-own
      `@agent_atlas:x` message. The recorded events are `[TimelineDiff, Turn]`, in that
      order, and `ended_by == Some(Reply)`.
    - `an_edited_agent_message_ends_the_rooms_turn`: a `Set` with `edited: true` (Review
      Focus 1).
    - `a_tool_only_turn_ends_when_its_turn_card_lands`: a `customMessage` with
      `detail "dev.agentpod.turn.v1"`.
    - `a_human_speaking_mid_turn_does_not_end_the_agents_turn`: `@rakesh:x`, with no runtime
      suffix (Review Focus 4).
    - `history_does_not_end_a_turn`: `PushFront` and `Reset` of agent messages.
  - In `live.rs`, extract `fn deliver(sink: &Arc<dyn EventSink>, turns: &TurnBook, room_id: &str, input: TurnInput, raw: CoreEvent)`
    and test it:
    - `the_turn_update_goes_out_before_the_delta_that_caused_it`
    - `a_late_tool_report_reaches_no_host`: nothing is emitted for a refused tool.
  - In `session.rs` tests, beside `logout_without_a_prior_login_is_a_safe_no_op`
    (`:2066`): `logout_forgets_every_turn`. Seed `session.turns` through `apply`, call
    `logout()`, and assert `session.turn("!r:x") == None` (Review Focus 3).
- [ ] **Step 2: Run them and confirm they fail.**
  `cargo test -p supermessage-core -- turn_watch deliver logout_forgets` fails with
  unresolved items.
- [ ] **Step 3: Implement.**
  - `answers_a_turn`, `replies_in` and `TurnSink`. `TurnSink` forwards the diff, then emits
    each `Some(update)`.
  - `deliver`: on `accepted`, emit the update (if any) and then `raw`. On refusal, emit
    nothing.
  - The three `listen` handlers build `TurnInput` with `now_ms()` and call `deliver`.
  - `spawn_sweeper` uses `tokio::time::interval(SWEEP_EVERY)`.
  - Session: a `turns: Arc<TurnBook>` field and a
    `turn_sweep: RwLock<Option<JoinHandle<()>>>` field.
  - `start_streams`: `live::listen(&client, sink, Arc::clone(&self.turns))`, and start the
    sweeper (stopping the old one first).
  - `logout`: `self.turns.clear()` next to `self.staged.clear()`, then stop the sweeper next
    to `stop_ignore_watch`.
  - `subscribe_timeline`: pass `TurnSink::wrap(sink, room_id, Arc::clone(&self.turns))`.
  - `pub fn turn(&self, room_id: &str) -> Option<TurnSnapshot>` is `self.turns.snapshot`.
- [ ] **Step 4: Run the core suite.** `cargo test -p supermessage-core`, then
  `cargo clippy --workspace --all-targets --all-features -- -D warnings`. Both pass. Then:
  - mutate the order in `deliver`, watch the test fail, and restore;
  - drop the `edited` condition, watch the test fail, and restore.
- [ ] **Step 5: Commit** `feat(core): feed turns from live, replies and a time limit; clear on logout`.

### Task C4: Expose turns across every boundary, and regenerate both bindings

**Files:**
- Modify:
  - `F/lib.rs`: free functions near `audio_clock_label` `:1086`; `Core::turn` near
    `room_info` `:768`
  - `F/events.rs:25-110` (`FfiEvent::Turn`, and the conversion at `:127`)
  - `src-tauri/src/commands.rs`, `src-tauri/src/lib.rs:22-30, 215+`, and
    `src-tauri/src/host.rs:19, 44-52` (`CoreEvent::Turn` maps to `TURN_EVENT`)
  - `src/lib/ipc.ts`: types, wrappers, and `onTurn` beside `onTool` `:2039`
  - `src/lib/stores/live.svelte.ts:59-64, 106-125`: delete `toolSeen` and its check
  - `src/lib/stores/live.test.ts:177-189`: delete "ignores an update the network delivered
    late", now `a_late_tool_report_reaches_no_host`
  - `AK/Session.kt:786-826`: `is FfiEvent.Turn -> Unit` with a KDoc saying "M4 consumes
    this"
  - `apple/Generated/**` and `android/core/src/main/kotlin/uniffi/**` (regenerated)
- Test: `src/lib/ipc.test.ts`

**Interfaces:**
- Consumes: C1–C3.
- Produces: the Swift API `turnRecordLabel(thought:steps:elapsedMs:)`,
  `turnDurationLabel(elapsedMs:)`, `elapsedLabel(elapsedMs:)`, `foldTurn(roomId:inputs:)`,
  `Core.turn(roomId:)` and `FfiEvent.turn(update:)`. Kotlin has the same names in camelCase.

- [ ] **Step 1: Write the failing desktop test** in `ipc.test.ts`:
  "turn asks for one room's record by camelCase roomId". It expects
  `invoke("turn", { roomId: "!r:x" })`.
- [ ] **Step 2: Run it and confirm it fails.** `pnpm test src/lib/ipc.test.ts` fails because
  `turn` is not exported.
- [ ] **Step 3: Implement the FFI exports, `FfiEvent::Turn`, the Tauri commands, the
  registration, the host mapping and `ipc.ts`.** Each doc comment names its core function.
  Then:
  - delete `toolSeen` and its test from the desktop live store;
  - add the Android branch;
  - run the Swift recipe;
  - run `./scripts/build-android-libs.sh`, alone.
- [ ] **Step 4: Run every host's checks.**
  - The core verification in Global Constraints.
  - `pnpm test` and `pnpm check`.
  - `cargo build -p supermessage-ffi`, then `cd android && ./gradlew test :app:testDebugUnitTest`.
  - `git status` shows both generated trees changed.

  All pass.
- [ ] **Step 5: Commit** `feat: expose the turn record over UniFFI and Tauri; regenerate bindings`.

### Task C5: iOS reads the turn from the core

**Files:**
- Modify:
  - `K/Stores/LiveStore.swift`: rewrite it as a mirror
  - `K/Session.swift:594-598` (`open`), `:745-748` (`signOut`) and `:812-829` (`handle`)
  - `K/CoreClient.swift` (`turn(roomId:)`) and `K/CoreSeam.swift` (new `TurnFetching`)
  - `APP/Timeline/LiveTurnView.swift:55-211`, `APP/Timeline/WhatIDidFooter.swift:24-60`,
    `APP/Composer/ComposerView.swift:502`, `APP/Platform/PlatformCoordinator.swift:80-82, 274-282, 354`
  - The previews: `APP/Previews/ComposerRevampFixtures.swift:38-72`,
    `APP/Previews/PreviewFixtures.swift:837-885` and `APP/Previews/ScrollFixture.swift:110-120`
- Delete: `K/TurnRecordLabel.swift`, `K/ElapsedTime.swift`,
  `K/Notifications/LiveTurnSummary.swift` and `KT/TurnRecordLabelTests.swift`. Also delete
  the `LiveTimingTests` and `ElapsedTimeTests` suites (`KT/ComposerRevampTests.swift:307-386`,
  keeping the agent-name test at `:366` as a `LiveStore` test) and `LiveTurnSummaryTests`
  (`KT/NotificationComposerTests.swift:501-531`).
- Test: `KT/LiveStoreTests.swift`, rewritten.

**Interfaces:**
- Consumes: the Swift API from C4.
- Produces: the `LiveStore` API.
  - Stored: `answer: String?`, `thought: String?`, `tools: [TurnStep]` and
    `summary: TurnSummary?`.
  - Computed:
    - `finished` = `summary?.endedBy != nil`;
    - `inProgress` = `summary?.inProgress ?? false`;
    - `isLive` = `summary != nil || answer != nil`;
    - `startedAt`, `endedAt` and `lastActivityAt`, as `Date?` from the ms fields;
    - `elapsed(at:) -> TimeInterval?`, which is unchanged in meaning.
  - Methods: `apply(_ update: TurnUpdate)`, `restore(_ snapshot: TurnSnapshot?)`,
    `focus(_ roomId: String?) async`, `handleLive(roomId:seq:text:done:)`,
    `handleThought(roomId:seq:text:done:)` and `clear()`.
  - `setAgentName` and `agentName` are unchanged.
  - `extension TurnStep: Identifiable {}`.
  - `init(turns: TurnFetching? = nil)`.

- [ ] **Step 1: Write the failing Kit tests** in `LiveStoreTests.swift`. Build fixtures with
  the real `foldTurn(roomId:inputs:)`, and use a stub `TurnFetching`.
  - "an update for another room is not this pane's business"
  - "an older revision does not rewind the card"
  - "a new turn drops the last one's steps, reasoning and answer"
  - "a step change lands at the index the core gave"
  - "opening a room restores its turn from the core": the stub returns a finished snapshot,
    giving `finished`, the thought restored and `answer == nil`.
  - "signing out forgets the turn on screen"
  - "the streamed answer comes from the live channel and goes on done"
  - "the header's name is kept per room", moved from `ComposerRevampTests.swift:366`
- [ ] **Step 2: Implement the mirror, the `Session` routing and the views.**
  - `Session`:
    - `case let .turn(update): live.apply(update)`;
    - `.tool` becomes `break`, with a comment that steps arrive on `.turn`;
    - `open` does `await live.focus(roomId)`, which resets and then restores from
      `TurnFetching`.
  - `LiveTurnView` reads these instead of its own logic:
    - `summary.currentStepTitle` (replacing `currentStepTitle` `:194-199`);
    - `summary.stepIsTool` (replacing `live.currentStep != nil` `:66`);
    - `summary.completedSteps` and `summary.failedStep`;
    - `elapsedLabel(elapsedMs:)`.
  - `WhatIDidFooter` uses `live.summary?.recordLabel`, and `ComposerView` uses
    `elapsedLabel`.
  - `PlatformCoordinator`'s `LiveSeen` carries `step: String?`, which is
    `live.inProgress ? live.summary?.currentStepTitle : nil`.
  - The previews build their turns with `foldTurn` plus `restore`, and stream with
    `handleLive`. The frames must be unchanged.
- [ ] **Step 3: Push and run CI.** `git push -u origin parity/m1-core-turns`, open the PR, then
  `gh pr checks <n> --watch`. The `ios` job is green, including "Kit tests" and "The previews
  still look like the baseline". If a preview frame differs, read the frame from the
  `ios-preview-frames` artifact and fix the cause. Do not re-record the baseline.
- [ ] **Step 4: Commit any CI fixes**, then update the PR body with the "iOS behaviour
  changes" for M1.3 below.

---

## M1.4 Design — timeline rules

**Data model** (fields on `TimelineRow`, `C/dto.rs:849-940`, all defaulted per Global
Constraints):

| Field | PR | Meaning | Computed in |
|---|---|---|---|
| `is_agent: bool` | 2 | `!is_own && agent_name_from_id(sender).is_some()` | `with_view` (per row) |
| `is_long_read: bool` | 2 | `!is_own && graphemes(body) > 600` | `with_view` |
| `continues_run: bool` | 3 | iOS `continuesRun` against the previous **drawn** row | settle pass |
| `ends_run: bool` | 3 | the next drawn row does not continue it | settle pass |
| `hides_quote: bool` | 3 | `reply_to.event_id` == the nearest drawn row above with an `event_id` | settle pass |
| `shows_runtime: bool` | 3 | ≥2 distinct non-own senders **and** `sender_name != sender_short` | settle pass |
| `membership: Option<MembershipPlacement>` | 3 | this membership row's run and stretch | settle pass |

```rust
// C/timeline_grouping.rs — uniffi::Record, Serialize, camelCase
pub struct MembershipPlacement {
    pub run_id: String,               // "group:<id of the run's first row>"
    pub run_text: Option<String>,     // Some only on the run's first row
    pub stretch_id: Option<String>,   // "stretch:<id of the stretch's first row>" when the stretch has ≥3 entries
    pub stretch_text: Option<String>, // Some only on the stretch's first row, with stretch_id
}
```

**Pass rules (verbatim `K/TimelineGrouping.swift:13-280`):**
- Rows with `ItemView::None` are skipped: they are not drawn and they split nothing.
- **Sender runs.** A row continues a run when:
  - both it and the previous drawn row are `ItemView::Bubble`;
  - they have the same `item.sender` and the same `item.is_own`;
  - both timestamps are present, with `0 ≤ now − then ≤ RUN_WINDOW_MS`.

  The "previous" row resets after any membership row. A date divider or the unread marker
  is a drawn non-Bubble row, so it breaks a run.
- **Reply quotes.** `previous_event` is the nearest drawn row above with an `event_id`. The
  unread marker, date dividers and local echoes have none, so they are skipped. That is the
  "across the new-messages line" case. `previous_event` resets after a membership row.
- **Membership.** Stretches of consecutive drawn membership rows are split with rules 1–3
  (`K/TimelineGrouping.swift:119-154`):
  - The person is `membership_subject.unwrap_or(sender_short)`.
  - The verb is `membership_verb.unwrap_or("updated their membership")`.
  - Text comes from `run_text`, `churn_text` and `stretch_text`.

  Expansion is not in the core. The fields carry both the run lines and the stretch line,
  and the host picks between them.

**Host walk** (identical in Swift, Kotlin and TypeScript; mechanical, no rules). For each
row whose view is not `None`:
- If it has `membership: m`:
  - if `m.stretch_id = Some(s)` and `s` is not expanded: emit a stretch entry
    `(s, m.stretch_text)` when the text is `Some`, otherwise nothing;
  - else emit a run entry `(m.run_id, m.run_text)` when the text is `Some`, otherwise
    nothing.
- Otherwise emit the row.

The host keeps an `expanded: Set<String>` per room, reset when the room changes.

**Where the pass runs:** last in `emit_ops` (`C/timeline.rs:3611-3624`), after
`settle_unread_marker`. It must run after `voice_reply` and `voice_transcript` (they set
`None`) and after the unread marker (it flips between `None` and `UnreadMarker`). The resync
snapshot reads the same folded state, so it agrees with the wire.

**IPC cost:**
- A naive pass would `Set` every row whose fields changed. On a `Reset` or a back-paginated
  `Append` of 200 rows, that sends every row twice.
- Instead, after the pass, `emit_ops` stamps the final grouping fields onto every op value
  in `wire_ops` by `item.id`, and it adds `Set`s only for changed indices whose id is not
  carried by any op in this batch.
- Appending one message typically sends the `PushBack` plus at most one `Set` (the previous
  row's `ends_run`). A growing membership stretch also `Set`s its first row.
- `shows_runtime` flips are confined to rows with a runtime suffix.
- `is_long_read` is computed once per row, never per batch. It takes a fast path when
  `body.len() <= 600` bytes.

**Wire-format change:** the desktop JSON gains up to seven optional keys per row:
`isAgent`, `isLongRead`, `continuesRun`, `endsRun`, `hidesQuote`, `showsRuntime` and
`membership`. The Swift and Kotlin records gain defaulted fields. No existing fixture breaks
at compile time. Fixtures that assert grouping behaviour change (listed per task).

**Pure export for fixtures:** `group_timeline_rows(rows: Vec<TimelineRow>) -> Vec<TimelineRow>`
runs the pass over a list. It is for previews, fixtures and Kit/`:kit` tests, never the live
path. It is exported over UniFFI only: no Tauri command and no `ipc.ts` wrapper, because the
desktop has no caller (R9, amending ruling 8).

**Migrating the three host copies:**
- **iOS.** `K/TimelineGrouping.swift` is reduced to the host walk (`displayRows`) and
  `DisplayRow`. `continuesRun`, `hasSingleSpeaker`, `quoteRepeatsPrevious`, `isAgent`,
  `isLongRead` and the text functions are deleted.
- **Android.** `AK/TimelineGrouping.kt` is reduced to the same walk. `AA/Timeline.kt` reads
  the row fields.
- **Desktop.** `src/lib/components/timelineGrouping.ts` keeps `groupTimelineItems`
  (reduced to the walk, plus the desktop-only undecryptable grouping), `rowKey` and
  `shouldShift`. `continuesSenderRun`, `groupText` and `SENDER_RUN_WINDOW_MS` are deleted.
- **Not switched on Android and desktop** (ruling 8: no host copy exists, so this is M4/M5
  UI): `hides_quote`, `is_agent`, `is_long_read`, and `shows_runtime` on desktop, which shows
  `senderShort` today.

---

## PR 2 — M1.4a row facts (`parity/m1-core-row-facts`)

### Task C6: `is_agent` and `is_long_read` on the row

**Files:**
- Modify:
  - `crates/supermessage-core/Cargo.toml` (`unicode-segmentation = "1.13"`, with a
    comment: MIT/Apache-2.0, ruling 4) and `Cargo.lock`
  - `C/item_view.rs`, beside `agent_name_from_id` `:333`: `is_agent_row`, `is_long_read`,
    `LONG_READ_GRAPHEMES`
  - `C/dto.rs:849-940` (two fields; set in `with_view` `:974-995`)
  - `F/lib.rs` (`long_read_graphemes() -> u32`, UniFFI only: no Tauri command and no
    `longReadGraphemes()` wrapper, R9), `src/lib/ipc.ts:1071+` (`isAgent?: boolean;
    isLongRead?: boolean;` on `TimelineRow` only)
  - Both generated bindings

**Interfaces:**
- Produces:
  - `pub fn is_agent_row(item: &TimelineItemDto) -> bool`
  - `pub fn is_long_read(item: &TimelineItemDto) -> bool`
  - `pub const LONG_READ_GRAPHEMES: usize = 600`
  - `TimelineRow.is_agent` and `TimelineRow.is_long_read`
  - Swift `longReadGraphemes()`

- [ ] **Step 1: Port the failing tests** (`KT/TimelineGroupingTests.swift:441-479`) into
  `item_view.rs`.
  - `the_agent_namespace_marks_an_agent_and_nothing_else_does`:
    - `@agent_ashram_openclaw-atlas:x` → true;
    - `@atlas:x` → false;
    - `@my_agent_x:x` → false;
    - own `@agent_x:x` → false;
    - `@agent_:x` → false (ruling 1, an iOS change).
  - `past_six_hundred_characters_a_message_is_a_long_read`: 600 → false, 601 → true.
  - `characters_are_counted_as_a_reader_sees_them_not_as_utf16`: 600 × `"👍🏽"` → false, and
    601 × `"👍🏽"` → true.
  - `your_own_long_message_is_not_offered_back_to_you_as_a_long_read`
  - In `dto.rs`, `a_row_leaves_false_facts_off_the_wire`: `serde_json::to_value(row)` has no
    `isAgent` key when false, and has `"isAgent": true` when true.
- [ ] **Step 2: Run them and confirm they fail.**
  `cargo test -p supermessage-core -- item_view:: dto::` fails with unresolved functions.
- [ ] **Step 3: Implement** with `UnicodeSegmentation::graphemes(body, true).count()`. Then
  add the fields, the export, the command and the TypeScript mirror, and regenerate both
  bindings.
- [ ] **Step 4: Verify.**
  - The core verification.
  - The licence gate command.
  - `pnpm test` and `pnpm check`.
  - `cd android && ./gradlew test`.

  All pass. Then mutate `chars().count()` in for the grapheme count, watch the emoji test
  fail, and restore.
- [ ] **Step 5: Commit** `feat(core): agent and long-read facts on TimelineRow`.

### Task C7: iOS reads the row facts

**Files:**
- Modify:
  - `APP/Timeline/TimelineRowView.swift:227, 300, 395, 801`
  - `APP/Timeline/TimelineCollectionView.swift:1006`
  - `APP/Previews/VoiceNoteFixtures.swift:130` (`longReadGraphemes()`)
  - `K/TimelineGrouping.swift:252-280`: delete `isAgent`, `longReadCharacters` and
    `isLongRead`
  - `KT/TimelineGroupingTests.swift:441-479`: delete `AgentAndLongReadTests`

**Interfaces:** consumes `row.isAgent`, `row.isLongRead` and `longReadGraphemes()` from C6.

- [ ] **Step 1: Replace each call site** with the row field. `grep -rn "TimelineGrouping.isAgent\|isLongRead(\|longReadCharacters" apple`
  returns nothing.
- [ ] **Step 2: Push and watch the `ios` job.** It is green, including the previews.
- [ ] **Step 3: Commit**, and record the `@agent_:x` change in the PR body.

---

## PR 3 — M1.4b grouping pass (`parity/m1-core-timeline-grouping`)

### Task C8: The grouping pass

**Files:**
- Create: `C/timeline_grouping.rs`
- Modify: `C/lib.rs`, and `C/dto.rs` (five fields, defaulted, initialised in `with_view`)

**Interfaces:**
- Produces:
  - `MembershipPlacement`;
  - `pub fn settle(rows: &mut [TimelineRow]) -> Vec<usize>`, which returns the indices
    whose grouping fields changed;
  - `pub fn group_rows(rows: Vec<TimelineRow>) -> Vec<TimelineRow>`;
  - the constants `RUN_WINDOW_MS`, `MAX_NAMED` and `STRETCH_COLLAPSE_THRESHOLD`.

- [ ] **Step 1: Port the failing tests** (`KT/TimelineGroupingTests.swift:25-438`). Use
  local builders `msg(id, sender, at_ms, own)`, `membership(id, sender, verb, subject)`,
  `silent(id)`, `marker()` and `divider()`. Assert on `group_rows(..)` fields.
  - Runs:
    - `a_second_message_from_the_same_sender_moments_later_continues_the_run`
    - `a_different_sender_starts_a_new_run`
    - `a_long_gap_starts_a_new_run_even_from_the_same_sender`: `RUN_WINDOW_MS + 1` gives
      false, and `RUN_WINDOW_MS` gives true.
    - `anything_that_is_not_an_ordinary_message_ends_the_run`
    - `the_first_row_never_continues_anything`
    - `an_own_message_does_not_join_a_peers_run`
    - `ends_run_mirrors_the_next_rows_continues_run`
  - Speakers:
    - `a_room_where_one_agent_speaks_does_not_repeat_its_runtime`: `shows_runtime` false.
    - `a_room_where_several_speak_keeps_it`: true on rows with a suffix (Review Focus 4).
    - `your_own_messages_do_not_make_a_room_multi_voiced`
  - Membership:
    - `a_run_of_the_same_change_becomes_one_sentence`: the first row's `run_text` is
      `"Ganesha, Krishna and 2 others joined the room"`.
    - `interleaved_churn_by_several_people_collapses_into_one_line`: the first row's
      `stretch_text` is `"5 membership changes · Strategy Sam, Rakesh and 1 other"`, and
      `stretch_id` is `"stretch:1"` on all five.
    - `an_opened_stretch_still_has_its_lines`: each run's first row has `run_text`.
    - `two_lines_are_not_worth_collapsing`: `stretch_id` None.
    - `one_person_changing_twice_is_named_once`: `"Annapurna and Surya updated their membership"`.
    - `a_run_of_one_reads_exactly_like_an_ungrouped_line`: `"Ganesha joined the room"`.
    - `different_verbs_stay_different_sentences`
    - `messages_pass_through_untouched_and_break_a_run`
    - `a_silent_row_does_not_break_a_membership_run_either`
    - `a_membership_change_the_core_silenced_is_not_drawn_as_one`:
      `"Ganesha and Annapurna joined the room"`.
    - `one_person_joining_leaving_and_rejoining_is_one_line`:
      `"Strategy Sam made 3 membership changes"`.
    - `two_changes_read_as_what_happened_in_order`:
      `"Strategy Sam joined the room, then left the room"`.
    - `a_persons_churn_does_not_swallow_the_neighbours_who_share_a_verb_with_it`: the run
      texts are `["Alice joined the room", "Strategy Sam joined the room, then left the room", "Bob left the room"]`.
    - `one_person_repeating_one_change_still_joins_a_crowd_making_it`
    - `an_invite_and_the_join_that_follows_are_about_the_same_person`:
      `"Strategy Sam was invited, then joined the room"`.
    - `a_message_between_two_changes_keeps_them_apart`
  - Quotes:
    - `a_reply_directly_under_its_parent_drops_the_quote`
    - `a_reply_to_anything_further_up_keeps_it`
    - `a_local_echo_above_has_no_event_id_so_it_cannot_be_the_parent`
    - `a_message_that_is_not_a_reply_never_has_a_quote_to_drop`
    - `a_reply_across_the_new_messages_line_drops_the_quote`: parent, `marker()`, reply. This
      is the test iOS lacked.
    - `a_membership_line_between_parent_and_reply_keeps_the_quote`
  - From the desktop and Android suites (ruling 3, see the change lists):
    - `a_message_dated_before_its_predecessor_starts_a_new_run`: the old desktop
      "a second earlier" case, inverted.
    - `a_silent_row_between_two_messages_does_not_break_their_run`
    - `a_date_divider_breaks_a_membership_stretch`
    - `a_change_with_no_verb_reads_as_updated_their_membership`
    - `a_run_names_the_person_changed_not_who_changed_them`
    - `a_message_with_no_sender_never_continues_a_run`
    - `a_growing_run_keeps_its_id`
    - `more_than_two_people_name_two_and_count_the_rest`: singular `"and 1 other"`.
- [ ] **Step 2: Run them and confirm they fail.**
  `cargo test -p supermessage-core timeline_grouping` fails with unresolved items.
- [ ] **Step 3: Implement `settle`** as a single forward walk that tracks `previous_drawn`,
  `previous_event` and the current stretch, then a backward walk for `ends_run`. It resets
  the trackers at membership rows. `group_rows` clones the rows, calls `settle` and returns
  them.
- [ ] **Step 4: Run the tests and confirm they pass.** Then mutate each of:
  - the one-directional window (to `abs`);
  - the `None` skip;
  - the stretch threshold (3 to 2);
  - the `previous_event` reset.

  Watch each mutant fail, restore, and record the results.
- [ ] **Step 5: Commit** `feat(core): timeline grouping pass, ported from iOS`.

### Task C9: Run the pass on every batch, cheaply, and expose it

**Files:**
- Modify:
  - `C/timeline.rs:3611-3630` (`emit_ops`: call `timeline_grouping::settle` last, stamp the
    op values, add `Set`s only for uncarried ids)
  - `F/lib.rs` (`group_timeline_rows`, UniFFI only: no Tauri command, R9)
  - `src/lib/ipc.ts`: `TimelineRow` gains `continuesRun?`, `endsRun?`, `hidesQuote?`,
    `showsRuntime?` and `membership?: MembershipPlacement`; also the `MembershipPlacement`
    type (no `groupTimelineRows` wrapper: UniFFI-only, R9)
  - Both generated bindings

**Interfaces:**
- Consumes: `settle` and `group_rows` (C8).
- Produces (UniFFI only for `group_timeline_rows`, R9): grouping fields on every row the timeline emits; Swift
  `groupTimelineRows(rows:)` and Kotlin `groupTimelineRows(rows)`.

- [ ] **Step 1: Write the failing tests** in `C/timeline.rs` tests. Call `emit_ops` directly
  with a recording sink, as at `:5384` and `:6620`.
  - `the_wire_carries_grouping_fields`: a `PushBack` of two same-sender messages. The
    envelope's second value has `continues_run == true`.
  - `a_back_paginated_batch_carries_its_grouping_in_its_own_ops`: start from a state of 3
    rows, then `Append` 200 alternating-sender rows. The envelope has no `Set` for any id
    present in the `Append`, and each `Append` value's `continues_run` and `ends_run` equal
    the snapshot's (Review Focus 5).
  - `appending_a_message_sets_only_the_row_whose_run_it_ends`: ops are
    `[PushBack, Set{index: n-1}]` and nothing more.
- [ ] **Step 2: Run them and confirm they fail.**
  `cargo test -p supermessage-core -- the_wire_carries back_paginated appending_a_message`
  fails.
- [ ] **Step 3: Implement the `emit_ops` change, the export, the command and the TypeScript
  mirror, and regenerate both bindings.** The stamp copies the five grouping fields from
  `guard.1` (by id) into each op value. Collect the ids before `apply_ops` consumes the ops.
- [ ] **Step 4: Run the core verification.** It passes. Then remove the stamping, watch the
  back-pagination test fail (the `Set`s reappear), and restore.
- [ ] **Step 5: Commit** `feat(core): group the timeline in the diff, without re-sending rows`.

### Task C10: iOS renders the core's grouping

**Files:**
- Modify:
  - `K/TimelineGrouping.swift`: reduce it to
    `static func displayRows(_ rows: [TimelineRow], expanded: Set<String>) -> [DisplayRow]`,
    `isStretch(_:)` and `DisplayRow`; delete the rest
  - `APP/Timeline/TimelineCollectionView.swift:619-640, 745-800`: use `row.continuesRun`,
    `row.endsRun`, `row.hidesQuote` and `row.showsRuntime`; remove the `previous` and
    `previousEvent` walk
  - The `APP/Previews/*` fixtures that build timelines: pass their rows through
    `groupTimelineRows`
- Test: in `KT/TimelineGroupingTests.swift`, delete every suite except `TimelineDayTests`
  (`:482-515`), and add `DisplayRowTests`.

**Interfaces:** consumes the row fields and `groupTimelineRows(rows:)` from C9.

- [ ] **Step 1: Write the failing `DisplayRowTests`** using real `groupTimelineRows`.
  - "a collapsed stretch is one entry until it is opened"
  - "an opened stretch shows each run's line"
  - "a row that draws nothing gets no entry"
- [ ] **Step 2: Implement the walk and switch `TimelineCollectionView`.** The
  `singleSpeaker` state is deleted, and attribution is
  `row.showsRuntime ? row.senderName : row.senderShort`.
- [ ] **Step 3: Push and watch the `ios` job.** It is green. The preview frames are
  unchanged; if one differs, inspect it.
- [ ] **Step 4: Commit** `refactor(ios): the timeline's grouping is the core's`.

### Task C11: Android renders the core's grouping

**Files:**
- Modify:
  - `AK/TimelineGrouping.kt`: reduce it to
    `fun displayRows(rows: List<TimelineRow>, expanded: Set<String>): List<DisplayRow>`;
    `DisplayRow.MembershipRun` gains `isStretch: Boolean`
  - `AA/Timeline.kt:170-207, 326`: read `row.continuesRun`, `row.endsRun` and
    `row.showsRuntime`; add `expanded` with `rememberSaveable(roomId)`
  - `AA/TimelineRow.kt:240-260`: the stretch row is tappable, with a chevron and
    `contentDescription "Shows each change"`
  - `android/app/src/debug/kotlin/dev/supermessage/previews/TimelinePreviews.kt`: new
    `@Preview` "Membership stretch, collapsed"
- Test:
  - Replace `AKT/TimelineGroupingTest.kt` with `AKT/TimelineDisplayTest.kt`.
  - Update `AAT/TimelineTest.kt:83-101, 410-470`: `membershipRow(...)` takes a
    `MembershipPlacement`. Add `aCollapsedStretchOpensOnTap`.

**Interfaces:** consumes the row fields and `groupTimelineRows` from C9.

- [ ] **Step 1: Write the failing tests.**
  - `TimelineDisplayTest` calls the real core. It needs a host
    `cargo build -p supermessage-ffi` (the `RosterArrangementTest` precedent).
    - `collapsedStretchIsOneEntry`
    - `openedStretchShowsEachRun`
    - `silentRowsAreDropped`
  - `TimelineTest`:
    - `aCollapsedStretchOpensOnTap`: five rows from `groupTimelineRows`. Tap
      `"5 membership changes · Strategy Sam, Rakesh and 1 other"`, then assert
      `"Strategy Sam was invited, then joined the room"` is displayed. Use full-text
      matchers only.
  - Update `aRunOfMembershipChangesCollapses` and `aDifferentVerbStartsANewRun` to pass their
    rows through `groupTimelineRows`.
- [ ] **Step 2: Run them and confirm they fail.**
  `./gradlew :kit:test --tests '*TimelineDisplayTest*'` fails with an unresolved
  `displayRows`.
- [ ] **Step 3: Implement the walk, the `Timeline.kt` reads, the stretch row and the
  preview.** Delete `continuesRun`, `hasSingleSpeaker`, `collapseMembershipRuns` and `text`
  from the kit.
- [ ] **Step 4: Run the Android verification.** All of it from Global Constraints: unit,
  ci-parity, and instrumented at both heights. Also check that the stretch row at font scale
  2.0 at 411dp does not clip. All pass.
- [ ] **Step 5: Commit** `refactor(android): the timeline's grouping is the core's`.

### Task C12: Desktop renders the core's grouping

**Files:**
- Modify:
  - `src/lib/components/timelineGrouping.ts:84-393`:
    `groupTimelineItems(source, expanded: ReadonlySet<string>)` walks the core fields;
    `TimelineDisplayRow` gains `{ type: "membershipStretch"; key; text }`;
    `continuesRun` is `row.continuesRun ?? false`; delete `continuesSenderRun`, `groupText`,
    `joinNames`, `MAX_NAMED` and `SENDER_RUN_WINDOW_MS`, and rewrite the module doc
  - `src/lib/components/Timeline.svelte:406-412, 1384-1392`: an `expanded` `$state` reset
    on room change, and a stretch branch
- Create: `src/lib/components/timeline/MembershipStretch.svelte` (a `LogLine` look with a
  chevron, `<button>`, `aria-label "Shows each change"`) and
  `MembershipStretch.stories.svelte` (stories "Collapsed" and "Unbounded names", the latter
  modelled on `LogLine.stories.svelte`'s 600-character guard)
- Test: `src/lib/components/timelineGrouping.test.ts` (the changes below)

**Interfaces:** consumes the `TimelineRow` optional fields and `MembershipPlacement` from C9.

- [ ] **Step 1: Rewrite the tests.** The fixtures carry core fields set by hand.
  - **Keep:** "never mutates or reorders the source array", "passes non-membership items
    through…", "drops a membership row the core marked as drawing nothing", "handles an empty
    item list", "handles a timeline with no membership items at all", every `rowKey` and
    `shouldShift` test, and "undecryptable runs".
  - **Adapt to fields:** "keeps the group key anchored to the first item…" (now `runId`),
    "gives a new run… a fresh key", and "does not let a hidden membership row split the run".
  - **Add:** "a collapsed stretch shows one line until opened", "an opened stretch shows
    each run's line", and "continuesRun is read from the row".
  - **Delete, with their Rust counterparts named in the commit:**

    | Deleted test | Rust counterpart |
    |---|---|
    | "wraps a single membership item…" | `a_run_of_one_reads_exactly_like_an_ungrouped_line` |
    | "collapses a run of several…" | `a_run_of_the_same_change_becomes_one_sentence` |
    | "names exactly two people with singular 'other'…" | `more_than_two_people_name_two_and_count_the_rest` |
    | "joins exactly two names…" | `one_person_changing_twice_is_named_once` |
    | "a run split by a real message…" | `messages_pass_through_untouched_and_break_a_run` |
    | "a run split by a date divider…" | `a_date_divider_breaks_a_membership_stretch` |
    | "does not merge… different verbs" | `different_verbs_stay_different_sentences` |
    | "falls back to a generic verb phrase…" | `a_change_with_no_verb_reads_as_updated_their_membership` |
    | "falls back to the raw sender id…" | — (`sender_short` is the core's attribution) |
    | "carries the core's short sender name…" | `a_run_names_the_person_changed_not_who_changed_them` |
    | both "distinct names" tests | `one_person_changing_twice_is_named_once` |
    | all ten "sender runs" tests | the C8 run tests; "a second earlier" is now `a_message_dated_before_its_predecessor_starts_a_new_run`, inverted |

- [ ] **Step 2: Run them and confirm the new ones fail.**
  `pnpm test src/lib/components/timelineGrouping.test.ts` fails on the stretch cases.
- [ ] **Step 3: Implement the walk, the `Timeline.svelte` branch, the component and the
  stories.** Record the two baselines with `./scripts/snapshot-stories.sh --record`, only
  after inspecting each frame. Also check the stretch in the running app at a window under
  640px.
- [ ] **Step 4: Run the desktop verification and the core verification.** Both pass.
- [ ] **Step 5: Commit, push and watch every job.**
  `refactor(desktop): the timeline's grouping is the core's`. Then
  `gh pr checks <n> --watch`: `ios`, the Android jobs, the desktop jobs and the bindings
  diff are all green.

---

## iOS behaviour changes

**M1.3 (PR 1)**
1. **The widget step follows the live card** (ruling 2). It was: running tool → "Writing the
   answer" → "Thinking" → queued tool → "Working". It is now: running → queued → "Writing
   the answer" → "Thinking" → "Starting". A queued tool now beats "Writing the answer", and
   the fallback word is "Starting", not "Working".
2. **The completed count is "done" only everywhere.** `LiveTurnSummary` counted failed steps
   too. Its count had no reader (`PlatformCoordinator` uses only `step`), so nothing on
   screen changes.
3. **A stale tool report** (a lower seq for the same call in the same turn) is dropped. iOS
   used to apply it, so a finished step could show as running.
4. **Turns in rooms the reader is not looking at are tracked.** Opening a room mid-turn shows
   the card with the steps so far. A turn left mid-way is kept and keeps counting. iOS
   discarded it (`LiveStoreTests` "a turn left mid-way is not kept" is inverted).
5. **The 20 kept records are the 20 most recently finished turns across all rooms.** It
   used to be the 20 rooms most recently left. A busy account keeps fewer records for rooms
   the reader actually read.
6. **A turn now ends without `done`:**
   - when an agent's message, an edit of an agent's message, or a turn card lands in the
     open room;
   - after 30 minutes without activity (O1), ending at the last activity.

   It used to say "Working" forever.
7. **The live card's times come from the core's clock** at delta arrival, not Swift's.
   There is no visible difference beyond scheduling jitter.

**M1.4 (PRs 2 and 3)**
8. **`is_agent` uses the core's `agent_name_from_id`** (ruling 1). `@agent_:x` (nothing after
   the prefix) is no longer an agent. Every other id behaves as before.
9. **Long reads are counted by `unicode-segmentation` extended graphemes** rather than
   Swift `Character`. They are identical except where the two Unicode versions differ on a
   newer emoji sequence.
10. **Grouping is the iOS rules verbatim**, plus the quote test across the unread marker
    that iOS's caller already did. There is no visible change.

## Android and desktop behaviour changes (ruling 3)

**Android (PR 3)**
1. **Membership runs are keyed on the verb (`membership_verb`), not `item.detail`.** Details
   that map to the same verb now merge, for example every unknown detail as "updated their
   membership".
2. **A run names the person changed** (`membership_subject`), not who made the change. "Sam
   was invited" by Rakesh now names Sam.
3. **One person's mixed changes are one line:** "X joined the room, then left the room", or
   "X made N membership changes".
4. **Three or more lines in a stretch collapse** to "N membership changes · A, B and k
   others", which a tap expands. This is new UI.
5. **Sender runs read the previous *drawn* row.** A hidden row (a voice reply drawn on its
   text, an embedded card) between two messages from the same sender no longer breaks their
   run, and the header is not repeated.
6. **`ends_run` follows the same rule.** The bubble tail moves accordingly.
7. **The runtime suffix rule is unchanged.** It is now the core's.

**Desktop (PR 3)**
1. Same as Android 1–4. Desktop also drops the runtime suffix from membership lines, because
   names were `senderName` and are now `membership_subject ?? sender_short`.
2. **Only Bubbles (`m.text` and `m.notice`) form sender runs.** Images, audio and voice
   notes, files and emotes no longer continue a run or get continued; each shows its header.
   The desktop used to use `kind === "message"`.
3. **The run window is one-directional.** A message timestamped earlier than its predecessor
   starts a new run. It used to be `Math.abs`.
4. **Undecryptable grouping stays desktop-only and unchanged.**
5. **A stale tool report is dropped in the core** (PR 1). There is no visible change, because
   the desktop's own guard did the same.

---

## Self-review notes

- **Coverage:**
  - TurnRecordLabel: C1.
  - Retention of 20 rooms, elapsed time, current step, and tool steps kept for "What I did":
    C2, with steps in `TurnSnapshot.steps`.
  - Every room, sign-out clearing and the time limit: C2 and C3.
  - Events and DTOs: C3 and C4.
  - The iOS switch: C5.
  - Ruling-2 choices: the Design and changes 1–2.
  - Review Focus 1: C2 and C3.
  - D5 and D6: C8 and C9.
  - T1: C6.
  - The three host copies: C10–C12.
  - Visible changes: listed above.
  - Licence gate: C6.
  - The `ios` job: C5, C7, C10 and C12.
- **Types:** `TurnUpdate.step: Option<TurnStepChange>`, `MembershipPlacement` and
  `group_timeline_rows` are spelled the same in their producer and consumer tasks.
  `elapsed_label` takes `i64` everywhere.

## Decisions (formerly open questions)

1. Time limit. Decided: O1 — 30 minutes without activity ends a turn, at the last activity.
2. Turn end in rooms the reader is not looking at. Decided: R7 — only the 30-minute limit ends
   a turn there; room-list-driven ending is deferred.
3. "Agent-like" reply detection. Decided: R7 — bridge agents use `@agent_` ids (observed live:
   `@agent_super-chotu`), so edit-progress turns are covered.
4. Desktop `shows_runtime`. Decided: R8 — the desktop runtime-suffix rule waits for M4.
5. `group_timeline_rows` and `long_read_graphemes` Tauri commands. Decided: R9 — UniFFI-only;
   no Tauri command without a desktop caller.
