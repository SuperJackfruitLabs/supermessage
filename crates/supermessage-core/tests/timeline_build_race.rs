//! Issue #2: a timeline built while a sync lands.
//!
//! `matrix_sdk_ui`'s `TimelineBuilder::build` subscribes to the room's event
//! cache, discards that snapshot, awaits `latest_encryption_state()` (a
//! network request when the room's encryption state is not yet known), and
//! only then reads the cache for its initial items. An event that lands in
//! that window is in both the initial items and the update the subscription
//! delivers afterwards, so the SDK timeline holds it twice — and every later
//! positional op from the event cache is aimed one batch too early. Live, on
//! tuwunel 1.9.1, the `Remove` meant for this client's own sent message (the
//! event cache deduplicating it against the server's copy) deleted a message
//! the same account had sent from another device, and left the own message
//! on screen twice.
//!
//! This makes the window deterministic instead of lucky: the encryption-state
//! request is answered slowly, and a sync lands while `build()` is waiting on
//! it. Everything after that is the production path — `FocusedTimeline::
//! subscribe`, `send_text`, and the materialized list hosts are served from.

// Same reasoning as `lib.rs`'s identical attribute.
#![recursion_limit = "256"]

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use matrix_sdk::ruma::{event_id, room_id, user_id, EventId};
use matrix_sdk::test_utils::mocks::MatrixMockServer;
use matrix_sdk_test::event_factory::EventFactory;
use matrix_sdk_test::{JoinedRoomBuilder, BOB};
use serde_json::json;
use wiremock::ResponseTemplate;

use supermessage_core::dto::{DiffEnvelope, DiffOp, TimelineRow};
use supermessage_core::event::{CoreEvent, EventSink};
use supermessage_core::timeline::FocusedTimeline;
use supermessage_core::tls::install_ring_provider;

/// Every timeline envelope the core emits, in order.
#[derive(Default)]
struct Envelopes(Mutex<Vec<DiffEnvelope<TimelineRow>>>);

impl EventSink for Envelopes {
    fn emit(&self, event: CoreEvent) {
        if let CoreEvent::TimelineDiff(envelope) = event {
            self.0.lock().unwrap().push(envelope);
        }
    }
}

/// How many times each remote event id appears among `rows`. Local echoes
/// (anything still carrying a send state) are left out, as the core's own
/// check leaves them out.
fn remote_id_counts(rows: &[TimelineRow]) -> HashMap<String, usize> {
    let mut counts = HashMap::new();
    for row in rows {
        if row.item.send_state.is_some() {
            continue;
        }
        if let Some(id) = &row.item.event_id {
            *counts.entry(id.clone()).or_insert(0) += 1;
        }
    }
    counts
}

fn duplicated(rows: &[TimelineRow]) -> Vec<String> {
    let mut ids: Vec<String> = remote_id_counts(rows)
        .into_iter()
        .filter(|(_, n)| *n > 1)
        .map(|(id, _)| id)
        .collect();
    ids.sort();
    ids
}

async fn snapshot_rows(focused: &FocusedTimeline) -> Vec<TimelineRow> {
    focused.snapshot().await.expect("a room is focused").2
}

