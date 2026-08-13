//! Integration tests for `core::timeline::project_item`/`classify_content`
//! (reached here through `project_item`, the only public entry point — see
//! that function's doc comment) driven by **real matrix-rust-sdk-produced**
//! `TimelineItem`s, not hand-written DTOs.
//!
//! Every other test touching this module's projection logic (the `#[cfg(test)]
//! mod tests` at the bottom of `core::timeline`) feeds already-extracted plain
//! values (`&str`, `Option<&str>`, ...) straight into `project_item_parts` —
//! useful for pinning down the *logic* (truncation, scheme hardening, ...),
//! but unable to catch a wrong string literal in the SDK-facing arm of
//! `classify_content` itself, a variant mapped to the wrong `kind`, or a field
//! the SDK quietly stopped populating after an upgrade. The compiler enforces
//! that `classify_content`'s match is *exhaustive* (a new
//! `TimelineItemContent`/`MsgLikeKind` variant is a compile error); nothing
//! enforces that the value chosen *per arm* is actually correct. That is what
//! this file exists to close: real events, built with `matrix-sdk-test`'s
//! `EventFactory`, synced through a `MatrixMockServer`-backed homeserver into
//! a genuine `matrix_sdk_ui::Timeline` (`RoomExt::timeline`) — the same SDK
//! object `core::timeline::FocusedTimeline::subscribe` builds against a real
//! server — then run through the *exact* production `project_item` this
//! crate ships, not a reimplementation of it.
//!
//! ## Harness shape
//!
//! [`projected_items`] is the one seam every test below goes through: join a
//! fresh room on a mocked homeserver, subscribe a real `Timeline` against it
//! (so the room's event cache is actively listening — an unsubscribed room's
//! synced-in events are not reliably retained for a `Timeline` built
//! afterwards to see, which an earlier draft of this harness learned the hard
//! way: every test failed with zero materialized items until subscription
//! moved before the sync), then sync in whatever events the caller's closure
//! adds and fold the one resulting diff batch through the *exact* production
//! pipeline — `core::dto::project_diff`/`apply_ops`, the same two functions
//! `core::timeline::project_batch`/`emit_ops` call — onto an initially-empty
//! materialized list. This is the same "seed from an empty list, then fold
//! diffs in" shape `FocusedTimeline::subscribe`'s streaming task itself
//! uses, just single-batch instead of running forever.
//!
//! [`real_items`] strips the virtual items (date dividers, ...) every
//! non-empty timeline injects, since none of the table below is about those.
//!
//! ## What could not be constructed here, and why
//!
//! - **Poll, sticker, live location, call invite/RTC notification, custom
//!   message-like, failed-to-parse.** `classify_content`'s remaining
//!   `MsgLike`/top-level arms are real and exhaustively handled in production,
//!   but are out of scope for the table this task specifies — adding them
//!   would be easy (the same `EventFactory` has `poll_start`/`sticker`/
//!   `beacon`/`call_invite`/`rtc_notification`/`custom_message_like_event`
//!   builders) but wasn't asked for, so it's left undone rather than
//!   speculatively padded in.
//! - **A genuinely offline/undecryptable reply parent surfaced as
//!   `TimelineDetails::Unavailable`/`Pending`/`Error`.** The reply test below
//!   only exercises the `Ready` parent path (the common case, and the one
//!   with actual excerpt-truncation logic to verify); forcing one of the other
//!   three states deterministically would mean racing `fetch_details_for_event`
//!   or evicting an already-materialized parent from the timeline's own item
//!   list, neither of which this harness's synchronous "sync everything, then
//!   build the Timeline" shape does naturally, and is already covered by the
//!   *pure* `project_reply_to_projects_an_unavailable_parent_gracefully` unit
//!   test in `core::timeline`.
//!
//! Everything else in the task's table (`m.text`, `m.notice`, `m.emote`,
//! `m.image`, `m.room.name` state, a membership change, a redaction, an
//! undecryptable message, a reply, an edit, a reaction, and a hardened
//! formatted body) is exercised below against real SDK output.

// Same reasoning as `lib.rs`'s identical attribute: awaiting `Timeline::
// subscribe`'s stream type inside a `#[tokio::test]` async fn overflows
// rustc's default query recursion limit.
#![recursion_limit = "256"]

use std::time::Duration;

