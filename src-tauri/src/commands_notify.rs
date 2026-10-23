//! Notification plumbing the webview asks Rust for (parity M6): the permission,
//! withdrawing everything on sign-out, and answering a gate in a room that need
//! not be open.
//!
//! The webview has no `notification:*` capability; these are this app's own
//! commands.

use std::sync::Arc;

use supermessage_core::error::CoreError;
use supermessage_core::session::Session;
use tauri::{AppHandle, Runtime, State};
use tauri_plugin_notification::{NotificationExt, PermissionState};

use crate::notify::Poster;

/// The wire word for a permission state.
fn permission_word(state: PermissionState) -> &'static str {
    match state {
        PermissionState::Granted => "granted",
        PermissionState::Denied => "denied",
        _ => "prompt",
    }
}

/// Whether the app may post notifications: `granted`, `denied` or `prompt`.
/// With `prompt` set the OS is asked (only when the answer is still open); the
/// page sets it after a fresh sign-in and never on a restore, as iOS does
/// (`PlatformCoordinator.swift:124-131`).
#[tauri::command]
pub fn notification_permission<R: Runtime>(
    prompt: bool,
    app: AppHandle<R>,
) -> Result<String, CoreError> {
    let notification = app.notification();
    let mut state = notification
        .permission_state()
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    if prompt && !matches!(state, PermissionState::Granted | PermissionState::Denied) {
        state = notification
            .request_permission()
            .map_err(|e| CoreError::Protocol(e.to_string()))?;
    }
    Ok(permission_word(state).to_owned())
}

/// Removes every delivered notification (`LocalNotifier.swift:101-107`).
#[tauri::command]
pub fn notification_clear_all(poster: State<'_, Arc<dyn Poster>>) {
    poster.withdraw_all();
}

/// Answers a gate in `room_id`, open or not (`Session::send_gate_decision_to`).
#[tauri::command]
pub async fn send_gate_decision_to(
    room_id: String,
    gate_id: String,
    option_id: String,
    comment: Option<String>,
    in_reply_to: String,
    prompt: String,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session
        .send_gate_decision_to(
            &room_id,
            &gate_id,
            &option_id,
            comment.as_deref(),
            &in_reply_to,
            &prompt,
        )
        .await
}

#[cfg(test)]
mod tests {
    //! These run against a `Session` that never signed in, so nothing here can
    //! reach a homeserver.
    use super::*;
    use std::sync::Mutex;
    use tauri::Manager;

    struct NoSecrets;
    impl supermessage_core::secrets::SecretStore for NoSecrets {
        fn get(&self, _: &str) -> supermessage_core::error::CoreResult<Option<String>> {
            Ok(None)
        }
        fn set(&self, _: &str, _: &str) -> supermessage_core::error::CoreResult<()> {
            Ok(())
        }
        fn delete(&self, _: &str) -> supermessage_core::error::CoreResult<()> {
            Ok(())
        }
    }

    struct Recording(Mutex<u32>);
    impl Poster for Recording {
        fn post(&self, _: &crate::notify::PostedNote) -> Result<(), String> {
            Ok(())
        }
        fn withdraw_all(&self) {
            *self.0.lock().unwrap() += 1;
        }
    }

    #[test]
    fn the_permission_words_are_the_wire_words() {
        assert_eq!(permission_word(PermissionState::Granted), "granted");
        assert_eq!(permission_word(PermissionState::Denied), "denied");
        assert_eq!(permission_word(PermissionState::Prompt), "prompt");
        assert_eq!(
            permission_word(PermissionState::PromptWithRationale),
            "prompt"
        );
    }

    #[test]
    fn notification_clear_all_withdraws_through_the_poster() {
        let app = tauri::test::mock_app();
        let rec = Arc::new(Recording(Mutex::new(0)));
        let poster: Arc<dyn Poster> = rec.clone();
        app.manage(poster);
        notification_clear_all(app.state::<Arc<dyn Poster>>());
        assert_eq!(*rec.0.lock().unwrap(), 1);
    }

    /// Signed in against a mock homeserver that records every request, so the test
    /// can show that a refused answer never left the process.
    #[tokio::test]
    async fn send_gate_decision_to_refuses_a_bad_room_id_at_parsing() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        supermessage_core::tls::install_ring_provider();
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/_matrix/client/versions"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({ "versions": ["r0.6.0"] })),
            )
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/_matrix/client/r0/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "abc123",
                "device_id": "GHTYAJCE",
                "user_id": "@alice:localhost",
            })))
            .mount(&server)
            .await;
        let dir = std::env::temp_dir().join(format!("sm-notify-gate-{}", rand::random::<u64>()));
        let session = Session::new(dir.clone(), Box::new(NoSecrets));
        session
            .login(&server.uri(), "alice", "hunter2")
            .await
            .unwrap();
        let app = tauri::test::mock_app();
        app.manage(session);
        let before = server.received_requests().await.unwrap().len();

        let err = send_gate_decision_to(
            "not-a-room-id".into(),
            "g".into(),
            "approve".into(),
            None,
            "$e".into(),
            "p".into(),
            app.state::<Session>(),
        )
        .await
        .unwrap_err();

        assert_eq!(err.kind(), "protocol", "got {err:?}");
        assert_eq!(
            server.received_requests().await.unwrap().len(),
            before,
            "a refused answer must not reach the homeserver"
        );
        let _ = std::fs::remove_dir_all(dir);
    }
}
