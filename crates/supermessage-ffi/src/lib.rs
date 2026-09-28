// The same limit the core carries, and for the same reason: `timeline_subscribe`
// blocks on `Timeline::subscribe`'s deeply-nested stream type, and computing
// its layout overflows rustc's default query recursion limit. An attribute
// cannot cross a crate boundary, so every crate that lays that type out needs
// its own.
#![recursion_limit = "256"]

//! supermessage's core, as Swift and Kotlin see it.
//!
//! This crate is an adapter and nothing else. It owns no logic: every method
//! here calls straight into `supermessage-core` and converts the result into
//! something UniFFI can carry. If a decision is being made in this file, it is
//! in the wrong place.
//!
//! **Method names match the Tauri commands exactly.** `rooms_resync` means the
//! same thing on a phone as it does in the desktop app, so a bug report about
//! it means one thing rather than two.
//!
//! **Ordering.** Events reach the host through [`EventSink`], which UniFFI
//! invokes on whatever thread emitted — a tokio worker, or one of matrix-sdk's
//! event handlers. The diff envelopes carry `seq` and the timeline's recovery
//! logic depends on them arriving in order, so a host implementation must
//! serialise them onto one queue. A sink that spawns per event will corrupt
//! the reader's view in a way that looks like a rendering bug.

uniffi::setup_scaffolding!();

pub mod diff;
pub mod error;
pub mod events;
pub mod secrets;

use std::path::PathBuf;
use std::sync::Arc;

use supermessage_core::event::EventSink as CoreSink;
use supermessage_core::secrets::KeyringStore;
use supermessage_core::session::Session;
use supermessage_core::sync::ConnectionPayload as CoreConnection;

pub use diff::{RoomDiffEnvelope, RoomDiffOp, TimelineDiffEnvelope, TimelineDiffOp};
pub use error::FfiError;
pub use events::{EventSink, FfiEvent};
pub use secrets::HostSecretStore;

/// What the host is told about the connection.
///
/// A mirror of the core's `ConnectionPayload` for one reason: its `state` is a
/// `&'static str`, which cannot cross an FFI boundary that has to own what it
/// carries.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ConnectionState {
    /// `"live"`, `"connecting"` or `"offline"` — the same vocabulary the
    /// desktop app's connection indicator reads.
    pub state: String,
    /// Present only when the state is an unhappy one and there is something
    /// useful to say about it.
    pub message: Option<String>,
}

impl From<CoreConnection> for ConnectionState {
    fn from(payload: CoreConnection) -> Self {
        Self {
            state: payload.state.to_string(),
            message: payload.message,
        }
    }
}

/// The core, held by the host for the lifetime of the app.
///
/// Owns the tokio runtime, which on desktop is Tauri's job. There is no Tauri
/// on a phone, so the object that owns the session owns the runtime that
/// drives it.
#[derive(uniffi::Object)]
pub struct Core {
    session: Arc<Session>,
    runtime: tokio::runtime::Runtime,
}

impl Core {
    /// Run a future to completion on a thread whose stack this crate controls.
    ///
    /// `Runtime::block_on` drives the future on the **calling** thread, and the
    /// callers here are host threads. On iOS that is a GCD dispatch worker with
    /// a 512 KB stack, which is not enough: on 2026-08-29 opening a room
    /// crashed with `EXC_BAD_ACCESS (code=2)` inside
    /// `imbl::Vector<TimelineEvent>::promote_front`, faulting on its own
    /// stack-probe loop — which is what hitting a guard page looks like.
    ///
    /// That these types are enormous is already recorded at the top of this
    /// file: `recursion_limit` is raised there because computing
    /// `Timeline::subscribe`'s layout overflows *rustc*. A type that large at
    /// compile time is a large frame at run time.
    ///
    /// A scoped thread rather than `Runtime::spawn`: spawn requires `'static`
    /// and every call below borrows `self.session`. The cost is one thread per
    /// call, which is nothing beside the network and SQLite work each of these
    /// already does.
    ///
    /// Applied to EVERY blocking call rather than the few known to go deep.
    /// Which of them recurses is a property of the Matrix SDK's types, not of
    /// this file, so a list here would be wrong the moment those change — and
    /// the failure it prevents is a hard crash with no Rust-level error.
    fn block<F>(&self, fut: F) -> F::Output
    where
        F: std::future::Future + Send,
        F::Output: Send,
    {
        /// Eight megabytes: the main thread's budget on Apple platforms, and
        /// sixteen times what a dispatch worker gets.
        const CORE_STACK_BYTES: usize = 8 * 1024 * 1024;

        std::thread::scope(|scope| {
            std::thread::Builder::new()
                .name("supermessage-core".to_owned())
                .stack_size(CORE_STACK_BYTES)
                .spawn_scoped(scope, || self.runtime.block_on(fut))
                .expect("a core worker thread must be spawnable")
                .join()
                .unwrap_or_else(|panic| std::panic::resume_unwind(panic))
        })
    }
}

#[uniffi::export]
impl Core {
    /// Build a core rooted at `data_dir`, using the OS secret store.
    ///
    /// `data_dir` is the host's to choose — an app-support directory on macOS,
    /// the app container on iOS. The core puts its stores under it and does
    /// not look outside it.
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Arc<Self> {
        Self::build(data_dir, Box::new(KeyringStore))
    }