use futures_util::{pin_mut, StreamExt};
use matrix_sdk::ruma::events::macros::EventContent;
use matrix_sdk::ruma::events::receipt::{ReceiptThread, ReceiptType};
use matrix_sdk::ruma::events::room::message::{
    ImageMessageEventContent, MessageType, RedactedRoomMessageEventContent, RoomMessageEventContent,
};
use matrix_sdk::ruma::events::room::ImageInfo;
use matrix_sdk::ruma::UserId;
use matrix_sdk::ruma::{event_id, owned_mxc_uri, room_id, uint, user_id, RoomId};
use matrix_sdk::test_utils::mocks::MatrixMockServer;
use matrix_sdk_test::event_factory::EventFactory;
use matrix_sdk_test::{JoinedRoomBuilder, ALICE, BOB};
use matrix_sdk_ui::timeline::{
    RoomExt, TimelineDetails, TimelineItem, TimelineItemKind, TimelineReadReceiptTracking,
};
use serde::{Deserialize, Serialize};

use supermessage_lib::core::dto::{apply_ops, project_diff, TimelineItemDto};
use supermessage_lib::core::timeline::{
    latest_event_preview, project_item, timeline_event_filter, MessagePreview,
    CUSTOM_PAYLOAD_MAX_BYTES, PREVIEW_MAX_CHARS,
};
use supermessage_lib::core::tls::install_ring_provider;

/// A hand-rolled custom message-like event content, standing in for a real
/// (not-yet-designed — see `docs/matrix-events.md` §G) Kaambaan schema, so
/// `custom_message_payload`'s SDK-facing extraction (reading `content` back
/// out of `EventTimelineItem::original_json`, since `MsgLikeKind::Other`
/// itself discards it — see that function's doc comment) can be driven
/// end-to-end through a genuine `matrix_sdk_ui::Timeline`, the same way every
/// other event kind in this file is. Field names are plain Rust field names
/// (no `#[serde(rename)]`), so the JSON keys below match verbatim.
#[derive(Clone, Debug, Deserialize, Serialize, EventContent)]
#[ruma_event(type = "dev.supermessage.demo.note.v1", kind = MessageLike)]
struct DemoNoteEventContent {
    schema_version: u32,
    title: String,
    body: String,
}

/// Joins a fresh room (`!room:example.org`) on a mocked homeserver, syncs in
/// whatever `build` adds to it, and returns the resulting materialized
/// `TimelineItemDto` list — see this file's doc comment for the exact shape.
async fn projected_items(
    build: impl FnOnce(&RoomId, JoinedRoomBuilder) -> JoinedRoomBuilder,
) -> Vec<TimelineItemDto> {
    projected(build, |item, own_user| {
        project_item(item, own_user).expect("project_item is total over TimelineItemKind")
    })
    .await
}

/// The room-list preview `core::rooms::room_preview` would resolve for each
/// synced-in event, run against **real** SDK-produced `TimelineItemContent`
/// through the production `core::timeline::latest_event_preview`.
///
/// Why this exists next to the unit tests: `latest_event_preview`'s
/// eligibility filter is what decides whether a membership change, a
/// redaction or an undecryptable event ever reaches the roster, and the unit
/// tests can only drive that filter through `classify_content`'s `&str`
/// vocabulary — they cannot catch the filter agreeing with a *wrong*
/// classification of a real event. These can. `TimelineItemContent` has no
/// public constructor (see `core::timeline`'s test-module comment), so a
/// live synced timeline is the only way to get one at all.
///
/// The one thing this does **not** exercise is the SDK's own latest-event
/// selection — see `core::rooms::room_preview`'s doc comment for what that
/// filter drops before this code would ever see it. Here every synced event
/// is offered to the preview, which is exactly what makes the ineligible
/// cases below assertable.
async fn projected_previews(
    build: impl FnOnce(&RoomId, JoinedRoomBuilder) -> JoinedRoomBuilder,
) -> Vec<Option<MessagePreview>> {
    projected(build, |item, own_user| match item.kind() {
        TimelineItemKind::Event(event) => {
            // Mirrors `core::rooms::sender_display_name`, which resolves the
            // same fallback from a `LatestEventValue`'s profile rather than
            // an `EventTimelineItem`'s. Against this harness both are
            // `Unavailable`, so every emote below reads by raw user id.
            let sender_name = match event.sender_profile() {
                TimelineDetails::Ready(profile) => profile.display_name.clone(),
                _ => None,
            }
            .unwrap_or_else(|| event.sender().to_string());
            Some(latest_event_preview(
                event.content(),
                event.sender() == own_user,
                &sender_name,
                None,
            ))
        }
        // Virtual items (date dividers, the read marker) are not events and
        // have no content to preview. Dropped here rather than folded in as
        // `None`, so the assertions below can tell "this event is not
        // previewable" apart from "a date divider went past".
        TimelineItemKind::Virtual(_) => None,
    })
    .await
    .into_iter()
    .flatten()
    .collect()
}

