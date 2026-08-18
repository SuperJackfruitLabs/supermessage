# Core Decoupling & Native iOS Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract supermessage's Rust core into a host-agnostic crate and expose it to Swift through UniFFI, so a native iOS client can be built against the same logic the desktop app uses.

**Architecture:** A three-crate workspace. `supermessage-core` holds all logic and knows nothing of any host. `src-tauri` and `supermessage-ffi` are thin adapters over it — the first preserving today's exact JSON wire format for the Svelte desktop app, the second exposing UniFFI bindings for Swift and later Kotlin. Events leave the core through a typed `CoreEvent` enum and an `EventSink` trait rather than Tauri's `AppHandle`.

**Tech Stack:** Rust (cargo workspace), UniFFI 0.28+, matrix-sdk 0.18, Tauri 2, SwiftUI, Xcode 16.4.

**Spec:** `docs/superpowers/specs/2026-08-18-native-ios-core-decoupling-design.md`

## Global Constraints

- **The desktop app's JSON wire format must stay byte-identical.** Every DTO's serialised output is snapshotted in Task 1 and asserted unchanged thereafter.
- **The 557 JS tests and the desktop e2e specs must stay green** at every task boundary.
- **`supermessage-core` must not depend on `tauri`.** No `AppHandle`, no `emit`, no `#[tauri::command]`.
- **Event channel names are frozen**: `sm://connection`, `sm://rooms/diff`, `sm://timeline/diff`, `sm://typing`, `sm://live`, `sm://thought`, `sm://tool`, `sm://attachment/staged`.
- **Command names are frozen** and identical across hosts.
- **`seq` ordering is a correctness requirement** — diff envelopes must reach the client in order.
- Run Rust tests with `cd src-tauri && cargo test --lib` until Task 2, `cargo test --workspace` after.

---

### Task 1: Freeze the wire format

The safety net for every later task. Written first so it snapshots today's output, before anything moves.

**Files:**
- Create: `src-tauri/src/core/dto_golden_test.rs`
- Modify: `src-tauri/src/core/mod.rs` (register the test module)

**Interfaces:**
- Produces: `golden_json(value) -> String` helper used by later tasks to re-assert the format.

- [ ] **Step 1: Write the failing test**

```rust
// src-tauri/src/core/dto_golden_test.rs
//! The desktop app's wire format, frozen.
//!
//! Everything after this task moves files, adds derives and re-homes types.
//! None of that may change a single byte the webview receives. These tests are
//! what turn that promise into a check — they fail loudly if a field is
//! renamed, reordered into a different shape, or silently dropped.

use super::dto::*;

fn golden(value: &impl serde::Serialize) -> String {
    serde_json::to_string(value).expect("DTO must serialise")
}

#[test]
fn room_summary_wire_format_is_frozen() {
    let room = RoomSummary::fixture_for_golden();
    assert_eq!(
        golden(&room),
        r#"{"id":"!r:example.org","name":"Room","membership":"joined"}"#
    );
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd src-tauri && cargo test --lib dto_golden`
Expected: FAIL — `fixture_for_golden` does not exist, and the literal will not match.

- [ ] **Step 3: Add fixtures and correct the literals**

Add a `#[cfg(test)] impl RoomSummary { pub fn fixture_for_golden() -> Self }` to `dto.rs` for each of the 10 types. Run the test once, copy the *actual* serialised string into the assertion. The literal must be transcribed from real output, never hand-written — a hand-written literal tests your assumption rather than the code.

Cover all 10: `Membership`, `RoomSummary`, `MediaMetaDto`, `ReplyToDto`, `ReactionDto`, `TimelineItemDto`, `TypingUserDto`, `DiffOp<RoomSummary>`, `DiffEnvelope<RoomSummary>`, `SeqCounter`.

- [ ] **Step 4: Run to verify they pass**

