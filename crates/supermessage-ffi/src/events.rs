//! Carrying the core's events out to a native host.
//!
//! The core emits [`CoreEvent`]; a Swift or Kotlin host implements
//! [`EventSink`]. [`HostSink`] is the piece in between, converting each
//! variant and handing it over.
//!
//! **Delivery order is the host's responsibility and it is not optional.**
//! UniFFI invokes a callback interface on whatever thread called it, and these
//! fire from tokio workers and matrix-sdk event handlers. The diff envelopes
//! carry `seq`, and the timeline's recovery logic assumes they arrive in the
//! order they were emitted. A host that dispatches each event onto a concurrent
//! queue will reorder them and corrupt the reader's view — and it will look
//! like a rendering bug, not a threading one.

use supermessage_core::event::{CoreEvent, EventSink as CoreSink};

use crate::diff::{RoomDiffEnvelope, TimelineDiffEnvelope};
use crate::ConnectionState;

/// One thing that happened, as the host sees it.
///
/// A flattened mirror of [`CoreEvent`]: same eight cases, with the two
/// generic envelopes replaced by their monomorphised forms.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum FfiEvent {
    Connection {
        state: ConnectionState,
    },
    RoomsDiff {
        envelope: RoomDiffEnvelope,
    },
    TimelineDiff {
        envelope: TimelineDiffEnvelope,
    },
    Typing {
        room_id: String,
        users: Vec<String>,
    },
    Live {
        room_id: String,
        seq: u64,
        text: String,
        done: bool,
    },
    Thought {
        room_id: String,
        seq: u64,
        text: String,
        done: bool,
    },
    Tool {
        room_id: String,
        seq: u64,
        tool_call_id: String,
        title: String,
        status: String,
    },
    AttachmentStaged {
        token: String,
        filename: String,
        size_bytes: u64,
        mime: String,
    },
}

/// What a native host implements to hear from the core.
#[uniffi::export(callback_interface)]
pub trait EventSink: Send + Sync {
    /// Deliver one event. Must not block, and must preserve call order — see
    /// this module's note on ordering.
    fn on_event(&self, event: FfiEvent);
}

/// Adapts a host's [`EventSink`] to the core's.
pub struct HostSink(pub Box<dyn EventSink>);

impl CoreSink for HostSink {
    fn emit(&self, event: CoreEvent) {
        self.0.on_event(match event {
            CoreEvent::Connection(p) => FfiEvent::Connection { state: p.into() },
            CoreEvent::RoomsDiff(e) => FfiEvent::RoomsDiff { envelope: e.into() },
            CoreEvent::TimelineDiff(e) => FfiEvent::TimelineDiff { envelope: e.into() },
            CoreEvent::Typing(p) => FfiEvent::Typing {
                room_id: p.room_id,
                // Only the display name reaches the host: the desktop app
                // renders exactly that, and a user id here would invite a
                // client to show one.
                users: p
                    .users
                    .into_iter()
                    .map(|u| u.display_name.unwrap_or(u.user_id))
                    .collect(),
            },
            CoreEvent::Live(p) => FfiEvent::Live {
                room_id: p.room_id,
                seq: p.seq,
                text: p.text,
                done: p.done,
            },
            CoreEvent::Thought(p) => FfiEvent::Thought {
                room_id: p.room_id,
                seq: p.seq,
                text: p.text,
                done: p.done,
            },
            CoreEvent::Tool(p) => FfiEvent::Tool {
                room_id: p.room_id,
                seq: p.seq,
                tool_call_id: p.tool_call_id,
                title: p.title,
                status: p.status,
            },
            CoreEvent::AttachmentStaged(m) => FfiEvent::AttachmentStaged {
                token: m.token,
                filename: m.filename,
                size_bytes: m.size_bytes,
                mime: m.mime,
            },
        });
    }
}
