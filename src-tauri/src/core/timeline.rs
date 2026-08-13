//! Streams the focused room's timeline to the webview as versioned diffs,
//! and drives backward pagination and sending from the same subscription.
//!
//! Mirrors `core::rooms`'s shape (read that module's doc comment first): the
//! streaming task owns a materialized `Vec<TimelineItemDto>` alongside the
//! last emitted sequence number, updated inside the same critical section
//! that emits, so [`FocusedTimeline::snapshot`] can serve a resync out of
//! the live task's own state instead of opening a second, independently
//! numbered subscription.
//!
//! Unlike the room list, only **one** timeline is ever subscribed at a
//! time — the focused room. [`FocusedTimeline::subscribe`] always drops the
//! previous subscription first (see its doc comment), so switching rooms
//! can never leave a background timeline's task running.

use std::sync::{Arc, Mutex};

use eyeball_im::VectorDiff;
use futures_util::{pin_mut, StreamExt};
use imbl::Vector;
use matrix_sdk::ruma::events::room::message::RoomMessageEventContent;
use matrix_sdk::ruma::events::AnyMessageLikeEventContent;
use matrix_sdk::ruma::{MilliSecondsSinceUnixEpoch, RoomId, UserId};
use matrix_sdk::Client;
use matrix_sdk_ui::timeline::{
    EventSendState, EventTimelineItem, RoomExt, Timeline, TimelineDetails, TimelineItem,
    TimelineItemKind, VirtualTimelineItem,
};
use tauri::{AppHandle, Emitter};
use tokio::task::JoinHandle;

use super::dto::{apply_ops, project_diff, DiffEnvelope, DiffOp, SeqCounter, TimelineItemDto};
use super::error::{CoreError, CoreResult};

/// Tauri event channel carrying timeline diffs for the webview's timeline
/// store.
pub const TIMELINE_DIFF_EVENT: &str = "sm://timeline/diff";

/// The `DiffEnvelope::channel` value used for every timeline envelope.
const TIMELINE_CHANNEL: &str = "timeline";

/// The sequence number of the last diff folded into the materialized item
/// list, and the resulting list itself — always mutually consistent (see
/// `core::rooms::RoomListHandle`'s identical `RoomListSnapshot` for why).
type TimelineState = (u64, Vec<TimelineItemDto>);

/// What [`FocusedTimeline::snapshot`] hands the webview: `(subject, seq,
/// items)` — the room id the snapshot belongs to, followed by the same
/// `(seq, items)` pair the room list returns.
///
/// The subject is not decoration. Spec §4 defines the sequence as
/// "monotonic, per channel+**subject**", and the timeline channel's subject
/// changes every time the user switches rooms. A snapshot without it cannot
/// be checked against the room the webview currently has focused, so a
/// resync issued during a room switch — where the fast mutex read behind
/// this call easily beats the slow `room.timeline()` build behind
/// `timeline_subscribe` — would be served out of the *previous* room's
/// still-installed handle and silently install that room's messages, at that
/// room's high seq, under the new room's header. The new room's stream then
/// starts back at seq 1 and is discarded as duplicates, so the wrong
/// messages stay until the next room switch. Returning the subject lets the
/// webview reject exactly that.
pub type TimelineSnapshot = (String, u64, Vec<TimelineItemDto>);

/// Build a [`TimelineItemDto`] from already-extracted parts.
///
/// Pure and SDK-free on purpose: it is the part `project_item` delegates
/// to, so the projection logic is testable without a live homeserver or SDK
/// timeline item.
#[allow(clippy::too_many_arguments)]
pub fn project_item_parts(
    id: &str,
    kind: &str,
    sender: Option<&str>,
    sender_display_name: Option<&str>,
    body: Option<&str>,
    timestamp_ms: Option<u64>,
    is_own: bool,
    send_state: Option<&str>,
) -> TimelineItemDto {
    TimelineItemDto {
        id: id.to_string(),
        kind: kind.to_string(),
        sender: sender.map(str::to_string),
        sender_display_name: sender_display_name.map(str::to_string),
        body: body.map(str::to_string),
        timestamp_ms,
        is_own,
        send_state: send_state.map(str::to_string),
    }
}

