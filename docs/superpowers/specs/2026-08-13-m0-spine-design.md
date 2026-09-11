# M0 — The Spine: Design

> **Correction, 2026-08-30. Nothing below is edited.** This document names `id.agentpod.dev` as Synapse and treats native OIDC as waiting on matrix-authentication-service. The homeserver was swapped to tuwunel on 2026-08-16 (Synapse is AGPLv3; this suite requires Apache/MIT) and MAS — a Synapse-family component — is not part of the suite at all. See `charter → decisions/2026-08-30-matrix-identity-without-mas.md`. Password login is the login path, not debt awaiting a migration.

**Status:** Approved, pending implementation plan.
**Date:** 2026-08-13.
**Scope:** Log in to a real account on `id.agentpod.dev`, sync it, render the room list and a room timeline, send and receive plaintext messages.

Supersedes nothing; implements the M0 milestone from `docs/positioning.md`.

## 1. Findings that shaped this design

Three facts were established by probing the live homeserver and the dependency tree. Each changes what the existing docs assume.

**Native OIDC is not available.** `id.agentpod.dev` runs Synapse 1.152.0 advertising spec versions up to v1.12. `/_matrix/client/v1/auth_metadata` and the MSC2965 unstable equivalent both return 404, and the only advertised login flows are `m.login.password` and `m.login.application_service`. `docs/tech-stack.md` calls OIDC "primary, password fallback"; the reverse is true today. Native OIDC would require deploying matrix-authentication-service — an infrastructure project, not client work.

**Simplified Sliding Sync is available.** The server advertises `org.matrix.simplified_msc3575`, so `SyncService` can use MSC4186 as planned. Spec v1.11 support also means authenticated media endpoints are available when M2 needs them.

**The "no copyleft" rule is inaccurate as written.** Across 673 crates there is no GPL, AGPL, or LGPL-only code, but 13 crates are MPL-2.0 and none are removable: `eyeball`, `eyeball-im`, `imbl`, `imbl-sized-chunks`, `bitmaps`, `readlock`, `readlock-tokio` and `as_variant` arrive with `matrix-sdk`; `cssparser`, `cssparser-macros`, `dtoa-short`, `selectors` and `option-ext` arrive with Tauri. MPL-2.0 is file-level copyleft — it obliges publishing modifications to those files and explicitly permits combination into a larger work under other terms. That is categorically weaker than the AGPL reach which drove the Flutter and trixnity rejections, so the stack decision stands.

## 2. Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Auth | `m.login.password` only, behind an `AuthProvider` trait | OIDC is unavailable; the trait keeps adding it additive rather than a rewrite |
| Secrets | OS keyring (`keyring` 4.1, MIT/Apache-2.0) | Real agent-org credentials; avoids a migration later |
| SDK stores | `ClientBuilder::sqlite_store(path, Some(passphrase))` — matrix-sdk-sqlite 0.18's `StoreCipher` (AEAD-encrypted keys and values inside a plain SQLite file), **not** SQLCipher | Encrypted at rest, passphrase held in the keyring |
| State bridge | Forward the SDK's `VectorDiff` streams as versioned DTO diffs | IPC proportional to change, not state size |
| UI | Two-pane desktop chat on the Tailwind token layer | M0 exists to be dogfooded; skins remain M2 |
| License rule | Amend to permissive + unmodified MPL-2.0; strong/network copyleft still banned | Makes the rule match the stack and stay checkable |

## 3. Architecture

New dependency: `matrix-sdk-ui` 0.18 (Apache-2.0), which provides `sync_service::SyncService`, `room_list_service::RoomListService` and `timeline::Timeline`, and re-exports `eyeball_im`.

### Rust core — `src-tauri/src/core/`

| Module | Responsibility | Depends on |
|---|---|---|
| `auth/mod.rs` | `AuthProvider` trait: `login`, `restore`, `logout` | `secrets` |
| `auth/password.rs` | `m.login.password` implementation | `auth` |
| `secrets.rs` | Keyring-backed tokens and store passphrase | — |
| `session.rs` | Owns the single `Client`; wires store paths and secrets | `auth`, `secrets` |
| `sync.rs` | `SyncService` lifecycle and connection state | `session` |
| `rooms.rs` | `RoomListService` diff stream → room-list events | `session`, `dto` |
| `timeline.rs` | Focused-room `Timeline`: diffs, back-pagination, send | `session`, `dto` |
| `dto.rs` | Versioned serde DTOs and diff envelopes | — |
| `commands.rs` | Tauri command surface | all of the above |

Existing `tls.rs` is unchanged, but its ordering constraint is load-bearing: `install_ring_provider()` must run before any TLS is constructed, which now includes every `Client` build.

### Frontend — `src/lib/`

