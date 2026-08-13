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
 *
 * `avatarUrl` is the room's raw `mxc://` URI when the room has one set, but
 * it is **not** the full picture: it's `null` for a room whose "avatar" is
 * really its other member's profile picture (Element shows one; the room
 * itself has no `m.room.avatar`), because that requires reading the room's
 * member list — async, store-backed work the core's synchronous room-list
 * projection can't do (see `core::rooms::resolve_room_avatar_mxc`'s doc
 * comment). Even when it isn't `null`, it's still just an identifier, not
 * loadable image data — browsers can't fetch `mxc://`, and this homeserver's
 * media endpoints are authenticated, so no bare `http(s)://` URL exists
 * either. Never use this field to decide whether to fetch an avatar; call
 * {@link roomAvatar} unconditionally, for every room, and let it resolve
 * (and return `null` when there is genuinely nothing to show).
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
  /**
   * The message's HTML formatted body, present only when the core reports
   * `format: "org.matrix.custom.html"` (`core::timeline::formatted_html_body`).
   * Already sanitised and hardened in the core, **before** it ever reaches
   * this process — that is what makes it safe for `Timeline.svelte` to
   * render with `{@html}`; see its doc comment. Never pipe unsanitised text
   * through the same path.
   *
   * Two core-side passes produce this, and they are not
   * redundant-with-each-other belt-and-braces: `matrix_sdk_ui`'s own
   * `HtmlSanitizerMode::Compat` allowlist pass is reliable for removing
   * *elements* / *attributes* outright (no `<script>`, no `on*` handler, no
   * `style` attribute survives it) but is **not** reliably enforcing the
   * `<a href>`/`<img src>` *scheme* rules it advertises — ruma-html 0.8.0
   * has a bug that can skip that specific check. `core::timeline::harden_formatted_body`'s
   * own pass — which removes `<img>`/`<mx-reply>` outright and narrows
   * `<a href>` to `http`/`https`/`mailto`/`matrix` — is what actually
   * enforces those two, and is the one that must never be removed on the
   * assumption the first pass already covers it. See that function's doc
   * comment for the exact mechanism and a worked example.
   */
  formattedBody: string | null;
  /**
   * Size/dimension metadata for an `m.image`/`m.file`/`m.audio`/`m.video`
   * message, mirroring `MediaMetaDto` from `src-tauri/src/core/dto.rs`.
   * `null` for every other message. Deliberately carries no bytes and no
   * mxc URI — see that struct's doc comment for why (a `Set` diff op
   * re-sends the whole item, so embedding image data here would inflate
   * every timeline update). Fetch the actual bytes lazily through
   * {@link mediaFetch}, keyed on the item's `id` (its event id), not
   * anything on this object.
   */
  media: MediaMeta | null;
  timestampMs: number | null;
  isOwn: boolean;
  sendState: string | null;
  /**
   * Present when this item is a reply (`m.in_reply_to`); `null` for an
   * ordinary message and for every non-message `kind`. Mirrors `ReplyToDto`
   * from `src-tauri/src/core/dto.rs` — see {@link ReplyTo} for how an
   * unloaded parent is represented.
   */
  replyTo: ReplyTo | null;
  /**
   * Whether the SDK has folded an `m.replace` edit into this item
   * (`Message::is_edited`). Always `false` for a non-message `kind`.
   */
  edited: boolean;
  /**
   * Reactions aggregated onto this item, one entry per distinct key. Empty
   * (never `null`) when the item has none. Mirrors `ReactionDto`.
   */
  reactions: Reaction[];
}

/** Mirrors `MediaMetaDto` from `src-tauri/src/core/dto.rs`. See {@link TimelineItem.media}. */
export interface MediaMeta {
  filename: string;
  mimetype: string | null;
  size: number | null;
  width: number | null;
  height: number | null;
}

/**
 * A reply's quoted parent, mirroring `ReplyToDto` from
 * `src-tauri/src/core/dto.rs`. The parent is fetched lazily by the SDK and
 * this build never resolves it further (no
 * `Timeline::fetch_details_for_event` call), so `available: false` is a real
 * and common outcome, not just an edge case — render it as a neutral
 * "Original message unavailable" quote, never an empty quote or a spinner
 * that will not resolve on its own.
 */
