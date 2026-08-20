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

use std::path::PathBuf;
use std::sync::Arc;

use supermessage_core::event::EventSink as CoreSink;
use supermessage_core::secrets::KeyringStore;
use supermessage_core::session::Session;
use supermessage_core::sync::ConnectionPayload as CoreConnection;

pub use diff::{RoomDiffEnvelope, RoomDiffOp, TimelineDiffEnvelope, TimelineDiffOp};
pub use error::FfiError;
pub use events::{EventSink, FfiEvent};

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

#[uniffi::export]
impl Core {
    /// Build a core rooted at `data_dir`.
    ///
    /// `data_dir` is the host's to choose — an app-support directory on macOS,
    /// the app container on iOS. The core puts its stores under it and does
    /// not look outside it.
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Arc<Self> {
        install_tracing();

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("a multi-thread runtime must be constructible");

        Arc::new(Self {
            session: Arc::new(Session::new(
                PathBuf::from(data_dir),
                Box::new(KeyringStore),
            )),
            runtime,
        })
    }

    /// Where the connection currently stands, without waiting for the next
    /// transition. A host that has just launched needs this to render
    /// something truthful before any event arrives.
    pub fn connection_state(&self) -> ConnectionState {
        self.runtime
            .block_on(self.session.connection_state())
            .into()
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
        self.runtime.block_on(self.session.login_and_start(
            &homeserver,
            &username,
            &password,
            sink,
        ))?;
        Ok(())
    }

    /// Pick up a session stored from a previous run.
    ///
    /// `false` means there was nothing stored — an ordinary outcome on first
    /// launch, and the host should show its sign-in screen rather than an
    /// error.
    pub fn restore_session(&self, sink: Box<dyn EventSink>) -> Result<bool, FfiError> {
        let sink: Arc<dyn CoreSink> = Arc::new(events::HostSink(sink));
        Ok(self
            .runtime
            .block_on(self.session.restore_and_start(sink))?)
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
        let (seq, rooms) = self.runtime.block_on(self.session.rooms_snapshot())?;
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
        self.runtime
            .block_on(self.session.subscribe_timeline(&room_id, sink))?;
        Ok(())
    }

    /// Load older messages. `true` means the start of the room was reached and
    /// there is nothing more to ask for.
    pub fn timeline_paginate_back(&self, room_id: String, count: u16) -> Result<bool, FfiError> {
        Ok(self.runtime.block_on(
            self.session
                .focused_timeline()
                .paginate_back(&room_id, count),
        )?)
    }

    /// The focused timeline as of now: its room, the sequence number, and the
    /// items. A host that has just subscribed uses this rather than waiting
    /// for a diff that may not come until something changes.
    pub fn timeline_resync(&self) -> Result<TimelineSnapshot, FfiError> {
        let (room_id, seq, items) = self
            .runtime
            .block_on(self.session.focused_timeline().snapshot())?;
        Ok(TimelineSnapshot {
            room_id,
            seq,
            items,
        })
    }

