// The single file that talks to Tauri. Every other module — stores,
// components — goes through the typed wrappers here instead of importing
// `@tauri-apps/api` directly, so the wire format (command names, argument
// casing, event channels) has exactly one place it can drift from the Rust
// core.
//
// Command names stay snake_case (that's what `#[tauri::command]` registers);
// their JS-side argument objects are camelCase, per Tauri's default arg
// conversion. Getting an argument name wrong here fails at runtime with
// "invalid args", not at compile time — the names below are copied
// verbatim from `src-tauri/src/core/commands.rs` and `src-tauri/src/lib.rs`.

import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { DiffEnvelope } from "./stores/diff";

/** Mirrors the `CoreStatus` struct returned by the `core_status` command. */
export interface CoreStatus {
  platform: string;
  cryptoProvider: string;
  sdkReady: boolean;
}

/**
 * Mirrors `RoomSummary` from `src-tauri/src/core/dto.rs`. `lastMessage` is
 * currently always `null` — the core defers message-preview decoding — so
 * callers must render that as "no preview", not treat it as a bug.
 */
export interface RoomSummary {
  id: string;
  name: string;
  avatarUrl: string | null;
  unread: number;
  lastMessage: string | null;
  lastActivityMs: number | null;
}

/**
 * Mirrors `TimelineItemDto` from `src-tauri/src/core/dto.rs`.
 *
 * `kind` is a semantic discriminant projected from the SDK's
 * `TimelineItemContent` (see `core::timeline::classify_content`), not a raw
 * Matrix event-type string. `msgtype` is only populated for `kind:
 * "message"`; `detail` carries kind-specific context (a membership change's
 * change kind, a state event's event type, a custom event's event type, …).
 * `$lib/components/timelineItemView.ts` is what turns this into a render
 * decision — see its doc comment and `docs/matrix-events.md` for the full
 * mapping.
 */
export interface TimelineItem {
  id: string;
  kind: string;
  msgtype: string | null;
  detail: string | null;
  sender: string | null;
  senderDisplayName: string | null;
  body: string | null;
  timestampMs: number | null;
  isOwn: boolean;
  sendState: string | null;
}

/** Mirrors `CoreError::kind()` from `src-tauri/src/core/error.rs`. */
export type CoreErrorKind = "auth" | "network" | "store" | "protocol" | "notReady";

/**
 * Mirrors the serialized shape of `CoreError`. Every command in this file
 * rejects with a value of this shape at runtime; TypeScript can't express
 * that in `invoke`'s rejection type, so callers that need to branch on
 * `kind` should catch and cast (`err as CoreError`).
 */
export interface CoreError {
  kind: CoreErrorKind;
  message: string;
}

/**
 * Mirrors the `state` values `core::sync::connection_state_name` can emit.
 * Nothing currently emits `"syncing"` — the UI is expected to treat every
 * non-`"live"` state the same — but it stays in the type because the core
 * doc comments reserve it for future use.
 */
export type ConnectionState = "offline" | "syncing" | "live" | "error";

/** Mirrors the `ConnectionPayload` struct emitted on `sm://connection`. */
export interface ConnectionPayload {
  state: ConnectionState;
  message: string | null;
}

const ROOMS_DIFF_EVENT = "sm://rooms/diff";
const TIMELINE_DIFF_EVENT = "sm://timeline/diff";
const CONNECTION_EVENT = "sm://connection";

/** Queries the core's basic identity — platform, crypto provider, SDK link. */
export async function coreStatus(): Promise<CoreStatus> {
  return invoke<CoreStatus>("core_status");
}

