//! Streams the room list to the webview as versioned diffs.
//!
//! Architecture rule (docs/tech-stack.md and `core::dto`'s doc comment): the
//! webview never gets the whole room list re-serialized on every change.
//! Instead this module forwards the SDK's own `VectorDiff` batches — turned
//! into wire [`DiffOp`]s by `core::dto::project_diff` — as [`DiffEnvelope`]s
//! stamped with a per-channel sequence number, so the webview can detect a
//! dropped event and force a resync instead of silently corrupting its list.
//!
//! [`RoomListHandle`] also keeps a materialized copy of the list in sync
//! with what it emits (via `core::dto::apply_ops`), guarded by the same lock
//! its `seq` counter is stamped under. That is what lets
//! [`RoomListHandle::snapshot`] serve a resync out of the live task's own
//! state instead of opening a second, independently-numbered subscription —
//! see that method's doc comment for why a second subscription cannot work.

use std::sync::{Arc, Mutex};

use eyeball_im::VectorDiff;
use futures_util::{pin_mut, StreamExt};
use matrix_sdk_ui::room_list_service::filters::new_filter_non_left;
use matrix_sdk_ui::room_list_service::RoomListItem;
use tauri::{AppHandle, Emitter};
use tokio::task::JoinHandle;

use super::dto::{apply_ops, op_name, project_diff, DiffEnvelope, DiffOp, RoomSummary, SeqCounter};
use super::error::{CoreError, CoreResult};
use super::sync::SyncHandle;

/// Tauri event channel carrying room-list diffs for the webview's room list
/// store.
pub const ROOMS_DIFF_EVENT: &str = "sm://rooms/diff";

/// The `DiffEnvelope::channel` value used for every room-list envelope.
const ROOMS_CHANNEL: &str = "rooms";

/// The page size passed to `entries_with_dynamic_adapters`. Chosen generously
/// above any plausible room count so the dynamic "head" window never trims
/// the list — sliding-window pagination of the room list itself is not part
/// of this task.
const ROOM_LIST_PAGE_SIZE: usize = 200;

/// The sequence number of the last diff folded into `rooms`, and the
/// resulting materialized list — always mutually consistent with each other
/// (see [`RoomListHandle::snapshot`]).
type RoomListSnapshot = (u64, Vec<RoomSummary>);

/// Build a [`RoomSummary`] from already-extracted parts.
///
/// Pure and SDK-free on purpose: it is the part `project_room` delegates to,
/// so the projection logic is testable without a live homeserver.
pub fn project_room_parts(
    id: &str,
    name: Option<String>,
    avatar_url: Option<String>,
    unread: u64,
    last_message: Option<String>,
    last_activity_ms: Option<u64>,
) -> RoomSummary {
    RoomSummary {
        id: id.to_string(),
        name: name.unwrap_or_else(|| id.to_string()),
        avatar_url,
        unread,
        last_message,
        last_activity_ms,
    }
}

/// Resolves the mxc URI to show as a room's avatar: the room's own
/// `m.room.avatar` state event if it has one, else — mirroring Element's
/// behavior — the sole hero's avatar when the room has exactly one hero.
///
/// `RoomInfo::avatar_url` (what `item.avatar_url()` reads) has no such
/// fallback: it is `None` for any room that never had `m.room.avatar` set,
/// which in practice is most 1:1 rooms — Matrix leaves a DM's "avatar" to be
/// inferred from its sole other member, and Element does exactly that. Left
/// unhandled, every one of those rooms would report no avatar at all despite
/// Element showing a picture for the very same room.
///
/// Deliberately narrow: a room with more than one hero and no room avatar
/// still resolves to `None`. With several members and no `m.room.avatar`,
/// there is no one member whose picture uniquely represents the room —
/// showing an arbitrary hero's face would misrepresent a group as a 1:1.
///
/// Pure and SDK-free, like [`project_room_parts`], so it's testable without
/// a live `RoomListItem`; [`project_room`] is what extracts
/// `hero_avatar_urls` from a real `Room::heroes()` call.
fn resolve_avatar_url(
    room_avatar_url: Option<String>,
    hero_avatar_urls: &[Option<String>],
) -> Option<String> {
    room_avatar_url.or_else(|| match hero_avatar_urls {
        [only_hero] => only_hero.clone(),
        _ => None,
    })
}

