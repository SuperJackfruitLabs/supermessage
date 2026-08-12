//! The Tauri command surface: the only way the webview reaches the core.
//!
//! Every command here is a thin wrapper — resolve managed state, call a core
//! function, map the result. No logic lives here; the actual behavior is in
//! `core::session`, `core::rooms`, and `core::timeline`.

use tauri::{AppHandle, State};

use super::dto::{RoomSummary, TimelineItemDto};
use super::error::CoreError;
use super::session::Session;
use super::timeline::FocusedTimeline;

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
    session.login(&homeserver, &username, &password).await?;
    session.start_streams(app).await?;
    Ok(())
}

/// Attempts to restore a previously persisted session. Returns `false` when
/// there is nothing to restore — the normal first-run path, not an error.
///
/// Starts sync and room-list streaming on success, same as [`login`].
#[tauri::command]
pub async fn restore_session(
    app: AppHandle,
    session: State<'_, Session>,
) -> Result<bool, CoreError> {
    let restored = session.restore().await?;
    if restored {
        session.start_streams(app).await?;
    }
    Ok(restored)
}

/// Logs out, clearing the session, secrets and local stores. `Session::logout`
/// stops sync and room-list streaming itself before it clears anything else.
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
    timeline: State<'_, FocusedTimeline>,
) -> Result<(), CoreError> {
    let client = session.require_client().await?;
    timeline.subscribe(&client, &room_id, app).await
}

/// Paginates the focused timeline backwards by up to `count` events. Returns
/// `true` when the start of the timeline was reached.
#[tauri::command]
pub async fn timeline_paginate_back(
    count: u16,
    timeline: State<'_, FocusedTimeline>,
) -> Result<bool, CoreError> {
    timeline.paginate_back(count).await
}

/// A full snapshot of the focused timeline — the sequence number of the last
/// diff folded in, and the resulting items — for the webview to reset its
/// store against after it detects a gap.
#[tauri::command]
pub async fn timeline_resync(
    timeline: State<'_, FocusedTimeline>,
) -> Result<(u64, Vec<TimelineItemDto>), CoreError> {
    timeline.snapshot().await
}

/// Sends a plain-text message to the focused room.
#[tauri::command]
pub async fn send_message(
    body: String,
    timeline: State<'_, FocusedTimeline>,
) -> Result<(), CoreError> {
    timeline.send_text(&body).await
}
