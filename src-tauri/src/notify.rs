//! Where a desktop notification is posted and withdrawn (parity M6, P2).
//!
//! The core decides what a notification says, what it offers and what an answer
//! means (`core::notification_policy`). This file decides only what the *platform*
//! decides: which OS surface carries it, and on Linux whether the notification
//! server can show buttons at all.
//!
//! - **Linux** speaks the Desktop Notifications protocol itself ([`XdgPoster`] over
//!   [`SessionBusBackend`]), because Linux is where answer buttons are wanted (open
//!   question Q4). A button is offered only when the server advertises the
//!   `actions` capability; otherwise the note is informational and the reader
//!   answers in the app. One session-bus connection carries every note and hears
//!   every press, so a remembered note costs only its ids.
//! - **macOS and Windows** post through `tauri-plugin-notification`
//!   ([`PluginPoster`]) and are informational.
//!
//! The webview is granted no `notification:*` permission: it asks Rust to post.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

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
    /// What the core says the note carries (`notification_policy::user_info`): the room,
    /// the event, and the option ids an answer needs. It is the note's own, whatever its
    /// actions, as iOS puts it on the content (`LocalNotifier.swift:51`), and it comes back
    /// with every press, the click on the note itself included.
    #[serde(default)]
    pub user_info: HashMap<String, String>,
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

/// An action performed on a notification: what the reader pressed, and which note.
#[derive(Debug, Clone, PartialEq)]
pub struct ActionTaken {
    pub action_id: String,
    /// The note's own info, as the core gave it ([`PostedNote::user_info`]).
    pub user_info: HashMap<String, String>,
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
pub use xdg::{SessionBusBackend, XdgPoster};

#[cfg(target_os = "linux")]
mod xdg {
    use super::*;
    use std::collections::VecDeque;
    use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

    use supermessage_core::notification::NotificationCategory;
    use supermessage_core::notification_policy::{self, ACTION_OPEN};

    /// The identifier of "the notification itself was clicked" in the XDG protocol.
    pub const DEFAULT_ACTION: &str = "default";

    /// How many posted notes are remembered, for replacement, for a press, and to be
    /// withdrawn. Past this the oldest is closed: a note that old has long been seen,
    /// and closing it is what keeps `withdraw_all` from leaving one behind.
    pub const MAX_KNOWN: usize = 256;

    /// The label of the click on the note itself: the core's own Open action
    /// (`LocalNotifier.swift:44-45`). Servers rarely draw it.
    fn open_label() -> String {
        notification_policy::actions(NotificationCategory::Decision)
            .into_iter()
            .find(|a| a.id == ACTION_OPEN)
            .map(|a| a.title)
            .unwrap_or_default()
    }

    /// What the notification server reports.
    #[derive(Debug, Clone, PartialEq, Eq)]
    pub enum Signal {
        /// `ActionInvoked`: the reader pressed `action` on notification `id`.
        Action { id: u32, action: String },
        /// `NotificationClosed`: notification `id` is gone, however it went.
        Closed { id: u32 },
    }

    /// Where the server's signals go.
    pub type SignalSink = Arc<dyn Fn(Signal) + Send + Sync>;

    /// What is sent to the notification server.
    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct XdgRequest {
        /// The server's id of the notification to replace.
        pub replaces: Option<u32>,
        pub summary: String,
        pub body: String,
        pub actions: Vec<(String, String)>,
    }

    /// The notification server. [`SessionBusBackend`] is the real one.
    pub trait XdgBackend: Send + Sync + 'static {
        /// Starts handing every signal of the server to `sink`. Called once, before
        /// anything is shown, so no press is missed. False when nothing can be heard,
        /// and then no note is given buttons.
        fn listen(&self, sink: SignalSink) -> bool;
        /// Whether the server advertises the `actions` capability.
        fn supports_actions(&self) -> bool;
        /// Shows the notification; returns the server's id for it.
        fn show(&self, request: &XdgRequest) -> Result<u32, String>;
        /// Closes notification `id`. Blocks for one round trip.
        fn close(&self, id: u32);
    }

