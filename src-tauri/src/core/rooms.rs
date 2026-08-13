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

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use eyeball_im::VectorDiff;
use futures_util::{pin_mut, StreamExt};
use matrix_sdk::ruma::{OwnedRoomId, UserId};
use matrix_sdk::{Room, RoomMemberships};
use matrix_sdk_ui::room_list_service::filters::new_filter_non_left;
use matrix_sdk_ui::room_list_service::RoomListItem;
use matrix_sdk_ui::timeline::{LatestEventValue, Profile, RoomExt, TimelineDetails};
use tauri::{AppHandle, Emitter};
use tokio::task::JoinHandle;

use super::dto::{
    apply_ops, op_name, op_values, project_diff, DiffEnvelope, DiffOp, RoomSummary, SeqCounter,
};
use super::error::{CoreError, CoreResult};
use super::sync::SyncHandle;
use super::timeline::{latest_event_preview, MessagePreview};

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
///
/// Takes the whole [`MessagePreview`] rather than the three preview fields
/// separately so they cannot be set inconsistently: destructuring one
/// `Option` is what makes "`lastMessageIsOwn` is `false` and
/// `lastEventType` is `null` whenever there is no preview" a property of the
/// type rather than a rule every caller has to remember.
pub fn project_room_parts(
    id: &str,
    name: Option<String>,
    avatar_url: Option<String>,
    unread: u64,
    preview: Option<MessagePreview>,
    last_activity_ms: Option<u64>,
) -> RoomSummary {
    let (last_message, last_message_is_own, last_event_type) = match preview {
        Some(preview) => (Some(preview.text), preview.is_own, preview.event_type),
        None => (None, false, None),
    };
    RoomSummary {
        id: id.to_string(),
        name: name.unwrap_or_else(|| id.to_string()),
        avatar_url,
        unread,
        last_message,
        last_message_is_own,
        last_event_type,
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

/// Resolves a two-person room's avatar to the sole *other* joined member's
/// avatar — the fallback that actually fires against this deployment.
/// Synapse only sends heroes for **unnamed** rooms (they exist so a client
/// can compute a display name for a room that doesn't have one); every room
/// here has an explicit name, so [`resolve_avatar_url`]'s hero step is
/// correct but inert, and this is what Element is really showing when it
/// displays a picture for one of these two-person agent rooms.
///
/// `member_avatar_urls` is `(is_own_member, avatar_url)` for every *joined*
/// member of the room, in no particular order — already extracted from a
/// live `RoomMember` by [`resolve_room_avatar_mxc`], so this stays pure and
/// SDK-free like [`resolve_avatar_url`], testable without a live room or
/// store.
///
/// Deliberately narrow like the hero rule: fires only with **exactly two**
/// joined members. A larger room with no avatar has no single member whose
/// picture uniquely represents the room — picking one arbitrarily would be
/// the same mistake the hero rule already avoids for `heroes.len() > 1`.
fn resolve_two_person_avatar_url(member_avatar_urls: &[(bool, Option<String>)]) -> Option<String> {
    if member_avatar_urls.len() != 2 {
        return None;
    }
    member_avatar_urls
        .iter()
        .find(|(is_own, _)| !*is_own)
        .and_then(|(_, avatar_url)| avatar_url.clone())
}

/// Resolves `room`'s avatar to an mxc URI, consulting — in order — the
/// room's own `m.room.avatar`, a sole hero's avatar
/// ([`resolve_avatar_url`]), and, when neither fires, the sole *other*
/// member's avatar in an exactly-two-joined-member room
/// ([`resolve_two_person_avatar_url`]).
///
/// **Why this can't live in [`project_room`]:** the member list is read via
/// `Room::members_no_sync`, which is `async` — it reads the local state
/// store, not the network (see below), but `async` all the same — and
/// `project_room` is called from inside [`project_diff`]'s synchronous
/// closure, shared with `core::timeline`'s identical projection. Forcing
/// that closure `async` for one field would reshape a function this module
/// doesn't own. So this lives here as its own entry point, called directly
/// by `Session::room_avatar` instead of from the streaming path.
///
/// **Why `members_no_sync` shouldn't cost a network round trip here:**
/// `matrix-sdk-ui`'s room list requests `(RoomMember, "$LAZY")` and
/// `(RoomMember, "$ME")` in its sliding-sync `required_state` by default
/// (`DEFAULT_REQUIRED_STATE`, `room_list_service/mod.rs`) — independent of
/// heroes, which is a separate sliding-sync field the server may or may not
/// populate. So by the time a room reaches the webview at all, its joined
/// members' `m.room.member` events are already in the local store from that
/// lazy-loaded `required_state`, not from anything this function has to go
/// fetch. `members_no_sync` (unlike `members`, which syncs first) reads only
/// that local store and never triggers a fetch of its own — deliberately
/// preferred here over `members()` for exactly that reason, per this
/// deployment's report: an unexpectedly empty local member list should
/// surface as "no avatar" rather than silently add a network round trip to
/// every avatar fetch.
pub async fn resolve_room_avatar_mxc(room: &Room) -> CoreResult<Option<String>> {
    let hero_avatar_urls: Vec<Option<String>> = room
        .heroes()
        .iter()
        .map(|hero| hero.avatar_url.as_ref().map(|url| url.to_string()))
        .collect();
    let avatar_url = resolve_avatar_url(
        room.avatar_url().map(|url| url.to_string()),
        &hero_avatar_urls,
    );
    if avatar_url.is_some() {
        return Ok(avatar_url);
    }

    let mut members = room
        .members_no_sync(RoomMemberships::JOIN)
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))?;

    // The local store is lazily populated: sliding sync's `$LAZY` member
    // state only carries members who sent an event inside the synced window,
    // and the room list's timeline limit is 1. So a two-person room whose
    // other member hasn't spoken recently has just *our own* member event
    // cached, and the two-person rule below can't fire — measured against
    // this deployment, that was 9 of 16 rooms.
    //
    // Fetch the real member list in that case, but only then: gated on the
    // room claiming exactly two active members, so this never pulls the
    // member list of a large room. It costs one `/members` round trip per
    // affected room, once — the webview caches the resolved avatar per room,
    // and the SDK caches the member list it fetches here.
    if members.len() < 2 && room.active_members_count() == 2 {
        members = room
            .members(RoomMemberships::JOIN)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))?;
    }
    let member_avatar_urls: Vec<(bool, Option<String>)> = members
        .iter()
        .map(|member| {
            (
                member.is_account_user(),
                member.avatar_url().map(|url| url.to_string()),
            )
        })
        .collect();

    let resolved = resolve_two_person_avatar_url(&member_avatar_urls);
    tracing::debug!(
        room = %room.room_id(),
        joined_members = member_avatar_urls.len(),
        others_with_avatar = member_avatar_urls
            .iter()
            .filter(|(is_own, url)| !*is_own && url.is_some())
            .count(),
        resolved = resolved.is_some(),
        "two-person avatar fallback"
    );

    Ok(resolved)
}