    /// Build a core that shares its stores with another process — on iOS, the
    /// app and its Notification Service Extension. See [`CoreOptions`].
    ///
    /// Infallible, like [`Core::new`]: every step that can fail here has a
    /// safe way to continue, and a core that refused to exist would leave the
    /// host with nothing to show even a sign-in screen through.
    #[uniffi::constructor]
    pub fn with_options(options: CoreOptions) -> Arc<Self> {
        install_tracing();
        let data_dir = settle_data_dir(&options);
        let store = settle_secret_store(&options);
        Self::build_for(data_dir, store, &options.process_name)
    }

    /// Build a core whose secrets live in a store the host supplies.
    ///
    /// For platforms where the core has no usable store of its own. Android is
    /// the only one today: `KeyringStore` has no implementation there and
    /// fails every call, so a host that used [`Core::new`] could never sign in.
    #[uniffi::constructor]
    pub fn with_secret_store(data_dir: String, store: Box<dyn HostSecretStore>) -> Arc<Self> {
        Self::build(data_dir, Box::new(crate::secrets::ForeignStore(store)))
    }

    /// Where the connection currently stands, without waiting for the next
    /// transition. A host that has just launched needs this to render
    /// something truthful before any event arrives.
    pub fn connection_state(&self) -> ConnectionState {
        self.block(self.session.connection_state()).into()
    }

    /// Sign in and start syncing, reporting progress through `sink`.
    ///
    /// The sink is handed over per call rather than stored, mirroring the
    /// desktop host, where each command wraps the app handle it was given.
    pub fn login(
        &self,
        homeserver: String,
        username: String,
        password: String,
        sink: Box<dyn EventSink>,
    ) -> Result<(), FfiError> {
        let sink: Arc<dyn CoreSink> = Arc::new(events::HostSink(sink));
        self.block(
            self.session
                .login_and_start(&homeserver, &username, &password, sink),
        )?;
        Ok(())
    }

    /// Pick up a session stored from a previous run.
    ///
    /// `false` means there was nothing stored — an ordinary outcome on first
    /// launch, and the host should show its sign-in screen rather than an
    /// error.
    pub fn restore_session(&self, sink: Box<dyn EventSink>) -> Result<bool, FfiError> {
        let sink: Arc<dyn CoreSink> = Arc::new(events::HostSink(sink));
        Ok(self.block(self.session.restore_and_start(sink))?)
    }

    /// Every room this account is in, with the sequence number the snapshot
    /// was taken at.
    ///
    /// The desktop host calls this `rooms_resync`, which is the one place the
    /// two vocabularies differ. The desktop name describes when it is called —
    /// after a reload, to catch up; this one describes what it returns. A host
    /// that has been backgrounded and lost diffs calls it for the same reason
    /// either way.
    ///
    /// The `seq` matters: room-list diffs arriving afterwards carry increasing
    /// sequence numbers, and a host that applies one older than its snapshot
    /// would move rows that have already moved.
    pub fn rooms_snapshot(&self) -> Result<RoomsSnapshot, FfiError> {
        let (seq, rooms) = self.block(self.session.rooms_snapshot())?;
        Ok(RoomsSnapshot { seq, rooms })
    }

    /// Focus a room and start streaming its timeline.
    ///
    /// Diffs arrive on the sink as `TimelineDiff`, carrying the `seq` their
    /// ordering depends on. Only one room is focused at a time — subscribing
    /// to a second replaces the first, which is what makes `room_id`
    /// verification meaningful on every write below.
    pub fn timeline_subscribe(
        &self,
        room_id: String,
        sink: Box<dyn EventSink>,
    ) -> Result<(), FfiError> {
        let sink: Arc<dyn CoreSink> = Arc::new(events::HostSink(sink));
        self.block(self.session.subscribe_timeline(&room_id, sink))?;
        Ok(())
    }

    /// Load older messages. `true` means the start of the room was reached and
    /// there is nothing more to ask for.
    pub fn timeline_paginate_back(&self, room_id: String, count: u16) -> Result<bool, FfiError> {
        Ok(self.block(
            self.session
                .focused_timeline()
                .paginate_back(&room_id, count),
        )?)
    }

    /// The focused timeline as of now: its room, the sequence number, and the
    /// items. A host that has just subscribed uses this rather than waiting
    /// for a diff that may not come until something changes.
    pub fn timeline_resync(&self) -> Result<TimelineSnapshot, FfiError> {
        let (room_id, seq, items) = self.block(self.session.focused_timeline().snapshot())?;
        Ok(TimelineSnapshot {
            room_id,
            seq,
            items,
        })
    }

    /// Mark the room read up to its latest event.
    pub fn mark_room_read(&self, room_id: String) -> Result<(), FfiError> {
        self.block(self.session.focused_timeline().mark_read(&room_id))?;
        Ok(())
    }

    /// Send a plain-text message to the focused room.
    ///
    /// `room_id` is checked against whichever room is actually focused before
    /// anything is sent — the fix for a wrong-recipient race, and the reason
    /// every write here takes a room id it could otherwise infer.
    ///
    /// `mentions` are user ids to notify; empty is the ordinary case.
    pub fn send_message(
        &self,
        room_id: String,
        body: String,
        mentions: Vec<String>,
    ) -> Result<(), FfiError> {
        self.block(
            self.session
                .focused_timeline()
                .send_text(&room_id, &body, &mentions),
        )?;
        Ok(())
    }

    /// Reply to `in_reply_to`, an event id in the focused room.
    pub fn send_reply(
        &self,
        room_id: String,
        body: String,
        in_reply_to: String,
    ) -> Result<(), FfiError> {
        self.block(
            self.session
                .focused_timeline()
                .send_reply(&room_id, &body, &in_reply_to),
        )?;
        Ok(())
    }

