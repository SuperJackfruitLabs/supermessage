//! Keeps a room's later messages going out after one of them fails.
//!
//! matrix-sdk 0.19's send queue sends a room's requests strictly in order. A
//! request that fails with an *unrecoverable* error (an HTTP 4xx other than
//! 429, a response it cannot parse, an encryption-policy refusal, a missing
//! attachment) is marked "wedged" and blocks every later request in that room
//! until it is retried (`SendHandle::unwedge`) or removed
//! (`SendHandle::abort`). The wedge is kept in the store, so it survives a
//! restart. And *any* failure, recoverable or not, turns that room's queue
//! off, and nothing in the SDK turns it back on.
//!
//! No host offers a Retry or Delete action on a failed message yet, so left
//! alone one refused message silences the room for good. Operator's ruling
//! (2026-11-27): until those actions exist, the core drops a refused request
//! and lets the room carry on, and turns a room's queue back on after a
//! recoverable failure with a bounded backoff.
//!
//! - **Unrecoverable failure:** every wedged request in the room is aborted
//!   and the room's queue is turned back on at once. Logged with the room id
//!   and the error's kind, never the message.
//! - **At start:** requests wedged in an earlier session are aborted the same
//!   way, before anything new fails. (Reading the local echoes also starts
//!   the queue of every room with unsent requests, which is what
//!   `SendQueue::respawn_tasks_for_rooms_with_unsent_requests` would do.)
//! - **Recoverable failure:** the request stays queued (the SDK keeps it),
//!   and the room's queue is turned back on after [`Backoff`]: one re-enable
//!   per failure, the delay doubling with each failure in a row, up to its
//!   cap, and starting over once something in the room is sent.
//!
//! A queue the client has turned off as a whole (`Client::pause`, when iOS
//! suspends the app) is left off: `Client::resume` turns every room back on.
//!
//! The task holds a `Client`, so the session stops it before wiping the store
//! — see `Session::logout`.

use std::collections::{HashMap, HashSet};
use std::time::Duration;

use matrix_sdk::ruma::{OwnedRoomId, RoomId};
use matrix_sdk::send_queue::{LocalEcho, LocalEchoContent, RoomSendQueueUpdate, SendQueueUpdate};
use matrix_sdk::{Client, QueueWedgeError};
use tokio::sync::broadcast::{self, error::RecvError};
use tokio::task::{JoinHandle, JoinSet};

/// How long a room waits after a recoverable failure before it tries again.
#[derive(Clone, Copy, Debug)]
pub struct Backoff {
    /// The wait after the first failure in a row.
    pub first: Duration,
    /// The longest wait, however many failures in a row.
    pub cap: Duration,
}

impl Backoff {
    /// The wait after the `failures`th failure in a row (counting from 1).
    fn delay(&self, failures: u32) -> Duration {
        let doublings = failures.saturating_sub(1).min(16);
        self.first.saturating_mul(1 << doublings).min(self.cap)
    }
}

/// The session's backoff. The SDK has already retried the request itself
/// (three attempts over about a second and a half) before it reports the
/// failure, so the first wait can be short.
pub const BACKOFF: Backoff = Backoff {
    first: Duration::from_secs(2),
    cap: Duration::from_secs(60),
};

/// Starts watching `client`'s send queue with the session's [`BACKOFF`].
pub fn spawn_send_watch(client: Client) -> JoinHandle<()> {
    spawn_with(client, BACKOFF)
}

/// [`spawn_send_watch`] with a chosen backoff.
///
/// Subscribes before it returns, so no failure after this call is missed.
pub fn spawn_with(client: Client, backoff: Backoff) -> JoinHandle<()> {
    let updates = client.send_queue().subscribe();
    tokio::spawn(Watch::new(client, backoff).run(updates))
}

