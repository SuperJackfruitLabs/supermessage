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
 * Mirrors `RoomSummary` from `src-tauri/src/core/dto.rs`.
 *
 * The four `last*` fields below are the roster preview contract (spec
 * §6.1.1). They are resolved together in the core
 * (`core::timeline::MessagePreview`) and are only ever consistent as a
 * group: whenever `lastMessage` is `null`, `lastMessageIsOwn` and
 * `lastMessageNamesSender` are `false` and `lastEventType` is `null`. The
 * core returns facts, never a composed display string — building the line
 * (including the `You: ` prefix) is the webview's job, and
 * `$lib/components/roomPreview.ts` is the one place it happens.
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
/**
 * This account's relationship to a room, mirroring `Membership` in
 * `src-tauri/src/core/dto.rs`.
 *
 * The roster lists invited rooms alongside joined ones (the core filters with
 * `new_filter_non_left`), so this is the only thing distinguishing "a room" from
 * "an invitation to a room" — see `$lib/components/invitationView.ts` for what
 * the UI does with each state.
 */
export type Membership = "joined" | "invited" | "left" | "knocked" | "banned";

export interface RoomSummary {
  id: string;
  name: string;
  avatarUrl: string | null;
  unread: number;
  /**
   * The roster preview text for the room's latest event, or `null` when
   * there is nothing to preview.
   *
   * Already bounded (100 code points, with an ellipsis) and
   * whitespace-collapsed by the core, and carries **no sender prefix** —
   * the `You: ` prefix is the webview's to add, and only its own (see
   * {@link RoomSummary.lastMessageIsOwn} and
   * {@link RoomSummary.lastMessageNamesSender}).
   *
   * `null` whenever the room's latest event is not message-like:
   * membership changes, room renames and other state, reactions,
   * redactions and undecryptable events are all ineligible **by design**,
   * not by omission — the row keeps showing the last thing actually *said*
   * rather than filling the roster with the churn a restarting fleet
   * generates (spec §6.1.1). A msgtype the timeline itself refuses to
   * render is ineligible too, so the roster can never claim something was
   * said that the timeline will not show. Render this as "no preview" —
   * omit the line entirely, no placeholder string — never as a bug.
   *
   * One honest caveat, latent while this deployment's rooms are
   * unencrypted: the SDK's latest-event scan skips events it cannot
   * decrypt and keeps walking backwards, so in an encrypted room missing
   * keys this text can be *older* than `lastActivityMs`, with nothing on
   * the row saying so.
   */
  lastMessage: string | null;
  /**
   * Whether the event {@link RoomSummary.lastMessage} previews was sent by
   * this account. Drives the `You: ` prefix — but only in combination with
   * {@link RoomSummary.lastMessageNamesSender}; see that field.
   *
   * `false` whenever there is no preview at all.
   */
  lastMessageIsOwn: boolean;
  /**
   * Whether {@link RoomSummary.lastMessage} **already names its own
   * sender**, so a caller adding a `You: `-style prefix would name them
   * twice.
   *
   * `true` only for an emote, which the core renders as `"<Name> waves"`
   * to match how `Timeline.svelte` renders the same event. Without this
   * flag an own emote composes to `"You: <MyName> waves"` — the defect
   * this field exists to prevent, and the reason the prefix rule is
   * `isOwn && !namesSender` rather than plain `isOwn`.
   *
   * `false` for every other previewable message, including a custom
   * event's plain-text fallback body (schema-author prose, which does not
   * name its sender), and `false` whenever there is no preview at all.
   */
  lastMessageNamesSender: boolean;
  /**
   * The Matrix event type behind the preview, populated **only for a
   * custom (unrecognized-type) event**; `null` for an ordinary message and
   * whenever there is no preview.
   *
   * The hook the roster's pending-decision path keys off
   * (`$lib/components/roomPreview.ts` against
   * `customEvents.ts`'s `DECISION_BEARING_EVENT_TYPES`). Unreachable in
   * production **twice over**, and the second reason survives the first
   * being fixed: no gate schema exists yet, *and* the SDK's latest-event
   * builder ends its message-like arm in an unqualified catch-all that
   * rejects ruma's `_Custom` variant, so a custom event never becomes a
   * room's latest event in the first place — the roster shows the last
   * ordinary message underneath it instead. That second one is inside the
   * SDK's own background task, with no builder hook equivalent to the
   * `event_filter` override `core::timeline` uses to patch the identical
   * gap for the timeline. Do not "fix" a preview path that never lights
   * up by loosening either end of this; see `core::rooms::room_preview`.
   */
  lastEventType: string | null;
  lastActivityMs: number | null;
  /**
   * Whether this account has joined the room or has merely been invited to
   * it — see {@link Membership}.
   *
   * An invitation carries no readable history and cannot be posted to, so
   * both the roster row and the room pane branch on it (issue #1).
   */
  membership: Membership;
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
  /**
   * The raw user ids of every *other* member whose latest read receipt
   * currently points at this event, mirroring `TimelineItemDto::read_by`
   * from `src-tauri/src/core/dto.rs`. Empty (never `null`) for a non-message
   * `kind`, an item nobody has read up to yet, or — per that struct's doc
   * comment — the sender's own message before anyone *else* has read it: the
   * SDK credits sending a message with an implicit receipt on it, so a
   * message's `readBy` is never really "empty" from its own sender's point
   * of view, just from every other member's.
   *
   * Deliberately raw ids, never resolved display names — see that struct's
   * doc comment for why. `Timeline.svelte`'s `seenMarker` is the only
   * consumer: a plain "Seen"/"Seen by N" count on the reader's own latest
   * message, never a per-message avatar stack or a name list.
   */
  readBy: string[];
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
  /**
   * The same key, bounded for rendering by the core. Use this on screen and
   * {@link Reaction.key} on the wire — they usually look alike, but `key` is
   * compared byte-for-byte against what other clients sent.
   */
  displayKey: string;
  /** How many distinct senders have reacted with this key. */
  count: number;
  /** Whether the current user is among those senders. */
  byMe: boolean;
}