    /// Answer a superpipeline approval gate.
    ///
    /// `option_id` must be one of `approve`, `request_changes` or
    /// `reject` — superpipeline's `GateDecision`, and the only values its
    /// resolution endpoint accepts. Anything else is refused here rather
    /// than reaching the room, so a mistake surfaces as an error the host
    /// can show instead of a tap that silently does nothing.
    ///
    /// `in_reply_to` is the gate event's own id: the decision references it,
    /// and the Application Service refuses a decision whose `gate_id` and
    /// reference disagree.
    ///
    /// `comment` is the feedback superpipeline merges into the card's handoff on
    /// `request_changes`, so the rework carries the reviewer's reasoning.
    /// Pass `None` for the other two — a host should only prompt for it on
    /// that option.
    ///
    /// `prompt` is the gate's own question, not the finished sentence. The
    /// line left in the room is derived in the core so iOS and Android
    /// cannot word the durable record differently.
    pub fn send_gate_decision(
        &self,
        room_id: String,
        gate_id: String,
        option_id: String,
        comment: Option<String>,
        in_reply_to: String,
        prompt: String,
    ) -> Result<(), FfiError> {
        self.block(self.session.focused_timeline().send_gate_decision(
            &room_id,
            &gate_id,
            &option_id,
            comment.as_deref(),
            &in_reply_to,
            &prompt,
        ))?;
        Ok(())
    }

    /// Set how loudly a room may interrupt. `Default` unsets the room's own
    /// rule so the account default applies again.
    pub fn set_room_notifications(
        &self,
        room_id: String,
        mode: supermessage_core::room_info::NotificationMode,
    ) -> Result<(), FfiError> {
        self.block(self.session.set_room_notification_mode(&room_id, mode))?;
        Ok(())
    }

    /// Register this device's push token with the homeserver, pointed at a
    /// push gateway. `event_id_only`, so no content leaves the homeserver.
    pub fn register_pusher(
        &self,
        registration: supermessage_core::push::PushRegistration,
    ) -> Result<(), FfiError> {
        self.block(self.session.register_pusher(&registration))?;
        Ok(())
    }

    /// Stop pushing to this device without signing out. [`Core::logout`]
    /// already does this first; a no-op when nothing was registered.
    pub fn unregister_pusher(&self) -> Result<(), FfiError> {
        self.block(self.session.unregister_pusher())?;
        Ok(())
    }

    /// Stop syncing while the app is away, so a second process can take the
    /// store lock. Streams stay subscribed. See `Session::pause_sync`.
    pub fn sync_pause(&self) {
        self.block(self.session.pause_sync());
    }

    /// Start a sync [`Core::sync_pause`] stopped.
    pub fn sync_resume(&self) {
        self.block(self.session.resume_sync());
    }

    /// Pin or unpin a room — the `m.favourite` tag, so it travels between
    /// clients.
    pub fn set_room_pinned(&self, room_id: String, pinned: bool) -> Result<(), FfiError> {
        self.block(self.session.set_room_pinned(&room_id, pinned))?;
        Ok(())
    }

    /// Rewrite a message this account sent.
    pub fn edit_message(
        &self,
        room_id: String,
        event_id: String,
        body: String,
    ) -> Result<(), FfiError> {
        self.block(
            self.session
                .focused_timeline()
                .edit_text(&room_id, &event_id, &body),
        )?;
        Ok(())
    }

    /// Delete a message — a Matrix redaction, which is permanent and visible
    /// to the whole room.
    pub fn delete_message(&self, room_id: String, event_id: String) -> Result<(), FfiError> {
        self.block(self.session.focused_timeline().redact(&room_id, &event_id))?;
        Ok(())
    }

    /// Add or remove a reaction. Returns whether the reaction is now present.
    pub fn toggle_reaction(
        &self,
        room_id: String,
        event_id: String,
        key: String,
    ) -> Result<bool, FfiError> {
        Ok(self.block(
            self.session
                .focused_timeline()
                .toggle_reaction(&room_id, &event_id, &key),
        )?)
    }

    /// Tell the room whether this account is typing.
    pub fn set_typing(&self, room_id: String, typing: bool) -> Result<(), FfiError> {
        self.block(self.session.focused_timeline().set_typing(&room_id, typing))?;
        Ok(())
    }

    /// Accept an invitation, or join a room already known by id.
    pub fn join_room(&self, room_id: String) -> Result<(), FfiError> {
        self.block(self.session.join_room(&room_id))?;
        Ok(())
    }

    /// Leave a room. It disappears from the roster on the next diff.
    pub fn leave_room(&self, room_id: String) -> Result<(), FfiError> {
        self.block(self.session.leave_room(&room_id))?;
        Ok(())
    }

    // ── Block and report (issue #60) ────────────────────────────────────────
    //
    // Grouped on purpose: one contiguous block, so a parallel change to this
    // file rebases around it rather than through it.

    /// Block someone — a person or an agent. Their messages stop reaching
    /// this account everywhere; an agent keeps running. The new list arrives
    /// as `FfiEvent::IgnoredUsers` once the homeserver echoes it back.
    pub fn ignore_user(&self, user_id: String) -> Result<(), FfiError> {
        self.block(self.session.ignore_user(&user_id))?;
        Ok(())
    }

    /// Unblock someone. A no-op when they were not blocked.
    pub fn unignore_user(&self, user_id: String) -> Result<(), FfiError> {
        self.block(self.session.unignore_user(&user_id))?;
        Ok(())
    }

    /// Everyone this account has blocked, named, with `is_ignored` set.
    pub fn ignored_users(
        &self,
    ) -> Result<Vec<supermessage_core::room_info::RoomMemberDto>, FfiError> {
        Ok(self.block(self.session.blocked_users())?)
    }

