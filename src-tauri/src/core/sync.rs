//! Drives the SDK's `SyncService` and publishes connection health to the
//! webview.
//!
//! `SyncService` wraps the room-list sliding sync and the encryption sync
//! into one supervised unit (MSC4186 Simplified Sliding Sync, which the
//! homeserver advertises — the SDK negotiates it, nothing here configures
//! it). This module starts that service and mirrors its state onto the
//! `sm://connection` event so the webview's connection indicator stays live
//! without polling.
//!
//! `Session` is the sole owner of the [`SyncHandle`] this produces (see
//! `core::session`) — a dropped handle with nothing left running the sync
//! loops would silently kill sync, so nothing here is meant to be used
//! standalone.

use std::sync::Arc;

use matrix_sdk::Client;
use matrix_sdk_ui::sync_service::{State, SyncService};
use matrix_sdk_ui::RoomListService;
use serde::Serialize;
use tauri::{AppHandle, Emitter};
use tokio::task::JoinHandle;

use super::error::{CoreError, CoreResult};

/// How many timeline events the room-list sliding sync asks for per room.
///
/// The SDK defaults this to **1** (`matrix-sdk-ui`'s
/// `room_list_service::DEFAULT_LIST_TIMELINE_LIMIT`), and that default is
/// actively hostile to a timeline anyone is reading. A sync that returns more
/// events than the limit is flagged `limited` and carries a fresh gap token,
/// and `matrix-sdk`'s event cache answers exactly that case by unloading every
/// chunk but the last one (`event_cache::caches::room::state`,
/// `shrink_to_last_chunk`) — deliberately, so a client cannot render across a
/// gap it has not back-filled. The focused timeline then sees its items
/// vanish, re-seeds, and the reader watches the room empty and refill.
///
/// At 1, *two messages between syncs* is enough to trigger it. Live logs
/// showed it firing about once a minute on a quiet room.
///
/// The number is a trade, not a truth: every room in the roster carries this
/// many events on every sync, so raising it costs bandwidth and sync latency
/// across the whole list. 32 is chosen to comfortably exceed the traffic a
/// single room sees between syncs while staying close to `INITIAL_PAGE_SIZE`
/// (30) — the depth a freshly seeded timeline holds anyway, so a limited sync
/// that does slip through unloads to roughly what the reader already had.
const ROOM_LIST_TIMELINE_LIMIT: u32 = 32;

/// The one thing about that number that is not a matter of taste.
///
/// At 1 — the SDK's own default — a room receiving two events between syncs
/// comes back `limited` with a fresh gap, and the event cache answers a
/// limited sync by unloading every chunk but the last. The focused timeline
/// then empties out from under its subscription and has to re-seed, which the
/// reader sees. Tuning the number up or down is fine; going back to the
/// default is a regression, and this fails the build rather than the room.
const _: () = assert!(
    ROOM_LIST_TIMELINE_LIMIT > 1,
    "a limit of 1 makes routine syncs unload the timeline"
);

/// Tauri event channel carrying connection health for the webview's
/// connection indicator.
pub const CONNECTION_EVENT: &str = "sm://connection";

/// What the webview is told about the connection, whether it was pushed on
/// [`CONNECTION_EVENT`] or pulled by the `connection_state` command.
#[derive(Debug, Serialize)]
pub struct ConnectionPayload {
    pub state: &'static str,
    pub message: Option<String>,
}

impl ConnectionPayload {
    /// The state to report when there is no session, and so no sync service
    /// to ask.
    pub fn offline() -> Self {
        Self {
            state: "offline",
            message: None,
        }
    }
}

/// A running [`SyncService`] plus the background task mirroring its state
/// onto [`CONNECTION_EVENT`].
///
/// Owned by `Session` (see the `RULING` in this task's brief): a dropped
/// `SyncHandle` must not leave the state-watching task running forever, so
/// `Drop` aborts it defensively. That is *not* a substitute for calling
/// [`SyncHandle::stop`] — aborting the watcher does not stop the SDK's own
/// sync loops, which is why `Session::stop_sync` always awaits `stop()`
/// before dropping the handle rather than relying on `Drop` alone.
pub struct SyncHandle {
    service: Arc<SyncService>,
    watcher: JoinHandle<()>,
}

impl SyncHandle {
    /// Stops the underlying sync loops and the state-watching task.
    pub async fn stop(&self) {
        self.service.stop().await;
        self.watcher.abort();
    }

    /// The room list service driving the room list — consumed by a later
    /// task to project rooms/timelines to the webview.
    pub fn room_list_service(&self) -> Arc<RoomListService> {
        self.service.room_list_service()
    }

