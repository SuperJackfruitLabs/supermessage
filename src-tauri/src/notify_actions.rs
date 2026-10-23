//! What a press on a notification does (parity M6): a button answers a decision in its own
//! room, and the body opens that room. The core decides which (`notification_policy::response`,
//! the port of `NotificationRouter.swift:113-160`); this file only carries it out.

use std::collections::HashMap;
use std::future::Future;
use std::time::Duration;

use serde_json::Value;
use supermessage_core::error::CoreResult;
use supermessage_core::notification_policy::{self, NotificationAnswer, NotificationResponse};
use supermessage_core::session::Session;
use tauri::{AppHandle, Emitter, Manager, Runtime};

use crate::commands_notify::post_planned;
use crate::notify::Poster;

/// The page listens for this and selects the room.
pub const OPEN_EVENT: &str = "sm://notification/open";

/// What a click on the notification body reports.
const XDG_DEFAULT_ACTION: &str = "default";

/// How long an answer may take before the reader is told to look
/// (`NotificationAnswerer.swift:22`). A desktop has no system limit; this is iOS's number.
pub const ANSWER_BUDGET: Duration = Duration::from_secs(15);

/// Sends an answer into its room, open or not.
pub trait Answerer: Send + Sync {
    fn permission(
        &self,
        room_id: &str,
        option_id: &str,
    ) -> impl Future<Output = CoreResult<()>> + Send;

    fn gate(
        &self,
        room_id: &str,
        gate_id: &str,
        option_id: &str,
        comment: Option<&str>,
        in_reply_to: &str,
        prompt: &str,
    ) -> impl Future<Output = CoreResult<()>> + Send;
}

impl Answerer for Session {
    async fn permission(&self, room_id: &str, option_id: &str) -> CoreResult<()> {
        self.send_permission_answer(room_id, option_id).await
    }

    async fn gate(
        &self,
        room_id: &str,
        gate_id: &str,
        option_id: &str,
        comment: Option<&str>,
        in_reply_to: &str,
        prompt: &str,
    ) -> CoreResult<()> {
        self.send_gate_decision_to(room_id, gate_id, option_id, comment, in_reply_to, prompt)
            .await
    }
}

/// Brings the window forward on a room.
pub trait Opener: Send + Sync {
    fn open_room(&self, room_id: &str);
}

/// The main window: unminimized, shown and focused, then told which room.
pub struct WindowOpener<R: Runtime>(pub AppHandle<R>);

impl<R: Runtime> Opener for WindowOpener<R> {
    fn open_room(&self, room_id: &str) {
        let Some(window) = self.0.get_webview_window("main") else {
            return;
        };
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
        let _ = window.emit(OPEN_EVENT, serde_json::json!({ "roomId": room_id }));
    }
}

/// The press's info as the core's string map. Anything that is not a string is dropped.
pub fn info_map(user_info: &Value) -> HashMap<String, String> {
    user_info
        .as_object()
        .map(|object| {
            object
                .iter()
                .filter_map(|(k, v)| v.as_str().map(|v| (k.clone(), v.to_owned())))
                .collect()
        })
        .unwrap_or_default()
}

/// Carries out what the core says a press means.
pub async fn handle(
    action_id: String,
    user_info: HashMap<String, String>,
    answerer: &impl Answerer,
    opener: &impl Opener,
    poster: &dyn Poster,
) {
    handle_within(
        ANSWER_BUDGET,
        action_id,
        user_info,
        answerer,
        opener,
        poster,
    )
    .await;
}

async fn handle_within(
    budget: Duration,
    action_id: String,
    user_info: HashMap<String, String>,
    answerer: &impl Answerer,
    opener: &impl Opener,
    poster: &dyn Poster,
) {
    // The XDG protocol calls a click on the notification itself "default"; it is the core's Open.
    let action_id = if action_id == XDG_DEFAULT_ACTION {
        notification_policy::ACTION_OPEN.to_owned()
    } else {
        action_id
    };
    match notification_policy::response(&action_id, &user_info, None) {
        NotificationResponse::Open { room_id } => opener.open_room(&room_id),
        NotificationResponse::Ignore => {}
        NotificationResponse::Answer { answer } => {
            let room_id = answer.room_id().to_owned();
            let sent = tokio::time::timeout(budget, send(&answer, answerer)).await;
            // A refusal and a send that outlives the budget are the same to the reader.
            if !matches!(sent, Ok(Ok(()))) {
                let _ = post_planned(poster, &notification_policy::answer_failed(&room_id));
            }
        }
    }
}