/**
 * One space the rail draws — joined, or merely invited — mirroring
 * `SpaceSummary` from
 * `src-tauri/src/core/spaces.rs`. Returned by {@link spacesList}; the rail
 * (`$lib/components/SpacesRail.svelte`) is its only consumer.
 *
 * A space **is a room** — same state, same timeline, marked only by
 * `m.room.type: "m.space"` — which is why `name` gets parsed by the same
 * `parseRoomIdentity` the roster uses (a space can carry the `glyph — Name
 * — Role` structure too) and why its avatar is fetched with the ordinary
 * {@link roomAvatar}, keyed on this `id`.
 */
export interface SpaceSummary {
  id: string;
  /**
   * The space's display name, **never empty**: the core falls back to the
   * room id, the same convention `RoomSummary.name` follows. Still
   * server-controlled text — bound and escape it like any other such
   * string, never `{@html}`.
   */
  name: string;
  /**
   * The space's own `m.room.avatar` as a raw `mxc://` URI, or `null`.
   * **Not loadable image data** and not a reason to skip the fetch — the
   * same rules as {@link RoomSummary.avatarUrl}: call {@link roomAvatar}
   * with this space's `id` unconditionally and let it resolve.
   *
   * Unlike a room's, this has no hero/two-person fallback behind it: those
   * rules infer a *conversation's* picture from the person on the other
   * side, and a space is not a conversation. A space with no avatar shows
   * its parsed initial in the rail (spaces-rail design §6).
   */
  avatarUrl: string | null;
  /**
   * How many **joined** rooms the reader will actually see when they select
   * this space: the size of the flattened subtree, which is the very list
   * that becomes the roster's filter. Not the `m.space.child` count —
   * children we have not joined, and nested spaces, are excluded, because a
   * space advertising twelve and then revealing four is worse than showing
   * no number at all.
   *
   * **`0` is a real, expected value**, not a "still loading" state: a space
   * whose joined children are all gone is still a space, and selecting it
   * yields an empty roster. Render it as the honest answer, never as an
   * error or a spinner.
   */
  childCount: number;
  /**
   * Whether this account has joined the space or has only been invited to
   * it — see {@link Membership}. Never `left`, `knocked` or `banned`: the
   * core enumerates joined and invited rooms and nothing else.
   *
   * An invitation is a **rail** entry, not a roster row: a space is not a
   * conversation, so it does not belong in a list of them even for the
   * seconds before it is accepted (`core::rooms::roster_admits` hides every
   * space). It carries `childCount: 0` — the subtree of a space you have not
   * joined is not visible, so any number would be invented — and selecting
   * it is not a thing that can work; offer Accept / Decline instead.
   */
  membership: Membership;
}

