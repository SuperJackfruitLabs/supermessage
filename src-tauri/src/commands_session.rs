//! Tauri commands that act on the signed-in `Session` itself rather than the
//! focused timeline: blocking, reporting, per-room settings, the known-people
//! directory, the account identity and the room-addressed permission answer.
//!
//! Like `commands.rs`, every command is a thin wrapper — resolve managed
//! state, call one core function, return its result. No logic lives here. It
//! is a separate file so this surface does not collide with `commands.rs`,
//! which every core-facing change edits.

use tauri::State;

use supermessage_core::dto::AccountDto;
use supermessage_core::error::CoreError;
use supermessage_core::people::{self, PersonDto};
use supermessage_core::room_info::{NotificationMode, RoomMemberDto};
use supermessage_core::session::Session;
use supermessage_core::{display_name, safety};

/// Blocks `user_id` (`Session::ignore_user`).
#[tauri::command]
pub async fn ignore_user(user_id: String, session: State<'_, Session>) -> Result<(), CoreError> {
    session.ignore_user(&user_id).await
}

/// Unblocks `user_id` (`Session::unignore_user`).
#[tauri::command]
pub async fn unignore_user(user_id: String, session: State<'_, Session>) -> Result<(), CoreError> {
    session.unignore_user(&user_id).await
}

/// Everyone this account has blocked, named (`Session::blocked_users`).
#[tauri::command]
pub async fn blocked_users(session: State<'_, Session>) -> Result<Vec<RoomMemberDto>, CoreError> {
    session.blocked_users().await
}

/// Reports one message to the homeserver's administrator (`Session::report_event`).
#[tauri::command]
pub async fn report_event(
    room_id: String,
    event_id: String,
    reason: String,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session.report_event(&room_id, &event_id, &reason).await
}

/// Reports a room (`Session::report_room`).
#[tauri::command]
pub async fn report_room(
    room_id: String,
    reason: String,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session.report_room(&room_id, &reason).await
}

/// Reports a person or an agent (`Session::report_user`).
#[tauri::command]
pub async fn report_user(
    user_id: String,
    reason: String,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session.report_user(&user_id, &reason).await
}

/// How many characters a report reason may still grow by
/// (`safety::report_reason_remaining`).
#[tauri::command]
pub fn report_reason_remaining(reason: String) -> i64 {
    safety::report_reason_remaining(&reason)
}

/// Sets a room's notification mode (`Session::set_room_notification_mode`).
#[tauri::command]
pub async fn set_room_notification_mode(
    room_id: String,
    mode: NotificationMode,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session.set_room_notification_mode(&room_id, mode).await
}

/// Pins or unpins a room (`Session::set_room_pinned`).
#[tauri::command]
pub async fn set_room_pinned(
    room_id: String,
    pinned: bool,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session.set_room_pinned(&room_id, pinned).await
}

/// The people this account has seen (`Session::known_people`), narrowed by
/// `people::matching` when a query is given. One command does both because
/// `PersonDto` is serialize-only: the webview cannot hand the list back to a
/// separate filter command.
#[tauri::command]
pub async fn known_people(
    query: Option<String>,
    session: State<'_, Session>,
) -> Result<Vec<PersonDto>, CoreError> {
    let all = session.known_people().await?;
    Ok(match query {
        Some(q) => people::matching(&all, &q),
        None => all,
    })
}

/// The direct room shared with `user_id`, if any (`Session::direct_room_with`).
#[tauri::command]
pub async fn direct_room_with(
    user_id: String,
    session: State<'_, Session>,
) -> Result<Option<String>, CoreError> {
    session.direct_room_with(&user_id).await
}

/// Who invited this account to `room_id` (`Session::room_inviter`).
#[tauri::command]
pub async fn room_inviter(
    room_id: String,
    session: State<'_, Session>,
) -> Result<Option<String>, CoreError> {
    session.room_inviter(&room_id).await
}

/// The signed-in identity (`Session::account`).
#[tauri::command]
pub async fn account(session: State<'_, Session>) -> Result<AccountDto, CoreError> {
    session.account().await
}

/// A name for a list of people (`display_name::people_label`).
#[tauri::command]
pub fn people_label(user_ids: Vec<String>) -> String {
    display_name::people_label(&user_ids)
}

/// Answers a permission request in `room_id`, returning once the homeserver
/// has the event (`Session::send_permission_answer`).
#[tauri::command]
pub async fn send_permission_answer(
    room_id: String,
    option_id: String,
    session: State<'_, Session>,
) -> Result<(), CoreError> {
    session.send_permission_answer(&room_id, &option_id).await
}

