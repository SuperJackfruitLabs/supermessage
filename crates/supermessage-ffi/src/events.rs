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
        /// The whole record, **including the user id**.
        ///
        /// It used to be flattened to a bare display name here, on the
        /// grounds that a host should not show an id. That is still true and
        /// no host shows one — but it left the host with no stable identity
        /// to match a typing notice against, so "X is typing…" could not be
        /// cleared by X's message arriving. It sat there until the
        /// server-side timeout expired.
        users: Vec<supermessage_core::dto::TypingUserDto>,
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
        /// ACP's tool kind, when the harness said. Display text, never
        /// switched on.
        kind: Option<String>,
        status: String,
        /// What the call touched — paths, mostly.
        locations: Vec<String>,
        /// What it was given, and what it produced, bounded by the core.
        /// `None` from a harness that does not report them.
        input: Option<String>,
        output: Option<String>,
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
                users: p.users,
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
                kind: p.kind,
                status: p.status,
                locations: p.locations,
                input: p.input,
                output: p.output,
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