/**
 * Mirrors `CoreError::kind()` from `src-tauri/src/core/error.rs`.
 *
 * `"roomChanged"` is distinct from `"protocol"`/`"notReady"` on purpose: it
 * means a room-scoped command ({@link sendMessage}, {@link sendReply},
 * {@link toggleReaction}, {@link timelinePaginateBack},
 * {@link attachmentStage}, {@link attachmentSend}) named a `roomId`
 * that wasn't the room actually focused when the core got around to running
 * it — the caller lost a race against a room switch — and **the command did
 * not act**. Every other kind here describes something that went wrong
 * while the core was doing what it was asked; this one describes the core
 * refusing to do something it was no longer being asked for. Callers should
 * surface it, not swallow it the way a generic failure might be: a send
 * that silently landed nowhere still needs the reader to know it needs
 * retrying, the same way a send that visibly failed does.
 *
 * The two attachment kinds are refusals in the same sense — nothing was
 * sent, nothing was read — and they must not be collapsed into each other,
 * because the reader's next move differs:
 *
 * - `"attachmentTooLarge"` comes from `core::attachments::check_upload_size`
 *   (attachments design §4), at staging time *and* again immediately before
 *   the bytes are read at send time. Its `message` already names both real
 *   sizes in binary units — *"that file is 200.0 MiB, but this homeserver
 *   accepts at most 50.0 MiB"* — because "upload failed" is not something a
 *   reader can act on. Show the core's own message; do not replace it with a
 *   generic one, and do not restate the numbers in decimal units (a
 *   homeserver limit is a power of two, so "52.4 MB" against a "50 MB"
 *   limit reads as a contradiction).
 * - `"unknownAttachment"` means the staging token names nothing: already
 *   sent (tokens are single use), discarded, swept by the core's staging
 *   timeout, or dropped by a room switch or a logout. The file is *gone* and
 *   the reader has to attach it again. This is the kind that is easiest to
 *   confuse with `"roomChanged"` and must not be: `"roomChanged"` means the
 *   file is **still staged, for a different room** and is recoverable by
 *   switching back, while this one means there is nothing left to recover.
 *   See `$lib/components/stagedAttachment.ts`'s `attachmentFailure`, which
 *   is the one place that mapping is written down.
 *
 * `"unknownSpace"` is a refusal in that same sense — nothing was filtered,
 * the roster is exactly as it was — and it is the one kind here with a
 * mandated *recovery*, not just a message: {@link spaceSelect} named a space
 * this account has not joined (it was left, or it never existed), so the
 * rail is highlighting an entry that is gone. The caller must re-fetch
 * {@link spacesList} and move its selection back to "All rooms". The core
 * deliberately refuses rather than quietly widening the roster, which would
 * show every room in the account underneath a highlight still claiming to
 * be scoped to one space. See `$lib/stores/spaces.svelte.ts`, which is the
 * one place that recovery is implemented.
 */
export type CoreErrorKind =
  | "auth"
  | "network"
  | "store"
  | "protocol"
  | "notReady"
  | "roomChanged"
  | "attachmentTooLarge"
  | "unknownAttachment"
  | "unknownSpace";

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

/**
 * One member currently typing in a room, mirroring `TypingUserDto` from
 * `src-tauri/src/core/dto.rs`. The current user is never present — the core
 * filters it out before this ever crosses IPC.
 */
export interface TypingUser {
  userId: string;
  /**
   * `null` when the room's local member store has nothing cached for this
   * id yet — fall back to `userId`, the same convention every other
   * sender-name field in this codebase uses. Server-controlled, arbitrary
   * text otherwise: cap its rendered length and guard it against overflow
   * like any other free-text field from a sender (see
   * `$lib/components/typingView.ts`).
   */
  displayName: string | null;
}

/**
 * Mirrors the payload emitted on {@link TYPING_EVENT}: the room this typing
 * state belongs to, plus who's typing there right now. Always a full
 * replace of "who's typing", never an incremental diff — see
 * `core::timeline::TYPING_EVENT`'s doc comment for why that's enough.
 */
export interface TypingPayload {
  roomId: string;
  users: TypingUser[];
}

/**
 * A file the core has staged for sending, mirroring `StagedAttachment` from
 * `src-tauri/src/core/attachments.rs`. Returned by {@link attachmentStage}
 * and carried, byte for byte the same shape, on {@link onStagedAttachment} —
 * so a picked file and a dropped file are the same thing to the composer.
 *
 * **There is no path here, and there is never going to be one** (attachments
 * design §3). The webview is told what it needs to render the review strip
 * and nothing that identifies a location on disk; {@link token} is what
 * comes back on send. A Rust-side test asserts this payload is exactly these
 * six fields, so a `path`/`dir`/`source` added to the struct later fails
 * there rather than quietly shipping a filesystem location into the webview.
 *
 * The token's rules, all enforced in the core and all worth knowing here
 * because each one is a way a send can legitimately fail:
 *
 * - **Single use.** Consumed by {@link attachmentSend} *before* a byte is
 *   read, so a replay cannot re-send the file — and so a failed send leaves
 *   the token spent. A second press of Send on the same strip gets
 *   `"unknownAttachment"`, which is why the composer drops the strip on a
 *   failure instead of offering a retry it cannot honour.
 * - **Bound to the room it was staged for.** Sending it into another room is
 *   refused with `"roomChanged"`, without sending and without consuming.
 * - **Bounded lifetime.** The core sweeps a staged file after ten minutes,
 *   on logout, and on a room switch. Nothing tells the webview when that
 *   happens; the token simply stops resolving.
 * - **One per room.** Staging a second file for the same room *replaces* the
 *   first and drops its token (see `StagedAttachments::insert_at`). The
 *   composer must overwrite what it is showing rather than accumulate a
 *   list — there is no multi-attachment state to hold, on either side.
 */