/// `MilliSecondsSinceUnixEpoch` wraps `js_int::UInt`, which only converts to
/// `i64`/`i128` directly; it is always non-negative and within `i64::MAX`,
/// so the round trip through `i64` is exact (same reasoning as
/// `core::rooms::project_room`'s identical conversion).
fn timestamp_to_millis(ts: MilliSecondsSinceUnixEpoch) -> u64 {
    i64::from(ts.get()) as u64
}

/// Maps an SDK send state onto the wire vocabulary.
///
/// Exhaustive and wildcard-free on purpose: if the SDK ever adds an
/// `EventSendState` variant, this must fail to compile rather than silently
/// misreport a message's delivery state.
fn send_state_name(state: &EventSendState) -> &'static str {
    match state {
        EventSendState::NotSentYet { .. } => "notSentYet",
        EventSendState::SendingFailed { .. } => "sendingFailed",
        EventSendState::Sent { .. } => "sent",
    }
}

/// The stable id used for an event item: its event id once the server has
/// echoed it back, or its transaction id while still a local echo.
fn event_item_id(event: &EventTimelineItem) -> String {
    use matrix_sdk_ui::timeline::TimelineEventItemId;
    match event.identifier() {
        TimelineEventItemId::TransactionId(txn) => txn.to_string(),
        TimelineEventItemId::EventId(id) => id.to_string(),
    }
}

/// Project an SDK event item into the wire [`TimelineItemDto`].
fn project_event_item(event: &EventTimelineItem, own_user: &UserId) -> TimelineItemDto {
    let id = event_item_id(event);
    let kind = event
        .content()
        .event_type_str()
        .unwrap_or_else(|| "unknown".to_string());
    let sender = event.sender().to_string();
    let sender_display_name = match event.sender_profile() {
        TimelineDetails::Ready(profile) => profile.display_name.clone(),
        _ => None,
    };
    let body = event.content().as_message().map(|m| m.body().to_string());
    let timestamp_ms = timestamp_to_millis(event.timestamp());
    let is_own = event.sender() == own_user;
    let send_state = event.send_state().map(send_state_name);

    project_item_parts(
        &id,
        &kind,
        Some(&sender),
        sender_display_name.as_deref(),
        body.as_deref(),
        Some(timestamp_ms),
        is_own,
        send_state,
    )
}

/// Project an SDK virtual item (date divider, read marker, timeline start)
/// into the wire [`TimelineItemDto`]. These carry no sender/body/ownership,
/// only an id and, for date dividers, a timestamp.
fn project_virtual_item(
    item: &TimelineItem,
    virtual_item: &VirtualTimelineItem,
) -> TimelineItemDto {
    let id = item.unique_id().0.as_str();
    let (kind, timestamp_ms) = match virtual_item {
        VirtualTimelineItem::DateDivider(ts) => ("dateDivider", Some(timestamp_to_millis(*ts))),
        VirtualTimelineItem::ReadMarker => ("readMarker", None),
        VirtualTimelineItem::TimelineStart => ("timelineStart", None),
    };
    project_item_parts(id, kind, None, None, None, timestamp_ms, false, None)
}

/// Project an SDK [`TimelineItem`] into the wire [`TimelineItemDto`].
///
/// A thin adapter: it only extracts values and delegates to
/// [`project_item_parts`] (via [`project_event_item`]/[`project_virtual_item`]),
/// which carry the actual logic and are what get unit-tested.
///
/// Always returns `Some` today — `TimelineItemKind` is exhaustively
/// `Event`/`Virtual` and both are handled — but stays `Option` in the
/// signature so a future item kind this format can't usefully represent can
/// be dropped without changing callers.
pub fn project_item(item: &TimelineItem, own_user: &UserId) -> Option<TimelineItemDto> {
    Some(match item.kind() {
        TimelineItemKind::Event(event) => project_event_item(event, own_user),
        TimelineItemKind::Virtual(virtual_item) => project_virtual_item(item, virtual_item),
    })
}

/// Project a raw batch of SDK diffs into the wire ops for one envelope.
///
/// `project_item` is total over `TimelineItemKind` (see its doc comment),
/// so the `expect` below never fires in practice; it exists only because
/// `project_diff`'s closure must return `T`, not `Option<T>`.
fn project_batch(
    batch: Vec<VectorDiff<Arc<TimelineItem>>>,
    own_user: &UserId,
) -> Vec<DiffOp<TimelineItemDto>> {
    batch
        .into_iter()
        .map(|diff| {
            project_diff(diff, |item| {
                project_item(&item, own_user).expect("project_item is total over TimelineItemKind")
            })
        })
        .collect()
}

