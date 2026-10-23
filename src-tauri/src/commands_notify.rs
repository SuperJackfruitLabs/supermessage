//! Notification plumbing the webview asks Rust for (parity M6): the permission,
//! withdrawing everything on sign-out, and answering a gate in a room that need
//! not be open.
//!
//! The webview has no `notification:*` capability; these are this app's own
//! commands.

use std::sync::Arc;

use supermessage_core::dto::RoomRow;
use supermessage_core::error::CoreError;
use supermessage_core::notification_policy::{
    self, NotificationContext, PlannedNotification, ACTION_OPEN,
};
use supermessage_core::room_info::NotificationMode;
use supermessage_core::session::Session;
use supermessage_core::timeline::FocusedTimeline;
use tauri::{AppHandle, Runtime, State};
use tauri_plugin_notification::{NotificationExt, PermissionState};

use crate::notify::{body_with_subtitle, PostedAction, PostedNote, Poster};

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

/// Removes every delivered notification (`LocalNotifier.swift:101-107`). Each close
/// is a bus round trip, so they run on the blocking pool, never on the main thread.
#[tauri::command]
pub async fn notification_clear_all(poster: State<'_, Arc<dyn Poster>>) -> Result<(), String> {
    let poster = Arc::clone(&poster);
    tokio::task::spawn_blocking(move || poster.withdraw_all())
        .await
        .map_err(|e| e.to_string())
}

/// Notifications for rooms whose roster row changed between two snapshots. The core
/// decides what is news (`notification_policy::for_roster`); the first roster, with
/// an empty `previous`, is a baseline. Async so the two roster copies are read off the
/// main thread: the page asks on every roster change.
#[tauri::command]
pub async fn notifications_for_roster(
    previous: Vec<RoomRow>,
    next: Vec<RoomRow>,
    context: NotificationContext,
) -> Vec<PlannedNotification> {
    notification_policy::for_roster(&previous, &next, &context)
}

/// Notifications for new rows in the focused timeline, read from the core's own
/// rows (`TimelineRow` never comes back from the webview). Fails `RoomChanged` when
/// the focused room is not `room_id`, and `NotReady` when none is.
#[tauri::command]
pub async fn notifications_for_focused(
    room_id: String,
    room_name: String,
    since_ms: u64,
    already_notified: Vec<String>,
    context: NotificationContext,
    timeline: State<'_, Arc<FocusedTimeline>>,
) -> Result<Vec<PlannedNotification>, CoreError> {
    plan_focused(
        &timeline,
        &room_id,
        &room_name,
        since_ms,
        &already_notified,
        &context,
    )
    .await
}

async fn plan_focused(
    focused: &FocusedTimeline,
    room_id: &str,
    room_name: &str,
    since_ms: u64,
    already_notified: &[String],
    context: &NotificationContext,
) -> Result<Vec<PlannedNotification>, CoreError> {
    let (subject, _seq, rows) = focused.snapshot().await?;
    if subject != room_id {
        return Err(CoreError::RoomChanged {
            requested: room_id.to_string(),
            focused: subject,
        });
    }
    Ok(notification_policy::for_timeline(
        room_id,
        room_name,
        &rows,
        since_ms,
        already_notified,
        context,
    ))
}

/// Whether the room's notification setting lets `note` through
/// (`notification_policy::delivers`).
#[tauri::command]
pub fn notification_delivers(note: PlannedNotification, mode: Option<NotificationMode>) -> bool {
    notification_policy::delivers(&note, mode)
}

/// Shows `note` with the buttons and info the core gives its category, for the account
/// signed in now.
#[tauri::command]
pub async fn notification_post(
    note: PlannedNotification,
    poster: State<'_, Arc<dyn Poster>>,
    session: State<'_, Session>,
) -> Result<(), String> {
    let poster = Arc::clone(&poster);
    let account = signed_in_account(&session).await;
    // The server round trip blocks; keep it off the async workers.
    tokio::task::spawn_blocking(move || post_planned(poster.as_ref(), &note, account))
        .await
        .map_err(|e| e.to_string())?
}

/// The Matrix user id signed in now, if any.
pub(crate) async fn signed_in_account(session: &Session) -> Option<String> {
    let client = session.client().await?;
    client.user_id().map(ToString::to_string)
}