export interface StagedAttachment {
  /** Opaque, unguessable, single use, room-bound. Carries no path information. */
  token: string;
  /**
   * The file's own name on disk. Local rather than sender-controlled at this
   * point — but it is echoed back from the homeserver once sent, at which
   * point it is (spec §9), and a filename can legally contain newlines,
   * control characters and bidi overrides. Bound and neutralize it before
   * rendering (`$lib/components/stagedAttachment.ts`'s `sanitizeFilename`),
   * never `{@html}` it.
   */
  filename: string;
  /** The file's size in bytes, as `stat` reported it before anything was read. */
  sizeBytes: number;
  /**
   * Detected from the file's *content*, not from its extension (design §5:
   * "an extension is a claim, not a fact"), falling back to
   * `"application/octet-stream"` when nothing recognised it — which is the
   * honest encoding of "we could not tell" and maps to `m.file`.
   */
  mime: string;
  /**
   * Image pixel width, read from the file header. **The key is absent, not
   * `null`**, for anything that is not an image and for an image whose
   * header could not be parsed — hence `width?: number` rather than the
   * `number | null` every other optional field in this file uses. This is
   * the one place the attachment DTO departs from the house convention, and
   * it is deliberate on the Rust side
   * (`#[serde(skip_serializing_if = "Option::is_none")]`).
   */
  width?: number;
  /** Image pixel height. Absent, not `null`, exactly like {@link StagedAttachment.width}. */
  height?: number;
}

/**
 * One inline-level element of a message body, as `core::rich` parsed it.
 *
 * Mirrors `RichInline` tag-for-tag. The webview never parses markdown or HTML
 * itself: the rule that raw HTML is dropped rather than escaped is made once,
 * in Rust, so iOS and Android cannot disagree with this app about it.
 */
export type RichInline =
  | { inline: "text"; text: string }
  | { inline: "emphasis"; inlines: RichInline[] }
  | { inline: "strong"; inlines: RichInline[] }
  | { inline: "code"; text: string }
  | { inline: "link"; href: string; inlines: RichInline[] }
  | { inline: "break" };

/** One cell of a rendered table. */
export interface RichTableCell {
  inlines: RichInline[];
}

/** Mirrors `RichBlock` tag-for-tag. Nesting is capped at 16 by the core. */
export type RichBlock =
  | { block: "paragraph"; inlines: RichInline[] }
  | { block: "heading"; level: number; inlines: RichInline[] }
  | { block: "codeBlock"; language: string | null; text: string }
  | { block: "blockQuote"; blocks: RichBlock[] }
  | {
      block: "list";
      ordered: boolean;
      start: number;
      items: { blocks: RichBlock[] }[];
    }
  | { block: "thematicBreak" }
  | { block: "table"; header: RichTableCell[]; rows: { cells: RichTableCell[] }[] };

/** One labelled row on a custom-event card. Both halves are display text. */
export interface CustomEventField {
  label: string;
  value: string;
}

/**
 * One answer the reader can give. `id` is an identifier, never rendered — it
 * is sent verbatim, so it is deliberately not truncated by the core.
 */
export interface CustomEventDecisionOption {
  label: string;
  id: string;
}

/** A pending decision. The only thing in this app that may be amber. */
export interface CustomEventDecision {
  prompt: string;
  options: CustomEventDecisionOption[];
}

/**
 * The custom-event fallback chain's outcome, decided by
 * `core::custom_events::resolve_custom_event`. This app renders its three
 * states; it never makes the decision itself.
 */
export type CustomEventView =
  | {
      status: "rendered";
      fields: CustomEventField[];
      newerVersion: boolean;
      decision: CustomEventDecision | null;
    }
  | { status: "fallbackBody"; text: string }
  | { status: "placeholder"; text: string };

/** The quoted parent of a reply, resolved by the core. */
export type ReplyQuoteView =
  | { state: "unavailable" }
  | {
      state: "available";
      sender: string;
      excerpt: string | null;
      label: string | null;
    };

/**
 * The render decision for one item, made by `core::item_view::view_for`.
 *
 * `dateDivider` has no variant: it renders real content (a formatted date),
 * which this vocabulary does not cover, and the component handles it before
 * reading a view.
 */
export type ItemView =
  | { render: "bubble"; muted: boolean; blocks: RichBlock[] }
  | { render: "emote" }
  | { render: "system"; text: string }
  | { render: "unreadMarker" }
  | { render: "placeholder"; text: string }
  | { render: "image"; alt: string; width: number | null; height: number | null }
  | {
      render: "mediaFile";
      label: "File" | "Audio" | "Video";
      filename: string;
      size: number | null;
      mimetype: string | null;
    }
  | { render: "customEvent"; view: CustomEventView; eventType: string }
  | { render: "none" };