struct Watch {
    client: Client,
    backoff: Backoff,
    /// Recoverable failures in a row, per room.
    failures: HashMap<OwnedRoomId, u32>,
    /// Rooms with a re-enable already waiting; a second failure while one
    /// waits does not add another.
    waiting: HashSet<OwnedRoomId>,
    /// The waits themselves. Dropped with the task, which cancels them.
    timers: JoinSet<OwnedRoomId>,
}

impl Watch {
    fn new(client: Client, backoff: Backoff) -> Self {
        Self {
            client,
            backoff,
            failures: HashMap::new(),
            waiting: HashSet::new(),
            timers: JoinSet::new(),
        }
    }

    async fn run(mut self, mut updates: broadcast::Receiver<SendQueueUpdate>) {
        // Whatever an earlier session left wedged in the store.
        self.sweep().await;
        loop {
            tokio::select! {
                update = updates.recv() => match update {
                    Ok(SendQueueUpdate { room_id, update }) => self.on_update(room_id, update).await,
                    Err(RecvError::Lagged(missed)) => {
                        // A missed error is a room left stuck; look at them all.
                        tracing::debug!(missed, "send queue updates lagged; sweeping every room");
                        self.sweep().await;
                    }
                    Err(RecvError::Closed) => break,
                },
                Some(done) = self.timers.join_next(), if !self.timers.is_empty() => {
                    if let Ok(room_id) = done {
                        self.waiting.remove(&room_id);
                        self.enable(&room_id);
                    }
                }
            }
        }
    }

    async fn on_update(&mut self, room_id: OwnedRoomId, update: RoomSendQueueUpdate) {
        match update {
            RoomSendQueueUpdate::SendError {
                error,
                is_recoverable: false,
                ..
            } => {
                tracing::warn!(
                    room_id = %room_id,
                    kind = %error_kind(&error),
                    "a message was refused; dropping it so later ones can be sent"
                );
                self.drop_wedged(&room_id).await;
                self.enable(&room_id);
            }
            RoomSendQueueUpdate::SendError {
                error,
                is_recoverable: true,
                ..
            } => {
                tracing::info!(
                    room_id = %room_id,
                    kind = %error_kind(&error),
                    "a message could not be sent yet; the room will try again"
                );
                self.retry_later(room_id);
            }
            RoomSendQueueUpdate::SentEvent { .. } => {
                self.failures.remove(&room_id);
            }
            _ => {}
        }
    }

    /// Drops every wedged request in every room with unsent requests, and
    /// schedules a retry for any such room whose queue is off.
    async fn sweep(&mut self) {
        let rooms = match self.client.send_queue().local_echoes().await {
            Ok(rooms) => rooms,
            Err(err) => {
                tracing::warn!(error = %err, "could not read the unsent messages");
                return;
            }
        };
        for (room_id, echoes) in rooms {
            if abort_wedged(&room_id, &echoes).await > 0 {
                self.enable(&room_id);
            }
            let off = self
                .client
                .get_room(&room_id)
                .is_some_and(|room| !room.send_queue().is_enabled());
            if off && !echoes.is_empty() {
                self.retry_later(room_id);
            }
        }
    }

    /// Drops every wedged request in `room_id`.
    async fn drop_wedged(&self, room_id: &RoomId) {
        let Some(room) = self.client.get_room(room_id) else {
            return;
        };
        match room.send_queue().subscribe().await {
            Ok((echoes, _)) => {
                abort_wedged(room_id, &echoes).await;
            }
            Err(err) => {
                tracing::warn!(room_id = %room_id, error = %err, "could not read the room's unsent messages");
            }
        }
    }

    /// Turns `room_id`'s queue back on after the backoff, unless a re-enable
    /// is already waiting.
    fn retry_later(&mut self, room_id: OwnedRoomId) {
        if self.waiting.contains(&room_id) {
            return;
        }
        let failures = self.failures.entry(room_id.clone()).or_insert(0);
        *failures = failures.saturating_add(1);
        let delay = self.backoff.delay(*failures);
        self.waiting.insert(room_id.clone());
        self.timers.spawn(async move {
            tokio::time::sleep(delay).await;
            room_id
        });
    }