    /// Report a message to the homeserver's administrator. `reason` may be
    /// empty; over the limit it is refused with `ReasonTooLong`.
    pub fn report_event(
        &self,
        room_id: String,
        event_id: String,
        reason: String,
    ) -> Result<(), FfiError> {
        self.block(self.session.report_event(&room_id, &event_id, &reason))?;
        Ok(())
    }

    /// Report a room — joined, or only invited to.
    pub fn report_room(&self, room_id: String, reason: String) -> Result<(), FfiError> {
        self.block(self.session.report_room(&room_id, &reason))?;
        Ok(())
    }

    /// Report a person or an agent.
    pub fn report_user(&self, user_id: String, reason: String) -> Result<(), FfiError> {
        self.block(self.session.report_user(&user_id, &reason))?;
        Ok(())
    }

    /// Create a room and return its id.
    ///
    /// `is_direct` marks it as a one-to-one conversation, which changes how
    /// clients name and group it rather than anything about the room itself.
    pub fn create_room(
        &self,
        name: String,
        invite: Vec<String>,
        is_direct: bool,
    ) -> Result<String, FfiError> {
        Ok(self.block(self.session.create_room(&name, &invite, is_direct))?)
    }

    /// How encryption recovery stands: "enabled", "disabled", "incomplete" or
    /// "unknown".
    ///
    /// A string rather than an enum because it crosses two FFI boundaries and
    /// the callers only ever switch on it. `"unknown"` means the first sync has
    /// not answered yet — a screen must say "checking", never "not set up",
    /// because offering a second recovery key to somebody who already has one
    /// is how the first one is orphaned.
    pub fn recovery_state(&self) -> Result<String, FfiError> {
        Ok(self.block(self.session.recovery_state())?)
    }

    /// Turn recovery on and return the key, once.
    ///
    /// Show it and forget it. There is deliberately no way to ask for it again:
    /// an app that can re-display a recovery key is an app that stored one.
    /// Never log it, never put it in analytics, never write it to a crash
    /// report.
    pub fn enable_recovery(&self) -> Result<String, FfiError> {
        Ok(self.block(self.session.enable_recovery())?)
    }

    /// Use a recovery key on this device, to read what other devices hold.
    pub fn recover_with_key(&self, recovery_key: String) -> Result<(), FfiError> {
        self.block(self.session.recover_with_key(&recovery_key))?;
        Ok(())
    }

    /// Set recovery up at sign-in if this account has none.
    ///
    /// Returns the key to show once, or nothing when there was nothing to do.
    pub fn ensure_recovery(&self) -> Result<Option<String>, FfiError> {
        Ok(self.block(self.session.ensure_recovery())?)
    }

    /// Throw the old identity away and start again, returning the new key.
    ///
    /// Destructive: deletes the old backup and replaces the cross-signing
    /// identity. Only for someone with no key and no device that holds the
    /// secrets — the caller must have said as much.
    pub fn reset_recovery(&self, password: String) -> Result<String, FfiError> {
        Ok(self.block(self.session.reset_recovery(&password))?)
    }

    /// Join by alias (`#room:server`) or id, returning the id joined.
    pub fn join_room_by_alias(&self, alias_or_id: String) -> Result<String, FfiError> {
        Ok(self.block(self.session.join_room_by_alias(&alias_or_id))?)
    }

    /// Invite someone to a room.
    pub fn invite_user(&self, room_id: String, user_id: String) -> Result<(), FfiError> {
        self.block(self.session.invite_user(&room_id, &user_id))?;
        Ok(())
    }

    /// A room's avatar as a `data:` URI, if it has one.
    pub fn room_avatar(&self, room_id: String) -> Result<Option<String>, FfiError> {
        Ok(self.block(self.session.room_avatar(&room_id))?)
    }

    /// A room's avatar at its original size, for viewing the picture itself.
    pub fn room_avatar_full(&self, room_id: String) -> Result<Option<String>, FfiError> {
        Ok(self.block(self.session.room_avatar_full(&room_id))?)
    }

    /// A member's avatar as a `data:` URI, given its `mxc:` URI.
    pub fn member_avatar(&self, mxc_uri: String) -> Result<Option<String>, FfiError> {
        Ok(self.block(self.session.member_avatar(&mxc_uri))?)
    }

    /// An event's media as a `data:` URI, fetched and decrypted.
    ///
    /// There is deliberately no `media_download` here, unlike the desktop
    /// host. That command exists to open a *save panel* and write the bytes to
    /// a path the person chooses — a desktop gesture. On iOS the host fetches
    /// with this and hands the result to a share sheet, which is the platform's
    /// own answer to the same question and needs no file picker crossing the
    /// FFI.
    pub fn media_fetch(&self, event_id: String) -> Result<Option<String>, FfiError> {
        Ok(self.block(self.session.media_fetch(&event_id))?)
    }

    /// An audio message's file, fetched, decrypted, and ready for this host's
    /// player. `None` when `event_id` is not an audio message in the focused
    /// room.
    ///
    /// `opus_in_caf`: the host's player reads Opus only from CAF — true on
    /// iOS (AVFoundation plays no Ogg), false on Android (MediaPlayer plays
    /// Ogg). An Ogg/Opus voice note is then remuxed, not transcoded. See
    /// `core::audio::playable_audio`.
    pub fn media_audio(
        &self,
        event_id: String,
        opus_in_caf: bool,
    ) -> Result<Option<supermessage_core::audio::PlayableAudio>, FfiError> {
        Ok(self.block(self.session.audio_playable(&event_id, opus_in_caf))?)
    }