/**
 * A timeline item together with every decision the core made about it.
 *
 * The set of fields beyond `item` is not decoration. Each was a synchronous
 * helper this app called from inside its markup, and markup cannot `await` —
 * so anything needed *while drawing* has to arrive with the item rather than
 * be fetchable. That is why the timeline channel carries rows and not DTOs.
 */
export interface TimelineRow {
  item: TimelineItem;
  view: ItemView;
  /** Display name, then the raw sender id, then a placeholder. Never empty. */
  senderName: string;
  /**
   * The verb phrase for a membership change, `null` otherwise. Carried apart
   * from the rendered system sentence because a grouped run composes one
   * sentence from many names and a single verb.
   */
  membershipVerb: string | null;
  replyQuote: ReplyQuoteView | null;
  /** False while the item is still a local echo with no real event id. */
  canReplyOrReact: boolean;
  /**
   * A short preview of this item's body, for the composer's "Replying to …"
   * row when someone replies to it. `null` when there is nothing to show.
   */
  replyPreview: string | null;
}

const ROOMS_DIFF_EVENT = "sm://rooms/diff";
const TIMELINE_DIFF_EVENT = "sm://timeline/diff";
const CONNECTION_EVENT = "sm://connection";
const TYPING_EVENT = "sm://typing";
/**
 * A turn's text while it is still being written — see `core::live`. Carried on
 * to-device messages, so it is **not history**: nothing here has been stored in
 * a room, and the real message follows when the turn ends.
 */
const LIVE_EVENT = "sm://live";
const THOUGHT_EVENT = "sm://thought";
const TOOL_EVENT = "sm://tool";
/**
 * A file dropped on the window and staged by the **Rust** drag-drop handler
 * (`core::attachments::on_files_dropped`). See {@link onStagedAttachment} —
 * and read that function's comment before adding any other drop handling.
 */
const STAGED_ATTACHMENT_EVENT = "sm://attachment/staged";

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

/**
 * The account's joined spaces, sorted by name — see {@link SpaceSummary}.
 *
 * A one-shot fetch, not a third diff-streamed channel (spaces-rail design
 * §5): spaces change far less than the room list, so this is `room_info`'s
 * shape rather than `rooms_resync`'s. Re-invoke on session start, and after
 * an {@link spaceSelect} `"unknownSpace"` refusal.
 *
 * Rejects with a `"notReady"`-kind {@link CoreError} before login.
 */
export async function spacesList(): Promise<SpaceSummary[]> {
  return invoke<SpaceSummary[]>("spaces_list");
}

/**
 * Scopes the roster to `spaceId`'s flattened subtree; `null` restores every
 * room, which is the rail's "All rooms" entry.
 *
 * **Resolves as soon as the selection is queued, not once the roster has
 * changed** — and the re-filtered roster is not this function's return
 * value. It arrives afterwards on the ordinary {@link onRoomsDiff} channel
 * as a `Reset`-bearing envelope carrying **the next sequence number**, like
 * every other batch the core emits.
 *
 * So a caller must **not** call {@link roomsResync} and must **not** re-arm
 * the room-list `DiffTracker` in response to selecting a space. Both are the
 * corruption hazard `$lib/stores/rooms.svelte.ts`'s module doc comment
 * describes at length: the tracker only detects gaps *forward*, so telling
 * it to expect a fresh sequence for a stream that is not restarting makes
 * the very next envelope read as an already-applied duplicate, silently
 * dropped, with later ops folding onto stale items. The continuity is
 * structural core-side (`drive_room_list` has one counter and one emit
 * path), and this is the webview's half of keeping it.
 *
 * Never touches the focused room or its timeline (design §7): **a space
 * switch must not re-subscribe the timeline**, and if the open room is
 * filtered out of the roster the room pane keeps showing it.
 *
 * Rejects with a `"notReady"`-kind {@link CoreError} before login, and an
 * `"unknownSpace"`-kind one for a space this account has not joined —
 * without filtering anything. See {@link CoreErrorKind} for the recovery
 * that kind obliges.
 */