    /// Mark the room read up to its latest event.
    pub fn mark_room_read(&self, room_id: String) -> Result<(), FfiError> {
        self.runtime
            .block_on(self.session.focused_timeline().mark_read(&room_id))?;
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
        self.runtime.block_on(
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
        self.runtime
            .block_on(
                self.session
                    .focused_timeline()
                    .send_reply(&room_id, &body, &in_reply_to),
            )?;
        Ok(())
    }

    /// Set how loudly a room may interrupt. `Default` unsets the room's own
    /// rule so the account default applies again.
    pub fn set_room_notifications(
        &self,
        room_id: String,
        mode: supermessage_core::room_info::NotificationMode,
    ) -> Result<(), FfiError> {
        self.runtime
            .block_on(self.session.set_room_notification_mode(&room_id, mode))?;
        Ok(())
    }

    /// Pin or unpin a room — the `m.favourite` tag, so it travels between
    /// clients.
    pub fn set_room_pinned(&self, room_id: String, pinned: bool) -> Result<(), FfiError> {
        self.runtime
            .block_on(self.session.set_room_pinned(&room_id, pinned))?;
        Ok(())
    }

    /// Rewrite a message this account sent.
    pub fn edit_message(
        &self,
        room_id: String,
        event_id: String,
        body: String,
    ) -> Result<(), FfiError> {
        self.runtime.block_on(
            self.session
                .focused_timeline()
                .edit_text(&room_id, &event_id, &body),
        )?;
        Ok(())
    }

    /// Delete a message — a Matrix redaction, which is permanent and visible
    /// to the whole room.
    pub fn delete_message(&self, room_id: String, event_id: String) -> Result<(), FfiError> {
        self.runtime
            .block_on(self.session.focused_timeline().redact(&room_id, &event_id))?;
        Ok(())
    }

    /// Add or remove a reaction. Returns whether the reaction is now present.
    pub fn toggle_reaction(
        &self,
        room_id: String,
        event_id: String,
        key: String,
    ) -> Result<bool, FfiError> {
        Ok(self.runtime.block_on(
            self.session
                .focused_timeline()
                .toggle_reaction(&room_id, &event_id, &key),
        )?)
    }

    /// Tell the room whether this account is typing.
    pub fn set_typing(&self, room_id: String, typing: bool) -> Result<(), FfiError> {
        self.runtime
            .block_on(self.session.focused_timeline().set_typing(&room_id, typing))?;
        Ok(())
    }

    /// Accept an invitation, or join a room already known by id.
    pub fn join_room(&self, room_id: String) -> Result<(), FfiError> {
        self.runtime.block_on(self.session.join_room(&room_id))?;
        Ok(())
    }

    /// Leave a room. It disappears from the roster on the next diff.
    pub fn leave_room(&self, room_id: String) -> Result<(), FfiError> {
        self.runtime.block_on(self.session.leave_room(&room_id))?;
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
        Ok(self
            .runtime
            .block_on(self.session.create_room(&name, &invite, is_direct))?)
    }

    /// Join by alias (`#room:server`) or id, returning the id joined.
    pub fn join_room_by_alias(&self, alias_or_id: String) -> Result<String, FfiError> {
        Ok(self
            .runtime
            .block_on(self.session.join_room_by_alias(&alias_or_id))?)
    }

    /// Invite someone to a room.
    pub fn invite_user(&self, room_id: String, user_id: String) -> Result<(), FfiError> {
        self.runtime
            .block_on(self.session.invite_user(&room_id, &user_id))?;
        Ok(())
    }

    /// A room's avatar as a `data:` URI, if it has one.
    pub fn room_avatar(&self, room_id: String) -> Result<Option<String>, FfiError> {
        Ok(self.runtime.block_on(self.session.room_avatar(&room_id))?)
    }

    /// A member's avatar as a `data:` URI, given its `mxc:` URI.
    pub fn member_avatar(&self, mxc_uri: String) -> Result<Option<String>, FfiError> {
        Ok(self
            .runtime
            .block_on(self.session.member_avatar(&mxc_uri))?)
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
        Ok(self.runtime.block_on(self.session.media_fetch(&event_id))?)
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
        let meta = self.runtime.block_on(async {
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
    pub fn attachment_send(&self, room_id: String, token: String) -> Result<(), FfiError> {
        let staged = self.session.staged_attachments();
        let focused = self.session.focused_timeline();
        self.runtime
            .block_on(supermessage_core::attachments::send_staged(
                &self.session,
                &focused,
                &staged,
                &room_id,
                &token,
            ))?;
        Ok(())
    }

    /// Throw a staged file away without sending it.
    pub fn attachment_discard(&self, token: String) {
        self.session.staged_attachments().discard(&token);
    }

    /// The spaces this account is in, for the roster's rail.
    pub fn spaces_list(&self) -> Result<Vec<supermessage_core::spaces::SpaceSummary>, FfiError> {
        Ok(self.runtime.block_on(self.session.spaces_list())?)
    }

    /// Filter the room list to a space, or `None` to clear the filter.
    ///
    /// The filter lives in the core, not the host: the next room-list diff
    /// reflects it, so both hosts see the same rooms for the same selection.
    pub fn space_select(&self, space_id: Option<String>) -> Result<(), FfiError> {
        self.runtime
            .block_on(self.session.select_space(space_id.as_deref()))?;
        Ok(())
    }

    /// Search messages — in one room when `room_id` is given, across every
    /// room this account can see otherwise.
    pub fn search_messages(
        &self,
        term: String,
        room_id: Option<String>,
    ) -> Result<Vec<supermessage_core::search::SearchResultDto>, FfiError> {
        Ok(self
            .runtime
            .block_on(self.session.search_messages(&term, room_id.as_deref()))?)
    }

    /// Everything the info panel shows about a room.
    pub fn room_info(
        &self,
        room_id: String,
    ) -> Result<supermessage_core::room_info::RoomInfoDto, FfiError> {
        Ok(self.runtime.block_on(self.session.room_info(&room_id))?)
    }

    /// Who invited this account to a room, or `None`.
    pub fn room_inviter(&self, room_id: String) -> Result<Option<String>, FfiError> {
        Ok(self.runtime.block_on(self.session.room_inviter(&room_id))?)
    }

    /// Who this app is signed in as, and where.
    pub fn account(&self) -> Result<supermessage_core::dto::AccountDto, FfiError> {
        Ok(self.runtime.block_on(self.session.account())?)
    }

    /// Sign out and wipe the local stores.
    pub fn logout(&self) -> Result<(), FfiError> {
        self.runtime.block_on(self.session.logout())?;
        Ok(())
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