/**
 * Builds the `login`/`restoreSession` commands, each wired to call `onArm`
 * *before* invoking the underlying Tauri command.
 *
 * `login` and `restore_session` are deliberately **not** exported as bare
 * functions the way every other command in this file is. The core restarts
 * its room-list sequence counter from scratch every time either of them
 * starts streaming (`SeqCounter::default()` inside `spawn_room_list`, run
 * again on every `start_streams` call), so whatever calls them must first
 * re-arm the room-list `DiffTracker` — see `rooms.svelte.ts`'s module doc
 * comment for the full hazard this guards against.
 *
 * A doc-comment warning on a bare exported function isn't enough — nothing
 * stops a future caller from importing it directly and skipping the
 * re-arm, silently reintroducing the corruption. Requiring `onArm` as a
 * constructor argument instead means there is no way to obtain a working
 * `login`/`restoreSession` function without supplying it: the arm is part
 * of the function's own body, not a step the caller has to remember to
 * take first. `rooms.svelte.ts` is the sole caller, passing its
 * `gapSync.resetForNewSubscription` as `onArm`.
 */
export function makeSessionCommands(onArm: () => void) {
  return {
    async login(homeserver: string, username: string, password: string): Promise<void> {
      onArm();
      await invoke<void>("login", { homeserver, username, password });
    },
    async restoreSession(): Promise<boolean> {
      onArm();
      return invoke<boolean>("restore_session");
    },
  };
}

/** Logs out, clearing the session, secrets and local stores. */
export async function logout(): Promise<void> {
  await invoke<void>("logout");
}

/**
 * A full snapshot of the room list for resync after a detected gap: the
 * sequence number of the last diff folded in, and the resulting list. The
 * core returns this as a 2-element JSON array, not an object — destructure
 * positionally (`const [seq, rooms] = await roomsResync();`).
 */
export async function roomsResync(): Promise<[number, RoomSummary[]]> {
  return invoke<[number, RoomSummary[]]>("rooms_resync");
}

/** Subscribes to `roomId`'s timeline, replacing any previously focused room. */
export async function timelineSubscribe(roomId: string): Promise<void> {
  await invoke<void>("timeline_subscribe", { roomId });
}

/**
 * Paginates the focused timeline backwards by up to `count` events. Resolves
 * `true` when the start of the timeline was reached.
 */
export async function timelinePaginateBack(count: number): Promise<boolean> {
  return invoke<boolean>("timeline_paginate_back", { count });
}

/**
 * A full snapshot of the focused timeline for resync after a detected gap.
 * A positional array like {@link roomsResync}, but a 3-element one:
 * `[subject, seq, items]`, where `subject` is the room id the snapshot
 * belongs to.
 *
 * The room id is load-bearing, not informational. The core serves this out
 * of whichever timeline subscription is currently installed, which during a
 * room switch is still the *previous* room's — a caller that folds the
 * result in without checking `subject` will show the previous room's
 * messages under the new room's header, permanently. See
 * `core::timeline::TimelineSnapshot` for the full sequence.
 */
export async function timelineResync(): Promise<[string, number, TimelineItem[]]> {
  return invoke<[string, number, TimelineItem[]]>("timeline_resync");
}

/** Sends a plain-text message to the focused room. */
export async function sendMessage(body: string): Promise<void> {
  await invoke<void>("send_message", { body });
}

/** Subscribes to room-list diff envelopes on {@link ROOMS_DIFF_EVENT}. */
export function onRoomsDiff(handler: (env: DiffEnvelope<RoomSummary>) => void): Promise<UnlistenFn> {
  return listen<DiffEnvelope<RoomSummary>>(ROOMS_DIFF_EVENT, (event) => handler(event.payload));
}

/** Subscribes to focused-timeline diff envelopes on {@link TIMELINE_DIFF_EVENT}. */
export function onTimelineDiff(handler: (env: DiffEnvelope<TimelineItem>) => void): Promise<UnlistenFn> {
  return listen<DiffEnvelope<TimelineItem>>(TIMELINE_DIFF_EVENT, (event) => handler(event.payload));
}

/** Subscribes to connection-health updates on {@link CONNECTION_EVENT}. */
export function onConnection(handler: (payload: ConnectionPayload) => void): Promise<UnlistenFn> {
  return listen<ConnectionPayload>(CONNECTION_EVENT, (event) => handler(event.payload));
}