    /// A note on screen, by its ids only.
    struct Known {
        note_id: String,
        server_id: u32,
        user_info: HashMap<String, String>,
    }

    #[derive(Default)]
    struct State {
        /// Oldest first.
        known: VecDeque<Known>,
    }

    impl State {
        fn forget(&mut self, server_id: u32) -> Option<Known> {
            let i = self.known.iter().position(|k| k.server_id == server_id)?;
            self.known.remove(i)
        }
    }

    pub struct XdgPoster {
        backend: Arc<dyn XdgBackend>,
        /// Whether buttons are offered, read once with the subscription in place:
        /// the server does not change under a running app.
        actions: OnceLock<bool>,
        state: Arc<Mutex<State>>,
        sender: ActionSender,
    }

    fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
        m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// A press is reported only for a note still remembered, and only once: that is
    /// what makes [`Poster::withdraw_all`] a barrier, since it forgets every note
    /// before closing them.
    fn on_signal(state: &Mutex<State>, sender: &ActionSender, signal: Signal) {
        match signal {
            Signal::Closed { id } => {
                lock(state).forget(id);
            }
            Signal::Action { id, action } => {
                let known = lock(state).forget(id);
                if let Some(known) = known {
                    let _ = sender.send(ActionTaken {
                        action_id: action,
                        user_info: known.user_info,
                    });
                }
            }
        }
    }

    impl XdgPoster {
        /// Touches nothing yet: the bus is reached on the first post, off the main thread.
        pub fn new(backend: Arc<dyn XdgBackend>, sender: ActionSender) -> Self {
            Self {
                backend,
                actions: OnceLock::new(),
                state: Arc::new(Mutex::new(State::default())),
                sender,
            }
        }

        /// Subscribes to the server on first use; whether buttons are offered.
        fn ready(&self) -> bool {
            *self.actions.get_or_init(|| {
                let state = Arc::clone(&self.state);
                let sender = self.sender.clone();
                let sink: SignalSink = Arc::new(move |signal| on_signal(&state, &sender, signal));
                self.backend.listen(sink) && self.backend.supports_actions()
            })
        }

        /// Whether buttons are offered on this desktop.
        #[cfg(test)]
        pub fn offers_actions(&self) -> bool {
            self.ready()
        }

        /// How many notes are remembered.
        #[cfg(test)]
        pub fn remembered(&self) -> usize {
            lock(&self.state).known.len()
        }

        /// The server ids of the remembered notes, oldest first.
        #[cfg(test)]
        pub fn remembered_ids(&self) -> Vec<u32> {
            lock(&self.state)
                .known
                .iter()
                .map(|k| k.server_id)
                .collect()
        }

