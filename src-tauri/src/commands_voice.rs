//! Voice recording on the desktop (parity M6, C2).
//!
//! The webview records (`MediaRecorder`) and hands the finished bytes here as a raw
//! IPC body. They are written under `<app_cache_dir>/recordings/<id>/Voice message.ogg`
//! and staged like a picked file, so the webview never learns a path. The recorder's
//! numbers and the waveform are the core's (`core::voice_recording`); the mark that makes
//! the staged file a voice message is `StagedAttachments::mark_voice`.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use supermessage_core::attachments::{self, StagedAttachment, StagedAttachments, VoiceNote};
use supermessage_core::error::{CoreError, CoreResult};
use supermessage_core::session::Session;
use supermessage_core::timeline::FocusedTimeline;
use supermessage_core::voice_recording::{self, VoiceRecordingSpec};
use tauri::ipc::{InvokeBody, Request};
use tauri::{AppHandle, Manager, State};

/// A recording larger than this is refused before it is written. At 24 kbit/s that is
/// hours of speech; the cap is a bound on the IPC body, not a product limit.
pub const MAX_RECORDING_BYTES: usize = 25 * 1024 * 1024;

/// Where recordings live under the app cache.
const RECORDINGS_DIR: &str = "recordings";

/// The file extension for a recorder's mime type, or `None` for one we do not stage. Only
/// Ogg/Opus is sent as a voice message as it is; WebM would need the core's remux (CP4).
fn extension_for(mime: &str) -> Option<&'static str> {
    let essence = mime
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    match essence.as_str() {
        "audio/ogg" => Some("ogg"),
        "audio/webm" => Some("webm"),
        _ => None,
    }
}

/// Checks the room and size, then writes the body to `<root>/<id>/Voice message.<ext>`.
/// `focused` is the room the core has open. Nothing is written when a check fails.
fn write_recording(
    root: &Path,
    id: &str,
    room_id: &str,
    focused: Option<String>,
    mime: &str,
    body: &[u8],
) -> CoreResult<PathBuf> {
    let focused = focused.ok_or(CoreError::NotReady)?;
    if focused != room_id {
        return Err(CoreError::RoomChanged {
            requested: room_id.to_string(),
            focused,
        });
    }
    let ext = extension_for(mime)
        .ok_or_else(|| CoreError::Store(format!("can't send a recording of type {mime}")))?;
    if body.len() > MAX_RECORDING_BYTES {
        return Err(CoreError::Store(
            "that recording is too large to send".into(),
        ));
    }
    let dir = root.join(id);
    std::fs::create_dir_all(&dir)
        .map_err(|e| CoreError::Store(format!("couldn't save the recording: {e}")))?;
    let path = dir.join(format!("{}.{ext}", voice_recording::spec().file_stem));
    std::fs::write(&path, body)
        .map_err(|e| CoreError::Store(format!("couldn't save the recording: {e}")))?;
    Ok(path)
}

fn recordings_root(app: &AppHandle) -> Result<PathBuf, CoreError> {
    app.path()
        .app_cache_dir()
        .map(|d| d.join(RECORDINGS_DIR))
        .map_err(|e| CoreError::Store(format!("no cache folder: {e}")))
}

/// Deletes every recording left behind. Called at startup and on logout: a staged
/// recording is single-use and the core holds only its path, so nothing here is live then.
pub fn sweep_recordings(app: &AppHandle) {
    if let Ok(root) = recordings_root(app) {
        sweep_dir(&root);
    }
}

fn sweep_dir(root: &Path) {
    match std::fs::remove_dir_all(root) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => tracing::warn!(error = %e, "couldn't sweep old recordings"),
    }
}

fn header(request: &Request<'_>, name: &str) -> Result<String, CoreError> {
    request
        .headers()
        .get(name)
        .and_then(|v| v.to_str().ok())
        .map(str::to_string)
        .ok_or_else(|| CoreError::Protocol(format!("missing {name} header")))
}