    /// Turns `room_id`'s queue on, unless the client's queue as a whole is
    /// off — `Client::resume` turns every room on again itself.
    fn enable(&self, room_id: &RoomId) {
        if !self.client.send_queue().is_enabled() {
            return;
        }
        if let Some(room) = self.client.get_room(room_id) {
            room.send_queue().set_enabled(true);
        }
    }
}

/// Aborts each wedged request among `echoes`; returns how many it aborted.
async fn abort_wedged(room_id: &RoomId, echoes: &[LocalEcho]) -> usize {
    let mut aborted = 0;
    for echo in echoes {
        let result = match &echo.content {
            LocalEchoContent::Event {
                send_handle,
                send_error: Some(wedge),
                ..
            } => Some((send_handle.abort().await, wedge)),
            LocalEchoContent::Redaction {
                send_handle,
                send_error: Some(wedge),
                ..
            } => Some((send_handle.abort().await, wedge)),
            _ => None,
        };
        match result {
            Some((Ok(true), wedge)) => {
                aborted += 1;
                tracing::warn!(
                    room_id = %room_id,
                    transaction_id = %echo.transaction_id,
                    kind = wedge_kind(wedge),
                    "dropped a refused message"
                );
            }
            Some((Ok(false), _)) | None => {}
            Some((Err(err), wedge)) => tracing::warn!(
                room_id = %room_id,
                transaction_id = %echo.transaction_id,
                kind = wedge_kind(wedge),
                error = %err,
                "could not drop a refused message"
            ),
        }
    }
    aborted
}

/// What kind of wedge, without its message: `GenericApiError` carries the
/// server's own words, which are not ours to log.
fn wedge_kind(wedge: &QueueWedgeError) -> &'static str {
    match wedge {
        QueueWedgeError::InsecureDevices { .. } => "insecure_devices",
        QueueWedgeError::IdentityViolations { .. } => "identity_violations",
        QueueWedgeError::CrossVerificationRequired => "own_verification_required",
        QueueWedgeError::MissingMediaContent => "missing_media_content",
        QueueWedgeError::InvalidMimeType { .. } => "invalid_mime_type",
        QueueWedgeError::GenericApiError { .. } => "api_error",
    }
}

