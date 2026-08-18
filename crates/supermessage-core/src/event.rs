//! What the core says, and who it says it to.
//!
//! The core produces events — a room list moved, a timeline grew, an agent is
//! typing — and something has to carry them to whoever is watching. Until this
//! module existed that something was Tauri's `AppHandle`, which meant the
//! timeline could not be compiled, let alone run, without a webview underneath
//! it.
//!
//! [`EventSink`] is the whole of the replacement: one method, taking one
//! closed enum. A host implements it once. The desktop app translates each
//! variant back into the `app.emit(channel, payload)` call it always made, so
//! the bytes on that channel are unchanged; a native app receives the variants
//! directly and never serialises anything.
//!
//! **Why an enum rather than `(channel: &str, payload: Value)`.** The stringly
//! version would be a smaller change and would let every host stay ignorant of
//! what it is carrying. It would also let a typo compile, and would force the
//! core to choose JSON on behalf of hosts that may not want it. The enum makes
//! the set of things the core can say a closed list that a reviewer can read in
//! one screen, and a new variant breaks every host that has not handled it —
//! which is the correct amount of friction for adding a new channel.
//!
//! **Ordering is a correctness requirement, not a nicety.** [`DiffEnvelope`]
//! carries a `seq`, and the timeline's recovery logic is built on those
//! arriving in order (see `timeline`'s notes on coalescing a re-seed). A sink
//! that delivers concurrently — say, by spawning a task per event — will
//! reorder them and corrupt the reader's view in ways that look like a
//! rendering bug. Implementations must deliver in the order they were called.

use std::sync::Arc;

use crate::attachments::StagedAttachment;
use crate::dto::{DiffEnvelope, RoomSummary, TimelineRow};
use crate::live::{LivePayload, ToolPayload};
use crate::sync::ConnectionPayload;
use crate::timeline::TypingPayload;

/// Everything the core can tell a host about, one variant per channel.
///
/// The variants correspond exactly to the eight channels the desktop app has
/// always listened on. Their names are the channel names with the transport
/// removed — `sm://rooms/diff` is [`CoreEvent::RoomsDiff`] — and the mapping
/// back is written once, in the desktop host's sink.
#[derive(Debug, Clone)]
pub enum CoreEvent {
    /// Sync came up, went away, or failed. `sm://connection`.
    Connection(ConnectionPayload),
    /// The room list moved. `sm://rooms/diff`.
    RoomsDiff(DiffEnvelope<RoomSummary>),
    /// The focused room's timeline moved. `sm://timeline/diff`.
    TimelineDiff(DiffEnvelope<TimelineRow>),
    /// Who is typing in the focused room. `sm://typing`.
    Typing(TypingPayload),
    /// An agent's answer, as it is written. `sm://live`.
    Live(LivePayload),
    /// An agent's reasoning, as it is produced. `sm://thought`.
    Thought(LivePayload),
    /// A tool call an agent made this turn. `sm://tool`.
    Tool(ToolPayload),
    /// A file was staged for sending. `sm://attachment/staged`.
    AttachmentStaged(StagedAttachment),
}

/// Where [`CoreEvent`]s go.
///
/// Held by the core as `Arc<dyn EventSink>` wherever it used to hold an
/// `AppHandle`. `Send + Sync + 'static` because these fire from tokio worker
/// tasks and from matrix-sdk's event handlers, neither of which run on a
/// thread the host chose.
///
/// Implementations must not block: this is called from inside sync and
/// timeline processing, and a slow sink stalls the client rather than the UI.
/// Hand the event to a queue and return.
pub trait EventSink: Send + Sync + 'static {
    /// Deliver one event. See the module docs on ordering — implementations
    /// must preserve call order.
    fn emit(&self, event: CoreEvent);
}

/// A sink that drops everything, for tests and for a core with no host yet.
///
/// Useful precisely because it is not a mock: code under test that emits
/// through this is exercising the real path, and a test that cares what was
/// emitted implements its own recording sink instead.
pub struct NullSink;

impl EventSink for NullSink {
    fn emit(&self, _event: CoreEvent) {}
}

/// Convenience for the common `Arc<dyn EventSink>` the core stores.
pub fn null_sink() -> Arc<dyn EventSink> {
    Arc::new(NullSink)
}

/// Choosing files, which is the host's job rather than the core's.
///
/// `attachments` and `session` need a file from the person using the app.
/// On desktop that is a Tauri dialog; on iOS it is a SwiftUI document picker;
/// in a test it is a fixed path. None of that belongs in logic that otherwise
/// only knows about Matrix, so the core asks for a path and lets the host
/// decide how to obtain one.
///
/// Returning `None` means the person cancelled — an ordinary outcome, not an
/// error, and the caller must treat it as one.
#[async_trait::async_trait]
pub trait FilePicker: Send + Sync + 'static {
    /// Ask for one file to read. `None` if the person cancelled.
    async fn pick_file(&self) -> Option<std::path::PathBuf>;

    /// Ask where to write one, offering `suggested_name`. `None` if cancelled.
    ///
    /// Separate from [`Self::pick_file`] because the two are different
    /// questions to a person and different APIs to every host — an open panel
    /// and a save panel on macOS, a document picker and an export sheet on
    /// iOS. Collapsing them into one method would push that distinction into
    /// a flag the core has no opinion about.
    async fn save_file(&self, suggested_name: &str) -> Option<std::path::PathBuf>;
}

/// A picker that always cancels — the honest default where no host has
/// supplied one, and the behaviour a test wants unless it says otherwise.
pub struct NoFilePicker;

#[async_trait::async_trait]
impl FilePicker for NoFilePicker {
    async fn pick_file(&self) -> Option<std::path::PathBuf> {
        None
    }

    async fn save_file(&self, _suggested_name: &str) -> Option<std::path::PathBuf> {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// Records what the core said, so a test can assert on it without a
    /// webview, an app handle, or a running Tauri app — which was impossible
    /// before this module existed.
    struct RecordingSink(Mutex<Vec<CoreEvent>>);

    impl EventSink for RecordingSink {
        fn emit(&self, event: CoreEvent) {
            self.0.lock().expect("sink lock poisoned").push(event);
        }
    }

    #[test]
    fn a_sink_receives_what_the_core_emits() {
        let sink = Arc::new(RecordingSink(Mutex::new(Vec::new())));
        let as_trait: Arc<dyn EventSink> = sink.clone();

        as_trait.emit(CoreEvent::Connection(ConnectionPayload {
            state: "live",
            message: None,
        }));

        assert_eq!(sink.0.lock().unwrap().len(), 1);
    }

    #[test]
    fn the_null_sink_swallows_without_complaint() {
        // The point of `NullSink` is that a core with no host attached still
        // runs — every test that does not care about events relies on this.
        null_sink().emit(CoreEvent::Connection(ConnectionPayload {
            state: "offline",
            message: None,
        }));
    }

    #[tokio::test]
    async fn no_picker_cancels_rather_than_failing() {
        // Cancelling is an ordinary outcome and must not read as an error.
        assert!(NoFilePicker.pick_file().await.is_none());
    }
}
