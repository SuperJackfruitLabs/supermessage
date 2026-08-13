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
 * One joined member of a room, mirroring `RoomMemberDto` from
 * `src-tauri/src/core/room_info.rs`. Part of {@link RoomInfo.members}.
 */
export interface RoomMember {
  userId: string;
  /** The member's own display name, when set. `null` means the reader
   * should fall back to `userId` — the same convention every other
   * sender-name field in this codebase already uses. */
  displayName: string | null;
  /** The member's avatar as a raw `mxc://` URI, resolved to a renderable
   * `data:` URI lazily through {@link memberAvatar} — never fetched bytes
   * on this object itself. */
  avatarUrl: string | null;
}

/**
 * A room's descriptive metadata plus its joined member list, mirroring
 * `RoomInfoDto` from `src-tauri/src/core/room_info.rs`. Fetched on demand
 * through {@link roomInfo} when the room-info panel opens — never streamed,
 * unlike {@link RoomSummary}/{@link TimelineItem}.
 */
export interface RoomInfo {
  roomId: string;
  name: string | null;
  topic: string | null;
  canonicalAlias: string | null;
  altAliases: string[];
  /** The room's active (joined + invited) member count — may exceed
   * `members.length`, which is joined-only; see `RoomInfoDto::active_member_count`'s
   * doc comment on the Rust side for why that's expected, not a mismatch. */
  activeMemberCount: number;
  members: RoomMember[];
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
  /**
   * The event's raw `content` object, present only for `kind:
   * "customMessage"` — mirrors `TimelineItemDto::custom_payload` from
   * `src-tauri/src/core/dto.rs`. `null` for a local echo of a custom event
   * (this app sends none today), for a payload that failed to bound (see
   * that struct's doc comment for the byte cap and why it's dropped whole
   * rather than truncated), or for every other `kind`.
   *
   * **Untrusted, arbitrary JSON from anyone who can send to the room.**
   * Typed `unknown`, not a shaped interface, deliberately: nothing may read
   * a field out of this without checking its type first (see
   * `$lib/components/customEvents.ts`'s `safeStringField` for the pattern),
   * and nothing read out of it may ever reach `{@html}`, an `href`, an
   * `src`, or a `style` — nothing here narrows that responsibility away.
   */
  customPayload: unknown;
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

/**
 * Mirrors `CoreError::kind()` from `src-tauri/src/core/error.rs`.
 *
 * `"roomChanged"` is distinct from `"protocol"`/`"notReady"` on purpose: it
 * means a room-scoped command ({@link sendMessage}, {@link sendReply},
 * {@link toggleReaction}, {@link timelinePaginateBack}) named a `roomId`
 * that wasn't the room actually focused when the core got around to running
 * it — the caller lost a race against a room switch — and **the command did
 * not act**. Every other kind here describes something that went wrong
 * while the core was doing what it was asked; this one describes the core
 * refusing to do something it was no longer being asked for. Callers should
 * surface it, not swallow it the way a generic failure might be: a send
 * that silently landed nowhere still needs the reader to know it needs
 * retrying, the same way a send that visibly failed does.
 */
export type CoreErrorKind = "auth" | "network" | "store" | "protocol" | "notReady" | "roomChanged";

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
 * Paginates `roomId`'s timeline backwards by up to `count` events. Resolves
 * `true` when the start of the timeline was reached.
 *
 * `roomId` must be the room the caller actually means: the core checks it
 * against whichever room is focused when the command runs and rejects with
 * a `"roomChanged"`-kind {@link CoreError} on a mismatch (e.g. the reader
 * switched rooms while this call was in flight) rather than silently
 * paginating whatever room ended up focused instead. See
 * {@link CoreErrorKind}'s doc comment.
 */
export async function timelinePaginateBack(roomId: string, count: number): Promise<boolean> {
  return invoke<boolean>("timeline_paginate_back", { roomId, count });
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

/**
 * Sends a plain-text message to `roomId`.
 *
 * `roomId` must be the room the caller actually means, same as
 * {@link timelinePaginateBack}'s `roomId` — the core verifies it against
 * whichever room is focused when the command runs and rejects with a
 * `"roomChanged"`-kind {@link CoreError} on a mismatch, **without sending**,
 * rather than silently delivering into whatever room ended up focused
 * instead. This is the command where that guard matters most: unlike
 * {@link sendReply}/{@link toggleReaction}, nothing about sending a plain
 * message would otherwise fail just because it landed in the wrong room —
 * see {@link CoreErrorKind}'s doc comment.
 */
export async function sendMessage(roomId: string, body: string): Promise<void> {
  await invoke<void>("send_message", { roomId, body });
}

/**
 * Sends a plain-text reply to `inReplyTo` (a parent event id) in `roomId`.
 * Does not append anything to `timelineStore.items` itself — same as
 * {@link sendMessage}, the SDK adds the local echo to the timeline, which
 * arrives back through the diff stream {@link onTimelineDiff} subscribes to.
 *
 * `roomId` is checked the same way, and for the same reason, as
 * {@link sendMessage}'s.
 *
 * `inReplyTo` must be a real Matrix event id, not a local echo's transaction
 * id — see `TimelineItem.sendState`'s doc comment and
 * `$lib/components/timelineItemView.ts`'s `canReplyOrReact` for the rule the
 * webview uses to only ever offer this for an item that already has one.
 */
export async function sendReply(roomId: string, body: string, inReplyTo: string): Promise<void> {
  await invoke<void>("send_reply", { roomId, body, inReplyTo });
}

/**
 * Toggles `key` as a reaction on `eventId` in `roomId`. Resolves to whether
 * the reaction was added (`true`) or removed (`false`) — mirrors
 * `Timeline::toggle_reaction`'s own return value. Does not append anything
 * to `timelineStore.items` itself, same reasoning as {@link sendReply}: the
 * SDK's local echo arrives back through the diff stream.
 *
 * `roomId` is checked the same way, and for the same reason, as
 * {@link sendMessage}'s. `eventId` has the same real-event-id requirement
 * as {@link sendReply}'s `inReplyTo`.
 */
export async function toggleReaction(roomId: string, eventId: string, key: string): Promise<boolean> {
  return invoke<boolean>("toggle_reaction", { roomId, eventId, key });
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

/**
 * Fetches `roomId`'s room-info panel data — name, topic, canonical alias,
 * alt aliases, room id and joined member list — mirroring `RoomInfoDto`
 * from `src-tauri/src/core/room_info.rs`.
 *
 * `roomId` must be the room the caller actually means: the core verifies it
 * against whichever room is focused when the command runs and rejects with
 * a `"roomChanged"`-kind {@link CoreError} on a mismatch (e.g. the reader
 * switched rooms while this call was in flight) rather than silently
 * showing one room's identity under another room's header — see
 * {@link CoreErrorKind}'s doc comment.
 */
export async function roomInfo(roomId: string): Promise<RoomInfo> {
  return invoke<RoomInfo>("room_info", { roomId });
}

/**
 * Fetches a room member's avatar as a `data:` URI, given the raw `mxc://`
 * URI already carried on their {@link RoomInfo.members} entry. `null` for
 * the same reasons {@link roomAvatar}'s are: nothing to show, or the
 * fetched bytes don't sniff to a renderable image format.
 *
 * Reuses the exact same authenticated-media fetch {@link roomAvatar} already
 * uses for a room's own avatar (`core::media::avatar_thumbnail`) — not a
 * second fetch path — called directly on the member's own mxc URI, since a
 * member's avatar (unlike a room's) has no hero/two-person fallback chain to
 * resolve first. Callers fetch this lazily and cache the result themselves,
 * keyed on the mxc URI (see `$lib/stores/memberAvatarCache.svelte.ts`), the
 * same pattern {@link roomAvatar}/{@link mediaFetch} use keyed on room id /
 * event id respectively.
 */
export async function memberAvatar(mxcUri: string): Promise<string | null> {
  return invoke<string | null>("member_avatar", { mxcUri });
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
