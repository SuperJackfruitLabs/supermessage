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
use matrix_sdk::ruma::events::room::message::{
    ImageMessageEventContent, MessageType, RedactedRoomMessageEventContent, RoomMessageEventContent,
};
use matrix_sdk::ruma::events::room::ImageInfo;
use matrix_sdk::ruma::{event_id, owned_mxc_uri, room_id, uint, user_id, RoomId};
use matrix_sdk::test_utils::mocks::MatrixMockServer;
use matrix_sdk_test::event_factory::EventFactory;
use matrix_sdk_test::{JoinedRoomBuilder, ALICE, BOB};
use matrix_sdk_ui::timeline::RoomExt;

use supermessage_lib::core::dto::{apply_ops, project_diff, TimelineItemDto};
use supermessage_lib::core::timeline::project_item;
use supermessage_lib::core::tls::install_ring_provider;

/// Joins a fresh room (`!room:example.org`) on a mocked homeserver, syncs in
/// whatever `build` adds to it, and returns the resulting materialized
/// `TimelineItemDto` list — see this file's doc comment for the exact shape.
async fn projected_items(
    build: impl FnOnce(&RoomId, JoinedRoomBuilder) -> JoinedRoomBuilder,
) -> Vec<TimelineItemDto> {
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

    let timeline = room
        .timeline()
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

    let ops = batch.into_iter().map(|diff| {
        project_diff(diff, |item| {
            project_item(&item, &own_user).expect("project_item is total over TimelineItemKind")
        })
    });

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
