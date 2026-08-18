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
    /// The `seq` matters: room-list diffs arriving afterwards carry increasing
    /// sequence numbers, and a host that applies one older than its snapshot
    /// would move rows that have already moved.
    pub fn rooms_snapshot(&self) -> Result<RoomsSnapshot, FfiError> {
        let (seq, rooms) = self.runtime.block_on(self.session.rooms_snapshot())?;
        Ok(RoomsSnapshot { seq, rooms })
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
    pub rooms: Vec<supermessage_core::dto::RoomSummary>,
}