    /// Stage a file the host has already chosen.
    ///
    /// The desktop command opens the picker from Rust; this takes a path
    /// instead, because on iOS the document picker is a SwiftUI presentation
    /// and the core has no business summoning it. The host picks, then stages.
    pub fn attachment_stage_path(
        &self,
        room_id: String,
        path: String,
    ) -> Result<StagedFile, FfiError> {
        let staged = self.session.staged_attachments();
        let meta = self.block(async {
            let client = self.session.require_client().await?;
            supermessage_core::attachments::stage_path(
                &client,
                &staged,
                &room_id,
                std::path::PathBuf::from(path),
            )
            .await
        })?;
        Ok(meta.into())
    }

    /// Upload and send the staged file `token` names.
    ///
    /// **Consumes the token**, so a replay cannot re-send the file. `room_id`
    /// is checked against both the focused room and the room the token was
    /// staged for — the first catches a stale send, the second a token kept
    /// across a room switch.
    ///
    /// `caption` is what the reader typed with the file. It travels in the
    /// same event (MSC2530) rather than as a message of its own.
    pub fn attachment_send(
        &self,
        room_id: String,
        token: String,
        caption: Option<String>,
    ) -> Result<(), FfiError> {
        let staged = self.session.staged_attachments();
        let focused = self.session.focused_timeline();
        self.block(supermessage_core::attachments::send_staged(
            &self.session,
            &focused,
            &staged,
            &room_id,
            &token,
            caption,
        ))?;
        Ok(())
    }

    /// Mark a staged recording as a voice message (MSC3245), with its length
    /// in milliseconds and a waveform of levels between 0 and 1.
    ///
    /// Call between staging and sending. Sent without it, a recording is a
    /// plain audio file: other clients draw a file row, and Hermes never
    /// transcribes it.
    pub fn attachment_mark_voice(
        &self,
        room_id: String,
        token: String,
        duration_ms: u64,
        waveform: Vec<f32>,
    ) -> Result<(), FfiError> {
        self.session.staged_attachments().mark_voice(
            &token,
            &room_id,
            supermessage_core::attachments::VoiceNote {
                duration_ms,
                waveform,
            },
        )?;
        Ok(())
    }

    /// Throw a staged file away without sending it.
    pub fn attachment_discard(&self, token: String) {
        self.session.staged_attachments().discard(&token);
    }

    /// The spaces this account is in, for the roster's rail.
    pub fn spaces_list(&self) -> Result<Vec<supermessage_core::spaces::SpaceSummary>, FfiError> {
        Ok(self.block(self.session.spaces_list())?)
    }

    /// Filter the room list to a space, or `None` to clear the filter.
    ///
    /// The filter lives in the core, not the host: the next room-list diff
    /// reflects it, so both hosts see the same rooms for the same selection.
    pub fn space_select(&self, space_id: Option<String>) -> Result<(), FfiError> {
        self.block(self.session.select_space(space_id.as_deref()))?;
        Ok(())
    }

    /// Search messages — in one room when `room_id` is given, across every
    /// room this account can see otherwise.
    pub fn search_messages(
        &self,
        term: String,
        room_id: Option<String>,
    ) -> Result<Vec<supermessage_core::search::SearchResultDto>, FfiError> {
        Ok(self.block(self.session.search_messages(&term, room_id.as_deref()))?)
    }

    /// Everyone this account shares a room with — the new-conversation
    /// screen's directory. See `core::people`.
    pub fn known_people(&self) -> Result<Vec<supermessage_core::people::PersonDto>, FfiError> {
        Ok(self.block(self.session.known_people())?)
    }

    /// The room this account already shares with `user_id` alone, if any.
    pub fn direct_room_with(&self, user_id: String) -> Result<Option<String>, FfiError> {
        Ok(self.block(self.session.direct_room_with(&user_id))?)
    }

    /// Everything the info panel shows about a room.
    pub fn room_info(
        &self,
        room_id: String,
    ) -> Result<supermessage_core::room_info::RoomInfoDto, FfiError> {
        Ok(self.block(self.session.room_info(&room_id))?)
    }

    /// Who invited this account to a room, or `None`.
    pub fn room_inviter(&self, room_id: String) -> Result<Option<String>, FfiError> {
        Ok(self.block(self.session.room_inviter(&room_id))?)
    }

    /// Who this app is signed in as, and where.
    pub fn account(&self) -> Result<supermessage_core::dto::AccountDto, FfiError> {
        Ok(self.block(self.session.account())?)
    }

    /// Sign out and wipe the local stores.
    pub fn logout(&self) -> Result<(), FfiError> {
        self.block(self.session.logout())?;
        Ok(())
    }
}

/// Answers from a notification, with no room open.
///
/// A block of its own so the surface reads as one feature. Every write above
/// goes through the focused timeline and fails unless its room is the one on
/// screen; these go to the room directly, need no sync, and return only once
/// the homeserver has accepted the event.
#[uniffi::export]
impl Core {
    /// Pick up a stored session **without starting sync** — enough to send
    /// an answer from a process iOS woke only to deliver a notification
    /// action. A no-op returning `true` when a session is already live, and a
    /// later [`Core::restore_session`] starts streams on the client this
    /// installed rather than building a second one.
    pub fn restore_session_quietly(&self) -> Result<bool, FfiError> {
        Ok(self.block(self.session.restore_quietly())?)
    }

    /// Answer an AgentPod permission request in `room_id`: the option's name
    /// as a plain message, the same bytes the composer would send. The room
    /// need not be open.
    pub fn send_permission_answer(
        &self,
        room_id: String,
        option_id: String,
    ) -> Result<(), FfiError> {
        self.block(self.session.send_permission_answer(&room_id, &option_id))?;
        Ok(())
    }