Run: `cd src-tauri && cargo test --lib dto_golden`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/core/dto_golden_test.rs src-tauri/src/core/dto.rs src-tauri/src/core/mod.rs
git commit -m "test: freeze the desktop wire format before decoupling"
```

---

### Task 2: Extract `supermessage-core`

Pure movement. No behaviour change, no new dependencies, no new abstractions.

**Files:**
- Create: `Cargo.toml` (workspace root), `crates/supermessage-core/Cargo.toml`
- Move: `src-tauri/src/core/*` → `crates/supermessage-core/src/*`
- Modify: `src-tauri/Cargo.toml`, `src-tauri/src/lib.rs`

**Interfaces:**
- Produces: crate `supermessage_core` exporting every module today's `crate::core::*` exports.

- [ ] **Step 1: Create the workspace root**

```toml
# Cargo.toml
[workspace]
members = ["crates/supermessage-core", "src-tauri"]
resolver = "2"
```

- [ ] **Step 2: Move the core, minus its Tauri parts**

`git mv src-tauri/src/core crates/supermessage-core/src`. Leave `commands.rs` behind in `src-tauri/src/` for now — it carries `#[tauri::command]` and is split in Task 3.

`crates/supermessage-core/Cargo.toml` takes every dependency `src-tauri` had **except** `tauri`, `tauri-plugin-*`, `tauri-build`.

- [ ] **Step 3: Rewire imports**

`crate::core::foo` becomes `supermessage_core::foo` in `src-tauri`; inside the new crate, `crate::foo`.

- [ ] **Step 4: Verify nothing changed**

```bash
cargo test --workspace          # 307 core tests + goldens pass
cd src-tauri && cargo check     # desktop still builds
```

Expected: all green. If a test fails, the move was not pure — revert and redo rather than fixing forward.

- [ ] **Step 5: Assert the core is clean**

Run: `grep -rc "tauri" crates/supermessage-core/src/ | grep -v ":0" || echo clean`
Expected: `clean` — except `commands.rs`, which has not moved yet.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: extract supermessage-core as its own crate"
```

---

### Task 3: `EventSink`, and the Tauri adapter

Removes the last Tauri coupling: 2 `AppHandle` values and 8 `.emit()` calls.

**Files:**
- Create: `crates/supermessage-core/src/event.rs`, `src-tauri/src/events.rs`
- Modify: `crates/supermessage-core/src/{sync,rooms,timeline,live,attachments}.rs`

**Interfaces:**
- Produces: `CoreEvent` enum, `EventSink` trait, `TauriSink` implementing it.
- Consumes: the frozen channel names from Global Constraints.

- [ ] **Step 1: Write the failing test**

```rust
// crates/supermessage-core/src/event.rs  (test module)
/// A sink that records what the core said, so a test can assert on it without
/// a webview, an app handle, or a running Tauri app.
struct RecordingSink(std::sync::Mutex<Vec<CoreEvent>>);

impl EventSink for RecordingSink {
    fn emit(&self, event: CoreEvent) {
        self.0.lock().expect("sink lock").push(event);
    }
}

#[test]
fn a_sink_receives_what_the_core_emits() {
    let sink = std::sync::Arc::new(RecordingSink(Default::default()));
    let as_trait: std::sync::Arc<dyn EventSink> = sink.clone();
    as_trait.emit(CoreEvent::Connection(ConnectionPayload {
        state: "live",
        message: None,
    }));
    assert_eq!(sink.0.lock().unwrap().len(), 1);
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cargo test -p supermessage-core event`
Expected: FAIL — `CoreEvent` and `EventSink` do not exist.

- [ ] **Step 3: Define the channel**

```rust
pub enum CoreEvent {
    Connection(ConnectionPayload),
    RoomsDiff(DiffEnvelope<RoomSummary>),
    TimelineDiff(DiffEnvelope<TimelineItemDto>),
    Typing(TypingPayload),
    Live(LivePayload),
    Thought(LivePayload),
    Tool(ToolPayload),
    AttachmentStaged(StagedAttachment),
}

pub trait EventSink: Send + Sync + 'static {
    fn emit(&self, event: CoreEvent);
}
```

One variant per frozen channel — eight, matching the eight emit sites.

- [ ] **Step 4: Replace the emit sites**

Each of the 8 becomes `self.sink.emit(CoreEvent::X(payload))`. Every struct that held `AppHandle` holds `Arc<dyn EventSink>` instead.

- [ ] **Step 5: Write the Tauri adapter**

```rust
// src-tauri/src/events.rs
pub struct TauriSink(pub AppHandle);

impl EventSink for TauriSink {
    fn emit(&self, event: CoreEvent) {
        // The match arms carry the frozen channel names. This is the only
        // place in the app that knows them, and changing one changes the
        // desktop wire format — see the golden tests.
        let _ = match event {
            CoreEvent::Connection(p) => self.0.emit("sm://connection", &p),
            CoreEvent::RoomsDiff(e) => self.0.emit("sm://rooms/diff", &e),
            CoreEvent::TimelineDiff(e) => self.0.emit("sm://timeline/diff", &e),
            CoreEvent::Typing(p) => self.0.emit("sm://typing", &p),
            CoreEvent::Live(p) => self.0.emit("sm://live", &p),
            CoreEvent::Thought(p) => self.0.emit("sm://thought", &p),
            CoreEvent::Tool(p) => self.0.emit("sm://tool", &p),
            CoreEvent::AttachmentStaged(m) => self.0.emit("sm://attachment/staged", &m),
        };
    }
}
```

- [ ] **Step 6: Split `commands.rs`**

Move the logic of each of the 30 commands into the core module it belongs to. The `#[tauri::command]` wrapper stays in `src-tauri/src/commands.rs` and does nothing but call core and map the error.

- [ ] **Step 7: Verify**

```bash
cargo test --workspace
grep -rc "tauri" crates/supermessage-core/src/ | grep -v ":0" || echo clean
cd .. && pnpm test && pnpm check
```

Expected: all green, and `clean` — the core no longer mentions Tauri anywhere.

- [ ] **Step 8: Run the desktop app and send a message**

The golden tests prove the shapes; only a running app proves the channels still arrive. Open a room, send a message, watch it land.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "refactor: give the core a host-agnostic event channel"
```

---

### Task 4: UniFFI derives on the shared DTOs

**Files:**
- Modify: `crates/supermessage-core/Cargo.toml`, `crates/supermessage-core/src/dto.rs`

- [ ] **Step 1: Add the dependency**

`uniffi = { version = "0.28", features = ["build"] }` to `supermessage-core`.

- [ ] **Step 2: Derive on the eight compatible types**

`Membership`, `RoomSummary`, `MediaMetaDto`, `ReplyToDto`, `ReactionDto`, `TimelineItemDto`, `TypingUserDto`, `SeqCounter` gain `#[derive(uniffi::Record)]` (`uniffi::Enum` for `Membership`). `DiffOp<T>` and `DiffEnvelope<T>` get nothing — they are generic, and UniFFI has no generics.

- [ ] **Step 3: Prove the wire format did not move**

Run: `cargo test --workspace dto_golden`
Expected: PASS, unchanged. This is the check the whole task exists for — if a byte moved, the derives are not additive and the design assumption is wrong.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(core): derive UniFFI records on the shared DTOs"
```

---

### Task 5: The FFI crate — thin slice

Deliberately narrow: enough to prove the boundary carries a login, a room list and a live event stream before 30 commands are written against assumptions.

**Files:**
- Create: `crates/supermessage-ffi/{Cargo.toml,src/lib.rs,src/error.rs,src/events.rs,src/diff.rs}`
- Modify: root `Cargo.toml` (workspace member)

**Interfaces:**
- Produces: `Core` object with `new`, `set_sink`, `login`, `restore_session`, `connection_state`, `rooms_resync`; `FfiError`; `EventSink` callback interface; `RoomDiffOp`/`RoomDiffEnvelope`.

- [ ] **Step 1: Write the failing test**

```rust
// crates/supermessage-ffi/src/diff.rs (test module)
/// The one place a field can silently go missing.
///
/// `DiffOp<T>` is generic and UniFFI has no generics, so mobile gets a
/// monomorphised mirror. Adding a field to the core type will NOT fail to
/// compile here — it will just never reach the phone. This test is the only
/// thing standing between that and a bug nobody can see.
#[test]
fn a_room_diff_survives_the_crossing() {
    let core_op = DiffOp::Insert { index: 3, value: RoomSummary::fixture_for_golden() };
    let ffi_op: RoomDiffOp = core_op.clone().into();
    match ffi_op {
        RoomDiffOp::Insert { index, value } => {
            assert_eq!(index, 3);
            assert_eq!(value.id, "!r:example.org");
            assert_eq!(value.name, "Room");
        }
        other => panic!("wrong variant: {other:?}"),
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cargo test -p supermessage-ffi`
Expected: FAIL — the crate does not exist.

- [ ] **Step 3: Create the crate and the monomorphised diffs**

```rust
#[derive(uniffi::Enum)]
pub enum RoomDiffOp {
    Insert { index: u32, value: RoomSummary },
    Update { index: u32, value: RoomSummary },
    Remove { index: u32 },
    Reset { values: Vec<RoomSummary> },
}

impl From<DiffOp<RoomSummary>> for RoomDiffOp { /* exhaustive match */ }
```

Same for `TimelineDiffOp`. The `From` impls must be exhaustive matches — no wildcard arm, so a new variant in core fails to compile here.

- [ ] **Step 4: Define `Core`, `FfiError` and the sink**

```rust
#[uniffi::export(callback_interface)]
pub trait EventSink: Send + Sync {
    fn on_event(&self, event: FfiEvent);
}

