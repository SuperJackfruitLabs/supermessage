//! Tauri's half of the bargain the core no longer knows about.
//!
//! `supermessage-core` produces [`CoreEvent`]s and asks for file paths; it has
//! no opinion about webviews, dialogs or app handles. This module is where
//! those become Tauri again — and it is deliberately the only place in the app
//! that knows the channel names.
//!
//! **The channel names here are a wire format, not a detail.** The webview
//! listens on these exact strings and parses exactly these payloads; the
//! golden tests in `supermessage_core::dto` freeze the shapes, and this file
//! freezes the names they arrive under. Changing either changes what a
//! shipped frontend receives.

use std::path::PathBuf;
use std::sync::Arc;

use supermessage_core::attachments::{stage_path, StagedAttachments, STAGED_ATTACHMENT_EVENT};
use supermessage_core::event::{CoreEvent, EventSink, FilePicker};
use supermessage_core::live::{LIVE_EVENT, THOUGHT_EVENT, TOOL_EVENT};
use supermessage_core::rooms::ROOMS_DIFF_EVENT;
use supermessage_core::session::Session;
use supermessage_core::sync::CONNECTION_EVENT;
use supermessage_core::timeline::{FocusedTimeline, TIMELINE_DIFF_EVENT, TYPING_EVENT};
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_dialog::DialogExt as _;

/// Carries the core's events to the webview, unchanged.
///
/// Every arm restates the pairing the core used to make inline: this variant
/// goes to that channel, carrying that payload. Written once, in one place,
/// where a reviewer can check it against the frontend's listeners.
pub struct TauriSink(pub AppHandle);

impl EventSink for TauriSink {
    fn emit(&self, event: CoreEvent) {
        // A failed emit means the webview is gone — during shutdown, or a
        // reload in flight. There is nothing to do about it and nobody left to
        // tell, so it is logged at the level the core used to log it and
        // dropped. It must never propagate: this is called from inside sync
        // and timeline processing, where an error would take the client down.
        let sent = match event {
            CoreEvent::Connection(p) => self.0.emit(CONNECTION_EVENT, &p),
            CoreEvent::RoomsDiff(e) => self.0.emit(ROOMS_DIFF_EVENT, &e),
            CoreEvent::TimelineDiff(e) => self.0.emit(TIMELINE_DIFF_EVENT, &e),
            CoreEvent::Typing(p) => self.0.emit(TYPING_EVENT, &p),
            CoreEvent::Live(p) => self.0.emit(LIVE_EVENT, &p),
            CoreEvent::Thought(p) => self.0.emit(THOUGHT_EVENT, &p),
            CoreEvent::Tool(p) => self.0.emit(TOOL_EVENT, &p),
            CoreEvent::AttachmentStaged(m) => self.0.emit(STAGED_ATTACHMENT_EVENT, &m),
        };
        if let Err(err) = sent {
            tracing::warn!(error = %err, "failed to emit a core event to the webview");
        }
    }
}

/// Opens the platform's own file dialogs on the core's behalf.
///
/// Both methods go through a oneshot rather than the `blocking_*` variants,
/// and that is load-bearing: the blocking ones park the calling thread until
/// the person chooses, which is a tokio worker held for however long someone
/// spends browsing — and on the main thread it deadlocks the event loop
/// outright. Awaiting a oneshot costs nothing while the dialog is up.
pub struct TauriFilePicker(pub AppHandle);

#[async_trait::async_trait]
impl FilePicker for TauriFilePicker {
    async fn pick_file(&self) -> Option<PathBuf> {
        let (tx, rx) = tokio::sync::oneshot::channel();
        self.0.dialog().file().pick_file(move |picked| {
            // The receiver is gone only if the command was cancelled, in which
            // case there is nobody left to tell.
            let _ = tx.send(picked);
        });
        rx.await.ok().flatten().and_then(|p| p.into_path().ok())
    }

    async fn save_file(&self, suggested_name: &str) -> Option<PathBuf> {
        let (tx, rx) = tokio::sync::oneshot::channel();
        self.0
            .dialog()
            .file()
            .set_file_name(suggested_name)
            .save_file(move |picked| {
                let _ = tx.send(picked);
            });
        rx.await.ok().flatten().and_then(|p| p.into_path().ok())
    }
}

/// Stages the first of a set of dropped files.
///
/// This lives here rather than in the core because every line of it is Tauri:
/// the paths arrive on Tauri's `tauri://drag-drop`, the session and timeline
/// come out of Tauri's managed state, and the work runs on Tauri's runtime.
/// The core's contribution is `stage_path`, which knows nothing about any of
/// that.
///
/// **A caveat worth stating rather than hiding.** Tauri's own drag-drop
/// handling cannot be split: `disable_drag_drop_handler()` turns the OS
/// handler off entirely, so Rust would stop seeing drops too. With it on,
/// Tauri also emits its built-in `tauri://drag-drop` — carrying the raw
/// paths — to the webview, and there is no hook to suppress just that. What
/// the core guarantees is that *its own IPC surface* never carries a path
/// (`attachments`, §3): no command returns one, no `sm://` event contains
/// one, and nothing the webview can invoke will read a path it supplies. The
/// frontend must not listen for `tauri://drag-drop`; it listens for
/// `STAGED_ATTACHMENT_EVENT`, which is the whole reason this handler exists.
///
/// Only the first file is staged. Multiple files in one send are explicitly
/// out of scope, and the composer shows a single strip, so a three-file drop
/// stages one file and logs the rest — visible in review, rather than three
/// unrecallable sends.
///
/// Failures are logged, not surfaced: there is no invocation to fail, and a
/// dropped directory or an oversized file should not become a dialog the
/// reader did not ask for. The absence of a staged strip is the feedback.
pub fn on_files_dropped(app: &AppHandle, paths: Vec<PathBuf>) {
    let Some(path) = paths.first().cloned() else {
        return;
    };
    if paths.len() > 1 {
        tracing::info!(
            dropped = paths.len(),
            "only the first dropped file is staged; multiple attachments are out of scope"
        );
    }

    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let session = app.state::<Session>();
        let focused = app.state::<Arc<FocusedTimeline>>();
        let staged = app.state::<Arc<StagedAttachments>>();

        let Some(room_id) = focused.focused_room_id() else {
            tracing::info!("ignoring a dropped file: no room is focused");
            return;
        };

        let client = match session.require_client().await {
            Ok(client) => client,
            Err(err) => {
                tracing::warn!(error = %err, "ignoring a dropped file: no active session");
                return;
            }
        };

        match stage_path(&client, &staged, &room_id, path).await {
            Ok(meta) => TauriSink(app.clone()).emit(CoreEvent::AttachmentStaged(meta)),
            Err(err) => tracing::warn!(error = %err, "could not stage a dropped file"),
        }
    });
}