/// Stages a recording the webview made. The body is the raw bytes; `x-room-id` and `x-mime`
/// carry the room and the recorder's type. Returns the staged attachment, whose token is then
/// marked as a voice message and sent like any staged file.
#[tauri::command]
pub async fn attachment_stage_recording(
    request: Request<'_>,
    app: AppHandle,
    session: State<'_, Session>,
    timeline: State<'_, Arc<FocusedTimeline>>,
    staged: State<'_, Arc<StagedAttachments>>,
) -> Result<StagedAttachment, CoreError> {
    let room_id = header(&request, "x-room-id")?;
    let mime = header(&request, "x-mime")?;
    let InvokeBody::Raw(body) = request.body() else {
        return Err(CoreError::Protocol(
            "a recording is sent as a raw body".into(),
        ));
    };
    let id = format!("{:032x}", rand::random::<u128>());
    let path = write_recording(
        &recordings_root(&app)?,
        &id,
        &room_id,
        timeline.focused_room_id(),
        &mime,
        body,
    )?;
    let client = match session.require_client().await {
        Ok(c) => c,
        Err(e) => {
            remove_with_folder(&path);
            return Err(e);
        }
    };
    match attachments::stage_path(&client, &staged, &room_id, path.clone()).await {
        Ok(meta) => Ok(meta),
        Err(e) => {
            remove_with_folder(&path);
            Err(e)
        }
    }
}

fn remove_with_folder(path: &Path) {
    if let Some(dir) = path.parent() {
        let _ = std::fs::remove_dir_all(dir);
    }
}

/// Marks the staged recording as a voice message with its length and waveform.
#[tauri::command]
pub fn attachment_mark_voice(
    room_id: String,
    token: String,
    duration_ms: u64,
    waveform: Vec<f32>,
    staged: State<'_, Arc<StagedAttachments>>,
) -> Result<(), CoreError> {
    staged.mark_voice(
        &token,
        &room_id,
        VoiceNote {
            duration_ms,
            waveform,
        },
    )
}

/// The recorder's numbers (core `voice_recording::spec`).
#[tauri::command]
pub fn voice_recording_spec() -> VoiceRecordingSpec {
    voice_recording::spec()
}

/// The meter's readings as a waveform (core `voice_recording::waveform`).
#[tauri::command]
pub fn voice_waveform(decibels: Vec<f32>, bins: u32) -> Vec<f32> {
    voice_recording::waveform(&decibels, bins as usize)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let d =
            std::env::temp_dir().join(format!("sm-voice-test-{name}-{:x}", rand::random::<u32>()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn stage_recording_refuses_another_room() {
        let root = scratch("room");
        let err = write_recording(
            &root,
            "a",
            "!a:x",
            Some("!b:x".into()),
            "audio/ogg",
            b"OggS",
        )
        .unwrap_err();
        assert_eq!(err.kind(), "roomChanged");
        assert!(!root.exists(), "nothing is written for another room");
    }

    #[test]
    fn stage_recording_refuses_an_oversized_body() {
        let root = scratch("big");
        let body = vec![0u8; MAX_RECORDING_BYTES + 1];
        let err = write_recording(&root, "a", "!a:x", Some("!a:x".into()), "audio/ogg", &body)
            .unwrap_err();
        assert!(err.to_string().contains("too large"), "{err}");
        assert!(!root.exists());
    }

    #[test]
    fn stage_recording_names_the_file_voice_message() {
        let root = scratch("name");
        let path = write_recording(
            &root,
            "abc",
            "!a:x",
            Some("!a:x".into()),
            "audio/ogg;codecs=opus",
            b"OggS",
        )
        .unwrap();
        assert_eq!(path.file_name().unwrap(), "Voice message.ogg");
        assert_eq!(std::fs::read(&path).unwrap(), b"OggS");
        assert_eq!(path.parent().unwrap().parent().unwrap(), root);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn stage_recording_refuses_a_type_it_does_not_stage() {
        let root = scratch("mime");
        assert!(
            write_recording(&root, "a", "!a:x", Some("!a:x".into()), "video/mp4", b"x").is_err()
        );
        assert!(!root.exists());
    }

    #[test]
    fn the_sweep_removes_every_recording() {
        let root = scratch("sweep");
        write_recording(
            &root,
            "one",
            "!a:x",
            Some("!a:x".into()),
            "audio/ogg",
            b"OggS",
        )
        .unwrap();
        sweep_dir(&root);
        assert!(!root.exists());
        sweep_dir(&root); // a second sweep with nothing there is not an error
    }
}