        fn request(note: &PostedNote, replaces: Option<u32>, buttons: bool) -> XdgRequest {
            let mut actions = Vec::new();
            if buttons {
                actions.push((DEFAULT_ACTION.to_owned(), open_label()));
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
    }

    impl Poster for XdgPoster {
        fn post(&self, note: &PostedNote) -> Result<(), String> {
            let buttons = self.ready();
            let evicted: Vec<u32> = {
                // Held while the note is sent, so a press that comes back at once waits
                // for its entry instead of being missed.
                let mut state = lock(&self.state);
                let replaces = state
                    .known
                    .iter()
                    .find(|k| k.note_id == note.id)
                    .map(|k| k.server_id);
                let server_id = self.backend.show(&Self::request(note, replaces, buttons))?;
                state
                    .known
                    .retain(|k| k.note_id != note.id && k.server_id != server_id);
                state.known.push_back(Known {
                    note_id: note.id.clone(),
                    server_id,
                    user_info: note.user_info.clone(),
                });
                let excess = state.known.len().saturating_sub(MAX_KNOWN);
                state.known.drain(..excess).map(|k| k.server_id).collect()
            };
            for id in evicted {
                self.backend.close(id);
            }
            Ok(())
        }

        fn withdraw_all(&self) {
            // Forgotten first: a press that lands while they close reports nothing.
            let all: Vec<u32> = lock(&self.state)
                .known
                .drain(..)
                .map(|k| k.server_id)
                .collect();
            for id in all {
                self.backend.close(id);
            }
        }
    }

    /// The bus name, object path and interface of the Desktop Notifications Specification.
    const BUS: (&str, &str, &str) = (
        "org.freedesktop.Notifications",
        "/org/freedesktop/Notifications",
        "org.freedesktop.Notifications",
    );

    /// The real server, over one session-bus connection opened on first use. Every note
    /// is sent on it and every signal heard on it: some servers (dunst) signal only the
    /// connection that sent a note, and some (GNOME) close a note once that connection
    /// goes away. `tauri-plugin-notification` 2.7 does the same on Linux
    /// (`desktop.rs`, `Tracker`).
    pub struct SessionBusBackend {
        bus: OnceLock<Option<zbus::blocking::Connection>>,
    }

    impl SessionBusBackend {
        pub fn new() -> Self {
            Self {
                bus: OnceLock::new(),
            }
        }

        fn connection(&self) -> Option<&zbus::blocking::Connection> {
            self.bus
                .get_or_init(|| zbus::blocking::Connection::session().ok())
                .as_ref()
        }

        fn call<B>(&self, method: &str, body: &B) -> zbus::Result<zbus::message::Message>
        where
            B: serde::Serialize + zbus::zvariant::DynamicType,
        {
            let connection = self
                .connection()
                .ok_or_else(|| zbus::Error::Failure("no session bus".into()))?;
            let (destination, path, interface) = BUS;
            connection.call_method(Some(destination), path, Some(interface), method, body)
        }
    }

    /// The server's signal as a [`Signal`]; anything else is `None`.
    fn parse(message: &zbus::message::Message) -> Option<Signal> {
        let header = message.header();
        let body = message.body();
        match header.member()?.as_str() {
            "ActionInvoked" => {
                let (id, action) = body.deserialize::<(u32, String)>().ok()?;
                Some(Signal::Action { id, action })
            }
            "NotificationClosed" => {
                let (id, _reason) = body.deserialize::<(u32, u32)>().ok()?;
                Some(Signal::Closed { id })
            }
            _ => None,
        }
    }

    impl XdgBackend for SessionBusBackend {
        fn listen(&self, sink: SignalSink) -> bool {
            let Some(connection) = self.connection() else {
                return false;
            };
            let (_, path, interface) = BUS;
            let Ok(rule) = zbus::MatchRule::builder()
                .msg_type(zbus::message::Type::Signal)
                .path(path)
                .and_then(|b| b.interface(interface))
                .map(|b| b.build())
            else {
                return false;
            };
            // Subscribed before this returns, so a press on the first note is heard.
            let Ok(signals) =
                zbus::blocking::MessageIterator::for_match_rule(rule, connection, None)
            else {
                return false;
            };
            std::thread::Builder::new()
                .name("notification-signals".into())
                .spawn(move || {
                    for message in signals.flatten() {
                        if let Some(signal) = parse(&message) {
                            // A panicking sink must not stop the signals of every other note.
                            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                sink(signal)
                            }));
                        }
                    }
                })
                .is_ok()
        }

        fn supports_actions(&self) -> bool {
            self.call("GetCapabilities", &())
                .and_then(|reply| reply.body().deserialize::<Vec<String>>())
                .map(|caps| caps.iter().any(|c| c == "actions"))
                .unwrap_or(false)
        }

