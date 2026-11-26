//! The real call: `superdial::Call` over the session's own `matrix_sdk::Client`.
//!
//! superdial's API is two-step: [`superdial::Call::new`], then route the call's to-device
//! events, then [`superdial::Call::join`]. A peer's key can arrive as soon as our membership is
//! published, which is inside `join`, so the to-device handler is registered on the client
//! before `join` is called. The core's `SyncService` runs the client's event handlers for
//! sliding sync too, and the room list asks for `org.matrix.msc3401.call.member` state
//! (matrix-sdk-ui's `DEFAULT_REQUIRED_STATE`), so `refresh_members` sees memberships from the
//! store; it runs on a one-second tick while the call exists.

use std::{sync::Arc, time::Duration};

use async_trait::async_trait;
use matrix_sdk::{
    deserialized_responses::{EncryptionInfo, ProcessedToDeviceEvent},
    event_handler::EventHandlerHandle,
    ruma::{events::AnyToDeviceEvent, serde::Raw, OwnedRoomId, OwnedUserId},
    Client,
};
use superdial::{Call, CallEvent, CallOptions, MediaEvent, TokenIssuer, TranscriptContent};
use tokio::{sync::mpsc, task::JoinHandle};

use super::controller::{CallLink, CallLinkFactory, LinkEvent};
use super::model::{join_failure, JoinFailure};

#[cfg(all(feature = "calls-dev-issuer", not(debug_assertions)))]
compile_error!(
    "`calls-dev-issuer` (the rtc-spike's lk-jwt-service as token issuer) is for development \
     builds only and must never be in a release"
);

/// How often call memberships are re-read from the store while a call exists.
const REFRESH_EVERY: Duration = Duration::from_secs(1);

/// The environment variable that picks the development token issuer, when the build has it:
/// `discover` (the homeserver's `livekit_service_url`) or a base URL.
pub const DEV_ISSUER_ENV: &str = "SUPERMESSAGE_CALLS_DEV_ISSUER";

/// Who issues LiveKit tokens.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IssuerChoice {
    /// SuperExchange's token service (C5) at the discovered base: the only one in a release.
    SuperExchange,
    /// The rtc-spike's lk-jwt-service; `None` means at the discovered base.
    #[cfg(feature = "calls-dev-issuer")]
    LkJwtDev(Option<String>),
}

/// Reads [`DEV_ISSUER_ENV`]'s value. Without `calls-dev-issuer` it is never consulted.
pub fn issuer_choice(dev_issuer: Option<&str>) -> IssuerChoice {
    #[cfg(feature = "calls-dev-issuer")]
    match dev_issuer.map(str::trim) {
        Some("") | None => {}
        Some("discover") => return IssuerChoice::LkJwtDev(None),
        Some(url) => return IssuerChoice::LkJwtDev(Some(url.to_owned())),
    }
    let _ = dev_issuer;
    IssuerChoice::SuperExchange
}

/// Where the link gets the logged-in client.
#[async_trait]
pub trait ClientSource: Send + Sync {
    async fn client(&self) -> Option<Client>;
}

/// Makes [`SuperdialLink`]s.
pub struct SuperdialLinkFactory {
    source: Arc<dyn ClientSource>,
    issuer: IssuerChoice,
}

impl SuperdialLinkFactory {
    pub fn new(source: Arc<dyn ClientSource>, issuer: IssuerChoice) -> Self {
        Self { source, issuer }
    }
}

#[async_trait]
impl CallLinkFactory for SuperdialLinkFactory {
    async fn create(
        &self,
        room_id: &str,
        agent_user_id: &str,
    ) -> Result<(Arc<dyn CallLink>, mpsc::UnboundedReceiver<LinkEvent>), JoinFailure> {
        let client = self
            .source
            .client()
            .await
            .ok_or_else(|| JoinFailure::Other("not signed in".into()))?;
        let link = SuperdialLink::create(client, room_id, agent_user_id, self.issuer.clone())?;
        Ok((Arc::new(link.0), link.1))
    }
}

/// One call. Dropping it does not leave; [`CallLink::leave`] does.
pub struct SuperdialLink {
    client: Client,
    call: Call,
    room_id: OwnedRoomId,
    agent: OwnedUserId,
    issuer: IssuerChoice,
    to_device: std::sync::Mutex<Option<EventHandlerHandle>>,
    tasks: std::sync::Mutex<Vec<JoinHandle<()>>>,
}

