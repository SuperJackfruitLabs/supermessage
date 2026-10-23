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
    /// The account (Matrix user id) signed in when the note was posted. A press on the
    /// note acts only while that account is still the one signed in, so a note that
    /// outlived its sign-out can never act on the next account.
    #[serde(default)]
    pub account: Option<String>,
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
/// Reported on Linux only (see `PluginPoster`).
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
#[derive(Debug, Clone, PartialEq)]
pub struct ActionTaken {
    pub action_id: String,
    /// The note's own info, as the core gave it ([`PostedNote::user_info`]).
    pub user_info: HashMap<String, String>,
    /// Who the note was posted for ([`PostedNote::account`]).
    pub account: Option<String>,
}

/// Receives the buttons the reader presses (Linux); `notify_actions` consumes them.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
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
    use std::time::Duration;

    use supermessage_core::notification::NotificationCategory;
    use supermessage_core::notification_policy::{self, ACTION_OPEN};

    /// The identifier of "the notification itself was clicked" in the XDG protocol.
    pub const DEFAULT_ACTION: &str = "default";

    /// How many posted notes are remembered, for replacement, for a press, and to be
    /// withdrawn. Past this the oldest is closed: a note that old has long been seen,
    /// and closing it is what keeps `withdraw_all` from leaving one behind.
    pub const MAX_KNOWN: usize = 256;

    /// How long one call to the session bus may take. A server that is alive but not
    /// answering (a frozen plasmashell or gnome-shell) then costs a post this long, and
    /// quit at most this long, instead of both for ever.
    pub const METHOD_TIMEOUT: Duration = Duration::from_secs(5);

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
        /// Another process now serves notifications (the server restarted). The old
        /// server's notes went with it, and the new one numbers its own from 1 again.
        ServerChanged,
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
        /// Closes notification `id`. Blocks for one round trip. False only when the
        /// server did not answer in time; an error answer (the note was already gone)
        /// still counts as an answer.
        fn close(&self, id: u32) -> bool;
    }

    /// A note on screen, by its ids only.
    struct Known {
        note_id: String,
        server_id: u32,
        user_info: HashMap<String, String>,
        account: Option<String>,
        /// Pressed once already. It is kept, so that `withdraw_all` still closes it on a
        /// server that leaves a note up after a press, but it answers nothing more.
        answered: bool,
    }

    #[derive(Default)]
    struct State {
        /// Oldest first.
        known: VecDeque<Known>,
        /// Moves on whenever every note is let go of (withdrawn, or gone with its server),
        /// so a note whose post was under way then is closed instead of remembered.
        generation: u64,
    }

    impl State {
        fn forget(&mut self, server_id: u32) -> Option<Known> {
            let i = self.known.iter().position(|k| k.server_id == server_id)?;
            self.known.remove(i)
        }

        fn forget_all(&mut self) -> Vec<u32> {
            self.generation += 1;
            self.known.drain(..).map(|k| k.server_id).collect()
        }
    }

    pub struct XdgPoster {
        backend: Arc<dyn XdgBackend>,
        /// Whether buttons are offered, read once with the subscription in place:
        /// the server does not change under a running app.
        actions: OnceLock<bool>,
        /// What is on screen. Never held across a call to the server: the listener takes
        /// it for every signal, and must never wait on the server's reply to a post.
        state: Arc<Mutex<State>>,
        /// One post at a time, so two posts of one note cannot both miss the note they
        /// replace. Only posts take it; neither the listener nor `withdraw_all` does.
        posting: Mutex<()>,
        sender: ActionSender,
        /// Posts that have begun (tests wait on it to know one is queued).
        #[cfg(test)]
        started: std::sync::atomic::AtomicUsize,
    }

    fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
        m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// A press is reported only for a note still remembered, and only once: that is
    /// what makes [`Poster::withdraw_all`] a barrier, since it forgets every note
    /// before closing them. The lock is held only for the bookkeeping.
    fn on_signal(state: &Mutex<State>, sender: &ActionSender, signal: Signal) {
        match signal {
            Signal::Closed { id } => {
                lock(state).forget(id);
            }
            Signal::ServerChanged => {
                lock(state).forget_all();
            }
            Signal::Action { id, action } => {
                let taken = {
                    let mut state = lock(state);
                    let Some(known) = state
                        .known
                        .iter_mut()
                        .find(|k| k.server_id == id && !k.answered)
                    else {
                        return;
                    };
                    known.answered = true;
                    ActionTaken {
                        action_id: action,
                        user_info: known.user_info.clone(),
                        account: known.account.clone(),
                    }
                };
                let _ = sender.send(taken);
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
                posting: Mutex::new(()),
                sender,
                #[cfg(test)]
                started: Default::default(),
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

        /// How many posts have begun.
        #[cfg(test)]
        pub fn started(&self) -> usize {
            self.started.load(std::sync::atomic::Ordering::SeqCst)
        }

        /// A sink onto this poster's own bookkeeping, as the backend would be given.
        #[cfg(test)]
        pub fn sink(&self) -> SignalSink {
            let (state, sender) = (Arc::clone(&self.state), self.sender.clone());
            Arc::new(move |signal| on_signal(&state, &sender, signal))
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
            // Read before anything can make this post wait (the subscription, a post ahead
            // of it): a sign-out that lands meanwhile moves it, and the note is then
            // closed instead of remembered, so no old account's text outlives its sign-out.
            let generation = lock(&self.state).generation;
            #[cfg(test)]
            self.started
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            let buttons = self.ready();
            let _posting = lock(&self.posting);
            let replaces = lock(&self.state)
                .known
                .iter()
                .find(|k| k.note_id == note.id)
                .map(|k| k.server_id);
            // Sent with the state unlocked. A press on this note cannot come back before
            // the reply does (nobody can press a note within one round trip), and holding
            // the lock here would park the listener behind the server's reply: with zbus's
            // bounded signal queue full, that reply is never read.
            let server_id = self.backend.show(&Self::request(note, replaces, buttons))?;
            let close: Vec<u32> = {
                let mut state = lock(&self.state);
                if state.generation != generation {
                    // Withdrawn, or its server replaced, while it was being shown.
                    vec![server_id]
                } else {
                    state
                        .known
                        .retain(|k| k.note_id != note.id && k.server_id != server_id);
                    state.known.push_back(Known {
                        note_id: note.id.clone(),
                        server_id,
                        user_info: note.user_info.clone(),
                        account: note.account.clone(),
                        answered: false,
                    });
                    let excess = state.known.len().saturating_sub(MAX_KNOWN);
                    state.known.drain(..excess).map(|k| k.server_id).collect()
                }
            };
            for id in close {
                self.backend.close(id);
            }
            Ok(())
        }

        fn withdraw_all(&self) {
            // Forgotten first: a press that lands while they close reports nothing, and a
            // post under way closes its own note when it returns.
            let all = lock(&self.state).forget_all();
            for id in all {
                // A server that does not answer one close will not answer the rest; quit
                // waits for one timeout, not one per note.
                if !self.backend.close(id) {
                    break;
                }
            }
        }
    }

    /// The bus name, object path and interface of the Desktop Notifications Specification.
    const BUS: (&str, &str, &str) = (
        "org.freedesktop.Notifications",
        "/org/freedesktop/Notifications",
        "org.freedesktop.Notifications",
    );

    /// The bus's error for a well-known name nobody owns.
    const NAME_HAS_NO_OWNER: &str = "org.freedesktop.DBus.Error.NameHasNoOwner";

    /// The message bus itself: its name (which is also its interface) and object path.
    const DBUS: (&str, &str) = ("org.freedesktop.DBus", "/org/freedesktop/DBus");

    /// Opens the connection, before the timeout is put on it.
    type Connect =
        Box<dyn Fn() -> zbus::Result<zbus::blocking::connection::Builder<'static>> + Send + Sync>;

    /// The real server, over one session-bus connection opened on first use. Every note
    /// is sent on it and every signal heard on it: some servers (dunst) signal only the
    /// connection that sent a note, and some (GNOME) close a note once that connection
    /// goes away. `tauri-plugin-notification` 2.7 does the same on Linux
    /// (`desktop.rs`, `Tracker`).
    pub struct SessionBusBackend {
        connect: Connect,
        timeout: Duration,
        bus: OnceLock<Option<zbus::blocking::Connection>>,
    }

    impl SessionBusBackend {
        pub fn new() -> Self {
            Self::over(
                Box::new(zbus::blocking::connection::Builder::session),
                METHOD_TIMEOUT,
            )
        }

        /// Over the connection `connect` opens, every call bounded by `timeout`.
        pub(super) fn over(connect: Connect, timeout: Duration) -> Self {
            Self {
                connect,
                timeout,
                bus: OnceLock::new(),
            }
        }

        fn connection(&self) -> Option<&zbus::blocking::Connection> {
            self.bus
                .get_or_init(|| {
                    (self.connect)()
                        .and_then(|b| b.method_timeout(self.timeout).build())
                        .ok()
                })
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

        /// Whether anyone owns the well-known `name` on the session bus now (the bus's own
        /// `NameHasOwner`), on this backend's connection and bounded by its timeout. Blocks for
        /// one round trip: never call it on the main thread.
        pub fn name_has_owner(&self, name: &str) -> zbus::Result<bool> {
            let connection = self
                .connection()
                .ok_or_else(|| zbus::Error::Failure("no session bus".into()))?;
            let (bus, path) = DBUS;
            connection
                .call_method(Some(bus), path, Some(bus), "NameHasOwner", &(name,))?
                .body()
                .deserialize::<bool>()
        }

        /// The unique name that owns the server's well-known name now: `Ok(None)` when
        /// the bus says nobody does (the server starts on the first note), an error for
        /// any other failure (a timeout, a refusal), which says nothing about an owner.
        fn name_owner(connection: &zbus::blocking::Connection) -> zbus::Result<Option<String>> {
            let (bus, path) = DBUS;
            match connection.call_method(Some(bus), path, Some(bus), "GetNameOwner", &(BUS.0,)) {
                Ok(reply) => reply.body().deserialize::<String>().map(Some),
                Err(zbus::Error::MethodError(name, ..)) if name.as_str() == NAME_HAS_NO_OWNER => {
                    Ok(None)
                }
                Err(e) => Err(e),
            }
        }
    }

    /// Which process is the notification server: the unique name owning its well-known
    /// name. Only its signals are its; anyone on the session bus can send the same
    /// signal, addressed to us, with a forged note id.
    #[derive(Debug, Default)]
    pub(super) struct ServerOwner {
        name: Option<String>,
        /// A change was heard, so a start-up `GetNameOwner` reply is older than it.
        heard_change: bool,
    }

    impl ServerOwner {
        #[cfg(test)]
        pub(super) fn of(name: &str) -> Self {
            Self {
                name: Some(name.into()),
                heard_change: false,
            }
        }

        #[cfg(test)]
        pub(super) fn name(&self) -> Option<&str> {
            self.name.as_deref()
        }
    }

    /// The server's signal as a [`Signal`], if the server sent it; anything else is `None`.
    pub(super) fn from_server(
        owner: &Mutex<ServerOwner>,
        message: &zbus::message::Message,
    ) -> Option<Signal> {
        let header = message.header();
        let sender = header.sender()?;
        if lock(owner).name.as_deref() != Some(sender.as_str()) {
            return None;
        }
        if header.interface()?.as_str() != BUS.2 {
            return None;
        }
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

    /// Follows the bus's `NameOwnerChanged` for the server's name. A change from one
    /// server to another (or to none) is [`Signal::ServerChanged`]; the first server
    /// appearing (started on demand by the first note) is not, since nothing was shown
    /// before it.
    pub(super) fn owner_changed(
        owner: &Mutex<ServerOwner>,
        message: &zbus::message::Message,
        on_change: &dyn Fn(Signal),
    ) {
        let Some(new) = new_owner(message) else {
            return;
        };
        let mut owner = lock(owner);
        owner.heard_change = true;
        if owner.name.is_some() && owner.name != new {
            // A panicking sink must not leave the old owner recorded.
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                on_change(Signal::ServerChanged)
            }));
        }
        owner.name = new;
    }

    /// Who owns the server's name now, if `message` is the bus saying so: `Some(None)`
    /// for nobody.
    fn new_owner(message: &zbus::message::Message) -> Option<Option<String>> {
        let header = message.header();
        let (bus, _) = DBUS;
        if header.sender()?.as_str() != bus
            || header.interface()?.as_str() != bus
            || header.member()?.as_str() != "NameOwnerChanged"
        {
            return None;
        }
        let (name, _old, new) = message
            .body()
            .deserialize::<(String, String, String)>()
            .ok()?;
        (name == BUS.0).then_some((!new.is_empty()).then_some(new))
    }

    /// Hands what `read` makes of each message to `sink`, on a thread of its own. The
    /// thread waits on nothing but short locks, so zbus's bounded queue is always drained
    /// and the connection's reader never stalls behind it.
    fn drain<F>(
        name: &str,
        messages: zbus::blocking::MessageIterator,
        read: F,
        sink: SignalSink,
    ) -> bool
    where
        F: Fn(&zbus::message::Message) -> Option<Signal> + Send + 'static,
    {
        std::thread::Builder::new()
            .name(name.into())
            .spawn(move || {
                for message in messages.flatten() {
                    if let Some(signal) = read(&message) {
                        // A panicking sink must not stop the signals of every other note.
                        let _ =
                            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| sink(signal)));
                    }
                }
            })
            .is_ok()
    }

    impl XdgBackend for SessionBusBackend {
        fn listen(&self, sink: SignalSink) -> bool {
            let Some(connection) = self.connection() else {
                return false;
            };
            let (name, path, interface) = BUS;
            let (bus, bus_path) = DBUS;
            // The bus applies `sender` to broadcast signals only, and zbus cannot apply a
            // well-known name at all, so each signal's sender is checked again on arrival.
            let signals = zbus::MatchRule::builder()
                .msg_type(zbus::message::Type::Signal)
                .sender(name)
                .and_then(|b| b.path(path))
                .and_then(|b| b.interface(interface))
                .map(|b| b.build());
            let changes = zbus::MatchRule::builder()
                .msg_type(zbus::message::Type::Signal)
                .sender(bus)
                .and_then(|b| b.path(bus_path))
                .and_then(|b| b.interface(bus))
                .and_then(|b| b.member("NameOwnerChanged"))
                .and_then(|b| b.arg(0, name))
                .map(|b| b.build());
            let (Ok(signals), Ok(changes)) = (signals, changes) else {
                return false;
            };
            // Subscribed before this returns, so a press on the first note is heard.
            let iterate =
                |rule| zbus::blocking::MessageIterator::for_match_rule(rule, connection, None);
            let (Ok(signals), Ok(changes)) = (iterate(signals), iterate(changes)) else {
                return false;
            };
            let owner = Arc::new(Mutex::new(ServerOwner::default()));
            let heard = Arc::clone(&owner);
            let followed = Arc::clone(&owner);
            let forget = Arc::clone(&sink);
            if !(drain(
                "notification-signals",
                signals,
                move |m| from_server(&heard, m),
                Arc::clone(&sink),
            ) && drain(
                "notification-owner",
                changes,
                move |m| {
                    owner_changed(&followed, m, &*forget);
                    None
                },
                sink,
            )) {
                return false;
            }
            // Asked only once both queues are being drained, so no burst of signals can
            // hold the reply up.
            let current = match Self::name_owner(connection) {
                Ok(current) => current,
                // Asked once more: a transient failure must not leave every press
                // unattributable while buttons are offered.
                Err(first) => match Self::name_owner(connection) {
                    Ok(current) => current,
                    Err(second) => {
                        tracing::warn!(
                            %first, %second,
                            "could not learn who serves notifications; posting without buttons"
                        );
                        return false;
                    }
                },
            };
            let mut owner = lock(&owner);
            if !owner.heard_change {
                owner.name = current;
            }
            true
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

        fn close(&self, id: u32) -> bool {
            // Never opens the bus just to close: nothing was shown without it.
            if self.bus.get().and_then(Option::as_ref).is_none() {
                return true;
            }
            !matches!(
                self.call("CloseNotification", &(id,)),
                Err(zbus::Error::InputOutput(e)) if e.kind() == std::io::ErrorKind::TimedOut
            )
        }
    }
}