/// The name to render a sender by: their display name where the local
/// member store has one, their raw user id otherwise — the same
/// `senderDisplayName ?? sender` fallback `Timeline.svelte` uses, so an
/// emote reads identically on both surfaces.
///
/// Pure and SDK-shaped rather than SDK-free: `TimelineDetails`/`Profile` are
/// plain data the caller already holds, and there is no logic here worth
/// isolating from them.
fn sender_display_name(profile: &TimelineDetails<Profile>, sender: &UserId) -> String {
    match profile {
        TimelineDetails::Ready(profile) => profile.display_name.clone(),
        _ => None,
    }
    .unwrap_or_else(|| sender.to_string())
}

/// Resolves a room's roster preview (spec §6.1.1) from the SDK's own
/// latest-event value.
///
/// **Why `matrix_sdk_ui::timeline::RoomExt::latest_event` and not the base
/// `Room::latest_event` a `RoomListItem` also derefs to** (the two share a
/// name; this calls the UI one through UFCS so the choice can't silently
/// flip): the base call hands back a raw `TimelineEvent`, which would mean
/// re-deriving "what kind of event is this" from ruma types — a second
/// classifier that could disagree with the timeline's about the very same
/// event, which is a bug visible on exactly one surface. The UI call returns
/// a `TimelineItemContent`, the *same* type `core::timeline::classify_content`
/// already takes, so the roster and the timeline are reading one
/// classification.
///
/// **What it costs:** `async`, which is what forces [`project_batch`]'s
/// two-pass shape (see its doc comment). Nothing in it reaches the network:
/// it deserializes an already-decrypted raw event, and loads the sender's
/// profile via `get_member_no_sync` — the local member store only, the same
/// choice [`resolve_room_avatar_mxc`] and `resolve_typing_users` make and
/// for the same reason.
///
/// **What the SDK has already filtered out, and what it has not.** The value
/// this reads is computed by `matrix-sdk`'s own backwards scan
/// (`latest_events/latest_event/builder.rs`), which *does* skip reactions,
/// redactions, `m.room.encrypted`/UTDs, verification requests and other
/// people's membership changes — so the "scan backwards for the last real
/// message" loop this task was told not to invent already exists upstream,
/// for free. It is **not** a preview filter, though: it also accepts polls,
/// stickers, call invites, RTC notifications, live-location beacons, and
/// *our own* joins and invites, all of which reach here as state or
/// non-message `MsgLikeKind`s and are dropped by
/// [`latest_event_preview`]'s own eligibility rules. That double filter is
/// deliberate — the SDK's job is "what is this room's newest interesting
/// event", §6.1.1's is "what was last said in it", and they are not the same
/// question.
///
/// Two consequences worth knowing before reading a roster:
///
/// - **A custom event can never be previewed.** That same upstream filter
///   ends in a `_ => filter_continue()` over `AnyMessageLikeEventContent`,
///   and ruma's catch-all `_Custom` variant falls into it — the identical
///   gap `timeline_event_filter` had to patch for the timeline, except that
///   this one is inside the SDK's own background task and cannot be
///   overridden from here. So a Kaambaan card/run/permission-request event
///   does not become the latest-event value at all; the roster shows the
///   last ordinary message underneath it instead. `lastEventType` is
///   therefore unreachable in production twice over (no gate schema exists
///   yet either), which is why `None` is passed for `custom_body` below:
///   `MsgLikeKind::Other` discards the content, and unlike
///   `custom_message_payload` there is no raw event here to read a fallback
///   `body` back out of. Fix the filter upstream, not this comment, if a
///   gate schema ever needs the amber row §6.1.1 describes.
/// - **An encrypted room whose keys have not arrived shows an older
///   message,** not "unable to decrypt": UTDs are explicitly skipped by that
///   scan, so it keeps walking backwards to something it can read.
async fn room_preview(item: &RoomListItem) -> Option<MessagePreview> {
    // Exhaustive with no wildcard arm, matching this crate's discipline for
    // every other SDK enum (`classify_content`, `send_state_name`,
    // `project_diff`): a new `LatestEventValue` variant must fail to compile
    // here rather than silently blank every affected room's preview.
    // `RoomExt` is implemented for `matrix_sdk::Room`, which `RoomListItem`
    // only *derefs* to — and UFCS does not apply a deref coercion to a
    // trait's `Self`, so the coercion is spelled out here. Calling it as
    // `item.latest_event()` would compile, but method resolution would then
    // be the only thing keeping this off the base `Room::latest_event` one
    // deref further down, which returns a raw event and no classification.
    let room: &Room = item;
    match RoomExt::latest_event(room).await {
        LatestEventValue::Remote {
            sender,
            is_own,
            profile,
            content,
            ..
        } => latest_event_preview(
            &content,
            is_own,
            &sender_display_name(&profile, &sender),
            None,
        ),
        // A local echo: something *we* just sent and the server hasn't
        // echoed back yet. Previewed the same way, and unconditionally
        // `is_own` — the SDK builds this variant from our own outgoing
        // send queue, so there is no other sender it could have.
        LatestEventValue::Local {
            sender,
            profile,
            content,
            ..
        } => latest_event_preview(
            &content,
            true,
            &sender_display_name(&profile, &sender),
            None,
        ),
        // `RemoteInvite` carries no content at all (just the inviter), and
        // an invitation is not something said in the room. `None` is the
        // ordinary state for any room the room-list service never subscribed
        // to while the event cache was listening.
        LatestEventValue::None | LatestEventValue::RemoteInvite { .. } => None,
    }
}

