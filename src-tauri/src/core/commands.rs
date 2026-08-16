//! The Tauri command surface: the only way the webview reaches the core.
//!
//! Every command here is a thin wrapper — resolve managed state, call a core
//! function, map the result. No logic lives here; the actual behavior is in
//! `core::session`, `core::rooms`, and `core::timeline`.

use std::sync::Arc;

use tauri::{AppHandle, State};

use super::attachments::{self, StagedAttachment, StagedAttachments};
use super::dto::RoomSummary;
use super::error::CoreError;
use super::room_info::RoomInfoDto;
use super::search::SearchResultDto;
use super::session::Session;
use super::spaces::SpaceSummary;
use super::timeline::{FocusedTimeline, TimelineSnapshot};

/// Logs in with a username and password, then starts sync and room-list
/// streaming so the webview has state to render as soon as login succeeds.
#[tauri::command]
pub async fn login(
    homeserver: String,
    username: String,
    password: String,
    app: AppHandle,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session
        .login_and_start(&homeserver, &username, &password, app)
        .await
}

/// Attempts to restore a previously persisted session. Returns `false` when
/// there is nothing to restore — the normal first-run path, not an error.
///
/// Starts sync and room-list streaming on success, same as [`login`], and is
/// a no-op returning `true` when a session is already active — see
/// `Session::restore_and_start` for why that guard lives in the core rather
/// than being left to the webview to remember.
#[tauri::command]
pub async fn restore_session(
    app: AppHandle,
    session: State<'_, Session>,
) -> Result<bool, CoreError> {
    session.restore_and_start(app).await
}

/// Logs out, clearing the session, secrets and local stores. `Session::logout`
/// stops the focused timeline, sync and room-list streaming itself before it
/// clears anything else.
#[tauri::command]
pub async fn logout(session: State<'_, Session>) -> Result<(), CoreError> {
    session.logout().await
}

/// A full snapshot of the room list — the sequence number of the last diff
/// folded in, and the resulting list — for the webview to reset its store
/// against after it detects a gap.
#[tauri::command]
pub async fn rooms_resync(
    session: State<'_, Session>,
) -> Result<(u64, Vec<RoomSummary>), CoreError> {
    session.rooms_snapshot().await
}

/// The account's joined spaces for the spaces rail: `{ id, name, avatarUrl,
/// childCount }` each, sorted by name.
///
/// `childCount` is the number of **joined** rooms in the space's flattened
/// subtree — precisely the rooms [`space_select`] will leave in the roster,
/// since both come from the same walk. A space advertising twelve and then
/// revealing four is worse than showing no number at all.
///
/// A one-shot fetch, not a stream (spaces-rail design §5): re-invoke on
/// session start and after a resync. Fails with a `notReady`-kind
/// [`CoreError`] before login.
#[tauri::command]
pub async fn spaces_list(session: State<'_, Session>) -> Result<Vec<SpaceSummary>, CoreError> {
    session.spaces_list().await
}

/// Scopes the room list to `space_id`'s flattened subtree. `null` (or an
/// omitted argument) restores every room — the rail's "All rooms" entry.
///
/// Returns as soon as the selection is queued into the room-list stream task.
/// The re-filtered roster arrives afterwards on the ordinary `sm://rooms/diff`
/// channel as a `Reset`-bearing envelope carrying **the next sequence
/// number**, like any other batch — callers must not resync against it or
/// re-arm their `DiffTracker` for it. See `core::rooms::drive_room_list` for
/// why that continuity is the load-bearing part.
///
/// **Never touches the focused room or its timeline** (design §7): the
/// roster is a navigation surface, and filtering it must not close what the
/// reader is reading.
///
/// Fails with a `notReady`-kind [`CoreError`] before login (a selection with
/// no roster to scope is a caller bug, not something to swallow), and an
/// `unknownSpace`-kind one when `space_id` is not a space this account has
/// joined — left since the rail was last fetched, say. The right response to
/// that is to re-invoke [`spaces_list`] and move the rail's own selection
/// back to "All rooms"; the core deliberately does not do it silently, which
/// would leave the rail highlighting a space that no longer exists while
/// showing every room in the account underneath it.
#[tauri::command]
pub async fn space_select(
    space_id: Option<String>,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session.select_space(space_id.as_deref()).await
}

/// Subscribes to `room_id`'s timeline, replacing any previously focused room.
#[tauri::command]
pub async fn timeline_subscribe(
    room_id: String,
    app: AppHandle,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session.subscribe_timeline(&room_id, app).await
}