/// Project the initial `Vector` `Timeline::subscribe` returns into the
/// values for a single seeding `Reset` op.
fn project_initial(items: &Vector<Arc<TimelineItem>>, own_user: &UserId) -> Vec<TimelineItemDto> {
    items
        .iter()
        .filter_map(|item| project_item(item, own_user))
        .collect()
}

/// Owns the background task streaming one room's timeline to the webview,
/// plus the `Timeline` itself so [`FocusedTimeline::paginate_back`] and
/// [`FocusedTimeline::send_text`] can drive it.
///
/// Mirrors `core::rooms::RoomListHandle`'s shape: dropping a
/// `TimelineHandle`, or calling `stop_and_join` on it, aborts the streaming
/// task. [`FocusedTimeline`] is the sole owner, replacing and stopping any
/// previous handle before storing a new one.
pub struct TimelineHandle {
    /// The room this subscription is for — the `subject` every envelope it
    /// emits is stamped with, and the one [`TimelineHandle::snapshot`]
    /// returns so the webview can tell whose messages it is being handed.
    room_id: String,
    timeline: Arc<Timeline>,
    state: Arc<Mutex<TimelineState>>,
    task: JoinHandle<()>,
}

impl TimelineHandle {
    /// Stops the background streaming task and waits for it to actually
    /// finish, for the same reason as `core::rooms::RoomListHandle`'s
    /// identical method: the task transitively holds the `Client` and so
    /// keeps the store's SQLite files open, and `Session::logout` deletes
    /// those files.
    async fn stop_and_join(&mut self) {
        self.task.abort();
        let _ = std::pin::Pin::new(&mut self.task).await;
    }

    /// A snapshot of the timeline as of the last diff this handle's task has
    /// applied, plus the sequence number of that diff and the room it
    /// belongs to — read directly out of the same state the streaming task
    /// maintains, not from a second subscription. See
    /// `core::rooms::RoomListHandle::snapshot`'s doc comment for why that
    /// distinction matters, and [`TimelineSnapshot`] for why the room id
    /// travels with it.
    fn snapshot(&self) -> CoreResult<TimelineSnapshot> {
        self.state
            .lock()
            .map(|guard| (self.room_id.clone(), guard.0, guard.1.clone()))
            .map_err(|_| CoreError::Protocol("timeline state lock poisoned".into()))
    }
}

impl Drop for TimelineHandle {
    fn drop(&mut self) {
        // Belt and suspenders, same reasoning as `RoomListHandle`'s `Drop`:
        // a `TimelineHandle` no one stopped explicitly must not leave its
        // task running forever.
        self.task.abort();
    }
}

/// Tauri managed state holding the currently focused room's timeline
/// subscription, if any. Exactly one at a time — see this module's doc
/// comment.
#[derive(Default)]
pub struct FocusedTimeline(Mutex<Option<TimelineHandle>>);