/// Project an SDK [`RoomListItem`] into the wire [`RoomSummary`].
///
/// A thin adapter: it only extracts values and delegates to
/// [`project_room_parts`] and [`resolve_avatar_url`], which carry the actual
/// logic (and are what get unit-tested).
///
/// `preview` is resolved separately, by [`room_preview`], and handed in:
/// that call is `async` and this function is not (see [`project_batch`]).
pub fn project_room(item: &RoomListItem, preview: Option<MessagePreview>) -> RoomSummary {
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
    // Diagnostic only, deliberately kept: this traced the missing-hero
    // hypothesis that led to `resolve_room_avatar_mxc`'s two-person fallback
    // above, and stays useful for the next "why is this room's avatar
    // missing" report — `resolved` here only reflects what this synchronous
    // path can determine (own avatar / heroes), not the async member-based
    // fallback, which `Session::room_avatar` resolves separately and isn't
    // visible from inside `project_diff`'s sync closure.
    tracing::debug!(
        room = %id,
        room_avatar = item.avatar_url().is_some(),
        heroes = hero_avatar_urls.len(),
        heroes_with_avatar = hero_avatar_urls.iter().filter(|u| u.is_some()).count(),
        active_members = item.active_members_count(),
        resolved_sync = avatar_url.is_some(),
        "avatar resolution (sync projection; async two-person fallback resolved separately)"
    );
    let unread = item.num_unread_messages();
    // `MilliSecondsSinceUnixEpoch` wraps `js_int::UInt`, which only converts
    // to `i64`/`i128` directly; it is always non-negative and within
    // `i64::MAX`, so the round trip through `i64` is exact.
    let last_activity_ms = item
        .latest_event_timestamp()
        .map(|ts| i64::from(ts.get()) as u64);

    project_room_parts(&id, name, avatar_url, unread, preview, last_activity_ms)
}