        fn show(&self, request: &XdgRequest) -> Result<u32, String> {
            let actions: Vec<&str> = request
                .actions
                .iter()
                .flat_map(|(id, label)| [id.as_str(), label.as_str()])
                .collect();
            let hints: HashMap<&str, zbus::zvariant::Value<'_>> =
                HashMap::from([("category", zbus::zvariant::Value::from("im.received"))]);
            self.call(
                "Notify",
                &(
                    "supermessage",
                    request.replaces.unwrap_or(0),
                    "",
                    request.summary.as_str(),
                    request.body.as_str(),
                    actions,
                    hints,
                    -1i32,
                ),
            )
            .and_then(|reply| reply.body().deserialize::<u32>())
            .map_err(|e| e.to_string())
        }

        fn close(&self, id: u32) {
            // Never opens the bus just to close: nothing was shown without it.
            if self.bus.get().and_then(Option::as_ref).is_some() {
                let _ = self.call("CloseNotification", &(id,));
            }
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

/// A fake notification server for the tests of this file and of `notify_actions`, which
/// drive the real [`XdgPoster`]'s bookkeeping end to end.
#[cfg(all(test, target_os = "linux"))]
pub(crate) mod fake {
    use super::xdg::{Signal, SignalSink, XdgBackend, XdgPoster, XdgRequest};
    use super::ActionTaken;
    use std::sync::{Arc, Mutex};

    /// A notification server that shows nothing and records everything.
    pub struct FakeServer {
        pub actions: bool,
        /// Whether a subscription can be made (a session bus exists).
        pub can_listen: bool,
        pub next: Mutex<u32>,
        pub shown: Mutex<Vec<XdgRequest>>,
        pub closed: Mutex<Vec<u32>>,
        /// "listen" and "show", in the order they happened.
        pub calls: Mutex<Vec<&'static str>>,
        /// Closes without reporting them, as a server that restarted would.
        pub silent_closes: std::sync::atomic::AtomicBool,
        sink: Mutex<Option<SignalSink>>,
    }

    impl FakeServer {
        pub fn new(actions: bool) -> Arc<Self> {
            Self::with(actions, true)
        }
        /// A server whose signals cannot be subscribed to.
        pub fn unheard(actions: bool) -> Arc<Self> {
            Self::with(actions, false)
        }
        fn with(actions: bool, can_listen: bool) -> Arc<Self> {
            Arc::new(Self {
                actions,
                can_listen,
                next: Mutex::new(100),
                shown: Mutex::new(vec![]),
                closed: Mutex::new(vec![]),
                calls: Mutex::new(vec![]),
                silent_closes: Default::default(),
                sink: Mutex::new(None),
            })
        }
        /// The server id of the newest notification shown.
        pub fn last_id(&self) -> u32 {
            *self.next.lock().unwrap()
        }
        fn signal(&self, signal: Signal) {
            let sink = self.sink.lock().unwrap().clone();
            if let Some(sink) = sink {
                sink(signal);
            }
        }
        /// The reader presses `action` on notification `id`.
        pub fn press(&self, id: u32, action: &str) {
            self.signal(Signal::Action {
                id,
                action: action.into(),
            });
        }
        /// The reader (or the server) closes notification `id`.
        pub fn dismiss(&self, id: u32) {
            self.signal(Signal::Closed { id });
        }
    }

    pub struct Backend(pub Arc<FakeServer>);

    impl XdgBackend for Backend {
        fn listen(&self, sink: SignalSink) -> bool {
            self.0.calls.lock().unwrap().push("listen");
            if self.0.can_listen {
                *self.0.sink.lock().unwrap() = Some(sink);
            }
            self.0.can_listen
        }
        fn supports_actions(&self) -> bool {
            self.0.actions
        }
        fn show(&self, request: &XdgRequest) -> Result<u32, String> {
            self.0.calls.lock().unwrap().push("show");
            self.0.shown.lock().unwrap().push(request.clone());
            Ok(match request.replaces {
                Some(id) => id,
                None => {
                    let mut next = self.0.next.lock().unwrap();
                    *next += 1;
                    *next
                }
            })
        }
        fn close(&self, id: u32) {
            self.0.closed.lock().unwrap().push(id);
            // A real server reports every close it makes.
            if !self
                .0
                .silent_closes
                .load(std::sync::atomic::Ordering::SeqCst)
            {
                self.0.dismiss(id);
            }
        }
    }

    pub fn poster_on(
        server: Arc<FakeServer>,
    ) -> (
        XdgPoster,
        Arc<FakeServer>,
        tokio::sync::mpsc::UnboundedReceiver<ActionTaken>,
    ) {
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        (
            XdgPoster::new(Arc::new(Backend(Arc::clone(&server))), tx),
            server,
            rx,
        )
    }

    pub fn poster(
        actions: bool,
    ) -> (
        XdgPoster,
        Arc<FakeServer>,
        tokio::sync::mpsc::UnboundedReceiver<ActionTaken>,
    ) {
        poster_on(FakeServer::new(actions))
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
        use super::super::fake::{poster, poster_on, FakeServer};
        use super::super::xdg::{SessionBusBackend, XdgPoster, MAX_KNOWN};
        use super::super::*;
        use std::sync::Arc;
        use std::time::Duration;

        fn note(id: &str, actions: Vec<PostedAction>) -> PostedNote {
            PostedNote {
                id: id.into(),
                room_id: "!r:x".into(),
                title: "Build agent".into(),
                body: "Ship it?".into(),
                actions,
                user_info: HashMap::from([("sm.roomId".to_owned(), "!r:x".to_owned())]),
            }
        }

        fn button(id: &str, text_input: bool) -> PostedAction {
            PostedAction {
                id: id.into(),
                title: id.into(),
                text_input,
            }
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
            drop(shown);
            assert_eq!(poster.remembered(), 2, "a replacement is one note");
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
        fn with_nothing_to_hear_a_press_no_buttons_are_added() {
            let server = FakeServer::unheard(true);
            let (poster, server, _rx) = poster_on(server);
            poster
                .post(&note("$a", vec![button("Allow once", false)]))
                .unwrap();
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
        fn the_subscription_is_made_once_and_before_the_first_note() {
            let (poster, server, _rx) = poster(true);
            for i in 0..5 {
                poster.post(&note(&format!("$n{i}"), vec![])).unwrap();
            }
            let calls = server.calls.lock().unwrap();
            assert_eq!(calls[0], "listen");
            assert_eq!(calls.iter().filter(|c| **c == "listen").count(), 1);
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
            let taken = rx.try_recv().expect("the press is reported");
            assert_eq!(taken.action_id, "approve");
            assert_eq!(
                taken.user_info,
                HashMap::from([("sm.roomId".to_owned(), "!r:x".to_owned())])
            );
            server.press(101, "approve");
            assert!(rx.try_recv().is_err(), "a note answers once");
        }

        #[test]
        fn a_press_carries_what_the_core_put_on_the_note() {
            let (poster, server, mut rx) = poster(true);
            let mut posted = note("$a", vec![button("approve", false)]);
            posted
                .user_info
                .insert("sm.allowOptionId".into(), "allow_once".into());
            poster.post(&posted).unwrap();
            server.press(101, "approve");
            assert_eq!(rx.try_recv().unwrap().user_info, posted.user_info);
        }

        #[test]
        fn a_press_on_another_apps_notification_reports_nothing() {
            let (poster, server, mut rx) = poster(true);
            poster.post(&note("$a", vec![])).unwrap();
            server.press(7, "default");
            assert!(rx.try_recv().is_err());
        }

        #[test]
        fn every_note_has_its_buttons_however_many_are_on_screen() {
            // No thread waits per note any more, so nothing caps which notes can be pressed.
            let (poster, server, _rx) = poster(true);
            for i in 0..40 {
                poster
                    .post(&note(&format!("$n{i}"), vec![button("approve", false)]))
                    .unwrap();
            }
            let shown = server.shown.lock().unwrap();
            assert!(shown.iter().all(|r| r.actions.len() == 2));
        }

        #[test]
        fn a_closed_note_is_forgotten() {
            let (poster, server, mut rx) = poster(true);
            poster.post(&note("$a", vec![])).unwrap();
            server.dismiss(101);
            assert_eq!(poster.remembered(), 0);
            server.press(101, "default");
            assert!(rx.try_recv().is_err(), "a closed note reports nothing");
            poster.post(&note("$a", vec![])).unwrap();
            assert_eq!(
                server.shown.lock().unwrap()[1].replaces,
                None,
                "a closed note is not replaced, it is posted again"
            );
        }

        #[test]
        fn withdraw_all_closes_every_note() {
            let (poster, server, _rx) = poster(true);
            poster.post(&note("$a", vec![])).unwrap();
            poster.post(&note("$b", vec![])).unwrap();
            poster.withdraw_all();
            let mut closed = server.closed.lock().unwrap().clone();
            closed.sort();
            assert_eq!(closed, vec![101, 102]);
            assert_eq!(poster.remembered(), 0);
        }

        #[test]
        fn a_press_after_withdraw_all_reports_nothing() {
            let (poster, server, mut rx) = poster(true);
            // Without the server's word that it closed, so only the poster's own
            // forgetting stands between the press and an answer.
            server
                .silent_closes
                .store(true, std::sync::atomic::Ordering::SeqCst);
            poster.post(&note("$a", vec![])).unwrap();
            poster.withdraw_all();
            server.press(101, "default");
            assert!(rx.try_recv().is_err());
        }

        #[test]
        fn past_the_remembered_limit_the_oldest_is_closed_so_withdraw_leaves_none() {
            let (poster, server, _rx) = poster(true);
            for i in 0..=MAX_KNOWN {
                poster.post(&note(&format!("$n{i}"), vec![])).unwrap();
            }
            assert_eq!(poster.remembered(), MAX_KNOWN);
            assert_eq!(*server.closed.lock().unwrap(), vec![101], "the oldest");
            poster.withdraw_all();
            assert_eq!(server.closed.lock().unwrap().len(), MAX_KNOWN + 1);
        }

        /// Manual: posts LOCAL notifications to the desktop's real server, the second
        /// replacing the first, then withdraws; then posts one more and closes it behind
        /// the poster's back, and waits to hear the server's `NotificationClosed` on the
        /// shared connection. `cargo test -- --ignored real_server`.
        #[test]
        #[ignore = "posts to the real desktop notification server"]
        fn real_server_posts_replaces_and_withdraws() {
            use super::super::xdg::XdgBackend;
            let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
            let backend = Arc::new(SessionBusBackend::new());
            let poster = XdgPoster::new(backend.clone(), tx);
            println!("server offers actions: {}", poster.offers_actions());
            let mut n = note(
                "$real",
                vec![button("approve", false), button("Request changes", true)],
            );
            n.body = body_with_subtitle(Some("Local test"), "First text");
            poster.post(&n).unwrap();
            let first = poster.remembered_ids();
            std::thread::sleep(Duration::from_secs(2));
            n.body = body_with_subtitle(Some("Local test"), "Replaced text");
            poster.post(&n).unwrap();
            println!(
                "ids: first {first:?}, after replace {:?}",
                poster.remembered_ids()
            );
            assert_eq!(poster.remembered(), 1);
            std::thread::sleep(Duration::from_secs(2));
            poster.withdraw_all();
            assert_eq!(poster.remembered(), 0);

            poster.post(&note("$closed", vec![])).unwrap();
            let id = poster.remembered_ids()[0];
            backend.close(id);
            for _ in 0..100 {
                if poster.remembered() == 0 {
                    println!("heard NotificationClosed for {id}");
                    return;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            panic!("the server's NotificationClosed for {id} was never heard");
        }
    }
}