/// macOS and Windows: informational notifications through the plugin.
///
/// Two known limits, both the plugin's (`tauri-plugin-notification` 2.7.0,
/// `desktop.rs`): it cannot remove a shown notification there, by id or all at once,
/// so sign-out leaves the last account's notes in Notification Center / Action Center
/// (RF5); and since no action handler is registered, a click reports nothing, so such a
/// note can never act on the next account (and, through [`PostedNote::account`] and
/// `notify_actions::handle`, would not if one were).
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
        // A no-op on macOS and Windows today (see above); kept so a plugin that learns to
        // remove notes there does so without a change here.
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
        /// When set, the next `show` waits for a message on it before it answers.
        pub held: Mutex<Option<std::sync::mpsc::Receiver<()>>>,
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
                held: Mutex::new(None),
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
        /// Another process takes over as the notification server.
        pub fn restart(&self) {
            self.signal(Signal::ServerChanged);
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
            let held = self.0.held.lock().unwrap().take();
            if let Some(release) = held {
                let _ = release.recv();
            }
            Ok(match request.replaces {
                Some(id) => id,
                None => {
                    let mut next = self.0.next.lock().unwrap();
                    *next += 1;
                    *next
                }
            })
        }
        fn close(&self, id: u32) -> bool {
            self.0.closed.lock().unwrap().push(id);
            // A real server reports every close it makes.
            if !self
                .0
                .silent_closes
                .load(std::sync::atomic::Ordering::SeqCst)
            {
                self.0.dismiss(id);
            }
            true
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
        use super::super::xdg::{
            from_server, owner_changed, ServerOwner, SessionBusBackend, Signal, XdgPoster,
            MAX_KNOWN,
        };
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
                account: Some("@me:x".into()),
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
            let taken = rx.try_recv().unwrap();
            assert_eq!(taken.user_info, posted.user_info);
            assert_eq!(
                taken.account.as_deref(),
                Some("@me:x"),
                "and who it was for"
            );
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

        #[test]
        fn a_pressed_note_answers_once_but_is_still_closed_on_withdraw() {
            let (poster, server, mut rx) = poster(true);
            server
                .silent_closes
                .store(true, std::sync::atomic::Ordering::SeqCst);
            poster.post(&note("$a", vec![])).unwrap();
            server.press(101, "default");
            assert!(rx.try_recv().is_ok());
            server.press(101, "default");
            assert!(rx.try_recv().is_err(), "a note answers once");
            poster.withdraw_all();
            assert_eq!(
                *server.closed.lock().unwrap(),
                vec![101],
                "a server may keep a pressed note up; sign-out still takes it down"
            );
        }

        #[test]
        fn a_new_server_forgets_every_note() {
            let (poster, server, mut rx) = poster(true);
            poster.post(&note("$a", vec![])).unwrap();
            poster.post(&note("$b", vec![])).unwrap();
            server.restart();
            assert_eq!(poster.remembered(), 0);
            // The new server numbers from 1 again: its 101 is someone else's note.
            server.press(101, "default");
            assert!(rx.try_recv().is_err());
            poster.post(&note("$a", vec![])).unwrap();
            assert_eq!(
                server.shown.lock().unwrap()[2].replaces,
                None,
                "the old server's id is not the new one's to replace"
            );
        }

        #[test]
        fn a_post_queued_before_sign_out_leaves_nothing_behind() {
            let (poster, server, _rx) = poster(true);
            let poster = Arc::new(poster);
            let (release, held) = std::sync::mpsc::channel();
            *server.held.lock().unwrap() = Some(held);
            let first = std::thread::spawn({
                let poster = Arc::clone(&poster);
                move || poster.post(&note("$a", vec![]))
            });
            while server.shown.lock().unwrap().is_empty() {
                std::thread::yield_now();
            }
            // $b is queued behind $a, for the account about to sign out.
            let second = std::thread::spawn({
                let poster = Arc::clone(&poster);
                move || poster.post(&note("$b", vec![]))
            });
            while poster.started() < 2 {
                std::thread::yield_now();
            }
            poster.withdraw_all();
            release.send(()).unwrap();
            first.join().unwrap().unwrap();
            second.join().unwrap().unwrap();
            assert_eq!(poster.remembered(), 0, "neither note outlives the sign-out");
            let closed = server.closed.lock().unwrap().clone();
            assert!(
                closed.contains(&101) && closed.contains(&102),
                "$a and the queued $b are both closed: {closed:?}"
            );
        }

        /// Runs `f` on its own thread; `None` if it has not returned within `limit`, so a
        /// regression fails the test instead of hanging it.
        fn within<T: Send + 'static>(
            limit: Duration,
            f: impl FnOnce() -> T + Send + 'static,
        ) -> Option<T> {
            let (tx, rx) = std::sync::mpsc::channel();
            std::thread::spawn(move || {
                let _ = tx.send(f());
            });
            rx.recv_timeout(limit).ok()
        }

        #[test]
        fn a_post_waiting_on_the_server_holds_up_neither_a_press_nor_withdraw_all() {
            let (poster, server, rx) = poster(true);
            let poster = Arc::new(poster);
            let rx = Arc::new(std::sync::Mutex::new(rx));
            poster.post(&note("$a", vec![])).unwrap();
            let (release, held) = std::sync::mpsc::channel();
            *server.held.lock().unwrap() = Some(held);
            let posting = std::thread::spawn({
                let poster = Arc::clone(&poster);
                move || poster.post(&note("$b", vec![]))
            });
            while server.shown.lock().unwrap().len() < 2 {
                std::thread::yield_now();
            }

            let pressed = within(Duration::from_secs(2), {
                let (server, rx) = (Arc::clone(&server), Arc::clone(&rx));
                move || {
                    server.press(101, "default");
                    rx.lock().unwrap().try_recv().is_ok()
                }
            });
            assert_eq!(pressed, Some(true), "the press is heard while $b is shown");
            let withdrawn = within(Duration::from_secs(2), {
                let poster = Arc::clone(&poster);
                move || poster.withdraw_all()
            });
            assert!(withdrawn.is_some(), "withdraw_all does not wait for $b");

            release.send(()).unwrap();
            posting.join().unwrap().unwrap();
            assert_eq!(
                poster.remembered(),
                0,
                "$b was withdrawn while it was shown"
            );
            assert!(
                server.closed.lock().unwrap().contains(&102),
                "so it is closed as soon as its id is known"
            );
        }

        /// Signals as the bus delivers them, for [`from_server`] and [`owner_changed`].
        fn signal<B>(
            sender: &str,
            path: &str,
            interface: &str,
            member: &str,
            body: &B,
        ) -> zbus::message::Message
        where
            B: serde::Serialize + zbus::zvariant::DynamicType,
        {
            zbus::message::Message::signal(path, interface, member)
                .unwrap()
                .sender(sender)
                .unwrap()
                .build(body)
                .unwrap()
        }

        const NOTIFICATIONS: (&str, &str) = (
            "/org/freedesktop/Notifications",
            "org.freedesktop.Notifications",
        );

        fn from(
            sender: &str,
            member: &str,
            body: &(u32, impl serde::Serialize + zbus::zvariant::Type),
        ) -> zbus::message::Message {
            let (path, interface) = NOTIFICATIONS;
            signal(sender, path, interface, member, body)
        }

        fn owner_change(sender: &str, name: &str, old: &str, new: &str) -> zbus::message::Message {
            signal(
                sender,
                "/org/freedesktop/DBus",
                "org.freedesktop.DBus",
                "NameOwnerChanged",
                &(name, old, new),
            )
        }

        #[test]
        fn only_the_servers_own_signals_are_heard() {
            let owner = std::sync::Mutex::new(ServerOwner::of(":1.7"));
            assert_eq!(
                from_server(&owner, &from(":1.7", "ActionInvoked", &(4, "approve"))),
                Some(Signal::Action {
                    id: 4,
                    action: "approve".into()
                })
            );
            assert_eq!(
                from_server(&owner, &from(":1.7", "NotificationClosed", &(4, 2u32))),
                Some(Signal::Closed { id: 4 })
            );
            assert_eq!(
                from_server(&owner, &from(":1.99", "ActionInvoked", &(4, "approve"))),
                None,
                "another process can send the same signal; it is not the server"
            );
            assert_eq!(
                from_server(&owner, &from(":1.99", "NotificationClosed", &(4, 2u32))),
                None
            );
            assert_eq!(
                from_server(&owner, &from(":1.7", "ActionInvoked", &(4, 2u32))),
                None,
                "ActionInvoked is (us)"
            );
            assert_eq!(
                from_server(&owner, &from(":1.7", "NotificationClosed", &(4, "x"))),
                None,
                "NotificationClosed is (uu)"
            );
            let nobody = std::sync::Mutex::new(ServerOwner::default());
            assert_eq!(
                from_server(&nobody, &from(":1.7", "ActionInvoked", &(4, "approve"))),
                None,
                "with no server known, nothing is the server's"
            );
        }

        /// What `owner_changed` reports for `message`, as the signals it handed on.
        fn followed(owner: &std::sync::Mutex<ServerOwner>, m: zbus::message::Message) -> usize {
            let heard = std::sync::atomic::AtomicUsize::new(0);
            owner_changed(owner, &m, &|signal| {
                assert_eq!(signal, Signal::ServerChanged);
                heard.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            });
            heard.into_inner()
        }

        #[test]
        fn the_server_is_followed_across_a_restart() {
            const BUS: &str = "org.freedesktop.DBus";
            const NAME: &str = "org.freedesktop.Notifications";
            let owner = std::sync::Mutex::new(ServerOwner::of(":1.7"));

            assert_eq!(
                followed(&owner, owner_change(":1.99", NAME, ":1.7", ":1.99")),
                0,
                "only the bus says who owns a name"
            );
            assert_eq!(
                followed(&owner, owner_change(BUS, "org.example.Other", "", ":1.5")),
                0
            );
            assert_eq!(owner.lock().unwrap().name(), Some(":1.7"));

            assert_eq!(followed(&owner, owner_change(BUS, NAME, ":1.7", ":1.8")), 1);
            assert_eq!(owner.lock().unwrap().name(), Some(":1.8"));
            assert_eq!(
                from_server(&owner, &from(":1.7", "ActionInvoked", &(4, "approve"))),
                None,
                "the old server is not the server any more"
            );
            assert!(from_server(&owner, &from(":1.8", "ActionInvoked", &(4, "approve"))).is_some());

            assert_eq!(
                followed(&owner, owner_change(BUS, NAME, ":1.8", "")),
                1,
                "the server went away"
            );
            assert_eq!(owner.lock().unwrap().name(), None);
            assert_eq!(
                followed(&owner, owner_change(BUS, NAME, "", ":1.9")),
                0,
                "a server starting where there was none had shown nothing"
            );
            assert_eq!(owner.lock().unwrap().name(), Some(":1.9"));
        }

        #[test]
        fn a_press_on_the_new_servers_note_cannot_slip_in_before_the_old_notes_are_forgotten() {
            let (poster, server, mut rx) = poster(true);
            poster.post(&note("$a", vec![])).unwrap();
            let id = server.last_id();
            let owner = Arc::new(std::sync::Mutex::new(ServerOwner::of(":1.7")));
            let sink = poster.sink();
            let (started_tx, started_rx) = std::sync::mpsc::channel();
            let change = std::thread::spawn({
                let owner = Arc::clone(&owner);
                move || {
                    owner_changed(
                        &owner,
                        &owner_change(
                            "org.freedesktop.DBus",
                            "org.freedesktop.Notifications",
                            ":1.7",
                            ":1.8",
                        ),
                        &|signal| {
                            // The window: the change is under way, the notes not yet forgotten.
                            started_tx.send(()).unwrap();
                            std::thread::sleep(Duration::from_millis(300));
                            sink(signal);
                        },
                    );
                }
            });
            started_rx.recv().unwrap();
            // The new server's first signal recycles the id of the old server's note.
            if let Some(Signal::Action { id, action }) =
                from_server(&owner, &from(":1.8", "ActionInvoked", &(id, "default")))
            {
                poster.sink()(Signal::Action { id, action });
            }
            change.join().unwrap();
            assert!(
                rx.try_recv().is_err(),
                "a click on another app's note must not open a room"
            );
        }

        /// The real [`SessionBusBackend`] against a notification server on a private
        /// socket (D-Bus peer to peer), which answers as a bus and a server would.
        mod peer {
            use super::super::super::xdg::SessionBusBackend;
            use std::collections::{HashMap, VecDeque};
            use std::os::unix::net::UnixStream;
            use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
            use std::sync::{Arc, Mutex};
            use std::time::Duration;

            /// The unique name the fake bus says owns `org.freedesktop.Notifications`.
            pub const OWNER: &str = ":1.7";

            struct Notifications {
                hung: Arc<AtomicBool>,
                /// How many calls the hung server has taken and is holding.
                held: Arc<AtomicU32>,
                next: AtomicU32,
            }

            #[zbus::interface(name = "org.freedesktop.Notifications")]
            impl Notifications {
                #[allow(clippy::too_many_arguments)]
                async fn notify(
                    &self,
                    _app_name: String,
                    replaces_id: u32,
                    _app_icon: String,
                    _summary: String,
                    _body: String,
                    _actions: Vec<String>,
                    _hints: HashMap<String, zbus::zvariant::OwnedValue>,
                    _expire_timeout: i32,
                ) -> u32 {
                    if self.hung.load(Ordering::SeqCst) {
                        self.held.fetch_add(1, Ordering::SeqCst);
                        std::future::pending::<()>().await;
                    }
                    match replaces_id {
                        0 => self.next.fetch_add(1, Ordering::SeqCst) + 1,
                        id => id,
                    }
                }

                async fn close_notification(&self, _id: u32) {
                    if self.hung.load(Ordering::SeqCst) {
                        std::future::pending::<()>().await;
                    }
                }

                fn get_capabilities(&self) -> Vec<String> {
                    vec!["actions".into()]
                }
            }

            /// What the fake bus answers to a `GetNameOwner`.
            #[derive(Clone, Copy)]
            pub enum OwnerReply {
                /// Fails the way a timeout or a refusal would.
                Failed,
                /// The bus's own "nobody owns that name".
                NoOwner,
            }

            struct Bus {
                /// Answers taken in turn; once spent, the server is owned by [`OWNER`].
                script: Arc<Mutex<VecDeque<OwnerReply>>>,
                /// What `NameHasOwner` answers: whether the name is owned, or `None` to fail.
                has_owner: Arc<Mutex<Option<bool>>>,
            }

            #[zbus::interface(name = "org.freedesktop.DBus")]
            impl Bus {
                fn name_has_owner(&self, _name: String) -> zbus::fdo::Result<bool> {
                    (*self.has_owner.lock().unwrap())
                        .ok_or_else(|| zbus::fdo::Error::Failed("no".into()))
                }

                fn get_name_owner(&self, _name: String) -> zbus::fdo::Result<String> {
                    match self.script.lock().unwrap().pop_front() {
                        None => Ok(OWNER.into()),
                        Some(OwnerReply::Failed) => Err(zbus::fdo::Error::Failed("no".into())),
                        Some(OwnerReply::NoOwner) => {
                            Err(zbus::fdo::Error::NameHasNoOwner("nobody".into()))
                        }
                    }
                }
            }

            pub struct Peer {
                /// While set, the server takes every call and never answers it.
                pub hung: Arc<AtomicBool>,
                /// How many `Notify` calls the hung server is holding.
                pub held: Arc<AtomicU32>,
                /// What `GetNameOwner` answers next; set before the first call.
                pub owner_script: Arc<Mutex<VecDeque<OwnerReply>>>,
                /// What `NameHasOwner` answers; `None` fails the call.
                pub has_owner: Arc<Mutex<Option<bool>>>,
                server: std::thread::JoinHandle<zbus::blocking::Connection>,
            }

            /// A backend whose every call is bounded by `timeout`, and its server. The
            /// connection is made on the backend's first call.
            pub fn start(timeout: Duration) -> (SessionBusBackend, Peer) {
                let (client, server) = UnixStream::pair().unwrap();
                let hung = Arc::new(AtomicBool::new(false));
                let held = Arc::new(AtomicU32::new(0));
                let notifications = Notifications {
                    hung: Arc::clone(&hung),
                    held: Arc::clone(&held),
                    next: AtomicU32::new(0),
                };
                let owner_script = Arc::new(Mutex::new(VecDeque::new()));
                let has_owner = Arc::new(Mutex::new(Some(true)));
                let bus = Bus {
                    script: Arc::clone(&owner_script),
                    has_owner: Arc::clone(&has_owner),
                };
                let server = std::thread::spawn(move || {
                    zbus::blocking::connection::Builder::async_io_unix_stream(server)
                        .server(zbus::Guid::generate())
                        .unwrap()
                        .p2p()
                        .serve_at("/org/freedesktop/Notifications", notifications)
                        .unwrap()
                        .serve_at("/org/freedesktop/DBus", bus)
                        .unwrap()
                        .build()
                        .unwrap()
                });
                let client = Mutex::new(Some(client));
                let backend = SessionBusBackend::over(
                    Box::new(move || {
                        let stream =
                            client.lock().unwrap().take().ok_or_else(|| {
                                zbus::Error::Failure("one connection only".into())
                            })?;
                        Ok(zbus::blocking::connection::Builder::async_io_unix_stream(stream).p2p())
                    }),
                    timeout,
                );
                (
                    backend,
                    Peer {
                        hung,
                        held,
                        owner_script,
                        has_owner,
                        server,
                    },
                )
            }

            impl Peer {
                /// The server's end, once the backend has connected.
                pub fn connected(self) -> (zbus::blocking::Connection, Arc<AtomicBool>) {
                    (self.server.join().unwrap(), self.hung)
                }
            }

            /// Sends a signal from `sender`, as the bus would stamp it.
            pub fn emit<B>(
                server: &zbus::blocking::Connection,
                sender: &str,
                (path, interface): (&str, &str),
                member: &str,
                body: &B,
            ) where
                B: serde::Serialize + zbus::zvariant::DynamicType,
            {
                let message = zbus::message::Message::signal(path, interface, member)
                    .unwrap()
                    .sender(sender)
                    .unwrap()
                    .build(body)
                    .unwrap();
                server.send(&message).unwrap();
            }
        }

        /// Polls `done` for up to two seconds.
        fn eventually(mut done: impl FnMut() -> bool) -> bool {
            for _ in 0..200 {
                if done() {
                    return true;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            false
        }

        #[test]
        fn a_server_that_never_answers_costs_one_timeout_and_stops_nothing_else() {
            const TIMEOUT: Duration = Duration::from_millis(800);
            let (backend, peer) = peer::start(TIMEOUT);
            let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
            let poster = Arc::new(XdgPoster::new(Arc::new(backend), tx));
            for id in ["$a", "$b", "$c", "$e", "$f"] {
                poster.post(&note(id, vec![])).unwrap();
            }
            let held = Arc::clone(&peer.held);
            let (server, hung) = peer.connected();
            hung.store(true, std::sync::atomic::Ordering::SeqCst);

            let started = std::time::Instant::now();
            let posting = std::thread::spawn({
                let poster = Arc::clone(&poster);
                move || poster.post(&note("$d", vec![]))
            });
            // The press is sent only once the server is wedged on $d's Notify, so the post is
            // certainly waiting when it comes.
            assert!(
                eventually(|| held.load(std::sync::atomic::Ordering::SeqCst) > 0),
                "the server holds $d's Notify"
            );
            // The server is wedged on $d's Notify, but its signals still come.
            peer::emit(
                &server,
                peer::OWNER,
                NOTIFICATIONS,
                "ActionInvoked",
                &(1u32, "default"),
            );
            let rx = Arc::new(std::sync::Mutex::new(rx));
            let heard = eventually({
                let rx = Arc::clone(&rx);
                move || rx.lock().unwrap().try_recv().is_ok()
            });
            assert!(heard, "a press is heard while a post waits on the server");
            assert!(
                started.elapsed() < TIMEOUT,
                "heard before the post's call timed out, not after: {:?}",
                started.elapsed()
            );

            let posted = within(TIMEOUT * 5, move || posting.join().unwrap());
            let posted = posted.expect("the post returns once the call times out");
            assert!(posted.is_err(), "and says it failed: {posted:?}");
            assert!(started.elapsed() >= TIMEOUT);

            let started = std::time::Instant::now();
            let withdrawn = within(TIMEOUT * 5, {
                let poster = Arc::clone(&poster);
                move || poster.withdraw_all()
            });
            assert!(withdrawn.is_some(), "quit's withdraw_all returns");
            assert!(
                started.elapsed() < TIMEOUT * 3,
                "after one timeout, not one per note (five would take five): {:?}",
                started.elapsed()
            );
        }

        /// The tray's watcher probe over the real backend and a fake bus: an owner means the
        /// tray can be seen, no owner means it cannot, and a failed call keeps it available.
        #[test]
        fn the_status_notifier_watcher_probe_reads_the_bus() {
            use crate::tray::{probe_watcher, WatcherProbe};
            for (answer, expected) in [
                (Some(true), WatcherProbe::Owned),
                (Some(false), WatcherProbe::NoOwner),
                (None, WatcherProbe::Failed),
            ] {
                let (backend, peer) = peer::start(Duration::from_secs(5));
                *peer.has_owner.lock().unwrap() = answer;
                assert_eq!(
                    probe_watcher(&backend),
                    expected,
                    "the bus answered {answer:?}"
                );
                drop(peer.connected());
            }
        }

        /// A poster over the fake bus whose `GetNameOwner` answers follow `script`.
        fn poster_with_owner_script(
            script: &[peer::OwnerReply],
        ) -> (
            XdgPoster,
            peer::Peer,
            tokio::sync::mpsc::UnboundedReceiver<super::super::ActionTaken>,
        ) {
            let (backend, peer) = peer::start(Duration::from_secs(5));
            peer.owner_script
                .lock()
                .unwrap()
                .extend(script.iter().copied());
            let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
            (XdgPoster::new(Arc::new(backend), tx), peer, rx)
        }

        #[test]
        fn a_failed_owner_lookup_is_asked_again() {
            let (poster, peer, mut rx) = poster_with_owner_script(&[peer::OwnerReply::Failed]);
            assert!(poster.offers_actions(), "the second ask answered");
            poster.post(&note("$a", vec![])).unwrap();
            let (server, _hung) = peer.connected();
            peer::emit(
                &server,
                peer::OWNER,
                NOTIFICATIONS,
                "ActionInvoked",
                &(1u32, "default"),
            );
            assert!(
                eventually(|| rx.try_recv().is_ok()),
                "and the press is heard"
            );
        }

        #[test]
        fn when_the_owner_cannot_be_learned_no_buttons_are_offered() {
            let (poster, _peer, _rx) =
                poster_with_owner_script(&[peer::OwnerReply::Failed, peer::OwnerReply::Failed]);
            assert!(
                !poster.offers_actions(),
                "buttons whose presses would all be dropped are not offered"
            );
        }

        #[test]
        fn a_server_not_running_yet_is_not_a_failed_lookup() {
            let (poster, peer, mut rx) = poster_with_owner_script(&[peer::OwnerReply::NoOwner]);
            assert!(
                poster.offers_actions(),
                "nobody owning the name is an answer"
            );
            poster.post(&note("$a", vec![])).unwrap();
            let (server, _hung) = peer.connected();
            peer::emit(
                &server,
                "org.freedesktop.DBus",
                ("/org/freedesktop/DBus", "org.freedesktop.DBus"),
                "NameOwnerChanged",
                &("org.freedesktop.Notifications", "", peer::OWNER),
            );
            // The match is heard before the press is, or the press would be dropped.
            std::thread::sleep(Duration::from_millis(100));
            peer::emit(
                &server,
                peer::OWNER,
                NOTIFICATIONS,
                "ActionInvoked",
                &(1u32, "default"),
            );
            assert!(eventually(|| rx.try_recv().is_ok()));
        }

        #[test]
        fn over_the_bus_a_forged_press_is_ignored_and_a_new_server_forgets() {
            let (backend, peer) = peer::start(Duration::from_secs(5));
            let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
            let poster = XdgPoster::new(Arc::new(backend), tx);
            poster.post(&note("$a", vec![])).unwrap();
            poster.post(&note("$b", vec![])).unwrap();
            let (server, _hung) = peer.connected();

            peer::emit(
                &server,
                ":1.99",
                NOTIFICATIONS,
                "ActionInvoked",
                &(1u32, "default"),
            );
            peer::emit(
                &server,
                peer::OWNER,
                NOTIFICATIONS,
                "ActionInvoked",
                &(2u32, "default"),
            );
            assert!(eventually(|| poster.remembered_ids() == [1, 2]
                && rx.try_recv().is_ok_and(|taken| taken.account.is_some())));
            assert!(
                rx.try_recv().is_err(),
                "the forged press on 1 reported nothing"
            );

            peer::emit(
                &server,
                "org.freedesktop.DBus",
                ("/org/freedesktop/DBus", "org.freedesktop.DBus"),
                "NameOwnerChanged",
                &("org.freedesktop.Notifications", peer::OWNER, ":1.8"),
            );
            assert!(
                eventually(|| poster.remembered() == 0),
                "the new server forgets every note"
            );
            peer::emit(
                &server,
                ":1.8",
                NOTIFICATIONS,
                "ActionInvoked",
                &(1u32, "default"),
            );
            peer::emit(
                &server,
                peer::OWNER,
                NOTIFICATIONS,
                "ActionInvoked",
                &(1u32, "default"),
            );
            std::thread::sleep(Duration::from_millis(200));
            assert!(rx.try_recv().is_err());
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
            // After a wait, so a server that reports the replaced note as closed is caught.
            std::thread::sleep(Duration::from_secs(2));
            assert_eq!(
                poster.remembered(),
                1,
                "the replacement is still remembered"
            );
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