    /// What the notification for `event_id` in `room_id` should say, fetched
    /// and decrypted here — the Notification Service Extension's one call
    /// after [`Core::restore_session_quietly`]. An error means the event could
    /// not be had; the host then keeps the push's own generic text.
    pub fn notification_for(
        &self,
        room_id: String,
        event_id: String,
    ) -> Result<supermessage_core::notification::NotificationDto, FfiError> {
        Ok(self.block(self.session.notification_for(&room_id, &event_id))?)
    }

    /// [`Core::send_gate_decision`] for a room that need not be open: the
    /// same content, validated the same way, sent straight to `room_id`.
    pub fn send_gate_decision_to(
        &self,
        room_id: String,
        gate_id: String,
        option_id: String,
        comment: Option<String>,
        in_reply_to: String,
        prompt: String,
    ) -> Result<(), FfiError> {
        self.block(self.session.send_gate_decision_to(
            &room_id,
            &gate_id,
            &option_id,
            comment.as_deref(),
            &in_reply_to,
            &prompt,
        ))?;
        Ok(())
    }
}

impl Core {
    /// Shared by both constructors, so neither can drift from the other on
    /// tracing setup or runtime construction.
    fn build(
        data_dir: String,
        store: Box<dyn supermessage_core::secrets::SecretStore>,
    ) -> Arc<Self> {
        install_tracing();
        Self::build_for(
            PathBuf::from(data_dir),
            store,
            supermessage_core::session::MAIN_PROCESS,
        )
    }

    fn build_for(
        data_dir: PathBuf,
        store: Box<dyn supermessage_core::secrets::SecretStore>,
        process: &str,
    ) -> Arc<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("a multi-thread runtime must be constructible");

        Arc::new(Self {
            session: Arc::new(Session::for_process(data_dir, store, process)),
            runtime,
        })
    }
}

/// How a host that shares its stores with another process builds its core.
///
/// On iOS both the app and the Notification Service Extension build one, over
/// the same App Group directory and the same keychain access group, under
/// different `process_name`s. The app also names where an earlier build kept
/// things, and they are moved once (see `settle_data_dir`).
#[derive(Debug, Clone, uniffi::Record)]
pub struct CoreOptions {
    /// Where the stores live. For a shared store, a directory both processes
    /// can reach — the App Group container on iOS.
    pub data_dir: String,
    /// The name this process holds the cross-process store lock under:
    /// `"main"` for the app, `"nse"` for the extension. Never the same in two
    /// processes that share `data_dir`.
    pub process_name: String,
    /// Where a previous build kept its stores. Moved into `data_dir` once,
    /// before any client is built, so an update does not sign anyone out.
    /// `None` in a process that never had any (the extension).
    pub legacy_data_dir: Option<String>,
    /// The keychain access group secrets live in — on iOS the group both
    /// processes are entitled to, team prefix included. `None` for the
    /// platform default. Ignored off iOS.
    pub keychain_access_group: Option<String>,
    /// The access group a previous build wrote its secrets to — the app's
    /// own default group, `<team>.dev.supermessage.ios`. Moved into
    /// `keychain_access_group` the first time they are readable. Ignored off
    /// iOS, and when `keychain_access_group` is `None`.
    pub legacy_keychain_access_group: Option<String>,
}

/// The directory to open the stores in, after moving an earlier build's there.
///
/// A move that fails leaves the stores where they were, and this process uses
/// them *there*: opening an empty directory with a stored session would mint
/// a fresh encryption identity under the same device id, which other devices
/// then refuse — far worse than one more launch in the old place.
fn settle_data_dir(options: &CoreOptions) -> PathBuf {
    let data_dir = PathBuf::from(&options.data_dir);
    let Some(legacy) = options.legacy_data_dir.as_deref().map(PathBuf::from) else {
        return data_dir;
    };
    if legacy == data_dir {
        return data_dir;
    }
    match supermessage_core::storage::move_dir(&legacy.join("store"), &data_dir.join("store")) {
        Ok(outcome) => {
            if outcome.moved > 0 || outcome.kept > 0 {
                tracing::info!(
                    moved = outcome.moved,
                    kept = outcome.kept,
                    "moved the stores"
                );
            }
            data_dir
        }
        Err(error) => {
            tracing::warn!(%error, "could not move the stores; using them where they are");
            legacy
        }
    }
}

/// The secret store for `options`: the OS store in the named access group,
/// reading through to the legacy one until everything has moved.
fn settle_secret_store(options: &CoreOptions) -> Box<dyn supermessage_core::secrets::SecretStore> {
    use supermessage_core::secrets::{os_store, MigratingStore};
    let group = options
        .keychain_access_group
        .as_deref()
        .filter(|g| !g.is_empty());
    let current = match os_store(group) {
        Ok(store) => store,
        Err(error) => {
            tracing::warn!(%error, "the shared keychain group is unavailable; using the default");
            return Box::new(KeyringStore);
        }
    };
    let legacy = options
        .legacy_keychain_access_group
        .as_deref()
        .filter(|g| !g.is_empty() && group.is_some());
    match legacy.map(|g| os_store(Some(g))) {
        Some(Ok(legacy)) => Box::new(MigratingStore::new(current, legacy)),
        _ => current,
    }
}

/// The room list as of one moment, and the sequence number it was taken at.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RoomsSnapshot {
    pub seq: u64,
    pub rooms: Vec<supermessage_core::dto::RoomRow>,
}