impl FocusedTimeline {
    /// Subscribes to `room_id`'s timeline, replacing (and stopping) any
    /// timeline already focused.
    ///
    /// The previous subscription is dropped *before* the new one is built —
    /// not swapped afterwards like `Session::start_room_list` does for the
    /// room list — because only one timeline is ever meant to exist:
    /// dropping first means a slow `room.timeline()`/`subscribe()` call
    /// against the new room can never overlap with the old room's task still
    /// emitting onto the same event.
    pub async fn subscribe(
        &self,
        client: &Client,
        room_id: &str,
        app: AppHandle,
    ) -> CoreResult<()> {
        // Waits for the previous room's task to be gone, not merely
        // cancelled, before the (slow) build below starts — so it cannot
        // still be emitting onto `TIMELINE_DIFF_EVENT` while the webview is
        // already tracking the new room.
        self.clear_and_join().await;

        let parsed_room_id =
            RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let room = client
            .get_room(&parsed_room_id)
            .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;
        let timeline = room
            .timeline()
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))?;
        let timeline = Arc::new(timeline);

        // Required to compute `TimelineItemDto::is_own` — a client with no
        // user id can't meaningfully own a subscription in the first place.
        let own_user = client.user_id().ok_or(CoreError::NotReady)?.to_owned();

        let (initial, stream) = timeline.subscribe().await;

        // Starts at `(0, [])`: "before any diff has been folded in, the
        // timeline is empty" — consistent with `SeqCounter` starting at 1,
        // since the first envelope (seq 1, the seeding `Reset` below) is
        // exactly what turns this into the true state.
        let state: Arc<Mutex<TimelineState>> = Arc::new(Mutex::new((0, Vec::new())));
        let task_state = Arc::clone(&state);

        let subject = room_id.to_string();
        let task = tokio::spawn(async move {
            pin_mut!(stream);
            let mut seq = SeqCounter::default();

            // The initial `Vector` `subscribe()` returns becomes the
            // stream's first envelope as a single `Reset`, so the webview
            // always starts from a known state instead of an empty list it
            // has to guess is complete.
            let ops = vec![DiffOp::Reset {
                values: project_initial(&initial, &own_user),
            }];
            emit_ops(&app, &task_state, &mut seq, &subject, ops);

            while let Some(batch) = stream.next().await {
                let ops = project_batch(batch, &own_user);
                emit_ops(&app, &task_state, &mut seq, &subject, ops);
            }
        });

        *self
            .0
            .lock()
            .map_err(|_| CoreError::Protocol("focused timeline lock poisoned".into()))? =
            Some(TimelineHandle {
                room_id: room_id.to_string(),
                timeline,
                state,
                task,
            });
        Ok(())
    }

    /// Paginates the focused timeline backwards by up to `count` events.
    /// Returns `true` when the start of the timeline was reached.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused.
    pub async fn paginate_back(&self, count: u16) -> CoreResult<bool> {
        let timeline = self.active_timeline()?;
        timeline
            .paginate_backwards(count)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))
    }

    /// Sends a plain-text message to the focused room.
    ///
    /// Does not emit anything itself: `Timeline::send` adds the local echo
    /// to the timeline, which arrives at the webview through the same diff
    /// stream `subscribe` set up — emitting it again here would show the
    /// message twice.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused.
    pub async fn send_text(&self, body: &str) -> CoreResult<()> {
        let timeline = self.active_timeline()?;
        let content =
            AnyMessageLikeEventContent::RoomMessage(RoomMessageEventContent::text_plain(body));
        timeline
            .send(content)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))?;
        Ok(())
    }

    /// A snapshot of the focused timeline as of the last diff its streaming
    /// task has applied, plus the sequence number of that diff — read
    /// directly out of the live task's own state (see
    /// `TimelineHandle::snapshot`), not from a second subscription.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused.
    pub async fn snapshot(&self) -> CoreResult<TimelineSnapshot> {
        let handle = self
            .0
            .lock()
            .map_err(|_| CoreError::Protocol("focused timeline lock poisoned".into()))?;
        handle.as_ref().ok_or(CoreError::NotReady)?.snapshot()
    }

    /// Clones the currently focused `Timeline`, if any. `Timeline` is
    /// wrapped in `Arc` on the handle, so this is cheap.
    fn active_timeline(&self) -> CoreResult<Arc<Timeline>> {
        let handle = self
            .0
            .lock()
            .map_err(|_| CoreError::Protocol("focused timeline lock poisoned".into()))?;
        Ok(Arc::clone(
            &handle.as_ref().ok_or(CoreError::NotReady)?.timeline,
        ))
    }

    /// Stops and drops the currently focused subscription, if any, and waits
    /// for its streaming task to actually finish before returning. A safe
    /// no-op when nothing is focused.
    ///
    /// `Session::logout` calls this before it wipes the encrypted store off
    /// disk. Merely aborting is not enough there: the task holds
    /// `Arc<Timeline>` -> `Room` -> `Client`, so until it has actually been
    /// dropped the store's SQLite files are still open — which on Windows
    /// makes `remove_dir_all` fail, and fail *after* the store passphrase
    /// has already been deleted. See `Session::logout` for what that costs.
    pub async fn clear_and_join(&self) {
        let previous = self.take();
        if let Some(mut previous) = previous {
            previous.stop_and_join().await;
        }
    }

    /// Takes the currently focused handle out, leaving nothing focused.
    ///
    /// The `Mutex` is `std::sync::Mutex`, so the guard cannot be held across
    /// an await — taking the handle out under the lock and stopping it after
    /// the guard is dropped is what keeps [`Self::clear_and_join`] legal.
    fn take(&self) -> Option<TimelineHandle> {
        self.0
            .lock()
            .expect("focused timeline lock poisoned by an earlier panic")
            .take()
    }
}