/// What kind of error, without its message: the status and `errcode` of a
/// homeserver's answer, else the error's family.
fn error_kind(error: &matrix_sdk::Error) -> String {
    if let Some(api) = error.as_client_api_error() {
        let status = api.status_code.as_u16();
        return match api.error_kind() {
            Some(kind) => format!("http {status} {}", kind.errcode()),
            None => format!("http {status}"),
        };
    }
    match error {
        matrix_sdk::Error::Http(_) => "http".into(),
        matrix_sdk::Error::ConcurrentRequestFailed => "concurrent_request_failed".into(),
        _ => "other".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use matrix_sdk::ruma::events::room::message::RoomMessageEventContent;
    use matrix_sdk::ruma::{event_id, room_id, OwnedEventId};
    use matrix_sdk::send_queue::{LocalEchoContent, RoomSendQueueUpdate};
    use matrix_sdk::test_utils::mocks::MatrixMockServer;
    use serde_json::json;
    use tokio::sync::broadcast;
    use wiremock::ResponseTemplate;

    /// Fast enough for a test, slow enough to see a backoff happen.
    const TEST_BACKOFF: Backoff = Backoff {
        first: Duration::from_millis(50),
        cap: Duration::from_millis(200),
    };

    fn text(body: &str) -> matrix_sdk::ruma::events::AnyMessageLikeEventContent {
        RoomMessageEventContent::text_plain(body).into()
    }

    /// Waits for the update `pick` accepts, failing after `secs`.
    async fn wait_for<T>(
        updates: &mut broadcast::Receiver<RoomSendQueueUpdate>,
        secs: u64,
        what: &str,
        mut pick: impl FnMut(&RoomSendQueueUpdate) -> Option<T>,
    ) -> T {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(secs);
        loop {
            let left = deadline.saturating_duration_since(tokio::time::Instant::now());
            match tokio::time::timeout(left, updates.recv()).await {
                Ok(Ok(update)) => {
                    if let Some(found) = pick(&update) {
                        return found;
                    }
                }
                Ok(Err(broadcast::error::RecvError::Lagged(_))) => {}
                Ok(Err(broadcast::error::RecvError::Closed)) => panic!("updates closed"),
                Err(_) => panic!("never saw {what}"),
            }
        }
    }

    fn sent(update: &RoomSendQueueUpdate) -> Option<OwnedEventId> {
        match update {
            RoomSendQueueUpdate::SentEvent { event_id, .. } => Some(event_id.clone()),
            _ => None,
        }
    }

    async fn wedged_echoes(room: &matrix_sdk::Room) -> usize {
        let (echoes, _) = room.send_queue().subscribe().await.unwrap();
        echoes
            .iter()
            .filter(|echo| {
                matches!(
                    &echo.content,
                    LocalEchoContent::Event {
                        send_error: Some(_),
                        ..
                    }
                )
            })
            .count()
    }

    /// One re-enable per failure, each wait double the last, never past the
    /// cap — so a room that keeps failing settles at one try a minute.
    #[test]
    fn the_backoff_doubles_up_to_its_cap() {
        let waits: Vec<_> = (1..=7).map(|n| BACKOFF.delay(n).as_secs()).collect();
        assert_eq!(waits, [2, 4, 8, 16, 32, 60, 60]);
        assert_eq!(BACKOFF.delay(u32::MAX), BACKOFF.cap);
    }

    /// matrix-sdk 0.19 parks a request the homeserver refused (a 413 here)
    /// and holds every later one in the room behind it. The watcher drops
    /// the refused one and lets the queue run on, so the second message
    /// still goes out.
    ///
    /// Fails without the watcher: "two" waits behind "one" forever.
    /// Mutation seen failing (run): removing `self.drop_wedged(&room_id)` from
    /// the unrecoverable branch of `on_update` fails this ("never saw "two"
    /// sent"), and only this.
    #[tokio::test]
    async fn a_refused_message_does_not_hold_back_the_next_one() {
        let server = MatrixMockServer::new().await;
        let client = server.client_builder().build().await;
        server.mock_room_state_encryption().plain().mount().await;
        let room_id = room_id!("!refused:example.org");
        let room = server.sync_joined_room(&client, room_id).await;

        server
            .mock_room_send()
            .body_matches_partial_json(json!({ "body": "one" }))
            .error_too_large()
            .named("one is too large")
            .mount()
            .await;
        server
            .mock_room_send()
            .body_matches_partial_json(json!({ "body": "two" }))
            .ok(event_id!("$two"))
            .expect(1)
            .named("two is sent")
            .mount()
            .await;

        let watch = spawn_with(client.clone(), TEST_BACKOFF);
        let queue = room.send_queue();
        let (_, mut updates) = queue.subscribe().await.unwrap();
        queue.send(text("one")).await.unwrap();
        queue.send(text("two")).await.unwrap();

        let event_id = wait_for(&mut updates, 10, "\"two\" sent", sent).await;
        assert_eq!(event_id, event_id!("$two"));
        assert_eq!(
            wedged_echoes(&room).await,
            0,
            "the refused one is still parked"
        );
        assert!(queue.is_enabled(), "the room's queue was left disabled");

        watch.abort();
    }

    /// A request already parked when the session starts — the wedge is kept
    /// in the store, so it survives a restart — is dropped at start, and the
    /// message queued behind it goes out.
    ///
    /// Fails without the watcher's first sweep: nothing new fails, so no
    /// error arrives to act on, and "two" waits behind "one" forever.
    /// Mutation seen failing (run): removing the `self.sweep()` at the top of
    /// `run` fails this, and only this.
    #[tokio::test]
    async fn a_request_parked_before_the_session_started_is_cleared() {
        let server = MatrixMockServer::new().await;
        let client = server.client_builder().build().await;
        server.mock_room_state_encryption().plain().mount().await;
        let room_id = room_id!("!parked:example.org");
        let room = server.sync_joined_room(&client, room_id).await;

        server
            .mock_room_send()
            .body_matches_partial_json(json!({ "body": "one" }))
            .respond_with(ResponseTemplate::new(403).set_body_json(json!({
                "errcode": "M_FORBIDDEN",
                "error": "You are not allowed to send here",
            })))
            .named("one is forbidden")
            .mount()
            .await;
        server
            .mock_room_send()
            .body_matches_partial_json(json!({ "body": "two" }))
            .ok(event_id!("$two"))
            .expect(1)
            .named("two is sent")
            .mount()
            .await;

        // No watcher yet: "one" is refused and parked, "two" waits behind it.
        let queue = room.send_queue();
        let (_, mut updates) = queue.subscribe().await.unwrap();
        queue.send(text("one")).await.unwrap();
        wait_for(&mut updates, 10, "\"one\" refused", |update| match update {
            RoomSendQueueUpdate::SendError { is_recoverable, .. } => {
                assert!(!is_recoverable, "a 403 should park the request");
                Some(())
            }
            _ => None,
        })
        .await;
        queue.send(text("two")).await.unwrap();
        assert_eq!(wedged_echoes(&room).await, 1);

        // The session starts.
        let watch = spawn_with(client.clone(), TEST_BACKOFF);

        let event_id = wait_for(&mut updates, 10, "\"two\" sent", sent).await;
        assert_eq!(event_id, event_id!("$two"));
        assert_eq!(
            wedged_echoes(&room).await,
            0,
            "the parked one is still there"
        );

        watch.abort();
    }

    /// A recoverable failure (the server answering 500 until the SDK's own
    /// retries run out) turns the room's queue off and keeps the message.
    /// The watcher turns it back on after a backoff, and the message is sent
    /// once the server answers 200.
    ///
    /// Fails without the watcher: nothing turns the room's queue back on
    /// until the app restarts. Mutation seen failing (run): removing
    /// `self.retry_later(room_id)` from the recoverable branch of `on_update`
    /// fails this, and only this.
    #[tokio::test]
    async fn a_message_that_failed_on_a_server_error_is_sent_once_it_recovers() {
        let server = MatrixMockServer::new().await;
        let client = server.client_builder().build().await;
        server.mock_room_state_encryption().plain().mount().await;
        let room_id = room_id!("!flaky:example.org");
        let room = server.sync_joined_room(&client, room_id).await;

        // More 500s than one round of the SDK's retries, so the room's queue
        // turns itself off at least once before the 200.
        server
            .mock_room_send()
            .error500()
            .up_to_n_times(4)
            .named("the server is down")
            .mount()
            .await;
        server
            .mock_room_send()
            .ok(event_id!("$one"))
            .expect(1)
            .named("the server is back")
            .mount()
            .await;

        let watch = spawn_with(client.clone(), TEST_BACKOFF);
        let queue = room.send_queue();
        let (_, mut updates) = queue.subscribe().await.unwrap();
        queue.send(text("one")).await.unwrap();

        wait_for(
            &mut updates,
            15,
            "a recoverable error",
            |update| match update {
                RoomSendQueueUpdate::SendError { is_recoverable, .. } => {
                    assert!(is_recoverable, "a 500 should keep the request");
                    Some(())
                }
                _ => None,
            },
        )
        .await;
        let event_id = wait_for(&mut updates, 15, "\"one\" sent", sent).await;
        assert_eq!(event_id, event_id!("$one"));
        assert!(queue.is_enabled());

        watch.abort();
    }
}