export interface ReplyTo {
  /** The parent event's id. Present even when the parent itself didn't load. */
  eventId: string;
  /** Whether the parent's details were actually loaded. */
  available: boolean;
  /** The parent's raw sender id. `null` when `available` is `false`. */
  sender: string | null;
  /** The parent's sender display name, when known. */
  senderDisplayName: string | null;
  /**
   * A short quote of the parent's body, **already truncated in the core**
   * (`core::timeline::REPLY_EXCERPT_MAX_CHARS`) — this string is safe to
   * render as-is, without a further display-only clamp standing in for real
   * truncation. `null` when `available` is `false`, or the parent isn't a
   * message (or has no body) to quote. Still sender-controlled text: guard
   * it against overflow the same as any other free-text field (see
   * `Timeline.svelte`).
   */
  excerpt: string | null;
  /**
   * A short label for *why* there's nothing to quote, when `available` is
   * `true` but `excerpt` is `null` — a redacted, sticker, poll, or
   * undecryptable parent has a sender but no body. Classified in the core
   * the same way a top-level item is (`core::timeline::reply_parent_label`,
   * built on `classify_content`), so it reads with the same vocabulary
   * `$lib/components/timelineItemView.ts`'s `viewFor` placeholders already
   * use (e.g. `"Message deleted"`). `null` whenever `excerpt` is non-null,
   * and always `null` when `available` is `false` (that case already has
   * its own "Original message unavailable" wording — see `ReplyQuoteView`
   * in `timelineItemView.ts`).
   */
  label: string | null;
}

/**
 * One reaction key aggregated across senders on a message, mirroring
 * `ReactionDto`.
 */
export interface Reaction {
  /**
   * The reaction's key — an arbitrary sender-controlled string, not
   * necessarily a single emoji. Cap its rendered length and guard it against
   * overflow like any other free-text field from a sender.
   */
  key: string;
  /** How many distinct senders have reacted with this key. */
  count: number;
  /** Whether the current user is among those senders. */
  byMe: boolean;
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

/**
 * Sends a plain-text reply to `inReplyTo` (a parent event id) in the focused
 * room. Does not append anything to `timelineStore.items` itself — same as
 * {@link sendMessage}, the SDK adds the local echo to the timeline, which
 * arrives back through the diff stream {@link onTimelineDiff} subscribes to.
 *
 * `inReplyTo` must be a real Matrix event id, not a local echo's transaction
 * id — see `TimelineItem.sendState`'s doc comment and
 * `$lib/components/timelineItemView.ts`'s `canReplyOrReact` for the rule the
 * webview uses to only ever offer this for an item that already has one.
 */
export async function sendReply(body: string, inReplyTo: string): Promise<void> {
  await invoke<void>("send_reply", { body, inReplyTo });
}

/**
 * Toggles `key` as a reaction on `eventId` in the focused room. Resolves to
 * whether the reaction was added (`true`) or removed (`false`) — mirrors
 * `Timeline::toggle_reaction`'s own return value. Does not append anything
 * to `timelineStore.items` itself, same reasoning as {@link sendReply}: the
 * SDK's local echo arrives back through the diff stream.
 *
 * `eventId` has the same real-event-id requirement as {@link sendReply}'s
 * `inReplyTo`.
 */
export async function toggleReaction(eventId: string, key: string): Promise<boolean> {
  return invoke<boolean>("toggle_reaction", { eventId, key });
}

/**
 * Resolves and fetches `roomId`'s avatar as a `data:` URI, or `null` when
 * the room genuinely has nothing to show (or the core couldn't identify the
 * fetched bytes as a renderable image format). Takes a room id, not an mxc
 * URI: resolution needs the room's member list for a DM whose "avatar" is
 * really the other member's profile picture — something `RoomSummary`'s
 * `avatarUrl` alone can't express, so **call this for every room**, not
 * only those with a non-null `avatarUrl` (see that field's doc comment).
 * Callers fetch this lazily and cache the result themselves, keyed on
 * `roomId` (see `$lib/stores/avatarCache.svelte.ts` for why room id rather
 * than mxc URI, and the trade-off that implies).
 */
export async function roomAvatar(roomId: string): Promise<string | null> {
  return invoke<string | null>("room_avatar", { roomId });
}

/**
 * Fetches `eventId`'s media (an `m.image`/`m.file`/`m.audio`/`m.video`
 * message's content) as a thumbnail `data:` URI, or `null` when the event
 * isn't in the focused timeline, isn't a media message, or its bytes don't
 * sniff to a renderable image format.
 *
 * Takes the item's **event id**, not an mxc URI — `TimelineItem.media`
 * never carries one (see its doc comment), and an mxc string alone
 * couldn't address encrypted media anyway; the core re-resolves the real
 * `MediaSource` from the live timeline item every call. Callers fetch this
 * lazily and cache the result themselves, keyed on the event id (see
 * `$lib/stores/mediaCache.svelte.ts`), the same pattern {@link roomAvatar}
 * uses keyed on room id.
 */
export async function mediaFetch(eventId: string): Promise<string | null> {
  return invoke<string | null>("media_fetch", { eventId });
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