    /// The connection health as of right now.
    ///
    /// [`CONNECTION_EVENT`] only fires on transitions, so a webview that
    /// starts up mid-session — a reload, an HMR module swap — has no way to
    /// learn a state whose transition happened before it was listening. This
    /// is how it asks instead.
    pub fn connection(&self) -> ConnectionPayload {
        connection_payload(&self.service.state().get())
    }
}

impl Drop for SyncHandle {
    fn drop(&mut self) {
        // Belt and suspenders: guarantees the watcher task never outlives
        // the handle even if a caller drops `SyncHandle` without awaiting
        // `stop()` first (in normal operation `Session::stop_sync` always
        // does). Can't await `SyncService::stop()` here — `Drop` is sync —
        // so this only stops the watcher, not the SDK's own sync loops.
        self.watcher.abort();
    }
}

/// Builds a [`SyncService`] for `client`, starts it, and spawns a task that
/// mirrors [`SyncService::state`] onto [`CONNECTION_EVENT`] for as long as
/// the returned [`SyncHandle`] lives.
pub async fn start(client: &Client, app: AppHandle) -> CoreResult<SyncHandle> {
    let service = SyncService::builder(client.clone())
        // See `ROOM_LIST_TIMELINE_LIMIT`: the SDK default of 1 makes
        // ordinary syncs unload the focused timeline out from under its
        // subscription.
        .with_room_list_timeline_limit(ROOM_LIST_TIMELINE_LIMIT)
        .build()
        .await
        .map_err(|e| CoreError::Network(e.to_string()))?;
    let service = Arc::new(service);

    service.start().await;

    // Subscribed after `start()`, whose synchronous state transition to
    // `Running` is therefore already reflected in `.get()` below — emit it
    // explicitly so the UI learns "live" right away, then let `.next()`
    // pick up every subsequent change (it only resolves on updates *after*
    // subscription, so skipping this step would leave the UI reporting
    // whatever it started at, `offline`, until the next transition).
    let mut states = service.state();
    emit_connection_state(&app, &states.get());

    let watcher_app = app.clone();
    let watcher = tokio::spawn(async move {
        while let Some(state) = states.next().await {
            emit_connection_state(&watcher_app, &state);
        }
    });

    Ok(SyncHandle { service, watcher })
}

/// Maps an SDK sync state onto the UI's connection vocabulary.
///
/// Exhaustive and wildcard-free on purpose: if the SDK ever adds a `State`
/// variant, this must fail to compile rather than silently misreport the
/// connection as something it isn't.
fn connection_state_name(state: &State) -> &'static str {
    match state {
        State::Idle => "offline",
        State::Terminated => "offline",
        // Not documented as reachable without `SyncServiceBuilder::with_offline_mode`,
        // which nothing here opts into — kept explicit anyway so the match
        // stays exhaustive if that ever changes.
        State::Offline => "offline",
        State::Running => "live",
        State::Error(_) => "error",
    }
}

/// An SDK sync state as the webview is told about it — the one place that
/// mapping is made, so a pushed event and a pulled answer can never disagree.
fn connection_payload(state: &State) -> ConnectionPayload {
    ConnectionPayload {
        state: connection_state_name(state),
        message: match state {
            State::Error(err) => Some(err.to_string()),
            _ => None,
        },
    }
}

fn emit_connection_state(app: &AppHandle, state: &State) {
    let payload = connection_payload(state);
    if let Err(err) = app.emit(CONNECTION_EVENT, &payload) {
        tracing::warn!(error = %err, "failed to emit {CONNECTION_EVENT}");
    }
}

#[cfg(test)]
mod tests {
    use matrix_sdk_ui::sync_service::State;

    #[test]
    fn maps_sdk_state_to_the_ui_vocabulary() {
        assert_eq!(super::connection_state_name(&State::Idle), "offline");
        assert_eq!(super::connection_state_name(&State::Running), "live");
        assert_eq!(super::connection_state_name(&State::Terminated), "offline");
    }

    #[test]
    fn a_pulled_answer_says_the_same_thing_a_pushed_event_would() {
        // The `connection_state` command exists so a reloaded webview can
        // ask instead of waiting for a transition that has already happened.
        // Its answer going through the same mapping as the event is what
        // makes the two interchangeable — a second mapping that drifted
        // would show one thing on reload and another the moment the next
        // transition landed.
        let live = super::connection_payload(&State::Running);
        assert_eq!(live.state, "live");
        assert_eq!(live.message, None);

        let idle = super::connection_payload(&State::Idle);
        assert_eq!(idle.state, "offline");
        assert_eq!(idle.message, None);

        // And with nothing running at all, the honest answer is the same one
        // the store already defaults to.
        assert_eq!(super::ConnectionPayload::offline().state, "offline");
    }
}