/// Project an SDK [`RoomListItem`] into the wire [`RoomSummary`].
///
/// A thin adapter: it only extracts values and delegates to
/// [`project_room_parts`] and [`resolve_avatar_url`], which carry the actual
/// logic (and are what get unit-tested).
///
/// `last_message` is left `None` here. A real preview requires decoding the
/// latest event's content the same way `core::timeline`'s `project_item`
/// will for `TimelineItemDto` (matching message types, handling
/// undecryptable events, etc.) — building that twice would duplicate that
/// later task's work, so it is deliberately deferred rather than
/// half-implemented here.
pub fn project_room(item: &RoomListItem) -> RoomSummary {
    let id = item.room_id().to_string();
    let name = item.cached_display_name().map(|name| name.to_string());
    let hero_avatar_urls: Vec<Option<String>> = item
        .heroes()
        .iter()
        .map(|hero| hero.avatar_url.as_ref().map(|url| url.to_string()))
        .collect();
    let avatar_url = resolve_avatar_url(
        item.avatar_url().map(|url| url.to_string()),
        &hero_avatar_urls,
    );
    let unread = item.num_unread_messages();
    // `MilliSecondsSinceUnixEpoch` wraps `js_int::UInt`, which only converts
    // to `i64`/`i128` directly; it is always non-negative and within
    // `i64::MAX`, so the round trip through `i64` is exact.
    let last_activity_ms = item
        .latest_event_timestamp()
        .map(|ts| i64::from(ts.get()) as u64);

    project_room_parts(&id, name, avatar_url, unread, None, last_activity_ms)
}

/// Project a raw batch of SDK diffs into the wire ops for one envelope.
fn project_batch(batch: Vec<VectorDiff<RoomListItem>>) -> Vec<DiffOp<RoomSummary>> {
    batch
        .into_iter()
        .map(|diff| project_diff(diff, |item| project_room(&item)))
        .collect()
}

/// Owns the background task streaming the room list to the webview.
///
/// Mirrors `core::sync::SyncHandle`'s shape: dropping a `RoomListHandle`, or
/// calling [`RoomListHandle::stop_and_join`] on it, aborts the streaming
/// task. `Session` is meant to be the sole long-term owner (see
/// `core::session::Session::start_room_list`), replacing and stopping any
/// previous handle before storing a new one — otherwise two independent
/// subscriptions would interleave envelopes on the same event, each with its
/// own `seq` counter restarting at 1, which the webview cannot tell apart
/// from a corrupted stream.
pub struct RoomListHandle {
    state: Arc<Mutex<RoomListSnapshot>>,
    task: JoinHandle<()>,
}

impl RoomListHandle {
    /// Stops the background streaming task and waits for it to actually
    /// finish. Safe to call more than once, and safe alongside letting the
    /// handle simply drop (`Drop` aborts the same task).
    ///
    /// Aborting alone would be enough to stop the *emissions*, but not
    /// enough for the caller that matters: the task holds a `RoomList`, and
    /// through it the `Client`, and through that the store's open SQLite
    /// files. `Session::logout` deletes those files, so it needs the
    /// stronger guarantee that the task is gone, not merely told to go.
    pub async fn stop_and_join(&mut self) {
        self.task.abort();
        // `JoinHandle` is `Unpin`, so it can be awaited through `&mut`
        // without consuming the handle (which `Drop` prevents anyway). The
        // result is always `Err(JoinError::Cancelled)` after an abort;
        // what's load-bearing is that this resolves only once the task has
        // been dropped.
        let _ = std::pin::Pin::new(&mut self.task).await;
    }

    /// A snapshot of the room list as of the last diff this handle's task
    /// has applied, plus the sequence number of that diff — read directly
    /// out of the same state the streaming task maintains, not from a
    /// second subscription.
    ///
    /// Why this has to be shared state rather than a fresh
    /// `entries_with_dynamic_adapters` call: a second subscription gets its
    /// own `SeqCounter`, starting at 1, with no relationship to whatever
    /// sequence number the *live*, already-running stream is currently on.
    /// A webview that gapped at, say, seq 47 and resynced against a fresh
    /// subscription's `seq: 1` would set its next-expected sequence to 2 —
    /// but the live task keeps counting forward from 48, 49, 50, ...; every
    /// subsequent live envelope would look like a gap, forever. Reading out
    /// of the *same* counter and the *same* materialized list the live task
    /// just updated, under the same lock, is what makes the returned
    /// `(seq, rooms)` pair unconditionally correct to hand to
    /// `DiffTracker::reset` regardless of when it's called — second 0 or
    /// hour 3 of the session, mid-batch or between batches.
    pub fn snapshot(&self) -> CoreResult<RoomListSnapshot> {
        self.state
            .lock()
            .map(|guard| guard.clone())
            .map_err(|_| CoreError::Protocol("room list state lock poisoned".into()))
    }
}