impl SuperdialLink {
    /// Creates the call and starts routing its to-device events. No network.
    pub fn create(
        client: Client,
        room_id: &str,
        agent_user_id: &str,
        issuer: IssuerChoice,
    ) -> Result<(SuperdialLink, mpsc::UnboundedReceiver<LinkEvent>), JoinFailure> {
        let room_id = OwnedRoomId::try_from(room_id)
            .map_err(|e| JoinFailure::Other(format!("room id: {e}")))?;
        let agent = OwnedUserId::try_from(agent_user_id)
            .map_err(|e| JoinFailure::Other(format!("agent user id: {e}")))?;
        let options = CallOptions {
            transcripts_from: Some(agent.clone()),
            ..CallOptions::default()
        };
        let (call, events) = Call::new(&client, &room_id, options).map_err(|e| join_failure(&e))?;

        let routed = call.clone();
        let handle = client.add_event_handler(
            move |raw: Raw<AnyToDeviceEvent>, info: Option<EncryptionInfo>| {
                let call = routed.clone();
                async move {
                    let event = match info {
                        Some(encryption_info) => ProcessedToDeviceEvent::Decrypted {
                            raw,
                            encryption_info,
                        },
                        None => ProcessedToDeviceEvent::PlainText(raw),
                    };
                    call.handle_to_device(&event).await;
                }
            },
        );

        let (tx, rx) = mpsc::unbounded_channel();
        let pump = tokio::spawn(pump(events, tx));
        let refreshed = call.clone();
        let refresh = tokio::spawn(async move {
            let mut tick = tokio::time::interval(REFRESH_EVERY);
            loop {
                tick.tick().await;
                if let Err(e) = refreshed.refresh_members().await {
                    tracing::debug!(error = %e, "call: refreshing members");
                }
            }
        });
        Ok((
            SuperdialLink {
                client,
                call,
                room_id,
                agent,
                issuer,
                to_device: std::sync::Mutex::new(Some(handle)),
                tasks: std::sync::Mutex::new(vec![pump, refresh]),
            },
            rx,
        ))
    }

    async fn issuer(&self) -> Result<Option<Arc<dyn TokenIssuer>>, JoinFailure> {
        match &self.issuer {
            IssuerChoice::SuperExchange => Ok(None),
            #[cfg(feature = "calls-dev-issuer")]
            IssuerChoice::LkJwtDev(base) => {
                let base = match base {
                    Some(b) => b.clone(),
                    None => superdial::Homeserver::from_client(&self.client)
                        .map_err(|e| join_failure(&e))?
                        .livekit_service_url()
                        .await
                        .map_err(|e| join_failure(&e))?,
                };
                tracing::warn!(%base, "call: using the development token issuer (lk-jwt-service)");
                Ok(Some(Arc::new(superdial::LkJwtDevIssuer::new(&base))))
            }
        }
    }
}

/// Narrows the call's events to what the controller uses, and logs the rest.
async fn pump(
    mut events: mpsc::UnboundedReceiver<CallEvent>,
    tx: mpsc::UnboundedSender<LinkEvent>,
) {
    while let Some(ev) = events.recv().await {
        let out = match ev {
            CallEvent::Media(MediaEvent::AttributesChanged {
                identity,
                attributes,
            }) => LinkEvent::Attributes {
                identity,
                attributes,
            },
            CallEvent::Media(MediaEvent::Audio { identity, samples }) => {
                LinkEvent::Audio { identity, samples }
            }
            CallEvent::Media(MediaEvent::E2ee { identity, state }) => {
                tracing::info!(%identity, state = state.as_str(), "call: e2ee");
                LinkEvent::E2ee {
                    identity,
                    state: state.as_str().to_owned(),
                }
            }
            CallEvent::Media(MediaEvent::Disconnected { reason }) => {
                tracing::info!(%reason, "call: media disconnected");
                LinkEvent::Disconnected { reason }
            }
            CallEvent::Transcript { from, content } => {
                tracing::info!(from = %from.identity(), turn_id = %content.turn_id, "call: transcript");
                LinkEvent::Transcript(content)
            }
            other => {
                tracing::info!(event = ?other, "call");
                continue;
            }
        };
        if tx.send(out).is_err() {
            break;
        }
    }
}

#[async_trait]
impl CallLink for SuperdialLink {
    async fn join(&self) -> Result<(), JoinFailure> {
        let issuer = self.issuer().await?;
        self.call.join(issuer).await.map_err(|e| {
            tracing::warn!(error = %e, "call: join failed");
            join_failure(&e)
        })?;
        tracing::info!(
            identity = self
                .call
                .grant()
                .map(|g| g.identity.as_str())
                .unwrap_or_default(),
            "call: joined"
        );
        Ok(())
    }

    async fn capture(&self, frame: &[i16]) -> Result<(), String> {
        self.call.capture(frame).await.map_err(|e| e.to_string())
    }

    async fn post_spoken_turn(&self, transcript: &TranscriptContent) -> Result<(), String> {
        let room = self
            .client
            .get_room(&self.room_id)
            .ok_or_else(|| format!("room {} is not known", self.room_id))?;
        superdial::post_spoken_turn(&room, transcript, &self.agent)
            .await
            .map(|id| tracing::info!(event_id = %id, "call: posted a spoken turn"))
            .map_err(|e| e.to_string())
    }