- `ipc.ts` — typed wrappers over commands and event channels; the only file that imports `@tauri-apps/api`.
- `stores/diff.ts` — the single diff-application primitive, including gap detection.
- `stores/rooms.ts`, `stores/timeline.ts` — thin stores built on that primitive.
- `stores/connection.ts` — connection state for the status banner.
- Routes: login screen, and a two-pane layout (room list, virtua timeline, composer).

### Data flow

```
password login ──> Session (one Client) ──> SyncService.start()
                                              │
                        RoomListService ──────┤──> VectorDiff<Room>
                                              │      └─> dto projection ─> sm://rooms/diff ─> rooms store
                        Timeline(focused) ────┘──> VectorDiff<TimelineItem>
                                                     └─> dto projection ─> sm://timeline/diff ─> timeline store

composer ──> send_message command ──> Timeline::send  (echo returns via the diff stream)
```

Exactly one `Timeline` subscription exists at a time. `timeline_subscribe(roomId)` drops the previous subscription before creating the new one, so background timelines cannot accumulate.

## 4. The IPC contract

DTOs are versioned and hand-written; SDK types never cross the boundary. This keeps the webview a dumb renderer, as `docs/tech-stack.md` requires, and means an SDK upgrade cannot silently reshape the UI's data.

Every diff event is an envelope:

```
DiffEnvelope {
  channel: "rooms" | "timeline",
  subject: String,        // room id for timeline; empty for rooms
  seq: u64,               // monotonic, per channel+subject
  ops: Vec<DiffOp>,
}
```

`DiffOp` mirrors `eyeball_im::VectorDiff` 0.8 exactly — all eleven variants, no
subset: `Append`, `Clear`, `PushFront`, `PushBack`, `PopFront`, `PopBack`,
`Insert`, `Set`, `Remove`, `Truncate`, `Reset`. Implementing a subset and
treating the rest as a reset would work but would throw away the SDK's
precision on the exact operations a busy timeline emits most.

**Sequence numbers are the correctness mechanism, not decoration.** A dropped or out-of-order event would otherwise leave a timeline that is subtly and permanently wrong. On seeing `seq != expected`, the store discards local state and calls a resync command for a fresh snapshot. `Reset` from the SDK is forwarded as-is and handled the same way.

Commands (all returning `Result<T, CoreError>`):

| Command | Purpose |
|---|---|
| `login(homeserver, username, password)` | Password login; persists session |
| `restore_session()` | Attempt restore from keyring at startup |
| `logout()` | Clear session, secrets and stores |
| `rooms_resync()` | Full room-list snapshot after a gap |
| `timeline_subscribe(room_id)` | Focus a room; replaces any existing subscription |
| `timeline_paginate_back(count)` | Load older events |
| `timeline_resync()` | Full timeline snapshot after a gap |
| `send_message(body)` | Send plaintext to the focused room |
| `core_status()` | Existing diagnostic |

## 5. Error handling

`CoreError` is a serializable enum — `Auth`, `Network`, `Store`, `Protocol`, `NotReady` — carrying a human-readable message. Stringified errors are not acceptable: the UI must be able to branch on the variant (bad password versus server unreachable) without parsing prose.

Sync health is a separate event channel carrying `Offline | Syncing | Live`, so a stalled sync shows a banner rather than a frozen-looking UI.

Keyring unavailability is an error, never a silent fallback. If the platform secret store cannot be reached, login fails with `CoreError::Store` and an explanatory message. Writing credentials to disk in plaintext because the keyring was missing would defeat the decision in §2.

## 6. Testing

- **Rust unit tests** — DTO projection and `VectorDiff` → `DiffOp` translation are pure functions and are tested without network or SDK state. This is where the diff vocabulary is pinned.
- **Rust integration test** — login and session restore against wiremock, using matrix-sdk's `testing` feature.
- **Frontend unit tests** — vitest (added as part of this work) over `stores/diff.ts`: every diff variant, plus the gap-detection and resync path.
- **Manual dogfooding** — against a real account on `id.agentpod.dev`.

The existing `cargo test` provider test stays. `pnpm tauri dev` remains the only correct way to run the app; a bare `cargo run` loads `devUrl` with nothing serving it.

## 7. Out of scope for M0

E2EE user experience (encrypted rooms render an explicit placeholder), media, replies, reactions, edits, read receipts, typing indicators, multi-account, push, and any mobile or per-platform skin. Each belongs to a later milestone in `docs/positioning.md`.

## 8. Follow-ups this creates

- **Android secret storage.** `keyring` covers desktop but not Android, which needs Android Keystore through JNI. M0 ships a documented stub that returns `CoreError::Store` on Android rather than degrading to plaintext. Real support is M2 work, alongside the 16KB-page verification already recorded in `AGENTS.md`.
- **Documentation amendments.** `AGENTS.md` and `docs/tech-stack.md` need the license rule corrected per §1, and the auth ordering corrected to reflect that password is primary until matrix-authentication-service exists.
- **Test credentials.** A real account on `id.agentpod.dev` is required for the dogfooding step. Everything else can be built and tested without it.