#[derive(uniffi::Object)]
pub struct Core { session: Arc<Session>, runtime: tokio::runtime::Runtime }

#[uniffi::export(async_runtime = "tokio")]
impl Core {
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Arc<Self>;
    pub fn set_sink(&self, sink: Box<dyn EventSink>);
    pub async fn login(&self, homeserver: String, username: String, password: String) -> Result<(), FfiError>;
    pub async fn restore_session(&self) -> Result<bool, FfiError>;
    pub fn connection_state(&self) -> ConnectionPayload;
    pub async fn rooms_resync(&self) -> Result<(), FfiError>;
}
```

- [ ] **Step 5: Verify**

Run: `cargo test --workspace && cargo build -p supermessage-ffi --target aarch64-apple-ios-sim`
Expected: PASS and a clean cross-compile.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(ffi): expose login, rooms and events to Swift"
```

---

### Task 6: XCFramework build

**Files:**
- Create: `scripts/build-xcframework.sh`, `apple/Generated/` (bindings output)

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Builds the Rust core for iOS and wraps it as an XCFramework Xcode can consume.
set -euo pipefail
cd "$(dirname "$0")/.."

for target in aarch64-apple-ios aarch64-apple-ios-sim; do
  cargo build -p supermessage-ffi --release --target "$target"