export async function spaceSelect(spaceId: string | null): Promise<void> {
  await invoke<void>("space_select", { spaceId });
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
export async function timelineResync(): Promise<[string, number, TimelineRow[]]> {
  return invoke<[string, number, TimelineRow[]]>("timeline_resync");
}

/**
 * Sends a message to `roomId`, with `mentions` carried as `m.mentions`.
 *
 * A mention is not decoration: `m.mentions` is what a client keys a highlight
 * off, and how an agent running its own Matrix client decides a message in a
 * room full of agents was addressed to it.
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
export async function sendMessage(
  roomId: string,
  body: string,
  mentions: string[] = [],
): Promise<void> {
  return invoke<void>("send_message", { roomId, body, mentions });
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
 * Sets (or clears) this device's typing notice in `roomId`.
 *
 * `roomId` must be the room the caller actually means, same as
 * {@link sendMessage}'s — the core verifies it against whichever room is
 * focused when the command runs and rejects with a `"roomChanged"`-kind
 * {@link CoreError} on a mismatch, without sending, rather than telling
 * whichever room ended up focused instead that the reader is typing there.
 *
 * Cheap to call often — `Room::typing_notice` already throttles the actual
 * network request (see `core::timeline::FocusedTimeline::set_typing`'s doc
 * comment) — but callers should still not invoke this on every keystroke;
 * see `$lib/components/typingTracker.ts`.
 */
export async function setTyping(roomId: string, typing: boolean): Promise<void> {
  await invoke<void>("set_typing", { roomId, typing });
}

/**
 * Marks `roomId` read by sending a public read receipt on the latest event
 * the focused timeline knows about. Resolves to whether a receipt was
 * actually sent (`false` when the room's read state already covered it).
 *
 * `roomId` is checked the same way, and for the same reason, as
 * {@link sendMessage}'s — see `core::timeline::FocusedTimeline::mark_read`'s
 * doc comment. **Does not decide whether the room is actually read**: the
 * caller (`Timeline.svelte`, via `$lib/components/readTracking.ts`'s
 * `shouldMarkRead`) must only call this once the reader is genuinely at the
 * live end of the timeline with the window focused.
 */
export async function markRoomRead(roomId: string): Promise<boolean> {
  return invoke<boolean>("mark_room_read", { roomId });
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
/**
 * Accepts an invitation to `roomId`.
 *
 * Nothing is returned and nothing needs to be: joining changes the room's
 * state, and the room-list stream emits the resulting diff like any other
 * change, so the roster and the room pane update themselves. A homeserver
 * refusal **rejects** rather than resolving quietly — the invitation must
 * stay on screen when the join did not happen.
 */
/**
 * Creates a room and resolves to its id.
 *
 * `isDirect` decides which half of a client's list it lands in: a room with
 * one other person is a DM, and filing thirty of those as group rooms buries
 * the few group rooms that matter. Invitees are sent at creation, because that
 * is the only place the DM flag can be set.
 */
export async function createRoom(
  name: string,
  invite: string[],
  isDirect: boolean,
): Promise<string> {
  return invoke<string>("create_room", { name, invite, isDirect });
}

/**
 * Joins a room by id or alias, resolving to its id.
 *
 * Distinct from {@link joinRoom}, which accepts an invitation to a room the
 * client already knows: this one reaches a room it has never seen.
 */
export async function joinRoomByAlias(aliasOrId: string): Promise<string> {
  return invoke<string>("join_room_by_alias", { aliasOrId });
}

/** Invites somebody to a room this account is in. */
export async function inviteUser(roomId: string, userId: string): Promise<void> {
  return invoke<void>("invite_user", { roomId, userId });
}

export async function joinRoom(roomId: string): Promise<void> {
  return invoke<void>("join_room", { roomId });
}

/**
 * Declines an invitation to `roomId`, or leaves a room already joined — one
 * call for both, because Matrix has one call for both (declining an
 * invitation *is* `POST /leave`). The wording is the webview's to choose.
 */
export async function leaveRoom(roomId: string): Promise<void> {
  return invoke<void>("leave_room", { roomId });
}

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
 * Saves an event's media **in full**, wherever the reader chooses. Resolves to
 * the path written, or `null` when they cancelled the dialog (or the event
 * carries no media).
 *
 * The counterpart to {@link mediaFetch}, which only ever returns a 640px
 * thumbnail: an agent sending a log, a diff or a full-size screenshot was
 * previously something you could see the existence of and nothing more.
 *
 * Takes an event id and nothing else. The save dialog is opened on the Rust
 * side, so no path the webview could name decides where bytes land.
 */
export async function mediaDownload(eventId: string): Promise<string | null> {
  return invoke<string | null>("media_download", { eventId });
}

/** One search hit, mirroring `SearchResultDto` in `src-tauri/src/core/search.rs`. */
export interface SearchResult {
  eventId: string;
  roomId: string;
  sender: string;
  /** The message text, never HTML — see the Rust struct's doc comment. */
  body: string;
  timestampMs: number | null;
}

/**
 * Searches every room this account can see, newest first.
 *
 * Server-side (`POST /_matrix/client/v3/search`), which rests on these rooms
 * being unencrypted — an encrypted room simply will not appear in results.
 * See `core::search`'s module doc for why that trade was taken.
 *
 * An empty term resolves to an empty list without asking the homeserver, since
 * it would otherwise ask for the whole of history.
 */
export async function searchMessages(term: string): Promise<SearchResult[]> {
  return invoke<SearchResult[]>("search_messages", { term });
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

/**
 * Opens the **native** file picker for `roomId` and stages whatever the
 * reader chooses, resolving to its metadata — or to `null` when they
 * cancelled.
 *
 * **`null` is not an error, and it is the common case.** Cancelling is the
 * most frequent outcome of opening a picker (design §7), so it comes back as
 * a normal empty result. Callers must branch on the value, never treat the
 * empty case as something to `catch` — a `try`/`catch` that reports "couldn't
 * attach that file" every time someone presses Escape in a file chooser will
 * also eventually swallow a real failure.
 *
 * The picker is opened from Rust, not from JavaScript, which is the whole
 * reason this command exists rather than the webview opening one itself:
 * `capabilities/default.json` grants no `dialog:*` and no `fs:*` permission,
 * so nothing running in this webview can learn — or read — a path. What
 * comes back is an opaque token (see {@link StagedAttachment}).
 *
 * Nothing is read here beyond a header: the file is `stat`ed and
 * size-checked against the homeserver's `m.upload.size` *before* any read
 * (design §4), and its first few KiB are probed for mime type and image
 * dimensions. The body is only read at {@link attachmentSend} time.
 *
 * `roomId` is verified against whichever room is actually focused, the same
 * way {@link sendMessage}'s is, before the dialog even opens. Rejects with a
 * `"roomChanged"`-kind {@link CoreError} on a mismatch, and with an
 * `"attachmentTooLarge"`-kind one — naming both sizes — for a file over the
 * limit. Staging a second file for the same room replaces the first; see
 * {@link StagedAttachment}.
 */
export async function attachmentStage(roomId: string): Promise<StagedAttachment | null> {
  return invoke<StagedAttachment | null>("attachment_stage", { roomId });
}

/**
 * Reads, uploads and sends the staged file `token` stands for, into
 * `roomId`. **Consumes the token**, whether or not the send then succeeds.
 *
 * Sends through the core's send queue, the same path {@link sendMessage}
 * uses, so the attachment gets a local echo immediately, retries across a
 * reconnect and orders against other sends. Like {@link sendMessage} it
 * appends nothing to `timelineStore.items` itself — the echo arrives through
 * the diff stream {@link onTimelineDiff} subscribes to, which is the rule
 * this codebase has broken once and paid for.
 *
 * The three refusals a caller has to tell apart (see {@link CoreErrorKind}):
 *
 * - `"roomChanged"` — either the room is no longer focused or the token was
 *   staged for a different one. Nothing was sent and **the token was not
 *   consumed**: the file is still staged for the room it was picked in.
 * - `"unknownAttachment"` — the token names nothing at all: spent, discarded,
 *   expired, or dropped by a room switch or logout. There is nothing to
 *   recover; the reader has to attach the file again.
 * - `"attachmentTooLarge"` — re-checked here, immediately before the read,
 *   because a file on disk can grow between staging and sending (a download
 *   completing, a log file, a video still rendering).
 */
export async function attachmentSend(roomId: string, token: string): Promise<void> {
  await invoke<void>("attachment_send", { roomId, token });
}

/**
 * Discards a staged file — what the composer's "remove" affordance calls,
 * and the way out the review step (design §2) requires.
 *
 * **Never rejects**, including for a token that is already gone: discarding
 * twice, discarding one the core's timeout already swept, or discarding one
 * a room switch already dropped is the outcome the caller wanted either way.
 * That is what makes it safe to call unconditionally on every path that
 * abandons an attachment (remove, room switch, a failed send, teardown)
 * without first working out whether the core still holds it.
 *
 * Takes no room id: a token identifies exactly one staged file, and the room
 * it belongs to is the core's business, not the caller's.
 */
export async function attachmentDiscard(token: string): Promise<void> {
  await invoke<void>("attachment_discard", { token });
}

/**
 * Subscribes to files dropped on the window and staged by the core, on
 * {@link STAGED_ATTACHMENT_EVENT}.
 *
 * **This is the only drop channel the webview may listen on, and the rule is
 * enforced by review rather than by the platform.** Tauri's own
 * `tauri://drag-drop` (and its `drag-enter`/`drag-over` siblings) still reach
 * this webview carrying the **raw filesystem paths** of the dropped files,
 * and cannot be suppressed while keeping the Rust handler that stages them:
 * `disable_drag_drop_handler()` turns off both at once. So the honest
 * statement of what the design's §3 buys is narrower than "the webview never
 * sees a path" — it is that *our* IPC surface never carries one, no command
 * we expose will read a path the webview supplies, and no filesystem
 * capability is granted, so knowing a path confers nothing.
 *
 * The part that only discipline can hold is this one: nothing in this
 * codebase listens for `tauri://drag-drop`. Adding a second drop handler
 * "just to show a filename sooner" would put attacker-reachable paths into
 * webview memory for no capability we do not already have through this
 * event, and would be a review defect rather than a bug the type system can
 * catch. See `core::attachments::on_files_dropped`.
 *
 * The payload is the same {@link StagedAttachment} {@link attachmentStage}
 * returns, and it names **no room** — a drop lands on whatever room is
 * focused, so the core resolves that itself. The handler must therefore
 * attribute the file to the room it believes is focused *at the moment the
 * event arrives*, and re-check it before sending.
 */
export function onStagedAttachment(handler: (staged: StagedAttachment) => void): Promise<UnlistenFn> {
  return listen<StagedAttachment>(STAGED_ATTACHMENT_EVENT, (event) => handler(event.payload));
}

/** Subscribes to room-list diff envelopes on {@link ROOMS_DIFF_EVENT}. */
export function onRoomsDiff(handler: (env: DiffEnvelope<RoomSummary>) => void): Promise<UnlistenFn> {
  return listen<DiffEnvelope<RoomSummary>>(ROOMS_DIFF_EVENT, (event) => handler(event.payload));
}

/** Subscribes to focused-timeline diff envelopes on {@link TIMELINE_DIFF_EVENT}. */
export function onTimelineDiff(handler: (env: DiffEnvelope<TimelineRow>) => void): Promise<UnlistenFn> {
  return listen<DiffEnvelope<TimelineRow>>(TIMELINE_DIFF_EVENT, (event) => handler(event.payload));
}

/**
 * Parses a live turn's partial markdown into blocks.
 *
 * A landed message arrives with its blocks already on its {@link TimelineRow};
 * this is for a turn still being written on `sm://live`, which has no timeline
 * item yet. Same parser on the Rust side, so a turn does not change appearance
 * the instant it lands.
 */
export async function richBlocksFromMarkdown(source: string): Promise<RichBlock[]> {
  return invoke<RichBlock[]>("rich_blocks_from_markdown", { source });
}

/**
 * The core's connection health *right now*, rather than at the next
 * transition.
 *
 * {@link onConnection} only fires on change, so a webview that starts up
 * mid-session — a reload, or an HMR module swap — has no way to learn a
 * state it was not listening for when it happened. This is that way.
 * Reports `offline` when there is no session at all.
 */
export async function connectionState(): Promise<ConnectionPayload> {
  return invoke<ConnectionPayload>("connection_state");
}

/** Subscribes to connection-health updates on {@link CONNECTION_EVENT}. */
export function onConnection(handler: (payload: ConnectionPayload) => void): Promise<UnlistenFn> {
  return listen<ConnectionPayload>(CONNECTION_EVENT, (event) => handler(event.payload));
}

/**
 * One update to a turn in progress, mirroring `core::live::LivePayload`.
 *
 * `text` is **everything the agent has said this turn**, not the increment —
 * to-device delivery is at-least-once and unordered, so the core hands over
 * whole text and drops anything stale before it reaches here.
 */
export interface LivePayload {
  roomId: string;
  seq: number;
  text: string;
  /** The turn is over; the room now holds the real message. */
  done: boolean;
}

/** Subscribes to live turn text on {@link LIVE_EVENT}. */
export function onLive(handler: (payload: LivePayload) => void): Promise<UnlistenFn> {
  return listen<LivePayload>(LIVE_EVENT, (event) => handler(event.payload));
}

/**
 * Subscribes to an agent's reasoning.
 *
 * The same payload as {@link onLive}, on its own channel, because the shape is
 * reused and the meaning is not: this is what the agent is thinking, and it
 * never reaches a room on either side of the bridge. Watchable while it
 * happens, then gone.
 */
export function onThought(handler: (payload: LivePayload) => void): Promise<UnlistenFn> {
  return listen<LivePayload>(THOUGHT_EVENT, (event) => handler(event.payload));
}

/**
 * One tool call's state, mirroring `core::live::ToolPayload`.
 *
 * Unlike the two text channels this one is **not** de-duplicated in the core —
 * see `live.rs`'s handler for why — so `seq` is here to be compared, per
 * `toolCallId`, by whatever consumes it.
 */
export interface ToolPayload {
  roomId: string;
  seq: number;
  toolCallId: string;
  title: string;
  /** ACP's tool kind, or null. Opaque display text; never switch on it. */
  kind: string | null;
  /** `pending` | `in_progress` | `completed` | `failed`, or something newer. */
  status: string;
  locations: string[];
}

/** Subscribes to tool-call state on {@link TOOL_EVENT}. */
export function onTool(handler: (payload: ToolPayload) => void): Promise<UnlistenFn> {
  return listen<ToolPayload>(TOOL_EVENT, (event) => handler(event.payload));
}

/** Subscribes to typing-state updates on {@link TYPING_EVENT}. */
export function onTyping(handler: (payload: TypingPayload) => void): Promise<UnlistenFn> {
  return listen<TypingPayload>(TYPING_EVENT, (event) => handler(event.payload));
}