/// The shared body of [`projected_items`] and [`projected_previews`] — see
/// this file's doc comment for the harness shape. `project` is applied to
/// every materialized `TimelineItem`, inside the *production*
/// `core::dto::project_diff`/`apply_ops` fold.
async fn projected<T: Clone>(
    build: impl FnOnce(&RoomId, JoinedRoomBuilder) -> JoinedRoomBuilder,
    project: impl Fn(&TimelineItem, &UserId) -> T,
) -> Vec<T> {
    // Every client construction path in this crate runs this first — see
    // `core::tls`'s doc comment for why a `ClientConfig::builder()` call
    // panics otherwise. Idempotent, so safe to call once per test.
    install_ring_provider();

    let room_id = room_id!("!room:example.org");
    let server = MatrixMockServer::new().await;
    let client = server.client_builder().build().await;

    // This deployment's rooms are unencrypted-only today (see
    // `core::timeline::FocusedTimeline::media_source`'s doc comment) — mocked
    // here for the same reason every comparable matrix-sdk-ui integration
    // test does, so `Timeline`'s own encryption-state probe has something to
    // resolve against instead of an unmocked 404.
    server.mock_room_state_encryption().plain().mount().await;

    let room = server.sync_joined_room(&client, room_id).await;

    // The mock client builder's default logged-in user (`mock_session_meta`
    // in matrix-sdk's own `test_utils::client`) — never overridden by any
    // test below, so `by_me`/`is_own` tests key on this literal.
    let own_user = client
        .user_id()
        .expect("MatrixMockServer's client is always already logged in")
        .to_owned();

    // Built with the app's own `timeline_event_filter`, not the SDK's plain
    // default — `FocusedTimeline::subscribe` builds every real room's
    // `Timeline` this same way (see that function's doc comment for why the
    // default alone silently drops a custom message-like event before it
    // ever reaches the timeline's item list at all), and this harness exists
    // specifically to run tests through the *exact* production pipeline, not
    // a reimplementation of it — see this file's doc comment.
    let timeline = room
        .timeline_builder()
        .event_filter(timeline_event_filter)
        // Mirrors `FocusedTimeline::subscribe`'s own builder call exactly —
        // see this file's doc comment on why this harness exists to run
        // tests through the *real* production pipeline, and
        // `TimelineItemDto::read_by`'s doc comment for why `read_by` would
        // otherwise stay unconditionally empty even when a receipt is
        // synced in below.
        .track_read_marker_and_receipts(TimelineReadReceiptTracking::MessageLikeEvents)
        .build()
        .await
        .expect("Timeline::new against a mocked, joined room");
    let (initial, stream) = timeline.subscribe().await;
    assert!(
        initial.is_empty(),
        "a freshly joined room with no events yet must start empty"
    );

    // Sync the caller's events in only *after* subscribing — see this file's
    // doc comment for why that order is load-bearing, not incidental.
    server
        .sync_room(&client, build(room_id, JoinedRoomBuilder::new(room_id)))
        .await;

    pin_mut!(stream);
    let batch = tokio::time::timeout(Duration::from_secs(10), stream.next())
        .await
        .expect("the timeline must emit a diff batch for the events just synced in")
        .expect("the timeline stream must not end while its Timeline is still alive");

    let ops = batch
        .into_iter()
        .map(|diff| project_diff(diff, |item| project(&item, &own_user)));

    let mut items = Vec::new();
    apply_ops(&mut items, &ops.collect::<Vec<_>>());
    items
}

/// The non-virtual items among `items` — every date divider / read marker /
/// timeline-start item stripped out, since none of the assertions below are
/// about those.
fn real_items(items: &[TimelineItemDto]) -> Vec<&TimelineItemDto> {
    items
        .iter()
        .filter(|item| {
            !matches!(
                item.kind.as_str(),
                "dateDivider" | "readMarker" | "timelineStart"
            )
        })
        .collect()
}

