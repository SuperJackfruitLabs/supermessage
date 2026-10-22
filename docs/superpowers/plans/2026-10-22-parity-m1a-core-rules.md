# Parity M1a — iOS Presentation Rules into the Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** move five sets of iOS-only rules into `supermessage-core` — agent avatar style (M1.5),
attachment send outcome with core-side re-stage (M1.7), room status and roster views (M1.1),
room cast and acknowledgement (M1.2), first-run demo script and readable space names (M1.6) —
expose each through UniFFI and Tauri, regenerate both bindings, and switch iOS to the core
version with its Swift copy deleted.

**Execution:** Core track, worktree `parity-core`. Five PRs, one per item, each on its own
branch from `main` (`parity/m1-core-avatar`, `parity/m1-core-send-outcome`,
`parity/m1-core-roster-views`, `parity/m1-core-cast`, `parity/m1-core-first-run`). They may be
developed in parallel but **merge one at a time, in this order: PR1 → PR2 → PR3 → PR4 → PR5**
(rulings 9, 10, 11). PR2 (M1.7) is what Android M2 Task 8 and desktop M2 Task 5a wait for; PR3 (M1.1), PR4 (M1.2) are what M2 tasks consuming room status, roster views or the cast wait for. Before each merge: rebase on `main`, rerun procedure **B** (bindings) and
**V** (verification) below, commit the regenerated bindings, wait for CI.

**Architecture:**
- Pure rules become free functions in a core module, re-exported as `#[uniffi::export]` free
  functions in `crates/supermessage-ffi/src/lib.rs` ("a free function for the same reason as
  `rich_blocks_from_markdown`: it touches no session state") and as thin `#[tauri::command]`s.
- Session-reading rules (`room_cast`) are `Session` methods → `Core` methods → async Tauri
  commands, so the desktop needs no M3 plumbing (ruling 6).
- Anything a desktop renderer needs per row rides on the row (`RosterRow.avatar`,
  `SpaceSummary.display_name`), the `RosterRow.state` precedent.
- iOS keeps only host state (stored chip, typers, demo step) and one-line Kit
  wrappers that convert `Date` to milliseconds, as `RosterArrangement` already does.

**Tech Stack:** Rust (matrix-sdk 0.18, UniFFI 0.28.3), Tauri 2 + Svelte 5 + Vitest, Kotlin
(bindings compile check + two small Kit adaptations), SwiftUI (verified on CI only).

**Spec:** `docs/superpowers/plans/2026-10-22-ios-parity-roadmap.md` (M1, Global Constraints);
binding rulings `.superpowers/sdd/planning-m1-m2/m1-rulings.md` (1–12) and `owner-decisions.md` (O1–O4, R1–R10); research inventory
`.superpowers/sdd/planning-m1-m2/m1-inventory.md` §0, §1, §2, §5, §6. M1.3 and M1.4 are the
separate M1b plan and are out of scope here.

Paths: `C=crates/supermessage-core/src`, `F=crates/supermessage-ffi/src/lib.rs`,
`T=src-tauri/src`, `K=apple/SupermessageKit`, `KT=apple/SupermessageKitTests`,
`A=apple/Supermessage`, `AK=android/kit/src/main/kotlin/dev/supermessage/kit`,
`AKT=android/kit/src/test/kotlin/dev/supermessage/kit`.

## Global Constraints

- Everything in the roadmap's Global Constraints applies (tokens only; amber means a pending
  decision only; the app decides nothing the core decides; no client-private event types;
  copy verbatim from the cited iOS file).
- Ruling 1: iOS behaviour and copy are the target, **except** where the core already decides:
  agent-room detection is `roster::describes_agent` (not `runtime != nil`); lists of people
  are worded by `display_name::people_label`'s shape — "A", "A and B", "A, B and 1 other",
  "A, B and N others". Every resulting iOS change is listed under "iOS behaviour changes".
- Ruling 5: `RosterFilter` has four cases (All, Unread, Agents, Needs you); offered chips are
  All and Unread; an unknown or not-offered stored value reads as All.
- Ruling 7: `room_status` and `acknowledgement` take `turn_in_progress: bool` as an input.
- Ruling 8: per PR — Rust module + tests ported from each named iOS Kit test; UniFFI export;
  Tauri command + `ipc.ts` wrapper; **both** bindings regenerated and committed; iOS switched
  and its Swift copy deleted; Android/desktop switch-over only where a host already holds a copy
  of the rule (here: the typing line, PR4).
- Ruling 11: `attachment_send` returns `Sent | Failed { error, restaged }`; the core re-stages
  the same path for the same room (re-applying a voice mark) after any failure that consumed
  the token; refusals before the token is taken are unchanged (`Err`).
- Ruling 12: a) `SendFailure { kind, message }` if UniFFI rejects `FfiError` as a field;
  b) expired/dropped-token sends leave a failed chip — accepted; c) desktop typing line moves in
  PR4; d) `RosterRow.avatar` and `SpaceSummary.display_name` stay; e) the core session owns
  "sent this session", cleared on sign-out and on leaving the room; f) all demo copy moves.
- Every new core type that crosses both boundaries derives
  `Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record|uniffi::Enum` with
  `#[serde(rename_all = "camelCase")]` (drop `Deserialize` only where a field type lacks it,
  as noted per task). New `RosterRow`/`SpaceSummary` fields go **last**; optional ones carry
  `#[uniffi(default = None)]` and `#[serde(default, skip_serializing_if = "Option::is_none")]`.
- Ellipsis is U+2026 `…`, em dash U+2014 `—`, middle dot U+00B7 `·`, apostrophes U+0027.
- Falsification (AGENTS.md): for every ported test, mutate the implementation, watch it fail,
  restore, and note the mutation in the test's doc comment ("Mutation seen failing: …").
- One heavy cargo build at a time (31 GB RAM, `/tmp` is RAM).

**Procedure B — bindings (every PR, and again after every rebase):**

```bash
cargo build -p supermessage-ffi
STAGE=$(mktemp -d)
cargo run -q -p supermessage-ffi --bin uniffi-bindgen -- generate \
    --library target/debug/libsupermessage_ffi.so --language swift --no-format --out-dir "$STAGE"
mkdir -p "$STAGE/headers" && mv "$STAGE"/*.h "$STAGE/headers/"
cat "$STAGE"/*.modulemap > "$STAGE/headers/module.modulemap" && rm -f "$STAGE"/*.modulemap
rm -rf apple/Generated && cp -r "$STAGE" apple/Generated
./scripts/generate-kotlin-bindings.sh                 # what CI's Android job diffs
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/29.0.14206865"
./scripts/build-android-libs.sh                       # ruling 8; ~15 min, alone
git status --short apple/Generated android/core/src/main/kotlin/uniffi
```
Expected: both generated trees changed, and nothing else regenerated unexpectedly.

