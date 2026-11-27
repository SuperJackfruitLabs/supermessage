//! Keeps a room's later messages going out after one of them fails.
//!
//! matrix-sdk 0.19's send queue sends a room's requests strictly in order. A
//! request that fails with an *unrecoverable* error is marked "wedged" and
//! blocks every later request in that room until it is retried
//! (`SendHandle::unwedge`) or removed (`SendHandle::abort`). "Unrecoverable"
//! is everything but a transient HTTP error: an HTTP 4xx other than 429, an
//! answer it cannot read, an encryption-policy refusal, a store or
//! crypto-store error, the cross-process store lock timing out, a missing
//! attachment. The wedge is kept in the store, so it survives a restart. And
//! *any* failure turns that room's queue off, and nothing in the SDK turns it
//! back on.
//!
//! No host offers a Retry or Delete action on a failed message yet, so left
//! alone one parked message silences the room for good. Operator's ruling
//! (2026-11-27): until those actions exist, the core retries what may yet
//! succeed, drops only what the homeserver has definitely refused, and lets
//! the room carry on.
//!
//! - **Refused:** the homeserver answered with a 4xx other than 401, 408 or
//!   429, or the attachment is gone or of a type that cannot be sent
//!   ([`Verdict`]). That request is aborted and the room's queue turned back
//!   on at once. Logged with the room id and the error's kind, never the
//!   message. The reader is not told: `CoreEvent` has no notice that the
//!   hosts show, and adding one is per-platform work.
//! - **Any other failure, parked or not:** nothing is deleted. After
//!   [`Backoff`] every parked request in the room is retried (`unwedge`; a
//!   redaction, which has no unwedge, is queued again and the parked copy
//!   removed) and the room's queue turned back on: one retry per failure, the
//!   wait doubling with each failure in a row up to its cap, starting over
//!   once something in the room is sent. A retry that is refused is then
//!   dropped as above.
//! - **At start:** requests parked in an earlier session are retried once,
//!   at once — the reason may have gone — and dropped only if that retry is
//!   refused. (Reading the local echoes also starts the queue of every room
//!   with unsent requests, which is what
//!   `SendQueue::respawn_tasks_for_rooms_with_unsent_requests` would do.)
//!
//! A queue the client has turned off as a whole (`Client::pause`, when iOS
//! suspends the app) is left off: `Client::resume` turns every room back on.
//!
//! The task holds a `Client`, so the session stops it before wiping the store
//! — see `Session::logout`.

use std::collections::{HashMap, HashSet};
use std::time::Duration;

use matrix_sdk::ruma::{OwnedRoomId, OwnedTransactionId, RoomId};
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
    /// Failures in a row, per room.
    failures: HashMap<OwnedRoomId, u32>,
    /// Rooms with a retry already waiting; a second failure while one waits
    /// does not add another.
    waiting: HashSet<OwnedRoomId>,
    /// The waits themselves. Dropped with the task, which cancels them.
    timers: JoinSet<OwnedRoomId>,
}

/// What to do with a request that failed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Verdict {
    /// The homeserver said no, or it can never be sent: remove it.
    Drop,
    /// It may yet succeed: keep it and try again.
    Retry,
}

/// Whether a failed request is dropped or retried. `error` is the live
/// failure, when there is one (a request parked in an earlier session has
/// only its `wedge`).
///
/// Dropped only on a definite refusal: a homeserver answer with a 4xx status
/// other than 401 (a token being refreshed), 408 (a proxy's timeout) or 429
/// (rate limited), or an attachment that is gone or of a type that cannot be
/// sent. Everything else — a store or crypto-store error, the cross-process
/// lock, an answer that cannot be read, an encryption-policy refusal the
/// reader can fix by verifying — is retried.
fn verdict(error: Option<&matrix_sdk::Error>, wedge: Option<&QueueWedgeError>) -> Verdict {
    let refused = error
        .and_then(|error| error.as_client_api_error())
        .is_some_and(|api| refused_status(api.status_code.as_u16()));
    let unsendable = matches!(
        wedge,
        Some(QueueWedgeError::MissingMediaContent | QueueWedgeError::InvalidMimeType { .. })
    );
    if refused || unsendable {
        Verdict::Drop
    } else {
        Verdict::Retry
    }
}

