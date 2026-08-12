//! Streams the room list to the webview as versioned diffs.
//!
//! Architecture rule (docs/tech-stack.md and `core::dto`'s doc comment): the
//! webview never gets the whole room list re-serialized on every change.
//! Instead this module forwards the SDK's own `VectorDiff` batches — turned
//! into wire [`DiffOp`]s by `core::dto::project_diff` — as [`DiffEnvelope`]s
//! stamped with a per-channel sequence number, so the webview can detect a
//! dropped event and force a resync instead of silently corrupting its list.

// `spawn_room_list` and `snapshot` have no caller yet — the Tauri command
// surface that starts room-list streaming after login and implements
// `rooms_resync` is a later M0 task. Revisit removing this once it lands.
#![allow(dead_code)]

use eyeball_im::VectorDiff;
use futures_util::{pin_mut, StreamExt};
use matrix_sdk_ui::room_list_service::filters::new_filter_non_left;
use matrix_sdk_ui::room_list_service::RoomListItem;
use tauri::{AppHandle, Emitter};

use super::dto::{project_diff, DiffEnvelope, DiffOp, RoomSummary, SeqCounter};
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

/// Project an SDK [`RoomListItem`] into the wire [`RoomSummary`].
///
/// A thin adapter: it only extracts values and delegates to
/// [`project_room_parts`], which carries the actual logic (and is what gets
/// unit-tested).
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
    let avatar_url = item.avatar_url().map(|url| url.to_string());
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

/// Spawns a task that streams the (non-left) room list to the webview for as
/// long as `app` lives, emitting one [`DiffEnvelope`] per batch on
/// [`ROOMS_DIFF_EVENT`].
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
pub async fn spawn_room_list(handle: &SyncHandle, app: AppHandle) -> CoreResult<()> {
    let room_list = handle
        .room_list_service()
        .all_rooms()
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))?;

    tokio::spawn(async move {
        let (stream, controller) = room_list.entries_with_dynamic_adapters(ROOM_LIST_PAGE_SIZE);
        controller.set_filter(Box::new(new_filter_non_left()));
        pin_mut!(stream);

        let mut seq = SeqCounter::default();
        while let Some(batch) = stream.next().await {
            let envelope = DiffEnvelope {
                channel: ROOMS_CHANNEL.into(),
                subject: String::new(),
                seq: seq.next(),
                ops: project_batch(batch),
            };
            if let Err(err) = app.emit(ROOMS_DIFF_EVENT, &envelope) {
                tracing::warn!(error = %err, "failed to emit {ROOMS_DIFF_EVENT}");
            }
        }
    });

    Ok(())
}

/// Fetches a one-off, self-consistent snapshot of the (non-left) room list —
/// the full state plus the sequence number that corresponds to it, so the
/// caller (`rooms_resync`) can hand both to the webview's `DiffTracker` via
/// `reset(items, seq)` and resume applying live diffs from exactly the right
/// point.
///
/// The sequence number is `1`, not an arbitrary placeholder like `0`. Here is
/// why that is the number that keeps this consistent with the live stream:
/// `entries_with_dynamic_adapters` has a documented contract — every time a
/// filter is set on a fresh subscription, the *first* batch the stream
/// yields is always `[VectorDiff::Reset { values }]` holding the complete
/// current list, before any incremental updates follow. `spawn_room_list`
/// relies on exactly this: its `SeqCounter` starts at 1, so the first
/// envelope it ever emits for a freshly (re)started stream necessarily
/// carries `seq: 1` and `ops: [Reset { values: <the full list at that
/// moment> }]`. `snapshot` opens its own independent subscription and reads
/// only that guaranteed-first Reset batch, stamping it with its own
/// `SeqCounter`'s first value — which, since `SeqCounter` always starts at
/// 1, is also `1`. The two are not coordinated through shared state; they
/// agree because they are both direct readings of the same SDK contract.
/// `DiffTracker::reset(items, 1)` therefore sets the webview's next expected
/// sequence number to `2` — exactly what a freshly (re)started
/// `spawn_room_list` stream's *second* envelope would carry. Returning
/// anything other than `1` here (e.g. `0`, or a value taken from some other
/// counter) would desynchronize the webview from that contract and either
/// reintroduce the silent-corruption bug the sequence numbers exist to
/// prevent, or send the webview into an endless resync loop.
pub async fn snapshot(handle: &SyncHandle) -> CoreResult<(u64, Vec<RoomSummary>)> {
    let room_list = handle
        .room_list_service()
        .all_rooms()
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))?;

    let (stream, controller) = room_list.entries_with_dynamic_adapters(ROOM_LIST_PAGE_SIZE);
    controller.set_filter(Box::new(new_filter_non_left()));
    pin_mut!(stream);

    let batch = stream.next().await.ok_or_else(|| {
        CoreError::Protocol("room list stream ended before yielding a snapshot".into())
    })?;

    let mut seq = SeqCounter::default();
    let mut ops = project_batch(batch).into_iter();
    match (ops.next(), ops.next()) {
        (Some(DiffOp::Reset { values }), None) => Ok((seq.next(), values)),
        _ => Err(CoreError::Protocol(
            "expected the room list's first batch to be a single reset".into(),
        )),
    }
}

#[cfg(test)]
mod tests {
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
}