impl Drop for RoomListHandle {
    fn drop(&mut self) {
        // Belt and suspenders, same reasoning as `SyncHandle`'s `Drop`: a
        // `RoomListHandle` no one stopped explicitly must not leave its task
        // running forever.
        self.task.abort();
    }
}

/// Spawns a task that streams the (non-left) room list to the webview for as
/// long as the returned [`RoomListHandle`] lives, emitting one
/// [`DiffEnvelope`] per batch on [`ROOMS_DIFF_EVENT`].
///
/// `RoomList::entries_with_dynamic_adapters` returns `impl Stream + '_`: the
/// stream borrows `&self` from the `RoomList` it's built from. Building the
/// stream in this function's own frame and only moving *the stream* into
/// `tokio::spawn` would not compile — the `RoomList` would be dropped when
/// this function returns while the spawned task (which outlives it) still
/// held a borrow into it. Moving `room_list` itself into the `async move`
/// block, and calling `entries_with_dynamic_adapters` from inside that
/// block, ties the `RoomList`'s lifetime to the task's for as long as the
/// task runs, so the borrow is always valid for the stream's entire life.
pub async fn spawn_room_list(handle: &SyncHandle, app: AppHandle) -> CoreResult<RoomListHandle> {
    let room_list = handle
        .room_list_service()
        .all_rooms()
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))?;

    // Starts at `(0, [])`: "before any diff has been folded in, the list is
    // empty" — consistent with `SeqCounter` starting at 1, since the first
    // live envelope (seq 1) is exactly what turns this into the true state.
    let state: Arc<Mutex<RoomListSnapshot>> = Arc::new(Mutex::new((0, Vec::new())));
    let task_state = Arc::clone(&state);

    let task = tokio::spawn(async move {
        let (stream, controller) = room_list.entries_with_dynamic_adapters(ROOM_LIST_PAGE_SIZE);
        controller.set_filter(Box::new(new_filter_non_left()));
        pin_mut!(stream);

        let mut seq = SeqCounter::default();
        while let Some(batch) = stream.next().await {
            let ops = project_batch(batch);
            let seq_no = seq.next();

            // Fold this batch into the materialized list *before* emitting,
            // under the same lock `snapshot()` reads. A `snapshot()` call
            // that races this either observes the state from just before
            // this batch or from just after it — never a torn mix of "the
            // new seq number with the old list" or vice versa.
            let folded_len = {
                let mut guard = task_state
                    .lock()
                    .expect("room list state lock poisoned by an earlier panic");
                apply_ops(&mut guard.1, &ops);
                guard.0 = seq_no;
                guard.1.len()
            };

            tracing::debug!(
                seq = seq_no,
                ops = ops.len(),
                kinds = ?ops.iter().map(op_name).collect::<Vec<_>>(),
                rooms = folded_len,
                "emitting room list diff"
            );

            let envelope = DiffEnvelope {
                channel: ROOMS_CHANNEL.into(),
                subject: String::new(),
                seq: seq_no,
                ops,
            };
            if let Err(err) = app.emit(ROOMS_DIFF_EVENT, &envelope) {
                tracing::warn!(error = %err, "failed to emit {ROOMS_DIFF_EVENT}");
            }
        }
    });

    Ok(RoomListHandle { state, task })
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    #[test]
    fn room_summary_falls_back_to_the_room_id_when_unnamed() {
        // project_room_parts is the pure inner function taking already-extracted
        // values, so it can be tested without constructing an SDK RoomListItem.
        let summary = project_room_parts("!abc:example.org", None, None, 0, None, None);
        assert_eq!(summary.name, "!abc:example.org");
        assert_eq!(summary.id, "!abc:example.org");
    }

    #[test]
    fn room_summary_prefers_the_display_name() {
        let summary =
            project_room_parts("!abc:example.org", Some("Ops".into()), None, 3, None, None);
        assert_eq!(summary.name, "Ops");
        assert_eq!(summary.unread, 3);
    }

    #[test]
    fn room_summary_carries_avatar_and_activity_through_untouched() {
        let summary = project_room_parts(
            "!abc:example.org",
            Some("Ops".into()),
            Some("mxc://example.org/abc".into()),
            2,
            Some("hello".into()),
            Some(1_700_000_000_000),
        );
        assert_eq!(summary.avatar_url.as_deref(), Some("mxc://example.org/abc"));
        assert_eq!(summary.last_message.as_deref(), Some("hello"));
        assert_eq!(summary.last_activity_ms, Some(1_700_000_000_000));
    }

    fn room(id: &str) -> RoomSummary {
        project_room_parts(id, None, None, 0, None, None)
    }

    #[test]
    fn resolve_avatar_url_prefers_the_rooms_own_avatar_over_any_hero() {
        let resolved = resolve_avatar_url(
            Some("mxc://x.org/room-avatar".into()),
            &[Some("mxc://x.org/hero-avatar".into())],
        );
        assert_eq!(resolved.as_deref(), Some("mxc://x.org/room-avatar"));
    }

    #[test]
    fn resolve_avatar_url_falls_back_to_the_sole_heros_avatar() {
        // The case this exists for: a DM with no `m.room.avatar` state
        // event, same as Element's own fallback for rooms shaped like this.
        let resolved = resolve_avatar_url(None, &[Some("mxc://x.org/hero-avatar".into())]);
        assert_eq!(resolved.as_deref(), Some("mxc://x.org/hero-avatar"));
    }

    #[test]
    fn resolve_avatar_url_is_none_for_a_multi_hero_room_with_no_room_avatar() {
        // A group room with several members and no room avatar: no single
        // hero's picture is "the room's" picture, so this must not pick one
        // arbitrarily.
        let resolved = resolve_avatar_url(
            None,
            &[
                Some("mxc://x.org/a".into()),
                Some("mxc://x.org/b".into()),
                Some("mxc://x.org/c".into()),
            ],
        );
        assert_eq!(resolved, None);
    }

    #[test]
    fn resolve_avatar_url_is_none_with_no_room_avatar_and_no_heroes() {
        assert_eq!(resolve_avatar_url(None, &[]), None);
    }

    #[test]
    fn resolve_avatar_url_is_none_when_the_sole_hero_has_no_avatar_either() {
        assert_eq!(resolve_avatar_url(None, &[None]), None);
    }

    #[test]
    fn snapshot_reflects_the_last_seq_and_rooms_written_under_the_lock() {
        let state: Arc<Mutex<RoomListSnapshot>> = Arc::new(Mutex::new((0, vec![room("!a:x.org")])));

        {
            let mut guard = state.lock().unwrap();
            apply_ops(
                &mut guard.1,
                &[DiffOp::PushBack {
                    value: room("!b:x.org"),
                }],
            );
            guard.0 = 3;
        }

        let snapshot = state.lock().unwrap().clone();
        assert_eq!(snapshot.0, 3);
        assert_eq!(snapshot.1, vec![room("!a:x.org"), room("!b:x.org")]);
    }

    #[tokio::test]
    async fn stop_and_join_aborts_the_background_task_and_waits_for_it() {
        let state: Arc<Mutex<RoomListSnapshot>> = Arc::new(Mutex::new((0, Vec::new())));
        let task = tokio::spawn(async {
            loop {
                tokio::time::sleep(Duration::from_secs(3600)).await;
            }
        });
        let mut handle = RoomListHandle { state, task };

        assert!(!handle.task.is_finished());
        handle.stop_and_join().await;
        // No sleep here on purpose: `stop_and_join` is only allowed to
        // return once the task has actually finished, which is exactly the
        // property `Session::logout` depends on before it deletes the
        // store's SQLite files.
        assert!(handle.task.is_finished());
    }

    #[tokio::test]
    async fn snapshot_reports_a_lock_poisoned_protocol_error_instead_of_panicking() {
        let state: Arc<Mutex<RoomListSnapshot>> = Arc::new(Mutex::new((0, Vec::new())));
        let poison_state = Arc::clone(&state);

        // Deliberately poison the mutex, the same way a bug elsewhere in the
        // streaming task might.
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _guard = poison_state.lock().unwrap();
            panic!("simulated panic while holding the lock");
        }));

        let task = tokio::spawn(async {});
        let handle = RoomListHandle { state, task };

        let err = handle.snapshot().unwrap_err();
        assert_eq!(err.kind(), "protocol");
    }
}