#[tokio::test]
async fn text_message_projects_as_a_message_with_its_msgtype_and_body() {
    let items = projected_items(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.text_msg("hello world").event_id(event_id!("$text1")))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let item = real[0];
    assert_eq!(item.kind, "message");
    assert_eq!(item.msgtype.as_deref(), Some("m.text"));
    assert_eq!(item.body.as_deref(), Some("hello world"));
}

#[tokio::test]
async fn notice_message_carries_the_notice_msgtype() {
    // The disposition that de-emphasises agent output in the webview keys
    // directly on this `msgtype` string — see `docs/matrix-events.md`.
    let items = projected_items(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.notice("agent output").event_id(event_id!("$notice1")))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let item = real[0];
    assert_eq!(item.kind, "message");
    assert_eq!(item.msgtype.as_deref(), Some("m.notice"));
    assert_eq!(item.body.as_deref(), Some("agent output"));
}

#[tokio::test]
async fn emote_message_carries_the_emote_msgtype() {
    let items = projected_items(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.emote("waves hello").event_id(event_id!("$emote1")))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let item = real[0];
    assert_eq!(item.kind, "message");
    assert_eq!(item.msgtype.as_deref(), Some("m.emote"));
    assert_eq!(item.body.as_deref(), Some("waves hello"));
}