    async fn leave(&self) {
        let tasks: Vec<_> = self.tasks.lock().expect("tasks lock").drain(..).collect();
        for t in tasks {
            t.abort();
        }
        if let Some(handle) = self.to_device.lock().expect("handler lock").take() {
            self.client.remove_event_handler(handle);
        }
        if let Err(e) = self.call.leave().await {
            tracing::warn!(error = %e, "call: leaving");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use matrix_sdk::test_utils::mocks::MatrixMockServer;
    use serde_json::json;
    use wiremock::{
        matchers::{method, path, path_regex},
        Mock, ResponseTemplate,
    };

    const ROOM: &str = "!call:example.org";

    /// A logged-in client that knows the room, on a homeserver offering a LiveKit transport at
    /// its own address, whose token service refuses (or not) like SuperExchange's C5.
    async fn homeserver(transport: bool) -> (MatrixMockServer, Client) {
        let server = MatrixMockServer::new().await;
        let client = server.client_builder().build().await;
        let room_id = OwnedRoomId::try_from(ROOM).unwrap();
        server.sync_joined_room(&client, &room_id).await;
        let transports = if transport {
            json!({"rtc_transports": [{"type": "livekit", "livekit_service_url": server.uri()}]})
        } else {
            json!({"rtc_transports": []})
        };
        Mock::given(method("GET"))
            .and(path(
                "/_matrix/client/unstable/org.matrix.msc4143/rtc/transports",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(transports))
            .mount(server.server())
            .await;
        Mock::given(method("POST"))
            .and(path_regex(
                r"^/_matrix/client/v3/user/.*/openid/request_token$",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "access_token": "o", "token_type": "Bearer",
                "matrix_server_name": "example.org", "expires_in": 3600
            })))
            .mount(server.server())
            .await;
        (server, client)
    }

    #[tokio::test]
    async fn refusing_issuer_publishes_no_membership() {
        let (server, client) = homeserver(true).await;
        Mock::given(method("POST"))
            .and(path("/v1/token"))
            .respond_with(ResponseTemplate::new(403).set_body_json(json!({
                "errcode": "M_FORBIDDEN",
                "error": "Calls are not available for this agent yet",
                "reason": "harness_mode"
            })))
            .expect(1)
            .mount(server.server())
            .await;
        Mock::given(method("PUT"))
            .and(path_regex(r"/state/org\.matrix\.msc3401\.call\.member/"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"delay_id": "d"})))
            .expect(0)
            .mount(server.server())
            .await;
        let (link, _events) = SuperdialLink::create(
            client,
            ROOM,
            "@agent_x:example.org",
            IssuerChoice::SuperExchange,
        )
        .unwrap();
        assert_eq!(link.join().await, Err(JoinFailure::Refused));
        link.leave().await;
        // `expect(0)` on the membership PUT is checked when the server drops.
    }

    #[tokio::test]
    async fn missing_transport_is_no_transport() {
        let (_server, client) = homeserver(false).await;
        let (link, _events) = SuperdialLink::create(
            client,
            ROOM,
            "@agent_x:example.org",
            IssuerChoice::SuperExchange,
        )
        .unwrap();
        assert_eq!(link.join().await, Err(JoinFailure::NoTransport));
        link.leave().await;
    }

    #[tokio::test]
    async fn leave_stops_routing_and_is_idempotent() {
        let (_server, client) = homeserver(false).await;
        let (link, _events) = SuperdialLink::create(
            client,
            ROOM,
            "@agent_x:example.org",
            IssuerChoice::SuperExchange,
        )
        .unwrap();
        link.leave().await;
        assert!(link.to_device.lock().unwrap().is_none(), "handler removed");
        assert!(link.tasks.lock().unwrap().is_empty());
        link.leave().await;
    }

    #[test]
    fn issuer_defaults_to_superexchange() {
        assert_eq!(issuer_choice(None), IssuerChoice::SuperExchange);
        #[cfg(not(feature = "calls-dev-issuer"))]
        assert_eq!(
            issuer_choice(Some("discover")),
            IssuerChoice::SuperExchange,
            "a release ignores the variable"
        );
    }

    #[cfg(feature = "calls-dev-issuer")]
    #[test]
    fn dev_issuer_is_chosen_only_by_the_variable() {
        assert_eq!(issuer_choice(Some("")), IssuerChoice::SuperExchange);
        assert_eq!(
            issuer_choice(Some("discover")),
            IssuerChoice::LkJwtDev(None)
        );
        assert_eq!(
            issuer_choice(Some("https://jwt.example")),
            IssuerChoice::LkJwtDev(Some("https://jwt.example".into()))
        );
    }
}