/// Polls the core's materialized list until `done` holds, or gives up.
async fn wait_until(focused: &FocusedTimeline, done: impl Fn(&[TimelineRow]) -> bool) {
    for _ in 0..50 {
        if done(&snapshot_rows(focused).await) {
            return;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_timeline_built_while_a_sync_lands_keeps_every_message_once() {
    install_ring_provider();

    let room_id = room_id!("!race:example.org");
    let own_user = user_id!("@example:localhost");
    let server = MatrixMockServer::new().await;
    let client = server.client_builder().build().await;

    // The await inside `build()` the race rides on. Answered as "not
    // encrypted" (404), as `mock_room_state_encryption().plain()` does, but
    // slowly, so a sync can land while the builder is parked here.
    server
        .mock_room_state_encryption()
        .respond_with(
            ResponseTemplate::new(404)
                .set_body_json(json!({ "errcode": "M_NOT_FOUND", "error": "Event not found." }))
                .set_delay(Duration::from_millis(800)),
        )
        .mount()
        .await;
    server
        .mock_room_send()
        .ok(event_id!("$own-send"))
        .mount()
        .await;
    server.sync_joined_room(&client, room_id).await;

    let f = EventFactory::new().room(room_id);
    let racing: Vec<&EventId> = vec![
        event_id!("$racing-1"),
        event_id!("$racing-2"),
        event_id!("$racing-3"),
    ];

    // Open the room, and while the builder waits on the encryption state,
    // let a sync carrying three messages land.
    let sink = Arc::new(Envelopes::default());
    let focused = FocusedTimeline::default();
    let (opened, ()) = tokio::join!(
        focused.subscribe(&client, room_id.as_str(), sink.clone()),
        async {
            tokio::time::sleep(Duration::from_millis(200)).await;
            let mut room = JoinedRoomBuilder::new(room_id);
            for (i, id) in racing.iter().enumerate() {
                room = room.add_timeline_event(
                    f.text_msg(format!("racing {i}")).sender(&BOB).event_id(id),
                );
            }
            server.sync_room(&client, room).await;
        }
    );
    opened.expect("the room opens");

    // The first frame hosts see. The streaming task emits it, so it may land
    // a moment after `subscribe` returns.
    let mut first = None;
    for _ in 0..50 {
        first = sink.0.lock().unwrap().first().cloned();
        if first.is_some() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let first = first.expect("a first envelope");
    let DiffOp::Reset { values } = &first.ops[0] else {
        panic!("the first envelope is a reset, got {:?}", first.ops);
    };
    assert_eq!(
        duplicated(values),
        Vec::<String>::new(),
        "the first frame must hold each event once"
    );

    // The same account says something from another device. Then, as the live
    // reproduction did, enough messages from someone else that the event
    // cache's positional `Remove` for this client's own send lines up with
    // the other device's message in a timeline that holds the racing batch
    // twice.
    let mut room = JoinedRoomBuilder::new(room_id).add_timeline_event(
        f.text_msg("What is 2+2? One short answer.")
            .sender(own_user)
            .event_id(event_id!("$other-device")),
    );
    for i in 0..racing.len() - 1 {
        room = room.add_timeline_event(
            f.text_msg(format!("filler {i}"))
                .sender(&BOB)
                .event_id(&EventId::parse(format!("$filler-{i}")).unwrap()),
        );
    }
    server.sync_room(&client, room).await;
    wait_until(&focused, |rows| {
        remote_id_counts(rows).contains_key("$filler-1")
    })
    .await;

    // This client sends; the send queue puts the sent event in the event
    // cache, and the server's copy arriving by sync is deduplicated against
    // it with a positional remove.
    focused
        .send_text(room_id.as_str(), "S6 own send", &[])
        .await
        .expect("the send is queued");
    wait_until(&focused, |rows| {
        rows.iter()
            .any(|row| row.item.event_id.as_deref() == Some("$own-send"))
    })
    .await;
    server
        .sync_room(
            &client,
            JoinedRoomBuilder::new(room_id).add_timeline_event(
                f.text_msg("S6 own send")
                    .sender(own_user)
                    .event_id(event_id!("$own-send")),
            ),
        )
        .await;
    // Nothing to wait *for* here — the failure is something disappearing —
    // so give the SDK time to apply the echo, then look.
    tokio::time::sleep(Duration::from_millis(1500)).await;

    let rows = snapshot_rows(&focused).await;
    let counts = remote_id_counts(&rows);
    // The two the live bug took: the other device's message (removed by an op
    // meant for the own send) and the own send (left on screen twice).
    let mut expected: Vec<String> = vec!["$other-device".into(), "$own-send".into()];
    expected.extend(racing.iter().map(|id| id.to_string()));
    expected.extend((0..racing.len() - 1).map(|i| format!("$filler-{i}")));
    for id in &expected {
        assert_eq!(
            counts.get(id).copied().unwrap_or(0),
            1,
            "{id} must be on screen exactly once; the timeline held {counts:?}"
        );
    }
    assert_eq!(duplicated(&rows), Vec::<String>::new());
}