**Procedure V — local verification (every PR):**

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace
pnpm test && pnpm check
(cd android && ./gradlew test)          # proves the regenerated Kotlin compiles; needs the host .so
./scripts/snapshot-stories.sh           # PR2 and PR4 only (they touch desktop components)
```
Expected: all green; `snapshot-stories.sh` reports no differing frame (never `--record`).

**CI (every PR):** jobs `Rust`, `Frontend`, `Android` (bindings diff + unit tests) and `iOS`
(`macos-15`: bindings diff, Kit tests, app build-for-testing, `snapshot-previews.sh`, Release
leak check, push/NSE/widgets build) green on the PR head. iOS cannot be built here; the `iOS`
job is the only proof of the switch-over. No iOS preview baseline may change in this plan: a
differing frame is a defect to diagnose, not a baseline to replace. If the `iOS` job is red for
a reason outside the task, the core API merges and the switch-over moves to a follow-up PR
(roadmap).

## Review Focus

1. **A bridge agent answers by editing a progress message.** The dock must go away, not hang
   in "Sent to". Pinned in Task 9 by `a_progress_message_the_agent_edits_ends_the_dock`.
2. **A room with several agents and several humans.** No single addressee; working agents are
   named the core's way; with the agents unknown, any other member's reply ends the dock.
   Pinned in Task 9 by `three_or_more_working_agents_are_named_the_cores_way` and
   `with_the_agents_unknown_anyone_elses_reply_ends_it`, and in Task 10 by the two-agent case.
3. **Sign out and back in as another account.** No cast, and no "Sent to …", from the first
   account. Pinned in Task 10 by `room_cast_without_a_session_is_not_ready` and
   `signing_out_forgets_every_room_sent_in` (the core session now owns "sent this session",
   ruling 12e).
4. **An attachment send that fails offline.** The chip stays sendable with a fresh token, a
   voice note stays a voice note, and a vanished file leaves an explained failed chip. Pinned in
   Task 3 by the three `failure_outcome` tests and in Task 4 by the Swift outcome tests.
5. **A stored chip from an older build** (`"agents"`, `"needsYou"`, `"machine"`, empty). Reads
   as All, never as a narrowed list with no chip lit. Pinned in Task 5 by
   `a_stored_filter_that_is_not_offered_reads_as_all`.

## iOS behaviour changes

Every visible iOS change this plan causes. Everything not listed must look identical, and the
preview baselines must not change.

| PR | Change | Cause |
|---|---|---|
| PR2 | A send refused because the staged token is gone (`UnknownAttachment` — swept after 10 min, or by a room switch) now leaves a **failed chip** ("Couldn't send…", remove or re-pick) instead of silently re-staging the file. A `RoomChanged` refusal keeps the chip's original token, which is still live. | Ruling 11: refusals before the take are unchanged `Err`s; the host re-stage is deleted. **Accepted** (ruling 12b): the core cannot re-stage without the path. |
| PR4 | In a room whose name follows the `<glyph> Name — Role` convention but carries **no runtime**, with exactly one agent member, that agent now takes the header's name on the typing line, the dock ("Sent to Atlas" instead of "Sent to atlas-bot") and the live card. | Ruling 1: `describes_agent` replaces `runtime != nil`. |
| PR4 | Three or more people typing: "Ann and 2 others are typing…" becomes "Ann, Bob and 1 other are typing…" (four: "… and 2 others …"). | Ruling 1: `people_label` shape. |
| PR4 | Three or more agents working: "Atlas and 2 others are on it…" becomes "Atlas, Quill and 1 other are on it…". | Ruling 1. |
| PR5 | A space named with non-ASCII digits or full-width hex (e.g. `９２４７ｅ５ｃ１`) is no longer rewritten to "Runtime …"; only ASCII hex and `-` read as an id. | Rust port uses ASCII checks; Swift's `isHexDigit`/`isNumber` were Unicode-wide. |

Outside iOS, PR4 changes Android's typing line for three or more typers
the same way; the desktop's wording is already the core's and does not change.

---

## PR1 — M1.5 Agent avatar style (branch `parity/m1-core-avatar`)

### Task 1: `core::agent_avatar` and its exports

**Files:**
- Create: `C/agent_avatar.rs`; register `pub mod agent_avatar;` in `C/lib.rs:32-73` (alphabetical)
- Modify: `C/roster.rs:74-86` (`RosterRow.avatar`), `C/roster.rs:171-178` (set it)
- Modify: `F` (beside `peer_color_index`, ~:1073)
- Modify: `T/commands.rs` (beside `roster_hidden_invitations`), `T/lib.rs:22-30` and the
  `generate_handler!` list
- Modify: `src/lib/ipc.ts` (beside `RosterRow`, ~:1679), `src/lib/ipc.test.ts`
- Create (R10, PR1 only): `docs/superpowers/plans/2026-10-22-parity-m1a-core-rules.md`,
  `…-parity-m1b-turns-timeline.md`, `…-parity-m2-android.md`, `…-parity-m2-desktop.md` (copies of
  the four plan files from `.superpowers/sdd/planning-m1-m2/`, same filenames)
- Modify (R10, PR1 only): `docs/research/2026-10-22-ios-parity/README.md` (§2 B6 row and D12 row)

**Interfaces:**
- Produces (core):
  - `pub enum AvatarRole { Accent, AccentSoft, AccentContent, Ok, ContentMuted, Surface, SurfaceRaised }` (`Copy, Eq, Hash` too; serde camelCase → `"accentSoft"` …)
  - `pub struct AgentAvatarStyle { pub ground: AvatarRole, pub ink: AvatarRole, pub variant: u8 }`
  - `pub const VARIANTS: [(AvatarRole, AvatarRole); 6]`
  - `pub fn fnv1a64(text: &str) -> u64`
  - `pub fn style_for_room(room_id: &str) -> AgentAvatarStyle`
  - `pub fn variants() -> Vec<AgentAvatarStyle>`
  - `RosterRow.avatar: Option<AgentAvatarStyle>` — `Some(style_for_room(id))` iff `describes_agent`
- Produces (FFI, free): `agent_avatar_style(room_id: String) -> AgentAvatarStyle`,
  `agent_avatar_variants() -> Vec<AgentAvatarStyle>`
- Produces (Tauri): `agent_avatar_style(room_id: String)`, `agent_avatar_variants()`; TS
  `type AvatarRole`, `interface AgentAvatarStyle { ground; ink; variant: number }`,
  `RosterRow.avatar?: AgentAvatarStyle`, `agentAvatarStyle(roomId: string): Promise<AgentAvatarStyle>`,
  `agentAvatarVariants(): Promise<AgentAvatarStyle[]>`

- [ ] **Step 0: Plans and research note travel with the work (R10).** Copy the four plan files
  from `.superpowers/sdd/planning-m1-m2/` into `docs/superpowers/plans/` under the same
  filenames. In `docs/research/2026-10-22-ios-parity/README.md`, append a dated superseding note
  to the §2 **B6** row and to the **D12** row, without editing the matrix values themselves:
  "*Superseded 2026-10-22, during M1 planning: iOS re-stages a failed attachment
  (`StagedAttachment.swift:143-178`); the M0 correction was wrong; tracked as M1.7.*" Commit
  `docs: plans for parity M1/M2 and the B6/D12 superseding note`.
- [ ] **Step 1: Write the failing tests** in `C/agent_avatar.rs` (ported from
  `KT/RosterViewsTests.swift:176-203`):
  - `a_rooms_tile_is_the_same_every_time_it_is_asked_for`: `fnv1a64("") == 0xcbf2_9ce4_8422_2325`;
    `fnv1a64("a") == 0xaf63_dc4c_8601_ec8c`; `style_for_room("!atlas:example.org")` equals itself.
  - `tiles_spread_across_the_palette_rather_than_collapsing_onto_one`: the `variant`s of
    `"!room0:example.org"…"!room59:example.org"` form a set of 6.
  - `no_tile_draws_amber_or_red_and_no_tile_is_ink_on_its_own_ground`: `ground != ink` for
    every `variants()`; serialising every `AvatarRole` yields none of `"signal"`, `"danger"`.
  - New, pinned so any later port cannot drift: `"!atlas:example.org"` → variant 3
    `(SurfaceRaised, Accent)`; `"!quill:example.org"` → 5 `(SurfaceRaised, Ok)`;
    `"!room0:example.org"` → 1 `(AccentSoft, Accent)`; `"!room1:example.org"` → 4
    `(ContentMuted, Surface)`; `"demo.atlas"` → 5.
  - New in `C/roster.rs` tests: `an_agents_row_carries_its_tile_and_a_peoples_row_none` —
    `sections` over `on_host("!h:x", …)` gives `avatar == Some(style_for_room("!h:x"))`; over
    `row("research", …)` gives `None`.
- [ ] **Step 2: Run and confirm they fail.** `cargo test -p supermessage-core agent_avatar roster`
  → compile errors (module and field missing).
- [ ] **Step 3: Implement.** `VARIANTS` in this exact order (index = `variant`):
  0 `(Accent, AccentContent)`, 1 `(AccentSoft, Accent)`, 2 `(Ok, Surface)`,
  3 `(SurfaceRaised, Accent)`, 4 `(ContentMuted, Surface)`, 5 `(SurfaceRaised, Ok)`.
  `fnv1a64`: offset `0xcbf2_9ce4_8422_2325`, prime `0x0000_0100_0000_01b3`, `wrapping_mul`, over
  UTF-8 bytes. `style_for_room`: `VARIANTS[(fnv1a64(id) % 6) as usize]`. Module doc carries the
  Swift file's rationale (id only, not `Hasher`; no `signal`, no `danger`). Add the `RosterRow`
  field last with the defaults from Global Constraints.
- [ ] **Step 4: Run and confirm they pass.** Same command → PASS. Mutate (swap two variants;
  use `% 5`) and record the failures.
- [ ] **Step 5: Export.** FFI free functions (doc: "A free function for the same reason as
  `peer_color_index`"); Tauri commands with the same bodies, imported and registered; TS types
  and wrappers. `ipc.test.ts`: `agentAvatarStyle invokes agent_avatar_style with roomId` asserts
  `invokeMock` called with `("agent_avatar_style", { roomId: "!a:x" })`.
- [ ] **Step 6: Run** `cargo test --workspace && pnpm test && pnpm check` → PASS.
- [ ] **Step 7: Commit.** `core: agent avatar style and its exports (M1.5)`.

### Task 2: Bindings and the iOS switch-over (M1.5)

**Files:**
- Regenerate: `apple/Generated/*`, `android/core/src/main/kotlin/uniffi/**`
- Delete: `K/AgentAvatarStyle.swift`
- Modify: `A/Rooms/RoomAvatar.swift:46` (`agentAvatarStyle(roomId: roomId)`), `:48`
  (`style.ground == .surfaceRaised`), `:92` (`static func colour(_ role: AvatarRole) -> Color`)
- Modify: `A/Previews/NavigationRevampFixtures.swift:19-21` — `agentAvatarVariants().count` and
  `Int(agentAvatarStyle(roomId: id).variant)` in place of `variants.firstIndex(of:)`
- Modify: `KT/RosterViewsTests.swift:176-203` — delete the three avatar tests; add one thin test

**Interfaces:** consumes Task 1's FFI names (Swift: `agentAvatarStyle(roomId:)`,
`agentAvatarVariants()`, `AvatarRole`, `AgentAvatarStyle`).

- [ ] **Step 1: Procedure B.**
- [ ] **Step 2: Switch iOS** as listed. Swift test kept as the boundary check (it is what
  catches a checksum mismatch at run time): `@Test("the tile is the core's")` —
  `agentAvatarStyle(roomId: "!atlas:example.org")` has `variant == 3`, `ground == .surfaceRaised`,
  `ink == .accent`.
- [ ] **Step 3: Procedure V.** Expected: green; Android compiles the new `RosterRow` field
  through its default.
- [ ] **Step 4: Commit** `ios: draw agent tiles from the core (M1.5)` and open PR1. Wait for
  CI; the `RoomAvatar` "Avatars" and "Avatars dark" previews must match their baselines.

---

## PR2 — M1.7 Attachment send outcome (branch `parity/m1-core-send-outcome`)

### Task 3: `send_staged` returns an outcome and re-stages in the core

**Files:**
- Modify: `C/attachments.rs:693-760` (`send_staged`), new helpers beside it, tests in its
  `mod tests`
- Modify: `F:684-700` (`attachment_send`), new enum beside `StagedFile` (~:1026)
- Modify: `T/commands.rs:670-684` (`attachment_send`)
- Modify: `src/lib/ipc.ts:1848-1854`, `src/lib/ipc.test.ts`, `src/lib/components/Composer.svelte:380-404`
- Modify: `AK/CoreClient.kt:264-266`, `AK/stores/StagedAttachment.kt:57-72`,
  `AKT/stores/StagedAttachmentTest.kt` (fake at ~:179),
  `android/kit/src/testFixtures/kotlin/dev/supermessage/kit/StubCore.kt:34`

**Interfaces:**
- Produces (core):
  - `pub enum AttachmentSendOutcome { Sent, Failed { error: CoreError, restaged: Option<StagedAttachment> } }`
    — `Debug, Serialize` only (`CoreError` is neither `Clone` nor `Deserialize`),
    `#[serde(tag = "outcome", rename_all = "camelCase")]` → `{"outcome":"sent"}` /
    `{"outcome":"failed","error":{"kind","message"},"restaged":{…}|null}`
  - `pub async fn send_staged(…same params…) -> CoreResult<AttachmentSendOutcome>` — `Err` only
    from `active_timeline_for`, `require_client` and `take_for_send` (before the take)
  - `pub(crate) async fn failure_outcome(client: &Client, staged: &StagedAttachments, room_id: &str, path: PathBuf, voice: Option<VoiceNote>, error: CoreError) -> AttachmentSendOutcome`
- Produces (FFI): `#[derive(uniffi::Enum)] pub enum AttachmentSendOutcome { Sent, Failed { error: FfiError, restaged: Option<StagedFile> } }`
  with `From` the core enum; `Core::attachment_send(…) -> Result<AttachmentSendOutcome, FfiError>`
- Produces (Tauri): `attachment_send(…) -> Result<AttachmentSendOutcome, CoreError>`; TS
  `type AttachmentSendOutcome = { outcome: "sent" } | { outcome: "failed"; error: CoreError; restaged: StagedAttachment | null }`;
  `attachmentSend(roomId, token, caption = null): Promise<AttachmentSendOutcome>`

- [ ] **Step 1: Spike the FFI shape first.** Add the FFI enum with `error: FfiError`, run
  `cargo build -p supermessage-ffi` and the Swift and Kotlin generation from Procedure B into a
  scratch directory. Expected: both generate and `cd android && ./gradlew :core:compileDebugKotlin`
  compiles. **If UniFFI 0.28.3 refuses an error type as a variant field** (ruling 12a), take
  this branch instead and keep everything else in this task:
  - FFI: `#[derive(Debug, Clone, uniffi::Record)] pub struct SendFailure { pub kind: String, pub message: String }`,
    built from the `CoreError` as `kind: error.kind().to_string(), message: error.to_string()` —
    the same two fields the desktop's `CoreError` JSON carries;
    `Failed { error: SendFailure, restaged: Option<StagedFile> }`.
  - Swift: `ErrorPresenter.message(for failure: SendFailure) -> String`, giving the copy the
    matching `FfiError` case gives today (kinds `"auth"`, `"network"`, `"store"`, `"protocol"`,
    `"notReady"`, `"roomChanged"`, `"attachmentTooLarge"`, `"unknownAttachment"`; unknown kind →
    the message), with a Kit test per kind; Task 4 calls it in place of the `FfiError` overload.
  - Kotlin: the same overload in Android's `ErrorPresenter`, used by Step 6.
  - Tauri and TS are unchanged (they already carry `{ kind, message }`).
- [ ] **Step 2: Write the failing tests** in `C/attachments.rs` `mod tests`, each a
  `#[tokio::test]` on a `MatrixMockServer` client with
  `mock_authenticated_media_config().ok_default()` mounted, a small PNG written under
  `std::env::temp_dir()` with a unique name, staged with `stage_path`, then consumed with
  `take_for_send` (the state `send_staged` is in when an upload fails). Ported from
  `KT/ComposerRevampTests.swift` `StagedAttachmentTests`:
  - `a_failed_send_puts_a_fresh_token_behind_the_file_and_keeps_the_error` ("a failed send puts
    a fresh token behind the chip…", :217): `failure_outcome(…, CoreError::Network("upload failed".into()))`
    is `Failed { error: Network(_), restaged: Some(fresh) }`; `fresh.token != old_token`;
    `fresh.filename == "shot.png"`; `staged.len() == 1`.
  - `a_failed_send_re_marks_the_fresh_token_so_the_retry_is_still_a_voice_message` (:249): with
    `voice = Some(VoiceNote { duration_ms: 1_000, waveform: vec![0.0, 0.5, 1.0] })`,
    `take_for_send(&fresh.token, room)` returns `Some(voice)` with `duration_ms == 1_000` and that
    waveform.
  - `when_the_file_cannot_be_staged_again_the_failure_has_nothing_behind_it` (:273): delete the
    file before calling → `Failed { restaged: None, .. }`, error kind unchanged, `staged.len() == 0`.
  - The existing token-rule tests (room-bound refusal leaves the entry; unknown token) stay
    green: refusals before the take are unchanged.
- [ ] **Step 3: Run and confirm they fail.** `cargo test -p supermessage-core attachments`
  → `failure_outcome` not found.
- [ ] **Step 4: Implement.** In `send_staged`, keep `voice.clone()` before `voice_upload`
  consumes it; move everything after `take_for_send` into a private
  `async fn send_taken(…) -> CoreResult<()>`; map its `Err(e)` to
  `Ok(failure_outcome(&client, staged, room_id, path, original_voice, e).await)`, success to
  `Ok(Sent)`. `failure_outcome`: `stage_path(client, staged, room_id, path)`; on `Ok(fresh)`
  and `Some(voice)`, `staged.mark_voice(&fresh.token, room_id, voice)` (a failed mark is logged,
  not fatal — the file still sends, as iOS documents). Move the "token after a failed send"
  rationale from `K/Stores/StagedAttachment.swift:18-33` into `send_staged`'s doc comment.
- [ ] **Step 5: Run and confirm they pass.** Mutate (drop the re-mark; return `restaged: None`
  always) and record the failures.
- [ ] **Step 6: Export and keep the hosts' current behaviour.**
  - FFI and Tauri as in Interfaces; TS type and wrapper; `ipc.test.ts`:
    `attachmentSend returns the core's outcome` (mock resolves
    `{ outcome: "failed", error: { kind: "network", message: "x" }, restaged: null }`; the wrapper
    returns it unchanged and invoked `("attachment_send", { roomId, token, caption: null })`).
  - `Composer.svelte` `sendStaged`: `const outcome = await attachmentSend(…)`; when
    `outcome.outcome === "failed"`, discard `outcome.restaged?.token` if present and
    `throw outcome.error` into the existing `catch`. Desktop behaviour is unchanged (the strip is
    dropped and the failure shown); consuming `restaged` is desktop M2 Task 5a.
  - Android: `CoreClient.attachmentSend(…): AttachmentSendOutcome`; `StagedAttachment.send` on
    `Failed` discards `restaged?.token` and returns `ErrorPresenter.message(outcome.error)`,
    leaving `_file` as today (Android M2 Task 8 consumes `restaged`; it must not port the Swift
    restage). Fakes return `AttachmentSendOutcome.Sent`. New test in `StagedAttachmentTest.kt`:
    `failedOutcomeReturnsTheMessageAndReleasesTheFreshToken` — fake returns
    `Failed(FfiException.Network("upload failed"), StagedFile("tok-2", …))`; `send` returns
    non-null and the fake recorded `discard("tok-2")`.
- [ ] **Step 7: Run** `cargo test --workspace`, `pnpm test && pnpm check`,
  `./scripts/snapshot-stories.sh`, `(cd android && ./gradlew test)` after Procedure B → PASS.
- [ ] **Step 8: Commit.** `core: attachment send outcome, re-staged in the core (M1.7)`.

### Task 4: Bindings and the iOS switch-over (M1.7)

**Files:**
- Regenerate both bindings (Procedure B)
- Modify: `K/CoreSeam.swift:83` → `func attachmentSend(roomId: String, token: String, caption: String?) async throws -> AttachmentSendOutcome`;
  `K/CoreClient.swift:343`; `A/Previews/PreviewFixtures.swift:126` (returns `.sent`)
- Modify: `K/Stores/StagedAttachment.swift:135-178` — `send` uses the outcome; delete
  `restage(after:in:)` and the type doc's "The token after a failed send" section (now in core)
- Modify: `KT/ComposerRevampTests.swift:13-55` (stub), `:212-299` (`StagedAttachmentTests`)

**Interfaces:** consumes `AttachmentSendOutcome` (Swift: `.sent`, `.failed(error:restaged:)`).

- [ ] **Step 1: Procedure B.**
- [ ] **Step 2: Rewrite `StagedAttachment.send(in:caption:)`.** `.sent` → clear `file`, `path`,
  `failure`, `voice`, `roomId`, return `nil`. `.failed(error, restaged)` → `file = restaged`,
  `failure = Failure(filename: <sent file's filename>, message: ErrorPresenter.message(for: error))`,
  `voice` kept, return the message. A thrown `FfiError` (refused before the take) →
  `failure` set; `file = nil` only for `.UnknownAttachment`, otherwise the file is kept.
- [ ] **Step 3: Update the Swift tests.** Stub: `attachmentSend` returns `.sent`, or with
  `failAttachmentSend` returns `.failed(error: .Network(detail: "upload failed"), restaged: <next StagedFile "tok-N">)`,
  `restaged: nil` when `failStagingFrom` is reached; add `refuseAttachmentSend: FfiError?` to
  throw. Keep "a failed send puts a fresh token behind the chip…" (drop its
  `.discard("tok-1")` expectation — the core consumed it), "when the file cannot be staged
  again…", the room-scope, mark-voice and discard tests. Delete "a failed send re-marks the
  fresh token…" (ported, Task 3). Add `@Test("a refusal for a token the core no longer holds leaves a failed chip")`
  (`UnknownAttachment` → `file == nil`, `blocksSend(in: room)`) and
  `@Test("a room-changed refusal keeps the chip sendable")` (`RoomChanged` → `file?.token == "tok-1"`,
  `!blocksSend(in: room)`).
- [ ] **Step 4: Procedure V** (stories included).
- [ ] **Step 5: Commit** `ios: take the core's send outcome, drop the host re-stage (M1.7)`;
  open PR2 after PR1 merged and rebased. Composer previews ("Attachment staged", "Chip send
  failed") must match their baselines.

---

## PR3 — M1.1 Room status and roster views (branch `parity/m1-core-roster-views`)

### Task 5: Roster filters, the Needs you inbox, the agent directory, one room's row

**Files:**
- Modify: `C/roster.rs` (new items after `sections`, tests in its `mod tests`)

**Interfaces:**
- Produces (all in `core::roster`):
  - `pub enum RosterFilter { All, Unread, Agents, NeedsYou }` (`Copy, Eq, Hash`; serde
    `"all" | "unread" | "agents" | "needsYou"` — the iOS raw values, so stored choices survive)
  - `impl RosterFilter { pub fn title(&self) -> &'static str; pub fn key(&self) -> &'static str; pub fn admits(&self, row: &RosterRow) -> bool }`
  - `pub const OFFERED_FILTERS: [RosterFilter; 2] = [All, Unread]`
  - `pub fn filter_from_key(key: &str) -> RosterFilter`
  - `pub fn filter_sections(sections: &[RosterSection], filter: RosterFilter) -> Vec<RosterSection>`
  - `pub struct RosterFilterChip { pub filter: RosterFilter, pub title: String, pub count: u32 }`
  - `pub fn filter_chips(sections: &[RosterSection]) -> Vec<RosterFilterChip>` — one per offered filter, in order
  - `pub struct RosterEmptyState { pub title: String, pub description: String }`
  - `pub fn filter_empty_state(filter: RosterFilter) -> RosterEmptyState`
  - `pub struct NeedsYouInbox { pub decisions: Vec<RosterRow>, pub invitations: Vec<RosterRow>, pub count: u32, pub badge_count: u32 }`
  - `pub fn needs_you_inbox(rows: &[RoomRow], now_ms: u64) -> NeedsYouInbox`
  - `pub fn agent_directory(rows: &[RoomRow], now_ms: u64) -> Vec<RosterRow>`
  - `pub fn roster_row(rows: &[RoomRow], room_id: &str, now_ms: u64) -> Option<RosterRow>`

- [ ] **Step 1: Write the failing tests.** Fixture mirrors `KT/RosterViewsTests.swift:14-56`:
  `NOW = 1_700_000_000_000`; `fixture(id, minutes_ago, unread, pending, agent, invited)` built
  on `RoomRow::new` (agent → `RuntimeDto { harness: "claude-code", host: "foundry" }`;
  invited → `Membership::Invited` + `RoomAffordance::RespondToInvitation`;
  `preview = RoomPreview { text: "hi", pending }`); `roster()` = a(1, unread 3, agent),
  b(2, pending, agent), c(3, unread 1), d(4, invited), e(5, pending);
  `waiting()` = `sections(&roster(), Waiting, false, NOW)`. Ported tests:
  - `all_is_the_cores_arrangement_unchanged` (:58): `filter_sections(&w, All) == w`.
  - `unread_keeps_only_rooms_with_unread_messages_in_the_cores_order` (:63): ids `[["a","c"]]`.
  - `agents_keeps_rooms_that_read_as_an_agents_across_sections` (:69): `[["b"],["a"]]`.
  - `needs_you_keeps_rooms_owing_an_answer` (:74): `[["b","e"]]`.
  - `filtering_never_reorders` (:79): over `sections(&roster(), Recent, true, NOW)`, for every
    filter the kept ids equal the flattened rows filtered by `admits`, in order.
  - `a_section_that_lost_rows_drops_its_count_an_untouched_one_keeps_it` (:90): `w[0].detail ==
    Some("2")`; Agents → `detail == None`; NeedsYou → `Some("2")` and the same title.
  - `the_inbox_is_pending_decisions_plus_invitations_and_the_badge_counts_decisions` (:107; the
    Swift title is wrong, the assertion is decisions only): decisions `["b","e"]`, invitations
    `["d"]`, `count == 3`, `badge_count == 2`.
  - `an_answered_decision_leaves_the_inbox` (:118): `[b not pending agent, c unread 4]` → `count == 0`.
  - `an_invitation_is_not_also_counted_as_a_decision` (:126): invited+pending `x` → decisions
    empty, `count == 1`, `badge_count == 0`.
  - `the_agents_tab_lists_agent_rooms_newest_first_and_leaves_out_invitations` (:137): roster +
    invited agent `z` (0.5 min) → `["a","b"]`.
  - `a_single_rooms_roster_row_is_the_one_the_core_arranged` (:159): `b` → `NeedsYou`,
    `describes_agent`; `c` → not; `"nope"` → `None`.
  - New: `a_stored_filter_that_is_not_offered_reads_as_all` — `"all"`→All, `"unread"`→Unread,
    `"agents"`, `"needsYou"`, `"machine"`, `""` → All.
  - New: `chips_count_what_each_offered_chip_would_leave` — `filter_chips(&waiting())` ==
    `[(All, "All", 4), (Unread, "Unread", 2)]`.
  - New: `each_chip_says_why_it_left_nothing` — All `("Nothing here", "")`; Unread
    `("Nothing unread", "Every room is read.")`; Agents `("No agents here", "No room here reads as an agent's.")`;
    NeedsYou `("You're all caught up", "Nothing is waiting on you.")` (copy from
    `A/Rooms/RoomListView.swift:219-235`); titles `"All"`, `"Unread"`, `"Agents"`, `"Needs you"`.
- [ ] **Step 2: Run and confirm they fail.** `cargo test -p supermessage-core roster` → unresolved names.
- [ ] **Step 3: Implement.** `admits`: All true; Unread `row.row.room.unread > 0`; Agents
  `row.describes_agent`; NeedsYou `row.state == NeedsYou`. `filter_sections`: All → clone;
  else keep admitted rows in order, drop empty sections, set `detail = None` when any row was
  removed. `needs_you_inbox`: flatten `sections(rows, Recent, true, now)`; decisions = state
  NeedsYou and affordance ≠ RespondToInvitation; invitations = affordance RespondToInvitation.
  `agent_directory`: flatten `sections(rows, Recent, false, now)`, keep `describes_agent`.
  `roster_row`: find by id, return the first row of `sections(&[row], Recent, true, now)`.
  Module-level doc gains the Swift header's rule ("keeps or drops, never orders").
- [ ] **Step 4: Run and confirm they pass**; mutate (`detail` kept; invitations counted in the
  badge; `filter_from_key` accepting `"agents"`) and record.
- [ ] **Step 5: Commit.** `core: roster filters, inbox, agent directory (M1.1)`.

### Task 6: Room status and the mute toggle

**Files:**
- Modify: `C/roster.rs` (after Task 5's items), `C/room_info.rs:70` (`NotificationMode`
  gains `Deserialize`; `next_notification_mode` beside it)

**Interfaces:**
- Produces:
  - `pub enum RoomStatus { Working, NeedsYou, Active, Idle, Quiet }` (serde camelCase)
  - `pub fn room_status(state: AgentState, describes_agent: bool, turn_in_progress: bool) -> Option<RoomStatus>`
  - `pub fn room_status_word(status: RoomStatus) -> &'static str`
  - `pub fn next_notification_mode(current: NotificationMode) -> NotificationMode` (in `room_info`)

- [ ] **Step 1: Write the failing tests.**
  - `the_header_status_vocabulary` (`KT/RosterViewsTests.swift:146`):
    `(Active, true, true)` → Working; `(NeedsYou, true, true)` → NeedsYou; `(Active, true, false)`
    → Active; `(Idle, true, false)` → Idle; `(Quiet, true, false)` → Quiet;
    `(NeedsYou, false, false)` → None; `(Active, false, true)` → None.
  - New: `the_status_words` — `"Working"`, `"Needs you"`, `"Active"`, `"Idle"`, `"Quiet"`.
  - `unmuting_restores_the_default_rather_than_inventing_a_rule` (:168, in `room_info` tests):
    Muted → Default; Default, AllMessages, MentionsOnly → Muted.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.** Order: not `describes_agent` → None; NeedsYou → NeedsYou;
  `turn_in_progress` → Working; else Active/Idle/Quiet by `state`. Doc comment says it differs
  from `widget::agent_state`'s lower-case "working" (a stored step within `ACTIVE_WITHIN_MS`),
  which is a different input, and that `turn_in_progress` is the host's until M1.3 (ruling 7).
- [ ] **Step 4: Run and confirm they pass**; mutate (live turn outranking NeedsYou) and record.
- [ ] **Step 5: Commit.** `core: room status and the mute toggle (M1.1)`.

### Task 7 (PR3): Exports for the roster views

**Files:** `F` (beside `roster_hidden_invitations`, ~:1162), `T/commands.rs` + `T/lib.rs`,
`src/lib/ipc.ts` (beside `rosterSections`, ~:1712), `src/lib/ipc.test.ts`

**Interfaces:**
- Produces (FFI free functions; Tauri commands of the same names and bodies; `ipc.ts`
  camelCase wrappers returning `Promise<…>`):
  - `roster_filter_sections(sections: Vec<RosterSection>, filter: RosterFilter) -> Vec<RosterSection>`
  - `roster_filter_chips(sections: Vec<RosterSection>) -> Vec<RosterFilterChip>`
  - `roster_filter_from_key(key: String) -> RosterFilter`
  - `roster_filter_key(filter: RosterFilter) -> String`
  - `roster_filter_empty_state(filter: RosterFilter) -> RosterEmptyState`
  - `roster_needs_you_inbox(rows: Vec<RoomRow>, now_ms: u64) -> NeedsYouInbox`
  - `roster_agent_directory(rows: Vec<RoomRow>, now_ms: u64) -> Vec<RosterRow>`
  - `roster_row(rows: Vec<RoomRow>, room_id: String, now_ms: u64) -> Option<RosterRow>`
  - `room_status(state: AgentState, describes_agent: bool, turn_in_progress: bool) -> Option<RoomStatus>`
  - `room_status_word(status: RoomStatus) -> String`
  - `next_notification_mode(current: NotificationMode) -> NotificationMode`
  - TS: `RosterFilter`, `RosterFilterChip`, `RosterEmptyState`, `NeedsYouInbox`, `RoomStatus`.

- [ ] **Step 1: Write the failing `ipc.test.ts` cases**: `rosterNeedsYouInbox invokes
  roster_needs_you_inbox with rows and nowMs`; `roomStatus passes describesAgent and
  turnInProgress camelCase`; `rosterFilterSections passes sections and filter`.
- [ ] **Step 2: Run** `pnpm test -- ipc` → FAIL (wrappers missing).
- [ ] **Step 3: Implement** the FFI functions, the Tauri commands (imported in `T/lib.rs:22-30`
  and listed after `roster_hidden_invitations`), the TS types and wrappers.
- [ ] **Step 4: Run** `cargo test --workspace && pnpm test && pnpm check` → PASS.
- [ ] **Step 5: Commit.** `core: export roster views and room status (M1.1)`.

### Task 8 (PR3): Bindings and the iOS switch-over (M1.1)

**Files:**
- Regenerate both bindings
- Delete: `K/RosterViews.swift`
- Modify: `K/RosterArrangement.swift:62-88` — add `needsYouInbox(_ rows: [RoomRow], now: Date) -> NeedsYouInbox`,
  `agentDirectory(_ rows: [RoomRow], now: Date) -> [RosterRow]`,
  `rosterRow(for roomId: String, in rows: [RoomRow], now: Date) -> RosterRow?`, each one call
  through `milliseconds(now)`
- Modify call sites: `A/Navigation/PhoneShell.swift:23`, `A/Navigation/SplitShell.swift:45`
  (`Int(RosterArrangement.needsYouInbox(…).badgeCount)`); `A/Inbox/NeedsYouView.swift:10,18`
  (`.isEmpty` → `count == 0`); `A/Agents/AgentsView.swift:17,25-27,70`;
  `A/Platform/PlatformCoordinator.swift:319`; `A/Navigation/RoomScreen.swift:75,81-84` and the
  status pill (`status.word` → `roomStatusWord(status:)`; previews' `RoomStatus.allCases` →
  `[.working, .needsYou, .active, .idle, .quiet]`); `A/Rooms/RoomListView.swift:32`
  (default `rosterFilterKey(filter: .all)`), `:56-62` (`rosterFilterFromKey(key:)`,
  `rosterFilterKey(filter:)`), `:86` (`rosterFilterSections`), `:93-99` (`rosterFilterChips`),
  `:199` (Show all), `:219-235` (`rosterFilterEmptyState(filter:)`), `:313`
  (`nextNotificationMode(current:)`); `A/Rooms/RosterFilterChips.swift` (takes
  `chips: [RosterFilterChip]`; drops `offered`; label `chip.title`; count shown when
  `filter != .all && count > 0`; a11y `"\(title), \(count)"`; preview passes
  `[RosterFilterChip(filter: .all, title: "All", count: 5), RosterFilterChip(filter: .unread, title: "Unread", count: 2)]`)
- Modify: `KT/RosterViewsTests.swift` — delete the filter, inbox, agents, status, row and mute
  tests (ported, Tasks 5–6); keep one thin boundary test

- [ ] **Step 1: Procedure B.**
- [ ] **Step 2: Switch iOS** as listed; nothing in `App` may compute a filter, count, status or
  inbox itself.
- [ ] **Step 3: Thin Swift test** `@Test("the roster views are the core's")`:
  `RosterArrangement.needsYouInbox(Self.roster, now: Self.now).badgeCount == 2` and
  `rosterFilterSections(sections: Self.waiting(), filter: .unread)` ids `[["a","c"]]`.
- [ ] **Step 4: Procedure V.**
- [ ] **Step 5: Commit** `ios: roster views and room status from the core (M1.1)`; open PR3
  after PR2 merged. Baselines `RosterFilterChips` "Filter chips", `NeedsYouView` (three),
  `AgentsView` (two), `RoomRowView` (three) and `TextScalePreviews` roster frames must match.

---

## PR4 — M1.2 Room cast and acknowledgement (branch `parity/m1-core-cast`)

### Task 9: Names, the typing line and the acknowledgement

**Files:**
- Modify: `C/display_name.rs:254-272` — extract `pub fn names_label(names: &[String]) -> String`;
  `people_label` maps ids through `user_label` and calls it (existing `people_label_tests` stay green)
- Modify: `C/dto.rs:474` — `TypingUserDto` gains `Deserialize`
- Create: `C/room_cast.rs` (this task: `Counterpart` only; Task 10 fills the rest),
  `C/typing_line.rs`, `C/acknowledgement.rs`; register all three in `C/lib.rs`

**Interfaces:**
- Produces:
  - `room_cast::Counterpart { pub user_id: String, pub name: String }` (Record, `Deserialize`)
  - `typing_line::TypingView { pub line: Option<String>, pub agents: Vec<String> }`
  - `typing_line::typing_view(typers: &[TypingUserDto], agent_ids: &[String], counterpart: Option<&Counterpart>) -> TypingView`
  - `acknowledgement::AcknowledgementKind { Sent, OnIt }`
  - `acknowledgement::Acknowledgement { pub kind: AcknowledgementKind, pub names: Vec<String>, pub text: String }`
  - `acknowledgement::state(rows: &[TimelineRow], addressee: Option<&str>, agent_ids: &[String], sent_this_session: bool, typing_agents: &[String], turn_in_progress: bool) -> Option<Acknowledgement>`
  - `acknowledgement::FINISHED_MARKS: [&str; 2] = ["✅", "❌"]`

- [ ] **Step 1: Write the failing tests.**
  - `display_name`: `names_label_names_two_and_counts_the_rest` — `[]`→`""`, `[A]`→`"A"`,
    `[A,B]`→`"A and B"`, `[A,B,C]`→`"A, B and 1 other"`, `[A,B,C,D]`→`"A, B and 2 others"`.
  - `typing_line` (from `KT/ComposerRevampTests.swift` `TypingCastTests` and
    `src/lib/components/typingView.test.ts`), typers built as
    `TypingUserDto { user_id, display_name: Some(label), label }`:
    - `an_agent_typing_is_named_by_the_header_and_is_not_on_the_peoples_line` (:516): agent ids
      `["@atlas:x"]`, counterpart `("@atlas:x","Atlas")`, typers `("@atlas:x","atlas-bot")`,
      `("@k:x","Krishna")` → `agents == ["Atlas"]`, `line == Some("Krishna is typing…")`.
    - `with_no_cast_everyone_typing_is_on_the_line` (:533): `("@atlas:x","atlas-bot")` →
      `line == Some("atlas-bot is typing…")`, `agents` empty.
    - `nobody_typing_is_no_line` → `None`.
    - `two_and_more_are_named_the_cores_way`: Alice+Bob → `"Alice and Bob are typing…"`;
      +Carol → `"Alice, Bob and 1 other are typing…"`; +Dave → `"Alice, Bob and 2 others are typing…"`.
    - `a_typer_is_named_by_label_not_display_name`: `display_name: None`, label `"Super Chotu"` →
      `"Super Chotu is typing…"`.
  - `acknowledgement` (from `AcknowledgementTests`, `KT/ComposerRevampTests.swift:391-505`):
    fixture `row(id, own, sender, reactions, membership)` builds a `TimelineItemDto` with
    `kind: "message"` (or `"membership"` with `detail: Some(verb)` so `membership_verb` is set),
    `event_id: Some(id)`, `body: Some("b")`, through `TimelineRow::new`; reaction fixture
    `ReactionDto { key, count, by_me, … }`; defaults `addressee Some("Atlas")`, agents
    `["@atlas:x"]`, `sent_this_session true`, typing `[]`, `turn_in_progress false`.
    - `sent_with_no_sign_of_the_agent_yet` (:418): `[own]` → `Sent`, names `["Atlas"]`, text `"Sent to Atlas"`.
    - `a_reaction_or_typing_means_it_is_on_it_a_live_turn_shows_its_own_card` (:423): own + 👀
      (count 1, by_me false) → `OnIt ["Atlas"]`, text `"Atlas is on it…"`; `[own]` + typing
      `["Atlas"]` → `OnIt`; same with `turn_in_progress` → `None`.
    - `the_agents_done_or_failed_mark_ends_it_with_or_without_a_reply` (:436): ✅ by agent →
      `None`; ❌ → `None`; own ✅ (by_me, count 1) → `Sent`; agent ✅ + typing `["Atlas"]` → `OnIt ["Atlas"]`.
    - `the_readers_own_reaction_is_not_the_agents` (:460): own 👍 (by_me, count 1) → `Sent`.
    - `the_agents_answer_ends_it_someone_elses_does_not` (:468): own, `@atlas:x` → `None`; own,
      `@krishna:x` → `Sent`; own, `@atlas:x` membership `"joined"` → `Sent`.
    - `an_answer_from_before_the_latest_send_does_not_count` (:481): own, atlas, own → `Sent`.
    - `nothing_to_say_without_a_send_this_session_or_without_an_agent` (:493): sent false →
      `None`; addressee `None` → `None`.
    - `several_agents_typing_in_a_room_with_no_one_addressee_are_all_named` (:500): no rows,
      typing `["Atlas","Quill"]`, addressee `None` → `OnIt ["Atlas","Quill"]`,
      `"Atlas and Quill are on it…"`.
    - Review Focus 2: `three_or_more_working_agents_are_named_the_cores_way` — typing
      `["Atlas","Quill","Hermes"]`, addressee `None` → `"Atlas, Quill and 1 other are on it…"`;
      with `"Vayu"` added → `"… and 2 others are on it…"`; and
      `with_the_agents_unknown_anyone_elses_reply_ends_it` — agents `[]`, own then `@krishna:x` → `None`.
    - Review Focus 1: `a_progress_message_the_agent_edits_ends_the_dock` — own, then an
      `@atlas:x` row with `edited: true` and body "🔎 Searching files…" → `None`.
- [ ] **Step 2: Run and confirm they fail.** `cargo test -p supermessage-core names_label typing_line acknowledgement`.
- [ ] **Step 3: Implement.** `typing_view`: a typer's name is the counterpart's `name` when
  its id is the counterpart's, else `label`; `agents` = typers in `agent_ids`; `line` = the
  others: none → `None`, one → `"{n} is typing…"`, more → `"{names_label} are typing…"`.
  `state` follows `K/Acknowledgement.swift:52-99` in order (turn → none; addressee + typing →
  `OnIt [addressee]`; no addressee + typing → `OnIt typing`; require addressee, sent, an own
  row; answered after the last own row by a non-own, sendered, non-membership row whose sender
  is in `agent_ids` or `agent_ids` is empty → none; a `FINISHED_MARKS` reaction with
  `count > (by_me ? 1 : 0)` on the last own row → none; any reaction with that count →
  `OnIt [addressee]`, else `Sent`). Text: `Sent` → `"Sent to {name}"`; `OnIt` with 0 names →
  `"On it…"`, 1 → `"{n} is on it…"`, more → `"{names_label} are on it…"`. Doc comments carry the
  Swift dates' rationale (2026-09-24 live card, 2026-09-26 NO_REPLY ✅).
- [ ] **Step 4: Run and confirm they pass**; mutate (counterpart name ignored; agents left on
  the line; `count > 0` instead of the by-me rule; dropping the membership check) and record.
- [ ] **Step 5: Commit.** `core: typing line and acknowledgement (M1.2)`.

### Task 10 (PR4): The room cast and `Session::room_cast`

**Files:**
- Modify: `C/room_cast.rs`; `C/session.rs` (beside `known_people`, ~:1516; `logout` :253;
  `leave_room` :1774)
- Create: `C/sent_rooms.rs` (register in `C/lib.rs`)
- Modify: `C/timeline.rs:2287` (`FocusedTimeline` gains a second tuple field `SentRooms`, so
  existing `.0` uses are untouched), `send_text` (:2733) and `send_reply` (:2794);
  `C/attachments.rs` (`send_staged`, on `Sent`)

**Interfaces:**
- Consumes: `Counterpart` (Task 9); `PersonDto`; `Mentionable`; `Session::known_people`,
  `Session::room_info`, `Session::rooms_snapshot`; `roster::describes_agent`.
- Produces:
  - `room_cast::RoomCast { pub room_id: String, pub people: Vec<PersonDto>, pub counterpart: Option<Counterpart>, pub agent_ids: Vec<String>, pub addressee: Option<String>, pub mentionables: Vec<Mentionable> }`
    — `Serialize` only (`PersonDto` has no `Deserialize`), `uniffi::Record`
  - `room_cast::cast_from(room_id: &str, everyone: &[PersonDto], member_ids: Option<&[String]>, header_name: Option<&str>, is_agent_room: bool) -> RoomCast`
  - `room_cast::counterpart(people: &[PersonDto], header_name: Option<&str>, is_agent_room: bool) -> Option<Counterpart>`
  - `Session::room_cast(&self, room_id: &str) -> CoreResult<RoomCast>`
  - `sent_rooms::SentRooms` (`Default`; a `std::sync::Mutex<HashSet<String>>`):
    `note(&self, room_id: &str)`, `has(&self, room_id: &str) -> bool`, `forget(&self, room_id: &str)`, `clear(&self)`
  - `FocusedTimeline::note_sent(&self, room_id: &str)`, `FocusedTimeline::has_sent(&self, room_id: &str) -> bool`
  - `Session::has_sent(&self, room_id: &str) -> bool` — "this account has sent in this room
    since signing in" (ruling 12e), the acknowledgement's `sent_this_session` input

- [ ] **Step 1: Write the failing tests** (from `RoomCastTests`, `KT/ComposerRevampTests.swift:554-603`).
  People: atlas `("@atlas:x","atlas-bot", agent)`, quill `("@quill:x","Quill", agent)`,
  krishna `("@k:x","Krishna", person)`, agent = `runtime: Some(RuntimeDto { harness: "claude", host: "ci" })`.
  - `an_agents_room_with_one_agent_in_it_that_agent_is_the_header` (:566): `[atlas,krishna]`,
    `"Atlas"`, true → `("@atlas:x","Atlas")`; `[atlas,quill]` → `None` (Review Focus 2);
    `[atlas]`, `"Ops"`, false → `None`; empty header → `None`.
  - `the_cast_is_the_rooms_members_not_everyone_known` (:580): everyone `[atlas,quill,krishna]`,
    members `["@atlas:x","@k:x","@me:x"]` → people ids `["@atlas:x","@k:x"]`, counterpart name
    `"Atlas"`, addressee `"Atlas"`, mentionables display names `["atlas-bot","Krishna"]`,
    `agent_ids == ["@atlas:x"]`.
  - `without_the_members_no_counterpart_is_guessed` (:593): `[atlas]`, members `None` →
    1 person, counterpart `None`, addressee `Some("atlas-bot")`.
  - `two_agents_and_no_header_have_no_addressee`: `[atlas,quill,krishna]` all members → addressee `None`.
  - Review Focus 3, in `session.rs` tests beside the `account()` NotReady test (~:2234):
    `room_cast_without_a_session_is_not_ready` —
    `Session::new(dir, Box::new(MemoryStore::default())).room_cast("!r:x")` is
    `Err(CoreError::NotReady)`.
  - "Sent this session", ported from `TypingCastTests` "a send is remembered per room, and
    forgotten at sign-out" (`KT/ComposerRevampTests.swift:542`), in `C/sent_rooms.rs`:
    `a_send_is_remembered_per_room` (note `"!r:x"` → `has("!r:x")`, not `has("!other:x")`);
    `leaving_a_room_forgets_it` (`forget` → `!has`); in `timeline.rs` tests:
    `a_refused_send_is_not_remembered` (`FocusedTimeline::default().send_text("!r:x", "hi", &[])`
    is `Err(NotReady)` and `!has_sent("!r:x")`); in `session.rs` tests (Review Focus 3):
    `signing_out_forgets_every_room_sent_in` — `Session::new(…)`,
    `focused_timeline().note_sent("!r:x")`, `let _ = session.logout().await`,
    `!session.has_sent("!r:x")` (the clear must run on every logout path, including with no
    client); `leaving_a_room_forgets_that_it_was_sent_in` asserts `leave_room` calls `forget`
    even when the leave itself fails.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.** `cast_from` per `K/Stores/RoomCast.swift:43-114`: members known →
  `everyone` filtered to them, order kept; unknown → `everyone` and no counterpart; agents are
  people with `runtime.is_some()`; addressee = counterpart's name, else the only agent's name;
  mentionables `Mentionable { user_id, display_name: person.name }`.
  `Session::room_cast`: `known_people()?`; member ids from `room_info(room_id).ok()`; the row
  from `rooms_snapshot().ok()` by id; `header_name = row.identity.name`,
  `is_agent_room = describes_agent(&row)` (ruling 1); no row → no header, not an agent room.
  Sent rooms: `send_text`, `send_reply` and `send_staged` (on `Sent`) call `note_sent` after
  the send queue accepted the event (iOS noted after a delivered send); `logout` calls
  `clear` first; `leave_room` calls `forget`. Gate and permission answers do not count (iOS
  noted only composer sends).
- [ ] **Step 4: Run and confirm they pass**; mutate (drop the one-agent check; use everyone when
  members are known; skip the `clear` in `logout`; note before the send succeeds) and record.
- [ ] **Step 5: Commit.** `core: the room cast, asked of the session (M1.2)`.

### Task 11 (PR4): Exports for cast, typing and acknowledgement

**Files:** `F`, `T/commands.rs` + `T/lib.rs`, `src/lib/ipc.ts`, `src/lib/ipc.test.ts`,
`C/acknowledgement.rs` (one async helper)

**Interfaces:**
- Produces (FFI):
  - `Core::room_cast(&self, room_id: String) -> Result<RoomCast, FfiError>` (blocks, like `room_info`)
  - `Core::has_sent(&self, room_id: String) -> bool`
  - free `room_cast_from(room_id: String, everyone: Vec<PersonDto>, member_ids: Option<Vec<String>>, header_name: Option<String>, is_agent_room: bool) -> RoomCast`
    — for iOS previews and Swift tests; **no Tauri twin** (`PersonDto` cannot deserialize, and the
    desktop uses `room_cast`)
  - free `typing_view(typers: Vec<TypingUserDto>, agent_ids: Vec<String>, counterpart: Option<Counterpart>) -> TypingView`
  - free `acknowledgement(rows: Vec<TimelineRow>, addressee: Option<String>, agent_ids: Vec<String>, sent_this_session: bool, typing_agents: Vec<String>, turn_in_progress: bool) -> Option<Acknowledgement>`
- Produces (core, for the desktop): `acknowledgement::for_focused(focused: &FocusedTimeline, room_id: &str, addressee: Option<&str>, agent_ids: &[String], typing_agents: &[String], turn_in_progress: bool) -> CoreResult<Option<Acknowledgement>>`
  — reads `focused.snapshot()` and `focused.has_sent(room_id)`; `RoomChanged` when its room is not `room_id`. The desktop sends
  no rows over IPC (`TimelineRow` is not `Deserialize`, and the rows are already in the core).
- Produces (Tauri): `room_cast(room_id, session)` async; `typing_view(typers, agent_ids, counterpart)`;
  `has_sent(room_id, timeline: State<Arc<FocusedTimeline>>) -> bool`;
  `acknowledgement(room_id, addressee, agent_ids, typing_agents, turn_in_progress, timeline: State<Arc<FocusedTimeline>>)` async.
- Produces (TS): `PersonDto`, `Counterpart`, `RoomCast`, `TypingView`, `AcknowledgementKind`,
  `Acknowledgement`; `roomCast(roomId): Promise<RoomCast>`;
  `typingView(typers: TypingUser[], agentIds: string[], counterpart: Counterpart | null): Promise<TypingView>`;
  `hasSent(roomId): Promise<boolean>`;
  `acknowledgement(roomId, addressee, agentIds, typingAgents, turnInProgress): Promise<Acknowledgement | null>`.

- [ ] **Step 1: Write the failing tests.** Rust: `for_focused_without_a_focused_room_is_not_ready`
  (`FocusedTimeline::default()` → `Err(NotReady)`). `ipc.test.ts`: `roomCast invokes room_cast
  with roomId`; `typingView passes typers, agentIds and counterpart`; `acknowledgement passes
  the five facts camelCase`; `hasSent invokes has_sent with roomId`.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement** the exports as listed; register the four commands.
- [ ] **Step 4: Run** `cargo test --workspace && pnpm test && pnpm check` → PASS.
- [ ] **Step 5: Commit.** `core: export the cast, typing line and acknowledgement (M1.2)`.

### Task 12 (PR4): The hosts' existing typing-line copies use the core

Android and the desktop each hold a copy of the typing-line rule, so ruling 8 replaces them
here. Neither consumes the cast or the acknowledgement yet (M4).

**Files:**
- Modify: `AK/stores/TypingStore.kt:85-94` (`line`), `AKT/stores/TypingStoreTest.kt`
- Modify: `src/lib/components/typingView.ts`, `typingView.test.ts`, `TypingIndicator.svelte`,
  `TypingIndicator.stories.svelte`, `src/routes/+page.svelte:1078`

**Interfaces:** consumes `typingView` (Kotlin `uniffi.supermessage_ffi.typingView`, TS wrapper).
Produces `boundTypers(users: TypingUser[]): TypingUser[]` in `typingView.ts` (labels cut to 32
code points + `…`, the desktop's existing display bound) and `TypingIndicator` prop
`text: string | null` replacing `users`.

- [ ] **Step 1: Write the failing tests.** Kotlin `threeTypersAreNamedTheCoresWay`: typers
  Ann, Bob, Cy → `"Ann, Bob and 1 other are typing…"` (calls the real core; needs the host
  `.so`). Vitest: `boundTypers truncates an overlong label` (200 × `x` → 32 `x` + `…`) and
  `boundTypers leaves a short label alone`.
- [ ] **Step 2: Run and confirm they fail** (`./gradlew :kit:test --tests '*TypingStoreTest*'`, `pnpm test -- typingView`).
- [ ] **Step 3: Implement.** Kotlin `line` = `typingView(typers.map { TypingUserDto(it.userId, null, it.label) }, emptyList(), null).line`.
  Desktop: delete `typingIndicatorText` and `MAX_NAMED_TYPERS` (their wording tests are now the
  Rust tests of Task 9); `+page.svelte` keeps `let typingLine = $state<string | null>(null)`
  and an `$effect` calling `typingView(boundTypers(typingStore.users), [], null)`, and passes
  `text={typingLine}`; stories pass the exact strings the old function produced for each story
  (e.g. "Many people" → `"Alice, Bob and 1 other are typing…"`).
- [ ] **Step 4: Run** `pnpm test && pnpm check && ./scripts/snapshot-stories.sh` and
  `(cd android && ./gradlew test)` → PASS, no differing story frame, Android preview baselines
  unchanged.
- [ ] **Step 5: Commit.** `android, desktop: the typing line is the core's (M1.2)`.

### Task 13 (PR4): Bindings and the iOS switch-over (M1.2)

**Files:**
- Regenerate both bindings
- Delete: `K/Stores/RoomCast.swift`, `K/Acknowledgement.swift`
- Modify: `K/CoreSeam.swift:162-166` (`AccountDirectory` gains `func roomCast(roomId: String) async throws -> RoomCast`),
  `K/CoreClient.swift` (beside `knownPeople`, :202), `K/Session.swift` (beside `people()`, :538:
  `public func roomCast(_ roomId: String) async -> RoomCast?`),
  `A/Previews/PreviewFixtures.swift:204` (stub returns `roomCastFrom(roomId:everyone: PreviewFixtures.people, memberIds: PreviewFixtures.roomInfo.members.map(\.userId), headerName: nil, isAgentRoom: false)`)
- Modify: `K/Stores/TypingStore.swift` — typers stored as `[TypingUserDto]`; `Cast` deleted,
  `recognise(_ cast: RoomCast, in:)`; `name(of:)` deleted; `typingAgents` and `line` read one
  `typingView(typers:agentIds:counterpart:)` call; `sentIn`, `noteSent(in:)`, `hasSent(in:)` and
  their clearing in `focus(nil)` deleted (the core owns them, ruling 12e); `focus`,
  `messagesArrived` otherwise unchanged
- Modify: `K/Session.swift:230` — drop `typing.noteSent(in:)`; add
  `public func hasSent(in roomId: String) async -> Bool` through `CoreClient` (on its queue, like
  every `Core` call) and the matching `CoreSeam` requirement; preview and test stubs answer `false`
- Add: `K/RoomCastCandidates.swift` — `extension RoomCast { func candidates(matching query: String, limit: Int = 6) -> [PersonDto] }`
  over `peopleMatching` (moved from the deleted class)
- Modify: `A/Timeline/TimelineView.swift:30,125,161-184,191-224,270-274` — `@State private var cast: RoomCast?`;
  `loadCast` = `cast = await session.roomCast(roomId)`, then `recognise` and `setAgentName(cast?.counterpart?.name, …)`;
  dock from `acknowledgement(rows:addressee:agentIds:sentThisSession:typingAgents:turnInProgress:)`
  (with `cast?.roomId == roomId` as today), where `sentThisSession` is `@State private var sentHere`
  refreshed by `.task(id: "\(roomId)-\(timeline.revision)") { sentHere = await session.hasSent(in: roomId) }`
  (a delivered send's echo bumps the revision); `isWorking` = `acknowledgement.kind == .onIt`; previews
  build `Acknowledgement(kind: .sent, names: ["Atlas"], text: "Sent to Atlas")` and
  `(kind: .onIt, names: ["Atlas"], text: "Atlas is on it…")`
- Modify: `A/Composer/ComposerView.swift:44,60,260,286` — same `roomCast` load; `cast?.candidates(matching:) ?? []`; `cast?.mentionables ?? []`
- Modify: `KT/ComposerRevampTests.swift` — delete `AcknowledgementTests` and `RoomCastTests` and
  `TypingCastTests` "with no cast…" (ported); keep "an agent typing is named by the header…"
  (cast built with `roomCastFrom(roomId: room, everyone: [atlas], memberIds: ["@atlas:x"], headerName: "Atlas", isAgentRoom: true)`)
  and delete "a send is remembered per room, and forgotten at sign-out" (ported to Task 10);
  `KT/TypingStoreTests.swift`
  (store event handling) stays as is

- [ ] **Step 1: Procedure B.**
- [ ] **Step 2: Switch iOS** as listed. `LiveStore.setAgentName` and its test stay (M1b).
- [ ] **Step 3: Procedure V** (stories included).
- [ ] **Step 4: Commit** `ios: cast, typing line and acknowledgement from the core (M1.2)`;
  open PR4 after PR3 merged. Composer "Mention picker" and every other preview baseline must match.

---

## PR5 — M1.6 First-run demo and readable space names (branch `parity/m1-core-first-run`)

### Task 14: Readable space names

**Files:** `C/spaces.rs` (function beside `SpaceSummary`, field on it, set where summaries are
built), `F`, `T/commands.rs` + `T/lib.rs`, `src/lib/ipc.ts` (`SpaceSummary.displayName: string`),
`src/lib/fixtures/spaces.ts` and the TS tests that build `SpaceSummary`, `src/lib/ipc.test.ts`

**Interfaces:**
- Produces: `spaces::space_display_name(name: &str) -> String`;
  `spaces::generated_id(name: &str) -> Option<&str>`; `SpaceSummary.display_name: String` (last);
  FFI free and Tauri `space_display_name(name: String) -> String`; TS `spaceDisplayName(name)`.

- [ ] **Step 1: Write the failing tests** (from `KT/SpaceNamesTests.swift`):
  - `an_id_shaped_space_name_reads_as_a_runtime` (:6): `"9247e5c1a0b34d2e"`,
    `"9247e5c1-a0b3-4d2e-8f00-123456789abc"`, `"9247e5…"`, `"9247e5..."` → `"Runtime 9247e5"`.
  - `a_host_keeps_its_name_without_the_mdns_suffix` (:15): `"Rakeshs-MacBook-Pro.local"` →
    `"Rakeshs-MacBook-Pro"`; `"molt-bot"` unchanged.
  - `a_chosen_name_is_left_exactly_as_it_is` (:21): `"Guild"`, `"molt-bot"`,
    `"Rakesh's MacBook Pro"`, `"cafe-bead"` unchanged; `"  Ashram "` → `"Ashram"`.
  - New, pinning the edges: `"HOST.LOCAL"` → `"HOST"`; `".local"` → `".local"`; `"9247…"` →
    `"Runtime 9247"`; `"924…"` and `"1234567"` unchanged; `"９２４７ｅ５ｃ１"` unchanged (ASCII
    only — see iOS behaviour changes).
  - `a_space_summary_carries_its_readable_name`: a summary built by the existing summaries path
    has `display_name == space_display_name(&name)`.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement** per `K/SpaceNames.swift:16-39`: trim (`str::trim`); `generated_id`
  strips one trailing `"…"` then one `"..."`, requires non-empty, every char ASCII hex digit or
  `-`, at least one ASCII digit, and ≥ 4 chars when truncated else ≥ 8; id →
  `format!("Runtime {}", first 6 chars)`; else an ASCII-case-insensitive `.local` suffix with
  `chars().count() > 6` is removed. Doc comment keeps the agentpod#554 note.
- [ ] **Step 4: Run and confirm they pass**; mutate (drop the digit requirement; `>= 6`) and record.
- [ ] **Step 5: Export** (FFI, Tauri, TS wrapper and `displayName` in type and fixtures;
  `ipc.test.ts` `spaceDisplayName invokes space_display_name with name`). Run Procedure V's
  cargo and pnpm lines → PASS. **Commit** `core: readable space names (M1.6)`.

### Task 15: The first-run demo script

**Files:** Create `C/first_run.rs` (register in `C/lib.rs`); `F`; `T/commands.rs` + `T/lib.rs`;
`src/lib/ipc.ts`, `src/lib/ipc.test.ts`

**Interfaces:**
- Produces:
  - `pub enum FirstRunStep { Greeting, Asking, Waiting, Approved }`
  - `pub struct FirstRunScript { pub agent_name, pub greeting, pub request, pub banner, pub typing_label, pub agent_tag, pub card_label, pub card_event_type, pub room_id, pub initial, pub approved_title, pub approved_body, pub finish_label, pub skip_label: String; pub card: CustomEventView; pub step_delays_ms: Vec<u64> }`
    — `Serialize` only (`CustomEventView` has no `Deserialize`)
  - `pub fn script() -> FirstRunScript`; `pub fn advance(step: FirstRunStep) -> FirstRunStep`;
    `pub fn answer(step: FirstRunStep) -> Option<FirstRunStep>`
  - FFI free and Tauri: `first_run_script()`, `first_run_advance(step)`, `first_run_answer(step)`;
    TS `firstRunScript()`, `firstRunAdvance(step)`, `firstRunAnswer(step)`

- [ ] **Step 1: Write the failing tests.**
  - `the_demo_only_gets_past_the_card_on_the_readers_answer` (`KT/RosterViewsTests.swift:207`):
    `answer(Greeting) == None`; `advance(Greeting) == Asking`; `advance(Asking) == Waiting`;
    `advance(Waiting) == Waiting`; `answer(Waiting) == Some(Approved)`; `answer(Approved) == None`;
    `advance(Approved) == Approved`.
  - `the_script_is_what_ios_says` — verbatim from `K/FirstRunDemo.swift:30-33` and
    `A/Onboarding/FirstRunDemoView.swift`: agent `"Atlas"`; greeting `"Hi — I'm Atlas, a sample
    agent. I'd like to tidy the release notes before today's build."`; request `"Can I edit
    docs/release-notes.md?"`; banner `"A sample conversation — nothing here is sent anywhere."`;
    typing label `"Atlas is typing"`; tag `"Agent"`; card label `"Permission"`; event type
    `"dev.agentpod.permission.request.v1"`; room id `"demo.atlas"`; initial `"✳"`; approved
    title `"That's it — approvals from chat."`; body `"When an agent needs you, it asks in its
    room, and the Needs you tab keeps the list."`; `"Get started"`; `"Skip"`; delays `[1100, 900]`;
    card `Rendered { fields: [("File","docs/release-notes.md"), ("Change","Tidy headings, add today's fixes")], reasoning: None, newer_version: false, decision: Some({ prompt: request, options: [("Approve","approve"), ("Reject","reject")], subject: "demo-permission" }), link: None, outcome: None }`.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.** Module doc keeps "local, nothing is sent": the script is data; the
  host holds the step and must hand the card a local answer closure, never a session.
- [ ] **Step 4: Run and confirm they pass**; mutate (`advance(Waiting) == Approved`) and record.
- [ ] **Step 5: Export** and add `ipc.test.ts` `firstRunAnswer passes the step`. Run the cargo
  and pnpm lines of Procedure V. **Commit** `core: first-run demo script (M1.6)`.

### Task 16 (PR5): Bindings and the iOS switch-over (M1.6)

**Files:**
- Regenerate both bindings
- Delete: `K/SpaceNames.swift`, `KT/SpaceNamesTests.swift` (ported)
- Modify: `A/Rooms/SpaceMenu.swift:19,54` and `A/Rooms/RoomListView.swift:146,172` —
  `SpaceNames.display(x)` → `spaceDisplayName(name: x)` (section titles keep going through it)
- Modify: the four Swift/Kotlin `SpaceSummary(` constructions (previews/tests) to pass
  `displayName: spaceDisplayName(name: …)` (Kotlin: the same function)
- Modify: `K/FirstRunDemo.swift` — rules and copy removed; stays a thin `@Observable` holder:
  `step: FirstRunStep = .greeting`, `answer: String?`, `let script = firstRunScript()`,
  `advance()` → `step = firstRunAdvance(step: step)`, `answer(_:)` → `guard let next = firstRunAnswer(step: step) else { return false }`
- Modify: `A/Onboarding/FirstRunDemoView.swift` — every literal and `FirstRunDemo.*` static
  reads `demo.script`; `Self.card` deleted (uses `script.card`); `demo.step >= .waiting` →
  `demo.step == .waiting || demo.step == .approved`; delays from `script.stepDelaysMs`
- Modify: `KT/RosterViewsTests.swift:207-221` — `demoSteps` kept as the thin boundary test,
  unchanged in its assertions

- [ ] **Step 1: Procedure B.**
- [ ] **Step 2: Switch iOS** as listed.
- [ ] **Step 3: Procedure V.**
- [ ] **Step 4: Commit** `ios: first-run script and space names from the core (M1.6)`; open
  PR5 after PR4 merged. `SpaceMenu` previews ("No spaces", "Three spaces") must match.

---

## PR map

| PR | Item | Tasks | Merges after |
|---|---|---|---|
| PR1 | M1.5 avatar style | 1, 2 | — |
| PR2 | M1.7 send outcome | 3, 4 | PR1 |
| PR3 | M1.1 roster views + status | 5, 6, 7, 8 | PR2 |
| PR4 | M1.2 cast + acknowledgement | 9, 10, 11, 12, 13 | PR3 |
| PR5 | M1.6 first run + space names | 14, 15, 16 | PR4 |

Each PR description lists its iOS behaviour changes (table above), the falsification notes,
the local Procedure V results and the CI run, and ends with the attribution lines.