#[tokio::test]
async fn image_message_projects_media_metadata() {
    let items = projected_items(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        // `ImageInfo` is `#[non_exhaustive]`, so it can't be built with
        // struct-literal syntax from outside ruma — construct the default
        // and set fields directly instead.
        let mut info = ImageInfo::new();
        info.width = Some(uint!(800));
        info.height = Some(uint!(600));
        info.mimetype = Some("image/png".to_owned());
        info.size = Some(uint!(45_000));
        let content = RoomMessageEventContent::new(MessageType::Image(
            ImageMessageEventContent::plain(
                "photo.png".to_owned(),
                owned_mxc_uri!("mxc://example.org/abc123"),
            )
            .info(Box::new(info)),
        ));
        room.add_timeline_event(f.event(content).event_id(event_id!("$image1")))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let item = real[0];
    assert_eq!(item.kind, "message");
    assert_eq!(item.msgtype.as_deref(), Some("m.image"));
    let media = item
        .media
        .as_ref()
        .expect("an m.image message must carry media metadata");
    assert_eq!(media.filename, "photo.png");
    assert_eq!(media.mimetype.as_deref(), Some("image/png"));
    assert_eq!(media.size, Some(45_000));
    assert_eq!(media.width, Some(800));
    assert_eq!(media.height, Some(600));
}

#[tokio::test]
async fn room_name_state_event_projects_as_state_with_its_event_type_as_detail() {
    // The regression that started this whole refactor: `m.room.name` must
    // classify as `kind: "state"` with `detail: "m.room.name"`, not silently
    // fall into some other bucket.
    let items = projected_items(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.room_name("New room name").event_id(event_id!("$name1")))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let item = real[0];
    assert_eq!(item.kind, "state");
    assert_eq!(item.detail.as_deref(), Some("m.room.name"));
}

#[tokio::test]
async fn membership_invite_projects_as_membership_with_the_invited_detail() {
    let items = projected_items(|room_id, room| {
        // No factory-level `.sender()`: `.invited()` requires the sender and
        // the invited user to differ, and `EventFactory::member` defaults the
        // sender to the member passed in.
        let f = EventFactory::new().room(room_id);
        room.add_timeline_event(f.member(&BOB).invited(&ALICE).display_name("Alice"))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let item = real[0];
    assert_eq!(item.kind, "membership");
    assert_eq!(item.detail.as_deref(), Some("invited"));
}

#[tokio::test]
async fn redacted_message_projects_as_the_redacted_kind() {
    let event_id = event_id!("$redact1");
    let items = projected_items(move |room_id, room| {
        let f = EventFactory::new().room(room_id);
        room.add_timeline_event(
            f.redacted(&ALICE, RedactedRoomMessageEventContent::new())
                .event_id(event_id),
        )
        .add_timeline_event(f.redaction(event_id).sender(&ALICE))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    assert_eq!(real[0].kind, "redacted");
}

#[tokio::test]
async fn undecryptable_message_projects_as_unable_to_decrypt() {
    // A real `m.room.encrypted` event this client has no megolm session
    // for — the same shape `matrix-sdk-ui-0.18.0/src/timeline/tests/
    // encryption.rs`'s `test_retry_message_decryption` sends through a live
    // sync, proven there to yield `MsgLikeKind::UnableToDecrypt`. Neither the
    // exact ciphertext nor the sender/device ids matter: decryption fails at
    // "no matching session for this id", before any of those are examined.
    let items = projected_items(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(
            f.encrypted(
                "AwgAEtABWuWeRLintqVP5ez5kki8sDsX7zSq++9AJo9lELGTDjNKzbF8sowUgg0D",
                "sKSGv2uD9zUncgL6GiLedvuky3fjVcEz9qVKZkpzN14",
                "PNQBRWYIJL",
                "unknown-session-id",
            )
            .event_id(event_id!("$enc1")),
        )
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    assert_eq!(real[0].kind, "unableToDecrypt");
}

#[tokio::test]
async fn reply_projects_a_reply_to_with_a_truncated_excerpt() {
    let long_body = "x".repeat(300);
    let original_id = event_id!("$original1");
    let items = projected_items(move |room_id, room| {
        let f = EventFactory::new().room(room_id);
        room.add_timeline_event(
            f.text_msg(long_body.clone())
                .sender(&ALICE)
                .event_id(original_id),
        )
        .add_timeline_event(
            f.text_msg("replying")
                .sender(&BOB)
                .reply_to(original_id)
                .event_id(event_id!("$reply1")),
        )
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        2,
        "expected the original and the reply, got {real:#?}"
    );
    let reply = real
        .iter()
        .find(|item| item.body.as_deref() == Some("replying"))
        .expect("the reply item must be present");
    let reply_to = reply
        .reply_to
        .as_ref()
        .expect("a reply must carry a populated reply_to");
    assert!(
        reply_to.available,
        "the parent was synced first, so it must resolve as Ready"
    );
    assert_eq!(reply_to.sender.as_deref(), Some(ALICE.as_str()));

    let excerpt = reply_to
        .excerpt
        .as_ref()
        .expect("the parent is a plain message, so it must have an excerpt");
    // `core::timeline::REPLY_EXCERPT_MAX_CHARS` (160) `char`s plus the
    // ellipsis appended when anything was actually cut.
    assert_eq!(excerpt.chars().count(), 161);
    assert!(
        excerpt.ends_with('…'),
        "expected a truncated excerpt to end with an ellipsis, got {excerpt:?}"
    );
}

#[tokio::test]
async fn edit_sets_the_edited_flag_and_carries_the_new_body() {
    let original_id = event_id!("$original2");
    let items = projected_items(move |room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.text_msg("hello").event_id(original_id))
            .add_timeline_event(
                f.text_msg("* hello there")
                    .event_id(event_id!("$edit1"))
                    .edit(
                        original_id,
                        RoomMessageEventContent::text_plain("hello there").into(),
                    ),
            )
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "an edit updates the original item in place, got {real:#?}"
    );
    let item = real[0];
    assert!(item.edited);
    assert_eq!(item.body.as_deref(), Some("hello there"));
}

#[tokio::test]
async fn reaction_projects_key_count_and_by_me() {
    let msg_id = event_id!("$msg1");
    // The mock client builder's own default user id — see `projected_items`'s
    // doc comment — reacting is what exercises `by_me: true`.
    let own_user = user_id!("@example:localhost");
    let items = projected_items(move |room_id, room| {
        let f = EventFactory::new().room(room_id);
        room.add_timeline_event(f.text_msg("hello").sender(&ALICE).event_id(msg_id))
            .add_timeline_event(f.reaction(msg_id, "😆").sender(&ALICE))
            .add_timeline_event(f.reaction(msg_id, "😆").sender(own_user))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let item = real[0];
    assert_eq!(item.reactions.len(), 1);
    let reaction = &item.reactions[0];
    assert_eq!(reaction.key, "😆");
    assert_eq!(reaction.count, 2);
    assert!(reaction.by_me, "the mock client's own user reacted too");
}

/// Sorts `read_by` (a `HashMap`-backed receipt map underneath, so its
/// iteration order isn't meaningful) into a stable order for comparison.
fn sorted_read_by(item: &TimelineItemDto) -> Vec<String> {
    let mut ids = item.read_by.clone();
    ids.sort();
    ids
}

#[tokio::test]
async fn read_receipt_populates_read_by_with_the_other_members_id() {
    // `core::timeline::read_by` end to end: a real `m.read` receipt, synced
    // in as an ephemeral event the same way a homeserver actually delivers
    // one, must show up on the event it points at. Alongside it: the
    // sender's own *implicit* receipt — `matrix_sdk_ui` credits sending a
    // message with having read up to it (`Timeline::latest_user_read_receipt`'s
    // doc comment), and that folds into `EventTimelineItem::read_receipts()`
    // the same as an explicit one — so Alice, this message's sender, is
    // expected here too, not just Bob's explicit receipt. Only the
    // *harness's own logged-in user* is ever filtered out (see
    // `TimelineItemDto::read_by`'s doc comment) — a plain "other member",
    // sender or not, always counts.
    let msg_id = event_id!("$read1");
    let items = projected_items(move |room_id, room| {
        let f = EventFactory::new().room(room_id);
        room.add_timeline_event(f.text_msg("hello").sender(&ALICE).event_id(msg_id))
            .add_receipt(
                f.read_receipts()
                    .add(msg_id, &BOB, ReceiptType::Read, ReceiptThread::Unthreaded)
                    .into_event(),
            )
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly the one message, got {real:#?}"
    );
    assert_eq!(
        sorted_read_by(real[0]),
        vec![ALICE.to_string(), BOB.to_string()]
    );
}

#[tokio::test]
async fn a_message_nobody_else_has_read_carries_only_the_senders_own_implicit_receipt() {
    // See `read_receipt_populates_read_by_with_the_other_members_id`'s doc
    // comment for why the sender's own implicit receipt is expected even
    // with no explicit `m.read` receipt synced in at all.
    let items = projected_items(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.text_msg("hello").event_id(event_id!("$unread1")))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    assert_eq!(real[0].read_by, vec![ALICE.to_string()]);
}

#[tokio::test]
async fn formatted_body_is_hardened_end_to_end_against_sdk_produced_content() {
    // `class` sorts before `href` on `<a>`, and `alt` sorts before `src` on
    // `<img>` — exactly the decoy-attribute shape that trips ruma's own
    // scheme-checking loop into skipping the real check entirely (see
    // `core::timeline::harden_formatted_body`'s doc comment and
    // https://github.com/ruma/ruma/issues/2557). This is the first time that
    // shape has been driven through matrix-sdk-ui's own HTML sanitizer pass
    // (`Message::from_event`) rather than asserted against by hand.
    let html = r#"<p>hi <a class="x" href="javascript:alert(1)">click</a> and <img alt="a" src="https://evil.example/beacon.png"></p>"#;
    let items = projected_items(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(
            f.text_html("hi click and", html)
                .event_id(event_id!("$html1")),
        )
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let formatted = real[0]
        .formatted_body
        .as_ref()
        .expect("an HTML-formatted m.text body must project a formatted_body");

    assert!(
        !formatted.contains("javascript:"),
        "the javascript: href must not survive end to end, got {formatted:?}"
    );
    assert!(
        !formatted.contains("<a "),
        "the anchor must be unwrapped, not merely stripped of its href, got {formatted:?}"
    );
    assert!(
        formatted.contains("click"),
        "the link's text content must be preserved, got {formatted:?}"
    );
    assert!(
        !formatted.contains("<img"),
        "<img> must not survive end to end, got {formatted:?}"
    );
    assert!(
        !formatted.contains("evil.example"),
        "the remote img src must not survive end to end, got {formatted:?}"
    );
}

#[tokio::test]
async fn custom_message_like_event_projects_a_bounded_payload_and_fallback_body() {
    let items = projected_items(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        let content = DemoNoteEventContent {
            schema_version: 1,
            title: "Deployed to staging".to_owned(),
            body: "Card: Deployed to staging".to_owned(),
        };
        room.add_timeline_event(f.event(content).event_id(event_id!("$custom1")))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let item = real[0];
    assert_eq!(item.kind, "customMessage");
    assert_eq!(
        item.detail.as_deref(),
        Some("dev.supermessage.demo.note.v1")
    );
    assert_eq!(item.body.as_deref(), Some("Card: Deployed to staging"));

    let payload = item
        .custom_payload
        .as_ref()
        .expect("a payload under the byte cap must be carried across IPC");
    assert_eq!(payload["title"], "Deployed to staging");
    assert_eq!(payload["schema_version"], 1);
}

#[tokio::test]
async fn custom_message_like_event_drops_an_oversized_payload_but_keeps_the_fallback_body() {
    // Comfortably over `CUSTOM_PAYLOAD_MAX_BYTES` once serialized (the field
    // itself already exceeds the cap, so the surrounding JSON object pushes
    // it further past it) — the whole point being that a genuinely oversized
    // real-world event (a `Set` diff re-sending it wholesale every touch)
    // must never reach the webview as a giant blob, but must still leave the
    // reader something to read.
    let huge_title = "x".repeat(CUSTOM_PAYLOAD_MAX_BYTES + 500);
    let items = projected_items(move |room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        let content = DemoNoteEventContent {
            schema_version: 1,
            title: huge_title,
            body: "fallback text for an oversized card".to_owned(),
        };
        room.add_timeline_event(f.event(content).event_id(event_id!("$custom2")))
    })
    .await;

    let real = real_items(&items);
    assert_eq!(
        real.len(),
        1,
        "expected exactly one real item, got {real:#?}"
    );
    let item = real[0];
    assert_eq!(item.kind, "customMessage");
    assert!(
        item.custom_payload.is_none(),
        "an oversized payload must be dropped whole, not truncated"
    );
    assert_eq!(
        item.body.as_deref(),
        Some("fallback text for an oversized card"),
        "the plain-text fallback body must survive even when the payload is dropped"
    );
}

// Room-list previews (spec §6.1.1) against real SDK-produced content — see
// [`projected_previews`] for why these exist alongside `core::timeline`'s
// pure unit tests, and `core::rooms::room_preview` for what the SDK's own
// latest-event filter drops before any of this runs in production.

/// The single preview `projected_previews` produced, asserting there was
/// exactly one event to preview.
fn only_preview(previews: &[Option<MessagePreview>]) -> &Option<MessagePreview> {
    assert_eq!(
        previews.len(),
        1,
        "expected exactly one event to preview, got {previews:#?}"
    );
    &previews[0]
}

#[tokio::test]
async fn text_message_previews_as_its_body_with_no_sender_prefix() {
    let previews = projected_previews(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.text_msg("hello world").event_id(event_id!("$p1")))
    })
    .await;

    let preview = only_preview(&previews)
        .as_ref()
        .expect("a text message is previewable");
    // No `Alice: ` — composing a prefix is the webview's job, and §6.1.1
    // only ever adds one for your own messages.
    assert_eq!(preview.text, "hello world");
    assert!(!preview.is_own, "ALICE is not the harness's own user");
    assert!(!preview.names_sender);
    assert_eq!(preview.event_type, None);
}

#[tokio::test]
async fn own_message_previews_with_is_own_set() {
    // The mock client builder's own default user id — see `projected_items`'s
    // doc comment. This is what drives the webview's `You: ` prefix.
    let own_user = user_id!("@example:localhost");
    let previews = projected_previews(move |room_id, room| {
        let f = EventFactory::new().room(room_id).sender(own_user);
        room.add_timeline_event(f.text_msg("on it").event_id(event_id!("$p2")))
    })
    .await;

    let preview = only_preview(&previews)
        .as_ref()
        .expect("an own text message is previewable");
    assert_eq!(preview.text, "on it");
    assert!(preview.is_own);
}

#[tokio::test]
async fn notice_message_previews_like_any_other_text() {
    // The msgtype most of this org's agent traffic actually uses (spec §A):
    // de-emphasised in the timeline, but never suppressed, so a roster that
    // dropped it would be blank for most rooms here.
    let previews = projected_previews(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.notice("build green").event_id(event_id!("$p3")))
    })
    .await;

    assert_eq!(
        only_preview(&previews)
            .as_ref()
            .expect("a notice is previewable")
            .text,
        "build green"
    );
}

#[tokio::test]
async fn emote_previews_with_its_sender_the_way_the_timeline_renders_it() {
    let previews = projected_previews(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.emote("waves hello").event_id(event_id!("$p4")))
    })
    .await;

    let preview = only_preview(&previews)
        .as_ref()
        .expect("an emote is previewable");
    // No profile is loaded against this harness, so the name falls back to
    // the raw user id — exactly as `Timeline.svelte`'s own
    // `senderDisplayName ?? sender` does for the same event.
    assert_eq!(preview.text, format!("{} waves hello", *ALICE));
    // And the flag that stops a webview prefixing `You: ` onto a line that
    // already names its sender.
    assert!(preview.names_sender);
}

#[tokio::test]
async fn image_message_previews_as_its_filename() {
    let previews = projected_previews(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        let content =
            RoomMessageEventContent::new(MessageType::Image(ImageMessageEventContent::plain(
                "photo.png".to_owned(),
                owned_mxc_uri!("mxc://example.org/abc123"),
            )));
        room.add_timeline_event(f.event(content).event_id(event_id!("$p5")))
    })
    .await;

    assert_eq!(
        only_preview(&previews)
            .as_ref()
            .expect("an image is previewable")
            .text,
        "photo.png"
    );
}

#[tokio::test]
async fn a_multiline_body_previews_collapsed_and_bounded() {
    // The two transformations that make the preview safe to render as one
    // line and cheap to re-send for every room on a `Reset`, verified
    // together on one real event.
    let body = format!("deploy failed\n\n\t{}", "x".repeat(64 * 1024));
    let previews = projected_previews(move |room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.text_msg(body).event_id(event_id!("$p6")))
    })
    .await;

    let preview = only_preview(&previews)
        .as_ref()
        .expect("a long multi-line body is still previewable");
    assert!(
        !preview.text.contains('\n') && !preview.text.contains('\t'),
        "expected no raw whitespace left in {:?}",
        preview.text
    );
    assert!(preview.text.starts_with("deploy failed x"));
    assert_eq!(preview.text.chars().count(), PREVIEW_MAX_CHARS + 1);
}

#[tokio::test]
async fn a_custom_message_like_event_previews_generically_and_names_its_type() {
    // Unreachable in production — see `core::rooms::room_preview` on the two
    // independent reasons — but the mechanism §6.1.1 asks for is real, and
    // this is the only place it can be driven against a genuine
    // `MsgLikeKind::Other`. The text is the generic rather than the event's
    // own `body` because `MsgLikeKind::Other` discards the content and the
    // room list has no raw event to read it back out of.
    let previews = projected_previews(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        let content = DemoNoteEventContent {
            schema_version: 1,
            title: "Deployed to staging".to_owned(),
            body: "Card: Deployed to staging".to_owned(),
        };
        room.add_timeline_event(f.event(content).event_id(event_id!("$p7")))
    })
    .await;

    let preview = only_preview(&previews)
        .as_ref()
        .expect("a custom event must never preview as nothing");
    assert_eq!(preview.text, "Custom event");
    assert_eq!(
        preview.event_type.as_deref(),
        Some("dev.supermessage.demo.note.v1")
    );
}

#[tokio::test]
async fn a_room_rename_is_not_previewable() {
    // §6.1.1's central rule, against a real state event: a fleet whose
    // agents restart and rename must not fill its roster with noise that
    // displaces the last thing actually said.
    let previews = projected_previews(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(f.room_name("New room name").event_id(event_id!("$p8")))
    })
    .await;

    assert_eq!(only_preview(&previews), &None);
}

