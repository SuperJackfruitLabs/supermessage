//! What a press on a notification does (parity M6): a button answers a decision in its own
//! room, and the body opens that room. The core decides which (`notification_policy::response`,
//! the port of `NotificationRouter.swift:113-160`); this file only carries it out.

use std::future::Future;
use std::sync::Arc;
use std::time::Duration;

use supermessage_core::error::CoreResult;
use supermessage_core::notification_policy::{self, NotificationAnswer, NotificationResponse};
use supermessage_core::session::Session;
use tauri::{AppHandle, Emitter, Manager, Runtime};

use crate::commands_notify::post_planned;
use crate::notify::{ActionTaken, Poster};

/// The page listens for this and selects the room.
pub const OPEN_EVENT: &str = "sm://notification/open";

/// What a click on the notification body reports.
const XDG_DEFAULT_ACTION: &str = "default";

/// How long an answer may take before the reader is told to look
/// (`NotificationAnswerer.swift:22`). A desktop has no system limit; this is iOS's number.
pub const ANSWER_BUDGET: Duration = Duration::from_secs(15);

/// The signed-in session: who it is, and how it sends an answer into a room, open or not.
pub trait Answerer: Send + Sync {
    /// The Matrix user id signed in now, if any.
    fn account(&self) -> impl Future<Output = Option<String>> + Send;

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
    async fn account(&self) -> Option<String> {
        crate::commands_notify::signed_in_account(self).await
    }

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

/// Carries out what the core says a press means, if the note's account is still the one
/// signed in.
pub async fn handle(
    taken: ActionTaken,
    answerer: &impl Answerer,
    opener: &impl Opener,
    poster: Arc<dyn Poster>,
) {
    handle_within(ANSWER_BUDGET, taken, answerer, opener, poster).await;
}

async fn handle_within(
    budget: Duration,
    taken: ActionTaken,
    answerer: &impl Answerer,
    opener: &impl Opener,
    poster: Arc<dyn Poster>,
) {
    let ActionTaken {
        action_id,
        user_info,
        account,
    } = taken;
    // A note left from another account (or from before a sign-out) acts on nothing: not
    // an answer, not even opening a room, in the session signed in now.
    if account.is_none() || answerer.account().await != account {
        return;
    }
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
                // The server round trip blocks; keep it off the async workers.
                let failure = notification_policy::answer_failed(&room_id);
                let _ = tokio::task::spawn_blocking(move || {
                    post_planned(poster.as_ref(), &failure, account)
                })
                .await;
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

    /// The account the tests' notes are posted for and the session is signed in as.
    const ME: &str = "@me:x";

    struct Recording {
        /// Who the session is signed in as.
        account: Option<String>,
        sent: Mutex<Vec<String>>,
        opened: Mutex<Vec<String>>,
        posted: Mutex<Vec<PostedNote>>,
        posted_on: Mutex<Vec<std::thread::ThreadId>>,
        refuse: bool,
        hang: bool,
    }

    impl Default for Recording {
        fn default() -> Self {
            Self {
                account: Some(ME.into()),
                sent: Default::default(),
                opened: Default::default(),
                posted: Default::default(),
                posted_on: Default::default(),
                refuse: false,
                hang: false,
            }
        }
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
        async fn account(&self) -> Option<String> {
            self.account.clone()
        }
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
            self.posted_on
                .lock()
                .unwrap()
                .push(std::thread::current().id());
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

    fn taken(action: &str, note: &PlannedNotification, account: Option<&str>) -> ActionTaken {
        ActionTaken {
            action_id: action.to_owned(),
            user_info: notification_policy::user_info(note),
            account: account.map(Into::into),
        }
    }

    async fn press(rec: &Arc<Recording>, action: &str, note: &PlannedNotification) {
        handle(taken(action, note, Some(ME)), &**rec, &**rec, rec.clone()).await;
    }

    #[tokio::test]
    async fn allow_once_sends_the_permission_option_to_its_room() {
        let rec = Arc::new(Recording::default());
        press(&rec, ACTION_ALLOW_ONCE, &permission_note()).await;
        assert_eq!(*rec.sent.lock().unwrap(), ["permission !r:x allow_once"]);
        assert!(rec.opened.lock().unwrap().is_empty());
        assert!(rec.posted.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn reject_sends_the_permission_reject_option() {
        let rec = Arc::new(Recording::default());
        press(&rec, ACTION_REJECT, &permission_note()).await;
        assert_eq!(*rec.sent.lock().unwrap(), ["permission !r:x reject"]);
    }

    #[tokio::test]
    async fn approve_sends_the_gate_decision_referencing_the_gate_event() {
        let rec = Arc::new(Recording::default());
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
        let rec = Arc::new(Recording::default());
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
        let rec = Arc::new(Recording::default());
        press(&rec, ACTION_APPROVE_GATE, &gate_note(&["reject"])).await;
        assert!(rec.sent.lock().unwrap().is_empty());
        assert_eq!(*rec.opened.lock().unwrap(), ["!g:x"]);
    }

    #[tokio::test]
    async fn the_body_click_opens_the_room() {
        let rec = Arc::new(Recording::default());
        press(&rec, "default", &permission_note()).await;
        assert_eq!(*rec.opened.lock().unwrap(), ["!r:x"]);
        assert!(rec.sent.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn a_refused_answer_posts_your_answer_wasnt_sent() {
        let rec = Arc::new(Recording {
            refuse: true,
            ..Default::default()
        });
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
    async fn the_blocking_post_runs_off_the_async_worker() {
        let rec = Arc::new(Recording {
            refuse: true,
            ..Default::default()
        });
        press(&rec, ACTION_ALLOW_ONCE, &permission_note()).await;
        let on = rec.posted_on.lock().unwrap();
        assert_eq!(on.len(), 1);
        assert_ne!(on[0], std::thread::current().id());
    }

    #[tokio::test]
    async fn a_send_that_hangs_is_given_up_on_within_the_budget() {
        let rec = Arc::new(Recording {
            hang: true,
            ..Default::default()
        });
        let note = permission_note();
        handle_within(
            Duration::from_millis(100),
            taken(ACTION_ALLOW_ONCE, &note, Some(ME)),
            &*rec,
            &*rec,
            rec.clone(),
        )
        .await;
        let posted = rec.posted.lock().unwrap();
        assert_eq!(posted.len(), 1);
        assert_eq!(posted[0].id, "!r:x#answer-failed");
    }

    #[tokio::test]
    async fn a_dismissal_does_nothing() {
        let rec = Arc::new(Recording::default());
        press(&rec, "sm.dismiss", &permission_note()).await;
        assert!(rec.sent.lock().unwrap().is_empty());
        assert!(rec.opened.lock().unwrap().is_empty());
        assert!(rec.posted.lock().unwrap().is_empty());
    }

    /// End to end through the real poster's bookkeeping: the core's note is posted through
    /// `XdgPoster` to a fake server, the reader presses it there, and what the poster
    /// reports is what `handle` gets, as `spawn_action_consumer` passes it on.
    #[cfg(target_os = "linux")]
    async fn press_through_the_poster(
        rec: &Arc<Recording>,
        note: &PlannedNotification,
        action: &str,
    ) {
        let (poster, server, mut rx) = crate::notify::fake::poster(true);
        post_planned(&poster, note, Some(ME.into())).unwrap();
        server.press(server.last_id(), action);
        let taken = rx.try_recv().expect("the press is reported");
        handle(taken, &**rec, &**rec, rec.clone()).await;
    }

    #[cfg(target_os = "linux")]
    fn message_note() -> PlannedNotification {
        PlannedNotification {
            id: "!m:x#1700".into(),
            room_id: "!m:x".into(),
            event_id: None,
            title: "Priya".into(),
            subtitle: None,
            body: "Lunch?".into(),
            category: NotificationCategory::Message,
            permission: None,
            gate: None,
        }
    }

    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn clicking_a_message_note_opens_its_room() {
        let rec = Arc::new(Recording::default());
        press_through_the_poster(&rec, &message_note(), "default").await;
        assert_eq!(*rec.opened.lock().unwrap(), ["!m:x"]);
        assert!(rec.sent.lock().unwrap().is_empty());
    }

    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn clicking_a_decision_note_opens_its_room() {
        let rec = Arc::new(Recording::default());
        let note = PlannedNotification {
            id: "!d:x#pending#3".into(),
            room_id: "!d:x".into(),
            category: NotificationCategory::Decision,
            ..message_note()
        };
        press_through_the_poster(&rec, &note, "default").await;
        assert_eq!(*rec.opened.lock().unwrap(), ["!d:x"]);
    }

    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn clicking_the_answer_failed_note_opens_its_room() {
        let rec = Arc::new(Recording::default());
        press_through_the_poster(&rec, &notification_policy::answer_failed("!f:x"), "default")
            .await;
        assert_eq!(*rec.opened.lock().unwrap(), ["!f:x"]);
    }

    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn a_button_pressed_on_the_poster_answers_in_its_room() {
        let rec = Arc::new(Recording::default());
        press_through_the_poster(&rec, &permission_note(), ACTION_ALLOW_ONCE).await;
        assert_eq!(*rec.sent.lock().unwrap(), ["permission !r:x allow_once"]);
        assert!(rec.opened.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn a_press_on_another_accounts_note_does_nothing() {
        // Signed in as B; the note was posted for A and outlived A's sign-out (the
        // withdraw failed, or the OS cannot withdraw).
        let rec = Arc::new(Recording {
            account: Some("@b:x".into()),
            ..Default::default()
        });
        for action in [ACTION_ALLOW_ONCE, "default"] {
            handle(
                taken(action, &permission_note(), Some("@a:x")),
                &*rec,
                &*rec,
                rec.clone(),
            )
            .await;
        }
        assert!(rec.sent.lock().unwrap().is_empty(), "no answer as B");
        assert!(rec.opened.lock().unwrap().is_empty(), "no room opened");
        assert!(rec.posted.lock().unwrap().is_empty(), "nothing posted");
    }

    #[tokio::test]
    async fn a_press_while_signed_out_does_nothing() {
        let rec = Arc::new(Recording {
            account: None,
            ..Default::default()
        });
        handle(
            taken("default", &permission_note(), Some(ME)),
            &*rec,
            &*rec,
            rec.clone(),
        )
        .await;
        // A note posted with no account known never acts either.
        let rec2 = Arc::new(Recording::default());
        handle(
            taken("default", &permission_note(), None),
            &*rec2,
            &*rec2,
            rec2.clone(),
        )
        .await;
        assert!(rec.opened.lock().unwrap().is_empty());
        assert!(rec2.opened.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn the_failure_note_is_posted_for_the_same_account() {
        let rec = Arc::new(Recording {
            refuse: true,
            ..Default::default()
        });
        press(&rec, ACTION_ALLOW_ONCE, &permission_note()).await;
        assert_eq!(rec.posted.lock().unwrap()[0].account.as_deref(), Some(ME));
    }
}
