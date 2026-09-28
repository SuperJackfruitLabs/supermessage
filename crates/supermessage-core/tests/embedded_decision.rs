//! One card per decision, through the production timeline.
//!
//! AgentPod is moving a permission request from a separate
//! `dev.agentpod.permission.v1` event onto the prose message that asks it
//! (`crate::embedded`). A hub mid-migration sends both, and every host would
//! draw two cards for one question unless the core settles it. This drives
//! the real `FocusedTimeline` against a mock homeserver and checks what a
//! host is left holding — both the snapshot a resync serves and the state a
//! host builds by applying every envelope it was sent.

// Same reasoning as `lib.rs`'s identical attribute.
#![recursion_limit = "256"]

use std::sync::{Arc, Mutex};
use std::time::Duration;

use matrix_sdk::ruma::events::AnySyncTimelineEvent;
use matrix_sdk::ruma::room_id;
use matrix_sdk::ruma::serde::Raw;
use matrix_sdk::test_utils::mocks::MatrixMockServer;
use matrix_sdk_test::JoinedRoomBuilder;
use serde_json::{json, Value};

use supermessage_core::dto::{apply_ops, DiffEnvelope, TimelineRow};
use supermessage_core::event::{CoreEvent, EventSink};
use supermessage_core::item_view::ItemView;
use supermessage_core::timeline::FocusedTimeline;
use supermessage_core::tls::install_ring_provider;

#[derive(Default)]
struct Envelopes(Mutex<Vec<DiffEnvelope<TimelineRow>>>);

impl EventSink for Envelopes {
    fn emit(&self, event: CoreEvent) {
        if let CoreEvent::TimelineDiff(envelope) = event {
            self.0.lock().unwrap().push(envelope);
        }
    }
}

fn request() -> Value {
    json!({
        "schema_version": 1,
        "session_id": "sess-1",
        "request_seq": 4,
        "title": "Run the migration",
        "options": [
            { "option_id": "allow_once", "name": "Allow once" },
            { "option_id": "reject", "name": "Reject" }
        ]
    })
}

fn raw(event: Value) -> Raw<AnySyncTimelineEvent> {
    Raw::from_json(serde_json::value::to_raw_value(&event).unwrap())
}

/// The hub's prose message, carrying the request under its key.
fn prose(event_id: &str) -> Raw<AnySyncTimelineEvent> {
    raw(json!({
        "type": "m.room.message",
        "event_id": event_id,
        "sender": "@agent_hermes:example.org",
        "origin_server_ts": 1_000,
        "content": {
            "msgtype": "m.text",
            "body": "Allow Run the migration? Reply 1 (Allow once) or 2 (Reject).",
            "dev.agentpod.permission": request(),
        }
    }))
}

/// The separate event a hub sent before it embedded the request.
fn separate(event_id: &str) -> Raw<AnySyncTimelineEvent> {
    let mut content = request();
    content["body"] = json!("Allow Run the migration?");
    raw(json!({
        "type": "dev.agentpod.permission.v1",
        "event_id": event_id,
        "sender": "@agent_hermes:example.org",
        "origin_server_ts": 1_001,
        "content": content,
    }))
}

fn row<'a>(rows: &'a [TimelineRow], event_id: &str) -> Option<&'a TimelineRow> {
    rows.iter()
        .find(|row| row.item.event_id.as_deref() == Some(event_id))
}

fn cards(rows: &[TimelineRow]) -> Vec<String> {
    rows.iter()
        .filter(|row| matches!(row.view, ItemView::CustomEvent { .. }))
        .filter_map(|row| row.item.event_id.clone())
        .collect()
}

/// What a host holds after applying every envelope, in order.
fn applied(sink: &Envelopes) -> Vec<TimelineRow> {
    let mut rows = Vec::new();
    for envelope in sink.0.lock().unwrap().iter() {
        apply_ops(&mut rows, &envelope.ops);
    }
    rows
}

async fn settled(
    focused: &FocusedTimeline,
    done: impl Fn(&[TimelineRow]) -> bool,
) -> Vec<TimelineRow> {
    for _ in 0..50 {
        let rows = focused.snapshot().await.expect("a room is focused").2;
        if done(&rows) {
            return rows;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    focused.snapshot().await.unwrap().2
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_request_sent_both_ways_is_one_card_and_it_is_the_prose() {
    install_ring_provider();
    let room_id = room_id!("!both:example.org");
    let server = MatrixMockServer::new().await;
    let client = server.client_builder().build().await;
    client.event_cache().subscribe().unwrap();
    server.mock_room_state_encryption().plain().mount().await;

    server
        .sync_room(
            &client,
            JoinedRoomBuilder::new(room_id)
                .add_timeline_event(separate("$separate"))
                .add_timeline_event(prose("$prose")),
        )
        .await;

    let sink = Arc::new(Envelopes::default());
    let focused = FocusedTimeline::default();
    focused
        .subscribe(&client, room_id.as_str(), sink.clone())
        .await
        .unwrap();

    let rows = settled(&focused, |rows| {
        row(rows, "$prose").is_some() && row(rows, "$separate").is_some()
    })
    .await;
    assert_eq!(
        cards(&rows),
        vec!["$prose".to_string()],
        "one card, on the prose"
    );
    assert_eq!(row(&rows, "$separate").unwrap().view, ItemView::None);

    // The same on the wire: a host applying the envelopes draws one card.
    let host = applied(&sink);
    assert_eq!(cards(&host), vec!["$prose".to_string()]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_prose_arriving_after_the_card_takes_its_place() {
    install_ring_provider();
    let room_id = room_id!("!later:example.org");
    let server = MatrixMockServer::new().await;
    let client = server.client_builder().build().await;
    client.event_cache().subscribe().unwrap();
    server.mock_room_state_encryption().plain().mount().await;
    server
        .sync_room(
            &client,
            JoinedRoomBuilder::new(room_id).add_timeline_event(separate("$separate")),
        )
        .await;

    let sink = Arc::new(Envelopes::default());
    let focused = FocusedTimeline::default();
    focused
        .subscribe(&client, room_id.as_str(), sink.clone())
        .await
        .unwrap();
    let before = settled(&focused, |rows| row(rows, "$separate").is_some()).await;
    assert_eq!(
        cards(&before),
        vec!["$separate".to_string()],
        "alone, it is the card"
    );

    server
        .sync_room(
            &client,
            JoinedRoomBuilder::new(room_id).add_timeline_event(prose("$prose")),
        )
        .await;
    let after = settled(&focused, |rows| row(rows, "$prose").is_some()).await;
    assert_eq!(cards(&after), vec!["$prose".to_string()]);
    assert_eq!(cards(&applied(&sink)), vec!["$prose".to_string()]);
}