async fn send(answer: &NotificationAnswer, answerer: &impl Answerer) -> CoreResult<()> {
    match answer {
        NotificationAnswer::Permission { room_id, option_id } => {
            answerer.permission(room_id, option_id).await
        }
        NotificationAnswer::Gate {
            room_id,
            gate_event_id,
            gate_id,
            option_id,
            comment,
            prompt,
        } => {
            answerer
                .gate(
                    room_id,
                    gate_id,
                    option_id,
                    comment.as_deref(),
                    gate_event_id,
                    prompt,
                )
                .await
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::notify::PostedNote;
    use std::sync::Mutex;
    use supermessage_core::error::CoreError;
    use supermessage_core::notification::NotificationCategory;
    use supermessage_core::notification_policy::{
        PlannedNotification, ACTION_ALLOW_ONCE, ACTION_APPROVE_GATE, ACTION_REJECT,
        ACTION_REJECT_GATE,
    };

    #[derive(Default)]
    struct Recording {
        sent: Mutex<Vec<String>>,
        opened: Mutex<Vec<String>>,
        posted: Mutex<Vec<PostedNote>>,
        refuse: bool,
        hang: bool,
    }

    impl Recording {
        async fn outcome(&self, what: String) -> CoreResult<()> {
            self.sent.lock().unwrap().push(what);
            if self.hang {
                std::future::pending::<()>().await;
            }
            if self.refuse {
                return Err(CoreError::Protocol("refused".into()));
            }
            Ok(())
        }
    }

    impl Answerer for Recording {
        async fn permission(&self, room_id: &str, option_id: &str) -> CoreResult<()> {
            self.outcome(format!("permission {room_id} {option_id}"))
                .await
        }
        async fn gate(
            &self,
            room_id: &str,
            gate_id: &str,
            option_id: &str,
            comment: Option<&str>,
            in_reply_to: &str,
            prompt: &str,
        ) -> CoreResult<()> {
            self.outcome(format!(
                "gate {room_id} {gate_id} {option_id} {comment:?} {in_reply_to} {prompt}"
            ))
            .await
        }
    }

    impl Opener for Recording {
        fn open_room(&self, room_id: &str) {
            self.opened.lock().unwrap().push(room_id.to_owned());
        }
    }

    impl Poster for Recording {
        fn post(&self, note: &PostedNote) -> Result<(), String> {
            self.posted.lock().unwrap().push(note.clone());
            Ok(())
        }
        fn withdraw_all(&self) {}
    }

    fn permission_note() -> PlannedNotification {
        PlannedNotification {
            id: "$ask".into(),
            room_id: "!r:x".into(),
            event_id: Some("$ask".into()),
            title: "Hermes".into(),
            subtitle: None,
            body: "Run it?".into(),
            category: NotificationCategory::Permission,
            permission: Some(supermessage_core::notification::PermissionAnswers {
                allow_option_id: "allow_once".into(),
                reject_option_id: "reject".into(),
            }),
            gate: None,
        }
    }

    fn gate_note(options: &[&str]) -> PlannedNotification {
        PlannedNotification {
            id: "$gate".into(),
            room_id: "!g:x".into(),
            event_id: Some("$gate".into()),
            title: "Pipeline".into(),
            subtitle: None,
            body: "Ship it?".into(),
            category: NotificationCategory::Gate,
            permission: None,
            gate: Some(supermessage_core::notification::GateAnswers {
                gate_id: "gate-7".into(),
                prompt: "Ship it?".into(),
                option_ids: options.iter().map(|o| o.to_string()).collect(),
            }),
        }
    }

    async fn press(rec: &Recording, action: &str, note: &PlannedNotification) {
        handle(
            action.to_owned(),
            notification_policy::user_info(note),
            rec,
            rec,
            rec,
        )
        .await;
    }

    #[tokio::test]
    async fn allow_once_sends_the_permission_option_to_its_room() {
        let rec = Recording::default();
        press(&rec, ACTION_ALLOW_ONCE, &permission_note()).await;
        assert_eq!(*rec.sent.lock().unwrap(), ["permission !r:x allow_once"]);
        assert!(rec.opened.lock().unwrap().is_empty());
        assert!(rec.posted.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn reject_sends_the_permission_reject_option() {
        let rec = Recording::default();
        press(&rec, ACTION_REJECT, &permission_note()).await;
        assert_eq!(*rec.sent.lock().unwrap(), ["permission !r:x reject"]);
    }

    #[tokio::test]
    async fn approve_sends_the_gate_decision_referencing_the_gate_event() {
        let rec = Recording::default();
        press(
            &rec,
            ACTION_APPROVE_GATE,
            &gate_note(&["approve", "reject", "request_changes"]),
        )
        .await;
        assert_eq!(
            *rec.sent.lock().unwrap(),
            ["gate !g:x gate-7 approve None $gate Ship it?"]
        );
    }

    #[tokio::test]
    async fn reject_sends_the_gate_reject_option() {
        let rec = Recording::default();
        press(
            &rec,
            ACTION_REJECT_GATE,
            &gate_note(&["approve", "reject", "request_changes"]),
        )
        .await;
        assert_eq!(
            *rec.sent.lock().unwrap(),
            ["gate !g:x gate-7 reject None $gate Ship it?"]
        );
    }

    #[tokio::test]
    async fn an_option_the_gate_does_not_offer_sends_nothing_and_opens() {
        let rec = Recording::default();
        press(&rec, ACTION_APPROVE_GATE, &gate_note(&["reject"])).await;
        assert!(rec.sent.lock().unwrap().is_empty());
        assert_eq!(*rec.opened.lock().unwrap(), ["!g:x"]);
    }

    #[tokio::test]
    async fn the_body_click_opens_the_room() {
        let rec = Recording::default();
        press(&rec, "default", &permission_note()).await;
        assert_eq!(*rec.opened.lock().unwrap(), ["!r:x"]);
        assert!(rec.sent.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn a_refused_answer_posts_your_answer_wasnt_sent() {
        let rec = Recording {
            refuse: true,
            ..Default::default()
        };
        press(&rec, ACTION_ALLOW_ONCE, &permission_note()).await;
        let posted = rec.posted.lock().unwrap();
        assert_eq!(posted.len(), 1, "nothing else is posted: {posted:?}");
        assert_eq!(posted[0].id, "!r:x#answer-failed");
        assert_eq!(posted[0].title, "Your answer wasn't sent");
        assert_eq!(posted[0].body, "Open the conversation to answer it there.");
        assert!(posted[0].actions.is_empty());
        assert!(rec.opened.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn a_send_that_hangs_is_given_up_on_within_the_budget() {
        let rec = Recording {
            hang: true,
            ..Default::default()
        };
        let note = permission_note();
        handle_within(
            Duration::from_millis(100),
            ACTION_ALLOW_ONCE.to_owned(),
            notification_policy::user_info(&note),
            &rec,
            &rec,
            &rec,
        )
        .await;
        let posted = rec.posted.lock().unwrap();
        assert_eq!(posted.len(), 1);
        assert_eq!(posted[0].id, "!r:x#answer-failed");
    }

    #[tokio::test]
    async fn a_dismissal_does_nothing() {
        let rec = Recording::default();
        press(&rec, "sm.dismiss", &permission_note()).await;
        assert!(rec.sent.lock().unwrap().is_empty());
        assert!(rec.opened.lock().unwrap().is_empty());
        assert!(rec.posted.lock().unwrap().is_empty());
    }

    #[test]
    fn a_press_info_keeps_only_strings() {
        let info = info_map(&serde_json::json!({ "roomId": "!r:x", "n": 3 }));
        assert_eq!(info.len(), 1);
        assert_eq!(info["roomId"], "!r:x");
    }
}