done

cargo run -p uniffi-bindgen -- generate \
  --library target/aarch64-apple-ios/release/libsupermessage_ffi.a \
  --language swift --out-dir apple/Generated

rm -rf apple/Supermessage.xcframework
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libsupermessage_ffi.a \
  -library target/aarch64-apple-ios-sim/release/libsupermessage_ffi.a \
  -output apple/Supermessage.xcframework
```

- [ ] **Step 2: Run it**

Run: `./scripts/build-xcframework.sh`
Expected: `apple/Supermessage.xcframework` exists and `apple/Generated/supermessage.swift` is non-empty.

- [ ] **Step 3: Commit the bindings**

Generated Swift is checked in deliberately — Xcode builds stay hermetic, and a moved boundary shows up in review.

```bash
git add scripts/build-xcframework.sh apple/Generated
git commit -m "build: package the core as an XCFramework for Swift"
```

---

### Task 7: The probe screen

Throwaway by design. Its only job is to answer "does this boundary actually work" before parity is attempted.

**Files:**
- Create: `apple/Supermessage.xcodeproj`, `apple/Supermessage/{App.swift,ProbeView.swift,Sink.swift}`

- [ ] **Step 1: Create the app and link the XCFramework**

New SwiftUI app target, minimum iOS 17, bundle id `dev.supermessage.native`. Add `apple/Supermessage.xcframework` as a binary dependency.

- [ ] **Step 2: Implement the sink on a serial queue**

```swift
// Ordering is a correctness requirement: diff envelopes carry `seq` and the
// timeline's recovery logic depends on them arriving in order. UniFFI invokes
// callbacks on whatever thread emitted, so they are serialised here and hopped
// to the main actor before touching any UI.
final class Sink: EventSink {
    private let queue = DispatchQueue(label: "dev.supermessage.events")
    private let onEvent: @MainActor (FfiEvent) -> Void

    func onEvent(event: FfiEvent) {
        queue.async { Task { @MainActor in self.onEvent(event) } }
    }
}
```

- [ ] **Step 3: Probe screen**

A `List` of room names, a login form, and the connection state as text. No styling. If it shows your rooms, the boundary works.

- [ ] **Step 4: Run on the simulator and confirm**

Expected: sign in, see room names, see the connection state change to `live`.

- [ ] **Step 5: Record what UniFFI was like**

Append findings to the spec's Risks section — particularly whether the callback interface carried the timeline subscription's load. This is the evidence Task 8 is planned against.

- [ ] **Step 6: Commit**

```bash
git add apple && git commit -m "feat(ios): probe screen proving the FFI boundary"
```

---

### Task 8: Widen to parity

Five batches, grouped so each leaves the app usable. Each batch repeats Task 5's cycle: monomorphise any generic, add the methods to `Core`, add conversion tests where a type is mirrored, cross-compile, commit.

- [ ] **Batch A — reading a room:** `timeline_subscribe`, `timeline_paginate_back`, `timeline_resync`, `mark_room_read`
- [ ] **Batch B — writing:** `send_message`, `send_reply`, `toggle_reaction`, `set_typing`
- [ ] **Batch C — membership:** `join_room`, `leave_room`, `create_room`, `join_room_by_alias`, `invite_user`, `logout`
- [ ] **Batch D — media:** `media_download`, `media_fetch`, `room_avatar`, `member_avatar`, `attachment_stage`, `attachment_send`, `attachment_discard`
- [ ] **Batch E — the rest:** `spaces_list`, `space_select`, `search_messages`, `room_info`, `log_from_webview`

After each batch: `cargo test --workspace`, cross-compile, and run the probe app.

---

## Definition of done

- `grep -rc tauri crates/supermessage-core/src/` returns nothing but zeros.
- `cargo test --workspace` green, including the golden and conversion tests.
- `pnpm test` (557) and `pnpm check` green; the desktop app sends and receives.
- All 30 commands reachable from Swift; the probe app exercises each batch.