/// Makes the core's `tracing` output visible to the host, once.
///
/// Without this the core is **silent on iOS**: `tracing` with no subscriber
/// discards everything, so every `warn!` the core emits — including the one
/// `session::start_streams` logs when the room list fails to start, which it
/// swallows and continues past — goes nowhere. A failure inside the core then
/// looks identical to the core simply not doing anything.
///
/// stderr rather than oslog: the simulator surfaces it through
/// `simctl launch --console`, and Xcode's console shows it on device. A
/// dedicated oslog layer would be tidier and is not worth a dependency yet.
///
/// `try_init` because a host may construct more than one `Core` over a process
/// lifetime, and a second attempt to install a global subscriber would panic.
fn install_tracing() {
    use tracing_subscriber::EnvFilter;

    let filter = EnvFilter::try_from_env("SUPERMESSAGE_LOG")
        .unwrap_or_else(|_| EnvFilter::new("supermessage_core=debug,supermessage_ffi=debug,warn"));

    let _ = tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_writer(std::io::stderr)
        .try_init();
}

/// The focused timeline as of one moment.
///
/// Named fields rather than the core's bare tuple: a tuple crossing an FFI
/// arrives in Swift as `.0`, `.1`, `.2`, and a host reading `snapshot.1` has
/// no way to know it is a sequence number.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TimelineSnapshot {
    pub room_id: String,
    pub seq: u64,
    pub items: Vec<supermessage_core::dto::TimelineRow>,
}

/// A file staged for sending, as the host sees it.
///
/// The core's `StagedAttachment` is already a plain record; this mirrors it so
/// the FFI surface does not depend on the core deriving UniFFI traits for a
/// type only this crate exposes.
#[derive(Debug, Clone, uniffi::Record)]
pub struct StagedFile {
    /// What `attachment_send` takes. Single-use.
    pub token: String,
    pub filename: String,
    pub size_bytes: u64,
    pub mime: String,
    pub width: Option<u64>,
    pub height: Option<u64>,
}

impl From<supermessage_core::attachments::StagedAttachment> for StagedFile {
    fn from(meta: supermessage_core::attachments::StagedAttachment) -> Self {
        Self {
            token: meta.token,
            filename: meta.filename,
            size_bytes: meta.size_bytes,
            mime: meta.mime,
            width: meta.width,
            height: meta.height,
        }
    }
}

/// Parse a live turn's partial markdown into blocks.
///
/// A free function rather than a `Core` method: it touches no session state,
/// and making it one would imply it needed a signed-in client. A landed
/// message already carries its blocks on `TimelineRow`; this is for a turn
/// still arriving on the live channel, so the two render through the same
/// parser and a turn does not change appearance the instant it lands.
#[uniffi::export]
pub fn rich_blocks_from_markdown(source: String) -> Vec<supermessage_core::rich::RichBlock> {
    supermessage_core::rich::blocks_from_markdown(&source)
}

/// Parse a matrix.to URL or a `matrix:` URI into what it addresses.
///
/// A free function for the same reason as `rich_blocks_from_markdown`: it
/// touches no session state, and making it a `Core` method would imply it
/// needed a signed-in client.
#[uniffi::export]
pub fn parse_matrix_link(
    href: String,
) -> Option<supermessage_core::matrix_links::MatrixLinkTarget> {
    supermessage_core::matrix_links::parse_matrix_link(&href)
}

/// Which of the seven person colours `user_id` is drawn in — see
/// `core::peer_color`. A free function for the same reason as
/// `rich_blocks_from_markdown`: every host asks the same question and the
/// answer must not differ between them.
#[uniffi::export]
pub fn peer_color_index(user_id: String) -> u8 {
    supermessage_core::peer_color::peer_color_index(&user_id)
}

/// A playing note's position as the clock under it reads — `"0:06"`,
/// `"1:02:03"` — truncated to the second. See `core::audio`: the length at
/// rest arrives already formatted on `AudioView`; this is for the one number
/// that changes while it plays, so every host ticks over at the same instant.
#[uniffi::export]
pub fn audio_clock_label(ms: u64) -> String {
    supermessage_core::audio::audio_clock_label(ms)
}

/// The user ids a finished message mentions, for `m.mentions`.
#[uniffi::export]
pub fn collect_mentions(
    text: String,
    members: Vec<supermessage_core::mentions::Mentionable>,
) -> Vec<String> {
    supermessage_core::mentions::collect_mentions(&text, &members)
}

/// Name a set of people from their user ids — "Cleaner Cody and 2 others".
///
/// A free function for the same reason as `rich_blocks_from_markdown`: read
/// receipts and reaction chips are handed user ids by the SDK and no display
/// names, and naming is a core decision (see `display_name`) rather than
/// something each host re-invents in its own idiom.
#[uniffi::export]
pub fn people_label(user_ids: Vec<String>) -> String {
    supermessage_core::display_name::people_label(&user_ids)
}

/// Filter a directory by what the reader has typed — name, machine, or id.
///
/// A free function for the same reason as `rich_blocks_from_markdown`: it
/// touches no session state, and it runs on every keystroke, so a round trip
/// through the session would be a round trip per character.
#[uniffi::export]
pub fn people_matching(
    people: Vec<supermessage_core::people::PersonDto>,
    query: String,
) -> Vec<supermessage_core::people::PersonDto> {
    supermessage_core::people::matching(&people, &query)
}