/// Folds `ops` into the materialized snapshot under one critical section,
/// then emits the resulting envelope — mirroring
/// `core::rooms::spawn_room_list`'s identical fold-then-emit sequencing (see
/// that function's doc comment for why the fold must happen before the lock
/// is released, and the lock must be released before emitting).
fn emit_ops(
    app: &AppHandle,
    state: &Arc<Mutex<TimelineState>>,
    seq: &mut SeqCounter,
    subject: &str,
    ops: Vec<DiffOp<TimelineItemDto>>,
) {
    let seq_no = seq.next();
    {
        let mut guard = state
            .lock()
            .expect("timeline state lock poisoned by an earlier panic");
        apply_ops(&mut guard.1, &ops);
        guard.0 = seq_no;
    }

    let envelope = DiffEnvelope {
        channel: TIMELINE_CHANNEL.into(),
        subject: subject.to_string(),
        seq: seq_no,
        ops,
    };
    if let Err(err) = app.emit(TIMELINE_DIFF_EVENT, &envelope) {
        tracing::warn!(error = %err, "failed to emit {TIMELINE_DIFF_EVENT}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn projects_a_text_message_with_ownership() {
        let dto = project_item_parts(
            "$e1",
            "m.room.message",
            Some("@me:x.org"),
            Some("Me"),
            Some("hello"),
            Some(1_700_000_000_000),
            true,
            None,
        );
        assert_eq!(dto.kind, "m.room.message");
        assert_eq!(dto.body.as_deref(), Some("hello"));
        assert!(dto.is_own);
    }

    #[test]
    fn virtual_items_are_projected_with_their_own_kind() {
        let dto = project_item_parts("vd1", "dateDivider", None, None, None, None, false, None);
        assert_eq!(dto.kind, "dateDivider");
        assert!(dto.sender.is_none());
    }

    #[test]
    fn send_state_names_are_mapped_to_the_wire_vocabulary() {
        assert_eq!(
            send_state_name(&EventSendState::NotSentYet { progress: None }),
            "notSentYet"
        );
        assert_eq!(
            send_state_name(&EventSendState::Sent {
                event_id: <&matrix_sdk::ruma::EventId>::try_from("$abc:example.org")
                    .unwrap()
                    .to_owned(),
            }),
            "sent"
        );
        // `SendingFailed`'s wire mapping only depends on the variant, not
        // the error payload, so any `matrix_sdk::Error` value nothing else
        // in this crate can construct more directly will do — an IO error
        // is the simplest one with a public constructor.
        let error = Arc::new(matrix_sdk::Error::Io(std::io::Error::other("boom")));
        assert_eq!(
            send_state_name(&EventSendState::SendingFailed {
                error,
                is_recoverable: false
            }),
            "sendingFailed"
        );
    }

    // `TimelineHandle` holds a live `Arc<Timeline>`, which (unlike
    // `RoomListHandle`'s state-only fields) has no test-friendly
    // constructor outside a real `room.timeline()` call against a synced
    // room — so, following this crate's existing precedent (`spawn_room_list`
    // and `sync::start`, which take an `AppHandle` and are likewise not
    // unit-tested directly), `subscribe` itself isn't exercised here. What's
    // tested below is everything that doesn't require a live subscription:
    // the pure projections above, and the `NotReady` paths through
    // `FocusedTimeline`'s public API when nothing is focused yet.

    #[tokio::test]
    async fn focused_timeline_snapshot_reports_not_ready_when_nothing_is_focused() {
        let focused = FocusedTimeline::default();
        let err = focused.snapshot().await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn focused_timeline_paginate_back_reports_not_ready_when_nothing_is_focused() {
        let focused = FocusedTimeline::default();
        let err = focused.paginate_back(10).await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn focused_timeline_send_text_reports_not_ready_when_nothing_is_focused() {
        let focused = FocusedTimeline::default();
        let err = focused.send_text("hi").await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }
}