/// Project a raw batch of SDK diffs into the wire ops for one envelope.
///
/// Two passes, because [`room_preview`] is `async` and `project_diff`'s
/// mapping closure is not. Pass one walks the batch's item values (through
/// `core::dto::op_values`, so no second exhaustive `VectorDiff` match has to
/// exist alongside `project_diff` and risk drifting from it) and resolves
/// one preview per room; pass two runs the ordinary synchronous projection
/// with those results in hand.
///
/// Keyed by room id and resolved at most once per room per batch: a `Reset`
/// re-sends the whole list, and a room can appear in several ops of one
/// batch, while each resolution deserializes that room's latest event and
/// hits the member store for its sender's profile.
///
/// The identity `project_diff` in pass one is not a wasted projection — it
/// is how the batch's values are reached at all without writing that second
/// match. `RoomListItem` is a handful of `Arc`s, so cloning the batch to do
/// it is cheap.
async fn project_batch(batch: Vec<VectorDiff<RoomListItem>>) -> Vec<DiffOp<RoomSummary>> {
    let mut previews: HashMap<OwnedRoomId, Option<MessagePreview>> = HashMap::new();
    for op in batch
        .iter()
        .cloned()
        .map(|diff| project_diff(diff, |item| item))
    {
        for item in op_values(&op) {
            if !previews.contains_key(item.room_id()) {
                previews.insert(item.room_id().to_owned(), room_preview(item).await);
            }
        }
    }

    batch
        .into_iter()
        .map(|diff| {
            project_diff(diff, |item| {
                // Cloned rather than removed: the same room can legitimately
                // appear in more than one op of a batch, and the second
                // occurrence must get the same preview as the first, not a
                // blank one.
                let preview = previews.get(item.room_id()).cloned().flatten();
                project_room(&item, preview)
            })
        })
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
            let ops = project_batch(batch).await;
            let seq_no = seq.next_seq();

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
            Some(MessagePreview {
                text: "hello".into(),
                is_own: false,
                event_type: None,
            }),
            Some(1_700_000_000_000),
        );
        assert_eq!(summary.avatar_url.as_deref(), Some("mxc://example.org/abc"));
        assert_eq!(summary.last_message.as_deref(), Some("hello"));
        assert_eq!(summary.last_activity_ms, Some(1_700_000_000_000));
    }

    #[test]
    fn room_summary_splits_a_preview_into_its_three_wire_fields() {
        let summary = project_room_parts(
            "!abc:example.org",
            None,
            None,
            0,
            Some(MessagePreview {
                text: "Approval needed".into(),
                is_own: true,
                event_type: Some("dev.supermessage.gate.v1".into()),
            }),
            None,
        );
        assert_eq!(summary.last_message.as_deref(), Some("Approval needed"));
        assert!(summary.last_message_is_own);
        assert_eq!(
            summary.last_event_type.as_deref(),
            Some("dev.supermessage.gate.v1")
        );
    }

    #[test]
    fn room_summary_with_no_preview_claims_neither_ownership_nor_an_event_type() {
        // The invariant `project_room_parts` takes a whole `MessagePreview`
        // to enforce: a row with nothing to show must not also tell the
        // webview the nothing was ours, or that it was a custom event —
        // either would light up a `You: ` prefix or the pending-decision
        // branch on an empty preview line.
        let summary = project_room_parts("!abc:example.org", None, None, 0, None, None);
        assert_eq!(summary.last_message, None);
        assert!(!summary.last_message_is_own);
        assert_eq!(summary.last_event_type, None);
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
    fn resolve_two_person_avatar_url_returns_the_other_members_avatar() {
        // The case this exists for: a two-person agent room with no
        // `m.room.avatar` and no heroes (Synapse only sends heroes for
        // unnamed rooms, and these rooms all have explicit names).
        let resolved = resolve_two_person_avatar_url(&[
            (true, Some("mxc://x.org/me".into())),
            (false, Some("mxc://x.org/agent".into())),
        ]);
        assert_eq!(resolved.as_deref(), Some("mxc://x.org/agent"));
    }

    #[test]
    fn resolve_two_person_avatar_url_works_regardless_of_member_order() {
        let resolved = resolve_two_person_avatar_url(&[
            (false, Some("mxc://x.org/agent".into())),
            (true, Some("mxc://x.org/me".into())),
        ]);
        assert_eq!(resolved.as_deref(), Some("mxc://x.org/agent"));
    }

    #[test]
    fn resolve_two_person_avatar_url_is_none_when_the_other_member_has_no_avatar() {
        let resolved =
            resolve_two_person_avatar_url(&[(true, Some("mxc://x.org/me".into())), (false, None)]);
        assert_eq!(resolved, None);
    }

    #[test]
    fn resolve_two_person_avatar_url_is_none_for_a_room_with_three_or_more_joined_members() {
        // A group room with no room avatar: no single member's picture is
        // "the room's" picture, so this must not pick one arbitrarily —
        // same reasoning as the hero rule for `heroes.len() > 1`.
        let resolved = resolve_two_person_avatar_url(&[
            (true, Some("mxc://x.org/me".into())),
            (false, Some("mxc://x.org/a".into())),
            (false, Some("mxc://x.org/b".into())),
        ]);
        assert_eq!(resolved, None);
    }

    #[test]
    fn resolve_two_person_avatar_url_is_none_for_a_single_member_room() {
        assert_eq!(
            resolve_two_person_avatar_url(&[(true, Some("mxc://x.org/me".into()))]),
            None
        );
    }

    #[test]
    fn resolve_two_person_avatar_url_is_none_for_an_empty_member_list() {
        assert_eq!(resolve_two_person_avatar_url(&[]), None);
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