/// Arrange a roster: order it, group it, and label the groups.
///
/// A free function for the same reason as `rich_blocks_from_markdown` — it
/// touches no session state — and in the core rather than in each host
/// because every rule inside it is a product decision about what a fleet
/// looks like. Two hosts each holding a copy is two clients that disagree
/// about what a roster is.
#[uniffi::export]
pub fn roster_sections(
    rows: Vec<supermessage_core::dto::RoomRow>,
    view: supermessage_core::roster::RosterView,
    shows_invitations: bool,
    now_ms: u64,
) -> Vec<supermessage_core::roster::RosterSection> {
    supermessage_core::roster::sections(&rows, view, shows_invitations, now_ms)
}

/// What the roster may say a room is doing.
#[uniffi::export]
pub fn roster_state(
    row: supermessage_core::dto::RoomRow,
    now_ms: u64,
) -> supermessage_core::roster::AgentState {
    supermessage_core::roster::state_for(&row, now_ms)
}

/// The initial to show where there is no picture — one full code point.
///
/// Exposed because a host otherwise writes `name[0]`, and that is a real bug in
/// every host language this project has: Kotlin's `take(1)` and JS's `[0]` both
/// count UTF-16 code units, so an emoji-initial name yields half a surrogate
/// pair and renders as tofu. `room_identity`'s header records that exact defect
/// shipping once already.
#[uniffi::export]
pub fn display_initial(name: String) -> String {
    supermessage_core::room_identity::display_initial(&name)
}

/// How many invitations are being withheld, for a filter to admit to.
#[uniffi::export]
pub fn roster_hidden_invitations(
    rows: Vec<supermessage_core::dto::RoomRow>,
    shows_invitations: bool,
) -> u32 {
    supermessage_core::roster::hidden_invitations(&rows, shows_invitations)
}

/// The notification a timeline row deserves, or `None` when it is not news —
/// the same decision the Notification Service Extension makes for a push, so
/// a local notification and a remote one say the same thing. See
/// `core::notification`.
#[uniffi::export]
pub fn notification_for_row(
    row: supermessage_core::dto::TimelineRow,
    room_id: String,
    event_id: String,
    room_name: String,
) -> Option<supermessage_core::notification::NotificationDto> {
    supermessage_core::notification::notification_for_row(&row, &room_id, &event_id, &room_name)
}

/// The Allow once / Reject pair a permission decision offers, if it offers
/// both. "Always" is never taken for "once".
#[uniffi::export]
pub fn notification_permission_answers(
    decision: supermessage_core::custom_events::CustomEventDecision,
) -> Option<supermessage_core::notification::PermissionAnswers> {
    supermessage_core::notification::permission_answers(&decision)
}

/// What a gate notification can answer, or `None` when it should only open.
#[uniffi::export]
pub fn notification_gate_answers(
    decision: supermessage_core::custom_events::CustomEventDecision,
    gate_id: String,
) -> Option<supermessage_core::notification::GateAnswers> {
    supermessage_core::notification::gate_answers(&decision, &gate_id)
}

/// The `UNNotificationCategory` identifier for `category` — the value the
/// push gateway also sends as `aps.category`.
#[uniffi::export]
pub fn notification_category_identifier(
    category: supermessage_core::notification::NotificationCategory,
) -> String {
    category.identifier().to_string()
}

/// How many more characters a report's reason may take — negative once it is
/// over. Counted by the core, so a host's counter agrees with the check that
/// refuses the report (Swift and Kotlin would each count differently).
#[uniffi::export]
pub fn report_reason_remaining(reason: String) -> i64 {
    supermessage_core::safety::report_reason_remaining(&reason)
}

#[cfg(test)]
mod options_tests {
    use super::*;

    fn options(data_dir: &std::path::Path, legacy: &std::path::Path) -> CoreOptions {
        CoreOptions {
            data_dir: data_dir.to_string_lossy().into_owned(),
            process_name: "main".into(),
            legacy_data_dir: Some(legacy.to_string_lossy().into_owned()),
            keychain_access_group: None,
            legacy_keychain_access_group: None,
        }
    }

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("sm-ffi-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn an_earlier_builds_store_is_moved_and_opened_in_the_new_place() {
        let root = scratch("moved");
        let (group, app) = (root.join("group"), root.join("app"));
        std::fs::create_dir_all(app.join("store")).unwrap();
        std::fs::write(app.join("store/matrix-sdk-crypto.sqlite3"), "keys").unwrap();

        assert_eq!(settle_data_dir(&options(&group, &app)), group);
        assert!(group.join("store/matrix-sdk-crypto.sqlite3").exists());
        assert!(!app.join("store").exists());
    }

    /// Opening an empty directory with a stored session would mint a new
    /// encryption identity under the old device id. A move that fails must
    /// leave this launch on the old store instead.
    #[test]
    fn a_move_that_fails_keeps_using_the_store_where_it_is() {
        let root = scratch("failed");
        let app = root.join("app");
        std::fs::create_dir_all(app.join("store")).unwrap();
        std::fs::write(app.join("store/matrix-sdk-crypto.sqlite3"), "keys").unwrap();
        // A data directory under a *file* cannot be created.
        std::fs::write(root.join("blocker"), "").unwrap();
        let group = root.join("blocker/group");

        assert_eq!(settle_data_dir(&options(&group, &app)), app);
        assert!(app.join("store/matrix-sdk-crypto.sqlite3").exists());
    }

    #[test]
    fn a_process_with_nothing_to_move_uses_its_own_directory() {
        let root = scratch("nothing");
        let mut opts = options(&root.join("group"), &root.join("app"));
        assert_eq!(settle_data_dir(&opts), root.join("group"));
        opts.legacy_data_dir = None;
        assert_eq!(settle_data_dir(&opts), root.join("group"));
    }
}