#[cfg(test)]
mod tests {
    //! These run against a `Session` that never signed in, so a command that
    //! reaches the core answers `notReady` before any network or homeserver
    //! write could happen. That proves each wrapper calls its core function
    //! without a write ever being possible.

    use super::*;
    use supermessage_core::error::CoreResult;
    use supermessage_core::secrets::SecretStore;
    use tauri::test::{mock_app, MockRuntime};
    use tauri::{App, Manager};

    struct NoSecrets;
    impl SecretStore for NoSecrets {
        fn get(&self, _: &str) -> CoreResult<Option<String>> {
            Ok(None)
        }
        fn set(&self, _: &str, _: &str) -> CoreResult<()> {
            Ok(())
        }
        fn delete(&self, _: &str) -> CoreResult<()> {
            Ok(())
        }
    }

    fn app() -> App<MockRuntime> {
        let app = mock_app();
        app.manage(Session::new(
            std::env::temp_dir().join("sm-commands-session-test"),
            Box::new(NoSecrets),
        ));
        app
    }

    fn not_ready<T: std::fmt::Debug>(result: Result<T, CoreError>) {
        assert_eq!(result.unwrap_err().kind(), "notReady");
    }

    #[tokio::test]
    async fn every_session_command_reaches_the_core() {
        let app = app();
        macro_rules! s {
            () => {
                app.state::<Session>()
            };
        }
        not_ready(ignore_user("@a:b.c".into(), s!()).await);
        not_ready(unignore_user("@a:b.c".into(), s!()).await);
        not_ready(blocked_users(s!()).await);
        not_ready(report_event("!r:b.c".into(), "$e".into(), "".into(), s!()).await);
        not_ready(report_room("!r:b.c".into(), "".into(), s!()).await);
        not_ready(report_user("@a:b.c".into(), "".into(), s!()).await);
        not_ready(set_room_notification_mode("!r:b.c".into(), NotificationMode::Muted, s!()).await);
        not_ready(set_room_pinned("!r:b.c".into(), true, s!()).await);
        not_ready(known_people(None, s!()).await);
        not_ready(known_people(Some("x".into()), s!()).await);
        not_ready(direct_room_with("@a:b.c".into(), s!()).await);
        not_ready(room_inviter("!r:b.c".into(), s!()).await);
        not_ready(account(s!()).await);
        not_ready(send_permission_answer("!r:b.c".into(), "Allow once".into(), s!()).await);
    }

    #[test]
    fn report_reason_remaining_trims_like_the_core() {
        assert_eq!(report_reason_remaining("  hi  ".into()), 1998);
        assert_eq!(report_reason_remaining("".into()), 2000);
        assert_eq!(report_reason_remaining("x".repeat(2105)), -105);
    }

    #[test]
    fn people_label_is_the_cores_label() {
        let ids = vec!["@rakesh:id.agentpod.dev".to_string()];
        assert_eq!(people_label(ids.clone()), display_name::people_label(&ids));
        assert_eq!(people_label(vec![]), display_name::people_label(&[]));
    }

    /// The registration list is a macro argument, so the only way a test can
    /// see it is to read the source: every command in this file must be named
    /// in `lib.rs`'s `generate_handler!`.
    #[test]
    fn all_fifteen_commands_are_registered_in_the_handler_list() {
        let lib = include_str!("lib.rs");
        let start = lib.find("generate_handler![").expect("handler list");
        let list = &lib[start..lib[start..].find("])").unwrap() + start];
        let names = [
            "ignore_user",
            "unignore_user",
            "blocked_users",
            "report_event",
            "report_room",
            "report_user",
            "report_reason_remaining",
            "set_room_notification_mode",
            "set_room_pinned",
            "known_people",
            "direct_room_with",
            "room_inviter",
            "account",
            "people_label",
            "send_permission_answer",
        ];
        for name in names {
            let hits = list
                .split(|c: char| !(c.is_alphanumeric() || c == '_'))
                .filter(|w| *w == name)
                .count();
            assert_eq!(hits, 1, "{name} must appear once in generate_handler!");
        }
        let own = include_str!("commands_session.rs");
        let tauri_commands = own[..own.find("#[cfg(test)]").unwrap()]
            .matches("#[tauri::command]")
            .count();
        assert_eq!(tauri_commands, names.len());
    }
}
