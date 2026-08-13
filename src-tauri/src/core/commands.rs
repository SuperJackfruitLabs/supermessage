//! The Tauri command surface: the only way the webview reaches the core.
//!
//! Every command here is a thin wrapper — resolve managed state, call a core
//! function, map the result. No logic lives here; the actual behavior is in
//! `core::session`, `core::rooms`, and `core::timeline`.

use std::sync::Arc;

use tauri::{AppHandle, State};

use super::dto::RoomSummary;
use super::error::CoreError;
use super::session::Session;
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

/// Subscribes to `room_id`'s timeline, replacing any previously focused room.
#[tauri::command]
pub async fn timeline_subscribe(
    room_id: String,
    app: AppHandle,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session.subscribe_timeline(&room_id, app).await
}

/// Paginates the focused timeline backwards by up to `count` events. Returns
/// `true` when the start of the timeline was reached.
#[tauri::command]
pub async fn timeline_paginate_back(
    count: u16,
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<bool, CoreError> {
    timeline.paginate_back(count).await
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

/// Sends a plain-text message to the focused room.
#[tauri::command]
pub async fn send_message(
    body: String,
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<(), CoreError> {
    timeline.send_text(&body).await
}

/// Fetches `mxc_uri` as a `data:` URI, or `None` when its bytes don't sniff
/// to a renderable image format (see `core::media::sniff_mime`). Takes the
/// mxc URI directly rather than a room id — see `core::media`'s doc comment
/// for why — which the caller (`avatarCache.svelte.ts`) already holds as a
/// room's `avatarUrl` (`RoomSummary`'s `avatarUrl`, projected by
/// `core::rooms::project_room` with a hero fallback — see
/// `core::rooms::resolve_avatar_url`) or a user's own avatar mxc URI. Fetched
/// lazily and cached by the caller, keyed on that same mxc URI — never
/// carried in the streamed `RoomSummary` itself, per `core::dto`'s doc
/// comment on IPC cost.
#[tauri::command]
pub async fn avatar_thumbnail(
    mxc_uri: String,
    session: State<'_, Session>,
) -> Result<Option<String>, CoreError> {
    session.avatar_thumbnail(&mxc_uri).await
}