#[tokio::test]
async fn a_membership_change_is_not_previewable() {
    let previews = projected_previews(|room_id, room| {
        // No factory-level `.sender()`, same reason as
        // `membership_invite_projects_as_membership_with_the_invited_detail`.
        let f = EventFactory::new().room(room_id);
        room.add_timeline_event(f.member(&BOB).invited(&ALICE).display_name("Alice"))
    })
    .await;

    assert_eq!(only_preview(&previews), &None);
}

#[tokio::test]
async fn a_redacted_message_is_not_previewable() {
    let previews = projected_previews(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(
            f.redacted(&ALICE, RedactedRoomMessageEventContent::new())
                .event_id(event_id!("$p9")),
        )
    })
    .await;

    assert_eq!(only_preview(&previews), &None);
}

#[tokio::test]
async fn an_undecryptable_message_is_not_previewable() {
    // Belt and braces: the SDK's own latest-event scan already skips UTDs
    // (`core::rooms::room_preview`), so in production the roster shows an
    // older readable message instead. This pins the behaviour if that ever
    // changes upstream — a roster row reading "Unable to decrypt" would be
    // the timeline's placeholder leaking onto a surface with no room for it.
    let previews = projected_previews(|room_id, room| {
        let f = EventFactory::new().room(room_id).sender(&ALICE);
        room.add_timeline_event(
            f.encrypted(
                "AwgAEtABWuWeRLintqVP5ez5kki8sDsX7zSq++9AJo9lELGTDjNKzbF8sowUgg0D",
                "sKSGv2uD9zUncgL6GiLedvuky3fjVcEz9qVKZkpzN14",
                "PNQBRWYIJL",
                "unknown-session-id",
            )
            .event_id(event_id!("$p10")),
        )
    })
    .await;

    assert_eq!(only_preview(&previews), &None);
}