/// A homeserver status that means "no", not "not now".
fn refused_status(status: u16) -> bool {
    (400..500).contains(&status) && !matches!(status, 401 | 408 | 429)
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
        // Whatever an earlier session left parked in the store: retried once,
        // now.
        self.sweep(false).await;
        loop {
            tokio::select! {
                update = updates.recv() => match update {
                    Ok(SendQueueUpdate { room_id, update }) => self.on_update(room_id, update).await,
                    Err(RecvError::Lagged(missed)) => {
                        // A missed failure is a room left stuck; look at them
                        // all, through the backoff.
                        tracing::debug!(missed, "send queue updates lagged; sweeping every room");
                        self.sweep(true).await;
                    }
                    Err(RecvError::Closed) => break,
                },
                Some(done) = self.timers.join_next(), if !self.timers.is_empty() => {
                    if let Ok(room_id) = done {
                        self.waiting.remove(&room_id);
                        self.resume(&room_id).await;
                    }
                }
            }
        }
    }

    async fn on_update(&mut self, room_id: OwnedRoomId, update: RoomSendQueueUpdate) {
        match update {
            RoomSendQueueUpdate::SendError {
                transaction_id,
                error,
                is_recoverable: false,
            } => self.on_parked(room_id, transaction_id, &error).await,
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

    /// The SDK parked `transaction_id`: drop it if it was refused, else retry
    /// it after the backoff.
    async fn on_parked(
        &mut self,
        room_id: OwnedRoomId,
        transaction_id: OwnedTransactionId,
        error: &matrix_sdk::Error,
    ) {
        let echoes = self.echoes(&room_id).await;
        let echo = echoes
            .iter()
            .find(|echo| echo.transaction_id == transaction_id);
        match verdict(Some(error), echo.and_then(wedge_of)) {
            Verdict::Drop => {
                tracing::warn!(
                    room_id = %room_id,
                    transaction_id = %transaction_id,
                    kind = %error_kind(error),
                    "a message was refused; dropping it so later ones can be sent"
                );
                if let Some(echo) = echo {
                    abort(&room_id, echo).await;
                }
                self.enable(&room_id);
            }
            Verdict::Retry => {
                tracing::info!(
                    room_id = %room_id,
                    transaction_id = %transaction_id,
                    kind = %error_kind(error),
                    "a message was parked; the room will try it again"
                );
                self.retry_later(room_id);
            }
        }
    }

    /// Every room with unsent requests: a room with a parked request, or
    /// whose queue is off, is resumed — through the backoff when `later`,
    /// else now.
    async fn sweep(&mut self, later: bool) {
        let rooms = match self.client.send_queue().local_echoes().await {
            Ok(rooms) => rooms,
            Err(err) => {
                tracing::warn!(error = %err, "could not read the unsent messages");
                return;
            }
        };
        for (room_id, echoes) in rooms {
            let parked = echoes.iter().any(|echo| wedge_of(echo).is_some());
            let off = self
                .client
                .get_room(&room_id)
                .is_some_and(|room| !room.send_queue().is_enabled());
            if !(parked || off) {
                continue;
            }
            if later {
                self.retry_later(room_id);
            } else {
                self.resume(&room_id).await;
            }
        }
    }

    /// Retries every parked request in `room_id`, drops any that can never be
    /// sent, and turns the room's queue back on.
    async fn resume(&self, room_id: &RoomId) {
        let Some(room) = self.client.get_room(room_id) else {
            return;
        };
        for echo in self.echoes(room_id).await {
            let Some(wedge) = wedge_of(&echo) else {
                continue;
            };
            match verdict(None, Some(wedge)) {
                Verdict::Drop => {
                    tracing::warn!(
                        room_id = %room_id,
                        transaction_id = %echo.transaction_id,
                        kind = wedge_kind(wedge),
                        "a message can never be sent; dropping it so later ones can be"
                    );
                    abort(room_id, &echo).await;
                }
                Verdict::Retry => retry(&room, &echo).await,
            }
        }
        self.enable(room_id);
    }

    /// The room's unsent requests; none when they cannot be read.
    async fn echoes(&self, room_id: &RoomId) -> Vec<LocalEcho> {
        let Some(room) = self.client.get_room(room_id) else {
            return Vec::new();
        };
        match room.send_queue().subscribe().await {
            Ok((echoes, _)) => echoes,
            Err(err) => {
                tracing::warn!(room_id = %room_id, error = %err, "could not read the room's unsent messages");
                Vec::new()
            }
        }
    }

    /// Resumes `room_id` after the backoff, unless a resume is already
    /// waiting.
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

/// The wedge on a parked request; `None` when it is not parked.
fn wedge_of(echo: &LocalEcho) -> Option<&QueueWedgeError> {
    match &echo.content {
        LocalEchoContent::Event { send_error, .. }
        | LocalEchoContent::Redaction { send_error, .. } => send_error.as_ref(),
        LocalEchoContent::React { .. } => None,
    }
}

/// Removes a parked request.
async fn abort(room_id: &RoomId, echo: &LocalEcho) {
    let result = match &echo.content {
        LocalEchoContent::Event { send_handle, .. } => send_handle.abort().await,
        LocalEchoContent::Redaction { send_handle, .. } => send_handle.abort().await,
        LocalEchoContent::React { .. } => return,
    };
    if let Err(err) = result {
        tracing::warn!(
            room_id = %room_id,
            transaction_id = %echo.transaction_id,
            error = %err,
            "could not drop a refused message"
        );
    }
}

/// Sends a parked request again, keeping its place.
///
/// A redaction's handle has no `unwedge`, so the same redaction is queued
/// again and only then is the parked copy removed: redacting twice is
/// harmless, losing the reader's delete is not.
async fn retry(room: &matrix_sdk::Room, echo: &LocalEcho) {
    let result = match &echo.content {
        LocalEchoContent::Event { send_handle, .. } => {
            send_handle.unwedge().await.map_err(|e| e.to_string())
        }
        LocalEchoContent::Redaction {
            redacts,
            reason,
            send_handle,
            ..
        } => match room
            .send_queue()
            .redact(redacts.clone(), reason.as_deref())
            .await
        {
            Ok(_) => send_handle
                .abort()
                .await
                .map(|_| ())
                .map_err(|e| e.to_string()),
            Err(e) => Err(e.to_string()),
        },
        LocalEchoContent::React { .. } => return,
    };
    match result {
        Ok(()) => tracing::info!(
            room_id = %room.room_id(),
            transaction_id = %echo.transaction_id,
            "retrying a parked message"
        ),
        Err(err) => tracing::warn!(
            room_id = %room.room_id(),
            transaction_id = %echo.transaction_id,
            error = %err,
            "could not retry a parked message"
        ),
    }
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
        matrix_sdk::Error::CrossProcessLockError(_) => "cross_process_lock".into(),
        matrix_sdk::Error::CryptoStoreError(_) => "crypto_store".into(),
        matrix_sdk::Error::StateStore(_) => "state_store".into(),
        matrix_sdk::Error::SendQueueWedgeError(wedge) => wedge_kind(wedge).into(),
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

    /// Only the homeserver saying no, or an attachment that can never be
    /// sent, drops a message. A store, crypto-store or lock error — none of
    /// them HTTP — is retried, whatever the wedge says, as is a parked
    /// request with no live error to judge (one from an earlier session).
    #[test]
    fn only_a_definite_refusal_drops_a_message() {
        let generic = QueueWedgeError::GenericApiError { msg: "x".into() };
        for error in [
            matrix_sdk::Error::BadCryptoStoreState,
            matrix_sdk::Error::NoOlmMachine,
            matrix_sdk::Error::Io(std::io::Error::other("the store is locked")),
            matrix_sdk::Error::ConcurrentRequestFailed,
        ] {
            assert_eq!(
                verdict(Some(&error), Some(&generic)),
                Verdict::Retry,
                "{error:?}"
            );
            assert_eq!(verdict(Some(&error), None), Verdict::Retry, "{error:?}");
        }
        for wedge in [
            generic.clone(),
            QueueWedgeError::CrossVerificationRequired,
            QueueWedgeError::IdentityViolations { users: vec![] },
            QueueWedgeError::InsecureDevices {
                user_device_map: Default::default(),
            },
        ] {
            assert_eq!(verdict(None, Some(&wedge)), Verdict::Retry, "{wedge:?}");
        }
        for wedge in [
            QueueWedgeError::MissingMediaContent,
            QueueWedgeError::InvalidMimeType {
                mime_type: "x/y".into(),
            },
        ] {
            assert_eq!(verdict(None, Some(&wedge)), Verdict::Drop, "{wedge:?}");
        }
        for status in [400, 403, 404, 413, 499] {
            assert!(refused_status(status), "{status}");
        }
        for status in [200, 401, 408, 429, 500, 502, 520] {
            assert!(!refused_status(status), "{status}");
        }
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
    /// Mutation seen failing (run): not aborting the echo in `on_parked`'s
    /// `Verdict::Drop` arm fails this ("never saw "two" sent") and the
    /// refused-again test below.
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
    /// in the store, so it survives a restart — is retried once at start
    /// (the server sees it twice), dropped when that retry is refused too,
    /// and the message queued behind it goes out.
    ///
    /// Fails without the watcher's first sweep: nothing new fails, so no
    /// error arrives to act on, and "two" waits behind "one" forever.
    /// Mutations seen failing (run): removing `self.sweep(false)` at the top
    /// of `run`; aborting instead of retrying in `resume` (the server sees
    /// "one" once).
    #[tokio::test]
    async fn a_request_parked_before_the_session_started_is_retried_and_dropped_if_refused_again() {
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
            .expect(2)
            .named("one is forbidden, at first and on its one retry")
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

    /// A request parked by a refusal before the session started is retried
    /// once at start rather than dropped: it may have been parked for a reason
    /// that has since gone. Here the server accepts it the second time.
    ///
    /// Mutations seen failing (run): removing `self.sweep(false)`; aborting
    /// instead of retrying in `resume`; `verdict` always dropping.
    #[tokio::test]
    async fn a_request_parked_before_the_session_started_is_retried_and_sent() {
        let server = MatrixMockServer::new().await;
        let client = server.client_builder().build().await;
        server.mock_room_state_encryption().plain().mount().await;
        let room_id = room_id!("!parked-then-sent:example.org");
        let room = server.sync_joined_room(&client, room_id).await;

        server
            .mock_room_send()
            .respond_with(ResponseTemplate::new(403).set_body_json(json!({
                "errcode": "M_FORBIDDEN",
                "error": "You are not allowed to send here",
            })))
            .mock_once()
            .named("one is forbidden the first time")
            .mount()
            .await;
        server
            .mock_room_send()
            .ok(event_id!("$one"))
            .expect(1)
            .named("one is accepted on its retry")
            .mount()
            .await;

        let queue = room.send_queue();
        let (_, mut updates) = queue.subscribe().await.unwrap();
        queue.send(text("one")).await.unwrap();
        wait_for(&mut updates, 10, "\"one\" parked", |update| match update {
            RoomSendQueueUpdate::SendError { is_recoverable, .. } => {
                assert!(!is_recoverable, "a 403 should park the request");
                Some(())
            }
            _ => None,
        })
        .await;
        assert_eq!(wedged_echoes(&room).await, 1);

        let watch = spawn_with(client.clone(), TEST_BACKOFF);
        let event_id = wait_for(&mut updates, 10, "\"one\" sent", sent).await;
        assert_eq!(event_id, event_id!("$one"));

        watch.abort();
    }

    /// The SDK parks a request on `response`, an answer that is not a refusal;
    /// the watcher must retry it, never drop it. The server then accepts it.
    async fn parked_but_not_refused_is_retried(room_id: &RoomId, response: ResponseTemplate) {
        let server = MatrixMockServer::new().await;
        let client = server.client_builder().build().await;
        server.mock_room_state_encryption().plain().mount().await;
        let room = server.sync_joined_room(&client, room_id).await;

        server
            .mock_room_send()
            .respond_with(response)
            .mock_once()
            .named("the first answer")
            .mount()
            .await;
        server
            .mock_room_send()
            .ok(event_id!("$one"))
            .expect(1)
            .named("one is accepted on its retry")
            .mount()
            .await;

        let watch = spawn_with(client.clone(), TEST_BACKOFF);
        let queue = room.send_queue();
        let (_, mut updates) = queue.subscribe().await.unwrap();
        queue.send(text("one")).await.unwrap();
        wait_for(&mut updates, 10, "\"one\" parked", |update| match update {
            RoomSendQueueUpdate::SendError { is_recoverable, .. } => {
                assert!(!is_recoverable, "this answer should park the request");
                Some(())
            }
            _ => None,
        })
        .await;
        let event_id = wait_for(&mut updates, 10, "\"one\" sent", sent).await;
        assert_eq!(event_id, event_id!("$one"));

        watch.abort();
    }

    /// A 408 (a proxy's timeout) parks the request, but the homeserver never
    /// refused it: it is retried.
    ///
    /// Mutation seen failing (run): dropping 408 from `refused_status`'s
    /// exceptions fails this and `only_a_definite_refusal_drops_a_message`.
    #[tokio::test]
    async fn a_408_is_retried_not_dropped() {
        parked_but_not_refused_is_retried(
            room_id!("!timeout:example.org"),
            ResponseTemplate::new(408).set_body_string("Request Timeout"),
        )
        .await;
    }

    /// An answer the SDK cannot read (a 200 without an event id) parks the
    /// request; it is not a refusal, so it is retried. Stands in for the other
    /// errors that are not the homeserver saying no: a store or crypto-store
    /// error, the cross-process lock.
    ///
    /// Mutations seen failing (run): `verdict` always dropping; aborting
    /// instead of retrying in `resume`.
    #[tokio::test]
    async fn an_unreadable_answer_is_retried_not_dropped() {
        parked_but_not_refused_is_retried(
            room_id!("!unreadable:example.org"),
            ResponseTemplate::new(200).set_body_json(json!({})),
        )
        .await;
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
