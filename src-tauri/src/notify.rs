//! Where a desktop notification is posted and withdrawn (parity M6, P2).
//!
//! The core decides what a notification says, what it offers and what an answer
//! means (`core::notification_policy`). This file decides only what the *platform*
//! decides: which OS surface carries it, and on Linux whether the notification
//! server can show buttons at all.
//!
//! - **Linux** posts through `notify-rust` directly ([`XdgPoster`]), because the
//!   plugin's action API is mobile-only and Linux is where answer buttons are
//!   wanted (open question Q4). A button is offered only when the server
//!   advertises the `actions` capability; otherwise the note is informational and
//!   the reader answers in the app.
//! - **macOS and Windows** post through `tauri-plugin-notification`
//!   ([`PluginPoster`]) and are informational.
//!
//! The webview is granted no `notification:*` permission: it asks Rust to post.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// One notification to show. Every word in it came from the core.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PostedNote {
    /// The note's id. Posting the same id again replaces what is on screen
    /// (`LocalNotifier.swift:65-85`: the request identifier is the note's id).
    pub id: String,
    pub room_id: String,
    pub title: String,
    pub body: String,
    pub actions: Vec<PostedAction>,
}

/// A button the core offered for a note.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PostedAction {
    pub id: String,
    pub title: String,
    /// The core marks an action that needs typed text ("Request changes"). The
    /// desktop notification protocol has no text input, so such an action is
    /// never a button; the reader types it in the app.
    pub text_input: bool,
    /// What the core says this action carries (`notification_policy::user_info`):
    /// the room, the event, and the option ids an answer needs. It comes back with
    /// the press.
    #[serde(default)]
    pub user_info: HashMap<String, String>,
}

/// Where notifications go. Implemented per platform; faked in tests.
pub trait Poster: Send + Sync {
    /// Shows `note`, replacing a note already posted with the same id.
    fn post(&self, note: &PostedNote) -> Result<(), String>;
    /// Removes every notification this app has delivered (sign-out,
    /// `LocalNotifier.swift:101-107`).
    fn withdraw_all(&self);
}

/// A desktop notification has a summary and a body only, so the subtitle leads
/// the body on its own line *(no iOS copy; layout)*.
pub fn body_with_subtitle(subtitle: Option<&str>, body: &str) -> String {
    match subtitle.map(str::trim).filter(|s| !s.is_empty()) {
        Some(subtitle) => format!("{subtitle}\n{body}"),
        None => body.to_owned(),
    }
}

/// The identifier of the "tap the notification itself" action in the XDG
/// protocol, labelled "Open" (`LocalNotifier.swift:44-45`).
const DEFAULT_ACTION: &str = "default";
const DEFAULT_ACTION_LABEL: &str = "Open";

/// An action performed on a notification: what the reader pressed, and which note.
#[derive(Debug, Clone, PartialEq)]
pub struct ActionTaken {
    pub action_id: String,
    /// `{ "id": <note id>, "roomId": <room id> }`.
    pub user_info: Value,
}

/// Receives the buttons the reader presses (Linux); `notify_actions` consumes them.
pub type ActionSender = tokio::sync::mpsc::UnboundedSender<ActionTaken>;

/// Holds the receiver of [`ActionTaken`] until the consumer takes it (once).
#[cfg(target_os = "linux")]
pub struct ActionInbox(std::sync::Mutex<Option<tokio::sync::mpsc::UnboundedReceiver<ActionTaken>>>);

#[cfg(target_os = "linux")]
impl ActionInbox {
    pub fn new(rx: tokio::sync::mpsc::UnboundedReceiver<ActionTaken>) -> Self {
        Self(std::sync::Mutex::new(Some(rx)))
    }

    /// The receiver, the first time it is asked for.
    pub fn take(&self) -> Option<tokio::sync::mpsc::UnboundedReceiver<ActionTaken>> {
        self.0.lock().ok()?.take()
    }
}

#[cfg(target_os = "linux")]
pub use xdg::{NotifyRustBackend, XdgPoster};