/// Paginates `room_id`'s timeline backwards by up to `count` events. Returns
/// `true` when the start of the timeline was reached.
///
/// `room_id` is checked against whichever room is actually focused
/// (`FocusedTimeline::active_timeline_for`) before pagination runs, and this
/// fails with a `roomChanged`-kind [`CoreError`] rather than silently
/// paginating whatever room a since-resolved room switch left focused — see
/// `FocusedTimeline::paginate_back`'s doc comment for why that guard is
/// worth paying here too, not just on the three commands where a mismatch
/// would be a real wrong-recipient hazard.
#[tauri::command]
pub async fn timeline_paginate_back(
    room_id: String,
    count: u16,
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<bool, CoreError> {
    timeline.paginate_back(&room_id, count).await
}

/// A full snapshot of the focused timeline — the room it belongs to, the
/// sequence number of the last diff folded in, and the resulting items — for
/// the webview to reset its store against after it detects a gap.
///
/// The room id leads so the webview can discard a snapshot for a room it is
/// no longer showing; see `core::timeline::TimelineSnapshot`.
#[tauri::command]
pub async fn timeline_resync(
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<TimelineSnapshot, CoreError> {
    timeline.snapshot().await
}

/// Sends a plain-text message to `room_id`.
///
/// `room_id` is verified against whichever room is actually focused
/// (`FocusedTimeline::active_timeline_for`) before anything is sent. This is
/// the fix for the wrong-recipient race this command used to be exposed to:
/// a room switch resolving on the Rust side while a send was in flight used
/// to send into whatever room ended up focused, not the room the caller
/// named — see `FocusedTimeline::send_text`'s doc comment. A mismatch fails
/// with a `roomChanged`-kind [`CoreError`] instead, and nothing is sent.
#[tauri::command]
pub async fn send_message(
    room_id: String,
    body: String,
    // The members this message addresses, as user ids. Optional so an older
    // caller (and every test that predates mentions) still compiles.
    mentions: Option<Vec<String>>,
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<(), CoreError> {
    timeline
        .send_text(&room_id, &body, &mentions.unwrap_or_default())
        .await
}

/// Sends a plain-text reply to `in_reply_to` (a parent event id) in
/// `room_id`.
///
/// `room_id` is checked the same way, and for the same race, as
/// [`send_message`] — see `FocusedTimeline::send_reply`'s doc comment for
/// why this command already failed (safely, by accident) on a mismatch
/// before this check existed, and what the check changes about that.
#[tauri::command]
pub async fn send_reply(
    room_id: String,
    body: String,
    in_reply_to: String,
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<(), CoreError> {
    timeline.send_reply(&room_id, &body, &in_reply_to).await
}

/// Toggles `key` as a reaction on `event_id` in `room_id`. Returns whether
/// the reaction was added (`true`) or removed (`false`).
///
/// `room_id` is checked the same way, and for the same race, as
/// [`send_message`] — see `FocusedTimeline::toggle_reaction`'s doc comment.
#[tauri::command]
pub async fn toggle_reaction(
    room_id: String,
    event_id: String,
    key: String,
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<bool, CoreError> {
    timeline.toggle_reaction(&room_id, &event_id, &key).await
}

/// Sets (or clears) this device's typing notice in `room_id`.
///
/// `room_id` is checked the same way, and for the same race, as
/// [`send_message`] — see `FocusedTimeline::set_typing`'s doc comment. A
/// notice sent into the wrong room after a room switch tells everyone
/// *there* the reader is typing, which they are not.
#[tauri::command]
pub async fn set_typing(
    room_id: String,
    typing: bool,
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<(), CoreError> {
    timeline.set_typing(&room_id, typing).await
}

/// Marks `room_id` read by sending a public read receipt on the latest event
/// the focused timeline knows about. Returns whether a receipt was actually
/// sent (`false` when the room's read state already covered it).
///
/// `room_id` is checked the same way, and for the same race, as
/// [`send_message`] — see `FocusedTimeline::mark_read`'s doc comment for why
/// that matters even more here than for an ordinary send: a stale call
/// landing after a room switch would otherwise mark whatever room is *now*
/// focused read for a message the reader never saw.
///
/// **Does not decide whether the room is actually read** — the caller (`Timeline.svelte`,
/// via `$lib/components/readTracking.ts`'s `shouldMarkRead`) is responsible
/// for only calling this once the reader is genuinely at the live end of the
/// timeline with the window focused; this command just performs the send.
#[tauri::command]
pub async fn mark_room_read(
    room_id: String,
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<bool, CoreError> {
    timeline.mark_read(&room_id).await
}

/// Resolves and fetches `room_id`'s avatar as a `data:` URI, or `None` when
/// the room has no avatar to show (by any of `core::rooms::resolve_room_avatar_mxc`'s
/// rules) or its bytes don't sniff to a renderable image format (see
/// `core::media::sniff_mime`). Takes a room id, not an mxc URI — resolution
/// needs the room's member list for the two-person fallback, which
/// `RoomSummary.avatarUrl` alone can't express (see `Session::room_avatar`'s
/// doc comment). Callers must invoke this for every room, not only those
/// with a non-null `avatarUrl` — most rooms in practice, since the fallback
/// is exactly what covers up a null `avatarUrl` — fetched lazily and cached
/// by the caller, keyed on `room_id` this time rather than the mxc URI (see
/// `avatarCache.svelte.ts` for the trade-off that implies).
#[tauri::command]
pub async fn room_avatar(
    room_id: String,
    session: State<'_, Session>,
) -> Result<Option<String>, CoreError> {
    session.room_avatar(&room_id).await
}

/// Fetches `event_id`'s media (an `m.image`/`m.file`/`m.audio`/`m.video`
/// message's content) as a thumbnail `data:` URI, or `None` when the event
/// isn't in the focused timeline, isn't a media message, or its bytes don't
/// sniff to a renderable image format (see `core::media::sniff_mime`).
///
/// Keyed by event id, not an mxc URI: `TimelineItemDto` never carries one
/// (see `MediaMetaDto`'s doc comment for why bytes/sources stay off that
/// struct entirely) and, separately, an mxc string alone couldn't address
/// encrypted media anyway — see `Session::media_fetch` and
/// `core::timeline::FocusedTimeline::media_source` for the full reasoning.
/// Callers fetch this lazily per item and cache the result themselves, keyed
/// on the event id (see `$lib/stores/mediaCache.svelte.ts`).
/// Accepts an invitation to `room_id` (issue #1). Idempotent against an
/// already-joined room; a homeserver refusal is returned rather than
/// swallowed, so the invitation stays on screen when the join did not
/// happen.
///
/// The roster updates itself: joining changes the room's state, and the
/// room-list stream emits the resulting diff like any other change. Nothing
/// here re-reads the list.
#[tauri::command]
pub async fn join_room(room_id: String, session: State<'_, Session>) -> Result<(), CoreError> {
    session.join_room(&room_id).await
}

/// Declines an invitation to `room_id`, or leaves a room already joined —
/// one command for both, because Matrix has one call for both (see
/// `Session::leave_room`).
#[tauri::command]
pub async fn leave_room(room_id: String, session: State<'_, Session>) -> Result<(), CoreError> {
    session.leave_room(&room_id).await
}

/// Saves an event's media in full, wherever the reader chooses. Returns the
/// path written, or `null` when they cancelled or the event carries no media.
///
/// Takes an event id, never a path or a URL: the save dialog is opened on the
/// Rust side (see `Session::media_download`), so nothing the webview says can
/// decide where bytes land.
/// Searches every room this account can see for `term`, newest first.
///
/// Server-side search: see `core::search`'s module doc for why, and for the
/// condition it rests on (these rooms are unencrypted, so the homeserver can
/// index them — an encrypted room simply will not appear in results).
#[tauri::command]
pub async fn search_messages(
    term: String,
    session: State<'_, Session>,
) -> Result<Vec<SearchResultDto>, CoreError> {
    session.search_messages(&term).await
}

#[tauri::command]
pub async fn media_download(
    event_id: String,
    app: tauri::AppHandle,
    session: State<'_, Session>,
) -> Result<Option<String>, CoreError> {
    session.media_download(&app, &event_id).await
}

#[tauri::command]
pub async fn media_fetch(
    event_id: String,
    session: State<'_, Session>,
) -> Result<Option<String>, CoreError> {
    session.media_fetch(&event_id).await
}

/// Builds `room_id`'s room-info panel data: name, topic, canonical alias,
/// alt aliases, room id and joined member list.
///
/// `room_id` is verified against whichever room is actually focused
/// (`Session::room_info`) before anything is resolved — the same
/// room-scoped guard `timeline_paginate_back`/`send_message`/`send_reply`/
/// `toggle_reaction` already take, for the same reason: a room switch
/// resolving mid-call must not silently show one room's identity under
/// another room's header. A mismatch fails with a `roomChanged`-kind
/// [`CoreError`] instead.
#[tauri::command]
pub async fn room_info(
    room_id: String,
    session: State<'_, Session>,
) -> Result<RoomInfoDto, CoreError> {
    session.room_info(&room_id).await
}

/// Fetches a room member's avatar as a `data:` URI, given the raw
/// `mxc://` URI already carried on their entry in [`room_info`]'s
/// `RoomInfoDto::members`. `None` for the same reasons [`room_avatar`]'s
/// are: nothing to show, or the fetched bytes don't sniff to a renderable
/// image format.
///
/// Reuses `core::media::avatar_thumbnail` — the exact authenticated-media
/// fetch [`room_avatar`] already uses for a room's own avatar — rather than
/// a second fetch path; see `Session::member_avatar`'s doc comment for why
/// no room/hero resolution step is needed first the way [`room_avatar`]
/// needs one.
#[tauri::command]
pub async fn member_avatar(
    mxc_uri: String,
    session: State<'_, Session>,
) -> Result<Option<String>, CoreError> {
    session.member_avatar(&mxc_uri).await
}

/// Opens the native file picker for `room_id` and stages whatever the reader
/// chooses, returning `{ token, filename, sizeBytes, mime, width?, height? }`
/// — or `null` when they cancelled.
///
/// **Cancelling is not an error** (attachments design §7). It is the most
/// common outcome of opening a picker, so it comes back as a normal empty
/// result rather than something the frontend has to catch.
///
/// The picker is opened **from Rust**, through `tauri-plugin-dialog`'s Rust
/// API. That is the whole reason this command exists rather than the webview
/// opening a picker itself and passing a path down: `capabilities/default.json`
/// grants no `dialog:*` (and no `fs:*`) permission, so there is no way for
/// anything running in the webview to learn where a file lives. What comes
/// back is an opaque token; the path stays in `core::attachments`.
///
/// `room_id` is verified against whichever room is actually focused before
/// the dialog opens, the same guard [`send_message`] takes — see
/// `core::attachments::stage_from_picker`. Nothing is read here: the file is
/// `stat`ed and size-checked against the homeserver's `m.upload.size`, and
/// its first few KiB are probed for mime type and image dimensions, but the
/// body is only read at [`attachment_send`] time (§4).
#[tauri::command]
pub async fn attachment_stage(
    room_id: String,
    app: AppHandle,
    session: State<'_, Session>,
    timeline: State<'_, Arc<FocusedTimeline>>,
    staged: State<'_, Arc<StagedAttachments>>,
) -> Result<Option<StagedAttachment>, CoreError> {
    attachments::stage_from_picker(&app, &session, &timeline, &staged, &room_id).await
}

/// Reads, uploads and sends the file `token` stands for, into `room_id`.
/// **Consumes the token**, so a replay cannot re-send the file.
///
/// `room_id` is verified twice over, and the two checks are not redundant:
/// against whichever room is actually focused (the same guard
/// [`send_message`] takes, failing with a `roomChanged`-kind [`CoreError`])
/// *and* against the room the token was staged for (failing the same way).
/// The first catches a stale send; the second catches a stale token — a
/// webview that kept a token across a room switch. Neither refusal sends
/// anything, and a room mismatch leaves the file staged so the reader can
/// switch back.
///
/// Fails with an `unknownAttachment`-kind [`CoreError`] when the token names
/// nothing: already sent, discarded, swept by the staging timeout, or
/// dropped by a room switch or a logout. Fails with an
/// `attachmentTooLarge`-kind one, naming both sizes, when the file exceeds
/// the homeserver's limit — re-checked here, immediately before the read,
/// because the file on disk can grow between staging and sending.
///
/// Sends through the **send queue** (§6), the same path [`send_message`]
/// uses, so the attachment gets a local echo, retries across a reconnect,
/// and orders against other sends. Emits nothing itself: the echo arrives
/// through the timeline diff stream like every other event.
#[tauri::command]
pub async fn attachment_send(
    room_id: String,
    token: String,
    session: State<'_, Session>,
    timeline: State<'_, Arc<FocusedTimeline>>,
    staged: State<'_, Arc<StagedAttachments>>,
) -> Result<(), CoreError> {
    attachments::send_staged(&session, &timeline, &staged, &room_id, &token).await
}

/// Discards a staged file — the "remove" affordance on the composer's staged
/// strip, which is the way out the review step (§2) requires.
///
/// Takes no room id and never fails, including for a token that is already
/// gone. Discarding twice, or discarding one the staging timeout already
/// swept, is the outcome the caller wanted either way, and an error there
/// would only give the frontend a failure to handle on the path whose whole
/// purpose is cancelling.
#[tauri::command]
pub async fn attachment_discard(
    token: String,
    staged: State<'_, Arc<StagedAttachments>>,
) -> Result<(), CoreError> {
    staged.discard(&token);
    Ok(())
}