/// Posts `note` for `account`, the only account a press on it may act for: the one signed
/// in when it is posted (`notification_post` reads it just before), or for a failure note,
/// the account whose answer failed.
pub(crate) fn post_planned(
    poster: &dyn Poster,
    note: &PlannedNotification,
    account: Option<String>,
) -> Result<(), String> {
    let actions = notification_policy::actions(note.category)
        .into_iter()
        // The notification's own click already opens the room; "Open" as well would
        // be the same button twice.
        .filter(|spec| spec.id != ACTION_OPEN)
        .map(|spec| PostedAction {
            id: spec.id,
            title: spec.title,
            text_input: spec.text_input.is_some(),
        })
        .collect();
    poster.post(&PostedNote {
        id: note.id.clone(),
        room_id: note.room_id.clone(),
        title: note.title.clone(),
        body: body_with_subtitle(note.subtitle.as_deref(), &note.body),
        actions,
        user_info: notification_policy::user_info(note),
        account,
    })
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
    use supermessage_core::notification::NotificationCategory;
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

    /// Records the thread each withdraw ran on.
    struct Recording(Mutex<Vec<std::thread::ThreadId>>);
    impl Poster for Recording {
        fn post(&self, _: &crate::notify::PostedNote) -> Result<(), String> {
            Ok(())
        }
        fn withdraw_all(&self) {
            self.0.lock().unwrap().push(std::thread::current().id());
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

    #[tokio::test]
    async fn notification_clear_all_withdraws_through_the_poster_off_this_thread() {
        let app = tauri::test::mock_app();
        let rec = Arc::new(Recording(Mutex::new(Vec::new())));
        let poster: Arc<dyn Poster> = rec.clone();
        app.manage(poster);
        notification_clear_all(app.state::<Arc<dyn Poster>>())
            .await
            .unwrap();
        let on = rec.0.lock().unwrap();
        assert_eq!(on.len(), 1, "withdrawn once");
        assert_ne!(on[0], std::thread::current().id(), "on the blocking pool");
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

    // --- the planner, over a seeded focused timeline -------------------------------

    /// A focused timeline over a mock homeserver holding one agent message that asks
    /// for a permission, which the core words as a PERMISSION note.
    async fn focused_with_a_permission_request(room: &str) -> Arc<FocusedTimeline> {
        use matrix_sdk::ruma::events::AnySyncTimelineEvent;
        use matrix_sdk::ruma::serde::Raw;
        use matrix_sdk::test_utils::mocks::MatrixMockServer;
        use matrix_sdk_test::JoinedRoomBuilder;

        struct Quiet;
        impl supermessage_core::event::EventSink for Quiet {
            fn emit(&self, _: supermessage_core::event::CoreEvent) {}
        }

        supermessage_core::tls::install_ring_provider();
        let room_id = matrix_sdk::ruma::RoomId::parse(room).unwrap();
        let server = MatrixMockServer::new().await;
        let client = server.client_builder().build().await;
        client.event_cache().subscribe().unwrap();
        server.mock_room_state_encryption().plain().mount().await;
        let event: Raw<AnySyncTimelineEvent> = Raw::from_json(
            serde_json::value::to_raw_value(&serde_json::json!({
                "type": "m.room.message",
                "event_id": "$ask",
                "sender": "@agent_hermes:example.org",
                "origin_server_ts": 5_000,
                "content": {
                    "msgtype": "m.text",
                    "body": "Allow Run the migration? Reply 1 (Allow once) or 2 (Reject).",
                    "dev.agentpod.permission": {
                        "schema_version": 1,
                        "session_id": "sess-1",
                        "request_seq": 4,
                        "title": "Run the migration",
                        "options": [
                            { "option_id": "allow_once", "name": "Allow once" },
                            { "option_id": "reject", "name": "Reject" }
                        ]
                    },
                }
            }))
            .unwrap(),
        );
        server
            .sync_room(
                &client,
                JoinedRoomBuilder::new(&room_id).add_timeline_event(event),
            )
            .await;
        let focused = Arc::new(FocusedTimeline::default());
        focused
            .subscribe(&client, room, Arc::new(Quiet))
            .await
            .unwrap();
        for _ in 0..50 {
            let rows = focused.snapshot().await.unwrap().2;
            if rows
                .iter()
                .any(|r| r.item.event_id.as_deref() == Some("$ask"))
            {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        }
        // The mock server must outlive the subscription's reads; it has served them.
        std::mem::forget(server);
        focused
    }

    fn context(open: Option<&str>) -> NotificationContext {
        NotificationContext {
            open_room_id: open.map(Into::into),
            app_active: true,
            timeline_room_id: open.map(Into::into),
            remote_push: false,
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn notifications_for_focused_reads_the_cores_rows() {
        let focused = focused_with_a_permission_request("!perm:example.org").await;
        // The window is not on this room, so nothing is suppressed.
        let notes = plan_focused(
            &focused,
            "!perm:example.org",
            "Hermes",
            0,
            &[],
            &context(None),
        )
        .await
        .unwrap();
        assert_eq!(notes.len(), 1, "got {notes:?}");
        assert_eq!(notes[0].category, NotificationCategory::Permission);
        assert_eq!(notes[0].event_id.as_deref(), Some("$ask"));
        assert_eq!(notes[0].room_id, "!perm:example.org");

        // The same event, already notified, is not news; neither is history.
        let again = plan_focused(
            &focused,
            "!perm:example.org",
            "Hermes",
            0,
            &["$ask".to_string()],
            &context(None),
        )
        .await
        .unwrap();
        assert!(again.is_empty());
        let history = plan_focused(
            &focused,
            "!perm:example.org",
            "Hermes",
            5_001,
            &[],
            &context(None),
        )
        .await
        .unwrap();
        assert!(history.is_empty());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn notifications_for_focused_refuses_another_room() {
        let focused = focused_with_a_permission_request("!perm2:example.org").await;
        let err = plan_focused(
            &focused,
            "!other:example.org",
            "Hermes",
            0,
            &[],
            &context(None),
        )
        .await
        .unwrap_err();
        assert_eq!(err.kind(), "roomChanged", "got {err:?}");
    }

    #[tokio::test]
    async fn notifications_for_focused_with_nothing_focused_is_not_ready() {
        let focused = FocusedTimeline::default();
        let err = plan_focused(&focused, "!r:x", "n", 0, &[], &context(None))
            .await
            .unwrap_err();
        assert_eq!(err.kind(), "notReady", "got {err:?}");
    }

    // --- posting -------------------------------------------------------------------

    struct Capturing(Mutex<Vec<crate::notify::PostedNote>>);
    impl Poster for Capturing {
        fn post(&self, note: &crate::notify::PostedNote) -> Result<(), String> {
            self.0.lock().unwrap().push(note.clone());
            Ok(())
        }
        fn withdraw_all(&self) {}
    }

    fn planned(category: NotificationCategory) -> PlannedNotification {
        PlannedNotification {
            id: "$ask".into(),
            room_id: "!r:x".into(),
            event_id: Some("$ask".into()),
            title: "Hermes".into(),
            subtitle: Some("Run the migration".into()),
            body: "Allow Run the migration?".into(),
            category,
            permission: (category == NotificationCategory::Permission).then(|| {
                supermessage_core::notification::PermissionAnswers {
                    allow_option_id: "allow_once".into(),
                    reject_option_id: "reject".into(),
                }
            }),
            gate: None,
        }
    }

    #[test]
    fn notification_post_carries_the_cores_actions_and_user_info() {
        let rec = Capturing(Mutex::new(Vec::new()));
        post_planned(&rec, &planned(NotificationCategory::Permission), None).unwrap();
        let posted = rec.0.lock().unwrap();
        let note = &posted[0];
        assert_eq!(note.id, "$ask");
        assert_eq!(note.title, "Hermes");
        assert_eq!(note.body, "Run the migration\nAllow Run the migration?");
        let ids: Vec<&str> = note.actions.iter().map(|a| a.id.as_str()).collect();
        assert_eq!(ids, ["sm.permission.allowOnce", "sm.permission.reject"]);
        assert_eq!(
            note.user_info,
            notification_policy::user_info(&planned(NotificationCategory::Permission))
        );
        assert_eq!(
            note.user_info.get("sm.allowOptionId").map(String::as_str),
            Some("allow_once")
        );
    }

    #[test]
    fn a_note_is_posted_for_the_account_it_was_planned_for() {
        let rec = Capturing(Mutex::new(Vec::new()));
        post_planned(
            &rec,
            &planned(NotificationCategory::Message),
            Some("@me:x".into()),
        )
        .unwrap();
        assert_eq!(rec.0.lock().unwrap()[0].account.as_deref(), Some("@me:x"));
    }

    #[test]
    fn a_note_without_actions_still_carries_its_room() {
        // A message has no actions and a decision's only one is the click itself; the
        // click must still find its room (`notification_policy::response`).
        for category in [
            NotificationCategory::Message,
            NotificationCategory::Decision,
        ] {
            let rec = Capturing(Mutex::new(Vec::new()));
            post_planned(&rec, &planned(category), None).unwrap();
            let posted = rec.0.lock().unwrap();
            assert!(posted[0].actions.is_empty());
            assert_eq!(
                posted[0].user_info.get("sm.roomId").map(String::as_str),
                Some("!r:x"),
                "{category:?}"
            );
        }
    }

    #[test]
    fn a_decision_note_has_no_button_of_its_own() {
        // "Open" is the notification's own click; a second button would repeat it.
        let rec = Capturing(Mutex::new(Vec::new()));
        post_planned(&rec, &planned(NotificationCategory::Decision), None).unwrap();
        assert!(rec.0.lock().unwrap()[0].actions.is_empty());
        let rec = Capturing(Mutex::new(Vec::new()));
        post_planned(&rec, &planned(NotificationCategory::Gate), None).unwrap();
        let ids: Vec<String> = rec.0.lock().unwrap()[0]
            .actions
            .iter()
            .map(|a| a.id.clone())
            .collect();
        assert_eq!(
            ids,
            [
                "sm.gate.approve",
                "sm.gate.requestChanges",
                "sm.gate.reject"
            ]
        );
    }

    #[test]
    fn delivers_is_the_cores_rule() {
        let message = planned(NotificationCategory::Message);
        assert!(!notification_delivers(
            message.clone(),
            Some(NotificationMode::Muted)
        ));
        assert!(notification_delivers(message, None));
        assert!(notification_delivers(
            planned(NotificationCategory::Permission),
            Some(NotificationMode::Muted)
        ));
    }
}