#[cfg(target_os = "linux")]
mod xdg {
    use super::*;
    use std::collections::VecDeque;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Arc, Mutex, MutexGuard};

    /// How many threads may wait on a button at once. Past it a note is posted
    /// without buttons (the reader still opens it by clicking). It counts live
    /// *threads*, not entries: a waiter whose close never arrives (a server that
    /// restarted) still holds its slot, so the cap bounds threads and bus
    /// connections whatever the server does.
    pub const MAX_WAITERS: usize = 16;

    /// One slot of the cap. Dropping it, including by a panic unwinding the
    /// waiter's thread, gives the slot back.
    struct Slot(Arc<AtomicUsize>);

    impl Slot {
        fn take(live: &Arc<AtomicUsize>) -> Option<Slot> {
            live.fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| {
                (n < MAX_WAITERS).then_some(n + 1)
            })
            .ok()
            .map(|_| Slot(Arc::clone(live)))
        }
    }

    impl Drop for Slot {
        fn drop(&mut self) {
            self.0.fetch_sub(1, Ordering::SeqCst);
        }
    }

    /// How many posted notes are remembered for replacement. Past this the oldest
    /// is forgotten (a note that old has long been seen).
    const MAX_KNOWN: usize = 256;

    /// What a press reports: the note and room, plus what the core put in the
    /// note's actions. The core gives every action of a note the same info, so the
    /// first stands for all of them.
    fn user_info_for(note: &PostedNote) -> Value {
        let mut info = serde_json::Map::new();
        if let Some(action) = note.actions.first() {
            for (k, v) in &action.user_info {
                info.insert(k.clone(), Value::String(v.clone()));
            }
        }
        info.insert("id".into(), Value::String(note.id.clone()));
        info.insert("roomId".into(), Value::String(note.room_id.clone()));
        Value::Object(info)
    }

    /// What the reader did with a notification.
    #[derive(Debug, Clone, PartialEq, Eq)]
    pub enum Outcome {
        Action(String),
        Closed,
    }

    /// What is sent to the notification server.
    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct XdgRequest {
        /// The server's id of the notification to replace.
        pub replaces: Option<u32>,
        pub summary: String,
        pub body: String,
        pub actions: Vec<(String, String)>,
    }

    /// A shown notification, held so it can be closed.
    pub trait XdgHandle: Send {
        fn id(&self) -> u32;
        fn close(self: Box<Self>);
    }

    /// The notification server. [`NotifyRustBackend`] is the real one.
    pub trait XdgBackend: Send + Sync + 'static {
        /// Whether the server advertises the `actions` capability.
        fn supports_actions(&self) -> bool;
        fn show(&self, request: &XdgRequest) -> Result<Box<dyn XdgHandle>, String>;
        /// Blocks until notification `id` is acted on or closed.
        fn wait(&self, id: u32) -> Outcome;
    }

    struct Known {
        note_id: String,
        server_id: u32,
        user_info: Value,
        waiting: bool,
        handle: Box<dyn XdgHandle>,
    }

    struct State {
        /// Oldest first.
        known: VecDeque<Known>,
    }

    pub struct XdgPoster {
        backend: Arc<dyn XdgBackend>,
        /// Read once: the server does not change under a running app.
        actions: bool,
        state: Arc<Mutex<State>>,
        sender: ActionSender,
        /// Threads waiting on a button right now.
        live: Arc<AtomicUsize>,
    }

    fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
        m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    impl XdgPoster {
        pub fn new(backend: Arc<dyn XdgBackend>, sender: ActionSender) -> Self {
            let actions = backend.supports_actions();
            Self {
                backend,
                actions,
                state: Arc::new(Mutex::new(State {
                    known: VecDeque::new(),
                })),
                sender,
                live: Arc::new(AtomicUsize::new(0)),
            }
        }

        /// How many threads are waiting on a button right now.
        #[cfg(test)]
        pub fn live_waiters(&self) -> usize {
            self.live.load(Ordering::SeqCst)
        }

        /// Whether buttons are offered on this desktop.
        #[cfg(test)]
        pub fn offers_actions(&self) -> bool {
            self.actions
        }

        fn request(&self, note: &PostedNote, replaces: Option<u32>, buttons: bool) -> XdgRequest {
            let mut actions = Vec::new();
            if buttons {
                actions.push((DEFAULT_ACTION.to_owned(), DEFAULT_ACTION_LABEL.to_owned()));
                actions.extend(
                    note.actions
                        .iter()
                        .filter(|a| !a.text_input)
                        .map(|a| (a.id.clone(), a.title.clone())),
                );
            }
            XdgRequest {
                replaces,
                summary: note.title.clone(),
                body: note.body.clone(),
                actions,
            }
        }

        /// Waits for the reader on a thread of its own; when they act, sends the
        /// action, and in every case forgets the notification.
        fn spawn_waiter(&self, server_id: u32, slot: Slot) {
            let backend = Arc::clone(&self.backend);
            let state = Arc::clone(&self.state);
            let sender = self.sender.clone();
            let work = move || {
                let _slot = slot;
                // A bus failure inside the backend must not leave the entry behind.
                let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    backend.wait(server_id)
                }))
                .unwrap_or(Outcome::Closed);
                let gone = {
                    let mut state = lock(&state);
                    state
                        .known
                        .iter()
                        .position(|k| k.server_id == server_id)
                        .and_then(|i| state.known.remove(i))
                };
                // A closed notification reports nothing; only a press does.
                if let (Outcome::Action(action_id), Some(known)) = (outcome, gone) {
                    let _ = sender.send(ActionTaken {
                        action_id,
                        user_info: known.user_info,
                    });
                }
            };
            // A dedicated thread, not the shared blocking pool: a waiter can sit for
            // as long as the notification does, and 16 of them would starve the pool.
            let _ = std::thread::Builder::new()
                .name("notification-waiter".into())
                .spawn(work);
        }
    }

    impl Poster for XdgPoster {
        fn post(&self, note: &PostedNote) -> Result<(), String> {
            let (replaces, replaces_waiting) = {
                let state = lock(&self.state);
                let known = state.known.iter().find(|k| k.note_id == note.id);
                (known.map(|k| k.server_id), known.is_some_and(|k| k.waiting))
            };
            // A repost of a note that already has a waiter keeps that waiter. Any
            // other note with buttons needs a slot, and without one it is posted
            // without buttons.
            let slot = if self.actions && !replaces_waiting {
                Slot::take(&self.live)
            } else {
                None
            };
            let buttons = replaces_waiting || slot.is_some();
            let handle = self.backend.show(&self.request(note, replaces, buttons))?;
            let server_id = handle.id();
            let user_info = user_info_for(note);

            {
                let mut state = lock(&self.state);
                if let Some(i) = state.known.iter().position(|k| k.server_id == server_id) {
                    state.known.remove(i);
                }
                state.known.push_back(Known {
                    note_id: note.id.clone(),
                    server_id,
                    user_info,
                    waiting: buttons,
                    handle,
                });
                while state.known.len() > MAX_KNOWN {
                    state.known.pop_front();
                }
            }
            if let Some(slot) = slot {
                self.spawn_waiter(server_id, slot);
            }
            Ok(())
        }

        fn withdraw_all(&self) {
            let all: Vec<Known> = lock(&self.state).known.drain(..).collect();
            for known in all {
                known.handle.close();
            }
        }
    }

    /// The real server, through `notify-rust` (the plugin's own Linux backend).
    pub struct NotifyRustBackend;

    struct RealHandle(notify_rust::NotificationHandle);

    impl XdgHandle for RealHandle {
        fn id(&self) -> u32 {
            self.0.id()
        }
        fn close(self: Box<Self>) {
            self.0.close();
        }
    }

    impl XdgBackend for NotifyRustBackend {
        fn supports_actions(&self) -> bool {
            notify_rust::get_capabilities()
                .map(|caps| caps.iter().any(|c| c == "actions"))
                .unwrap_or(false)
        }

        fn show(&self, request: &XdgRequest) -> Result<Box<dyn XdgHandle>, String> {
            let mut n = notify_rust::Notification::new();
            n.appname("supermessage")
                .summary(&request.summary)
                .body(&request.body)
                .hint(notify_rust::Hint::Category("im.received".to_owned()));
            for (id, label) in &request.actions {
                n.action(id, label);
            }
            if let Some(id) = request.replaces {
                n.id(id);
            }
            n.show()
                .map(|h| Box::new(RealHandle(h)) as Box<dyn XdgHandle>)
                .map_err(|e| e.to_string())
        }

        fn wait(&self, id: u32) -> Outcome {
            // `handle_action`, not `NotificationHandle::wait_for_action`: that one
            // consumes the handle, and the handle is what closes the notification.
            let mut outcome = Outcome::Closed;
            let _ = notify_rust::handle_action(id, |response| {
                if let notify_rust::ActionResponse::Custom(action) = response {
                    outcome = Outcome::Action((*action).to_owned());
                }
            });
            outcome
        }
    }
}

/// macOS and Windows: informational notifications through the plugin.
#[cfg(not(target_os = "linux"))]
pub struct PluginPoster<R: tauri::Runtime> {
    app: tauri::AppHandle<R>,
}

#[cfg(not(target_os = "linux"))]
impl<R: tauri::Runtime> PluginPoster<R> {
    pub fn new(app: tauri::AppHandle<R>) -> Self {
        Self { app }
    }
}

#[cfg(not(target_os = "linux"))]
impl<R: tauri::Runtime> Poster for PluginPoster<R> {
    fn post(&self, note: &PostedNote) -> Result<(), String> {
        use tauri_plugin_notification::NotificationExt;
        self.app
            .notification()
            .builder()
            .title(&note.title)
            .body(&note.body)
            .show()
            .map_err(|e| e.to_string())
    }

    fn withdraw_all(&self) {
        use tauri_plugin_notification::NotificationExt;
        let _ = self.app.notification().remove_all_active();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn body_with_subtitle_leads_with_the_subtitle() {
        assert_eq!(
            body_with_subtitle(Some("Build agent"), "Ship it?"),
            "Build agent\nShip it?"
        );
        assert_eq!(body_with_subtitle(None, "Ship it?"), "Ship it?");
        assert_eq!(body_with_subtitle(Some("  "), "Ship it?"), "Ship it?");
    }

    #[cfg(target_os = "linux")]
    mod linux {
        use super::super::xdg::{
            Outcome, XdgBackend, XdgHandle, XdgPoster, XdgRequest, MAX_WAITERS,
        };
        use super::super::*;
        use serde_json::json;
        use std::collections::HashMap;
        use std::sync::mpsc::{channel, Receiver, Sender};
        use std::sync::{Arc, Mutex};
        use std::time::Duration;

        /// A notification server that shows nothing and records everything.
        struct FakeServer {
            actions: bool,
            next: Mutex<u32>,
            shown: Mutex<Vec<XdgRequest>>,
            closed: Mutex<Vec<u32>>,
            /// What each blocked `wait` is waiting on: press, or close.
            gates: Mutex<HashMap<u32, Sender<Outcome>>>,
            waiters: Mutex<HashMap<u32, Receiver<Outcome>>>,
            /// Makes `wait` panic, as a failing bus connection can.
            panic_in_wait: std::sync::atomic::AtomicBool,
        }

        impl FakeServer {
            fn new(actions: bool) -> Arc<Self> {
                Arc::new(Self {
                    actions,
                    next: Mutex::new(100),
                    shown: Mutex::new(vec![]),
                    closed: Mutex::new(vec![]),
                    gates: Mutex::new(HashMap::new()),
                    waiters: Mutex::new(HashMap::new()),
                    panic_in_wait: std::sync::atomic::AtomicBool::new(false),
                })
            }
            fn press(&self, id: u32, action: &str) {
                if let Some(tx) = self.gates.lock().unwrap().get(&id) {
                    let _ = tx.send(Outcome::Action(action.into()));
                }
            }
        }

        struct FakeHandle {
            id: u32,
            server: Arc<FakeServer>,
        }

        impl XdgHandle for FakeHandle {
            fn id(&self) -> u32 {
                self.id
            }
            fn close(self: Box<Self>) {
                self.server.closed.lock().unwrap().push(self.id);
                if let Some(tx) = self.server.gates.lock().unwrap().get(&self.id) {
                    let _ = tx.send(Outcome::Closed);
                }
            }
        }

        struct Backend(Arc<FakeServer>);

        impl XdgBackend for Backend {
            fn supports_actions(&self) -> bool {
                self.0.actions
            }
            fn show(&self, request: &XdgRequest) -> Result<Box<dyn XdgHandle>, String> {
                self.0.shown.lock().unwrap().push(request.clone());
                let id = match request.replaces {
                    Some(id) => id,
                    None => {
                        let mut next = self.0.next.lock().unwrap();
                        *next += 1;
                        *next
                    }
                };
                let mut gates = self.0.gates.lock().unwrap();
                if let std::collections::hash_map::Entry::Vacant(slot) = gates.entry(id) {
                    let (tx, rx) = channel();
                    slot.insert(tx);
                    self.0.waiters.lock().unwrap().insert(id, rx);
                }
                Ok(Box::new(FakeHandle {
                    id,
                    server: Arc::clone(&self.0),
                }))
            }
            fn wait(&self, id: u32) -> Outcome {
                if self
                    .0
                    .panic_in_wait
                    .load(std::sync::atomic::Ordering::SeqCst)
                {
                    panic!("the bus went away");
                }
                let rx = self
                    .0
                    .waiters
                    .lock()
                    .unwrap()
                    .remove(&id)
                    .expect("one waiter");
                rx.recv_timeout(Duration::from_secs(5))
                    .unwrap_or(Outcome::Closed)
            }
        }

        fn note(id: &str, actions: Vec<PostedAction>) -> PostedNote {
            PostedNote {
                id: id.into(),
                room_id: "!r:x".into(),
                title: "Build agent".into(),
                body: "Ship it?".into(),
                actions,
            }
        }

        fn button(id: &str, text_input: bool) -> PostedAction {
            PostedAction {
                id: id.into(),
                title: id.into(),
                text_input,
                user_info: Default::default(),
            }
        }

        fn poster(
            actions: bool,
        ) -> (
            XdgPoster,
            Arc<FakeServer>,
            tokio::sync::mpsc::UnboundedReceiver<ActionTaken>,
        ) {
            let server = FakeServer::new(actions);
            let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
            (
                XdgPoster::new(Arc::new(Backend(Arc::clone(&server))), tx),
                server,
                rx,
            )
        }

        fn wait_until(what: &str, mut ok: impl FnMut() -> bool) {
            for _ in 0..500 {
                if ok() {
                    return;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            panic!("timed out waiting for {what}");
        }

        #[test]
        fn a_repost_with_the_same_id_replaces() {
            let (poster, server, _rx) = poster(true);
            poster.post(&note("$a", vec![])).unwrap();
            poster.post(&note("$a", vec![])).unwrap();
            poster.post(&note("$b", vec![])).unwrap();
            let shown = server.shown.lock().unwrap();
            assert_eq!(shown[0].replaces, None);
            assert_eq!(
                shown[1].replaces,
                Some(101),
                "the same id must replace the first"
            );
            assert_eq!(
                shown[2].replaces, None,
                "a different id is a new notification"
            );
        }

        #[test]
        fn without_the_actions_capability_no_buttons_are_added() {
            let (poster, server, _rx) = poster(false);
            poster
                .post(&note("$a", vec![button("Allow once", false)]))
                .unwrap();
            assert!(!poster.offers_actions());
            assert!(server.shown.lock().unwrap()[0].actions.is_empty());
        }

        #[test]
        fn with_the_capability_open_leads_and_each_plain_action_follows() {
            let (poster, server, _rx) = poster(true);
            poster
                .post(&note("$a", vec![button("Allow once", false)]))
                .unwrap();
            assert_eq!(
                server.shown.lock().unwrap()[0].actions,
                vec![
                    ("default".to_owned(), "Open".to_owned()),
                    ("Allow once".to_owned(), "Allow once".to_owned()),
                ]
            );
        }

        #[test]
        fn a_text_input_action_is_never_a_button() {
            let (poster, server, _rx) = poster(true);
            poster
                .post(&note(
                    "$a",
                    vec![button("approve", false), button("Request changes", true)],
                ))
                .unwrap();
            let ids: Vec<String> = server.shown.lock().unwrap()[0]
                .actions
                .iter()
                .map(|(id, _)| id.clone())
                .collect();
            assert_eq!(ids, ["default", "approve"]);
        }

        #[test]
        fn a_pressed_button_is_sent_with_its_note() {
            let (poster, server, mut rx) = poster(true);
            poster
                .post(&note("$a", vec![button("approve", false)]))
                .unwrap();
            server.press(101, "approve");
            let mut taken = None;
            wait_until("the action", || {
                taken = rx.try_recv().ok();
                taken.is_some()
            });
            let taken = taken.unwrap();
            assert_eq!(taken.action_id, "approve");
            assert_eq!(taken.user_info, json!({ "id": "$a", "roomId": "!r:x" }));
        }

        #[test]
        fn a_press_carries_what_the_core_put_in_the_action() {
            let (poster, server, mut rx) = poster(true);
            let mut action = button("approve", false);
            action
                .user_info
                .insert("sm.allowOptionId".into(), "allow_once".into());
            poster.post(&note("$a", vec![action])).unwrap();
            server.press(101, "approve");
            let mut taken = None;
            wait_until("the action", || {
                taken = rx.try_recv().ok();
                taken.is_some()
            });
            assert_eq!(
                taken.unwrap().user_info,
                json!({ "id": "$a", "roomId": "!r:x", "sm.allowOptionId": "allow_once" })
            );
        }

        #[test]
        fn the_live_waiters_never_exceed_the_cap_and_return_to_zero() {
            let (poster, server, _rx) = poster(true);
            for i in 0..MAX_WAITERS {
                poster.post(&note(&format!("$n{i}"), vec![])).unwrap();
            }
            assert_eq!(poster.live_waiters(), MAX_WAITERS);
            poster.post(&note("$last", vec![])).unwrap();
            assert_eq!(poster.live_waiters(), MAX_WAITERS, "the cap holds");
            let shown = server.shown.lock().unwrap();
            assert_eq!(shown.len(), MAX_WAITERS + 1);
            assert!(
                shown[MAX_WAITERS].actions.is_empty(),
                "past the cap a note has no buttons"
            );
            assert!(shown[0].actions.len() == 1, "under the cap it has Open");
            drop(shown);
            assert!(
                server.closed.lock().unwrap().is_empty(),
                "nothing is evicted"
            );
            poster.withdraw_all();
            wait_until("every waiter to end", || poster.live_waiters() == 0);
        }

        #[test]
        fn a_waiter_that_panics_gives_back_its_slot_and_leaves_no_entry() {
            let (poster, server, _rx) = poster(true);
            server
                .panic_in_wait
                .store(true, std::sync::atomic::Ordering::SeqCst);
            poster.post(&note("$a", vec![])).unwrap();
            wait_until("the slot to return", || poster.live_waiters() == 0);
            // No stale entry: the same id is a new notification, not a replacement.
            server
                .panic_in_wait
                .store(false, std::sync::atomic::Ordering::SeqCst);
            poster.post(&note("$a", vec![])).unwrap();
            assert_eq!(server.shown.lock().unwrap()[1].replaces, None);
        }

        /// Manual: posts two LOCAL notifications to the desktop's real server, the
        /// second replacing the first, then withdraws. `cargo test -- --ignored real_server`.
        #[test]
        #[ignore = "posts to the real desktop notification server"]
        fn real_server_posts_replaces_and_withdraws() {
            use super::super::xdg::NotifyRustBackend;
            let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
            let poster = XdgPoster::new(Arc::new(NotifyRustBackend), tx);
            println!("server offers actions: {}", poster.offers_actions());
            let mut n = note(
                "$real",
                vec![button("approve", false), button("Request changes", true)],
            );
            n.body = body_with_subtitle(Some("Local test"), "First text");
            poster.post(&n).unwrap();
            std::thread::sleep(Duration::from_secs(2));
            n.body = body_with_subtitle(Some("Local test"), "Replaced text");
            poster.post(&n).unwrap();
            std::thread::sleep(Duration::from_secs(3));
            poster.withdraw_all();
        }

        #[test]
        fn withdraw_all_closes_every_handle() {
            let (poster, server, _rx) = poster(true);
            poster.post(&note("$a", vec![])).unwrap();
            poster.post(&note("$b", vec![])).unwrap();
            poster.withdraw_all();
            let mut closed = server.closed.lock().unwrap().clone();
            closed.sort();
            assert_eq!(closed, vec![101, 102]);
        }
    }
}
