//! Telling the hub where to push this device's fleet Live Activity.
//!
//! The Lock Screen's fleet card is a Live Activity the AgentPod hub starts,
//! updates and ends with APNs pushes (spec 2026-09-29, Part A), so it is live
//! with the app suspended or force-quit — which a card the app updates itself
//! is not. ActivityKit hands the app two kinds of APNs token for that:
//!
//! - a **start** token (push-to-start), one per device, with which the hub
//!   starts the card when an agent becomes active and none is showing;
//! - an **update** token per running activity, with which it updates and
//!   ends that one.
//!
//! The app sends each to the hub's `POST /_supermessage/v1/live-activity/tokens`
//! on the push gateway's origin, authenticated with this session's Matrix
//! access token (the hub checks it with the homeserver's `whoami`), and
//! `DELETE`s it when the activity ends and when signing out. This module
//! holds that contract, and [`crate::session::Session`] makes the calls — so
//! the access token never leaves the core, and the host only relays what
//! ActivityKit gives it.
//!
//! **What the hub then pushes reaches Apple in plaintext:** agent names, the
//! step an agent is on, a decision's question and its options. That is the
//! operator's decision of 2026-09-29, and a deliberate exception to the
//! pusher's `event_id_only` stance (`crate::push`), which still holds for
//! every message.

use serde::{Deserialize, Serialize};

/// The endpoint's path on the push gateway's origin.
pub const TOKENS_PATH: &str = "/_supermessage/v1/live-activity/tokens";

/// Which of ActivityKit's two tokens this is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum LiveActivityTokenKind {
    /// Push-to-start: lets the hub start the card.
    Start,
    /// One running activity's: lets the hub update and end it.
    Update,
}

impl LiveActivityTokenKind {
    fn wire(self) -> &'static str {
        match self {
            Self::Start => "start",
            Self::Update => "update",
        }
    }
}

/// A token ActivityKit issued, as the host relays it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LiveActivityToken {
    pub kind: LiveActivityTokenKind,
    /// The APNs token, lowercase hex.
    pub token: String,
    /// Whether the token is for the APNs sandbox — the signing profile's
    /// `aps-environment`, as for the pusher (`PushConfiguration.isSandbox`).
    pub sandbox: bool,
    /// ActivityKit's id for the activity; required for `Update`.
    pub activity_id: Option<String>,
}

/// The token endpoint on `gateway_url`'s origin, or `None` when the gateway
/// is not an absolute `https` URL (plain `http` only to a loopback host, for
/// tests).
pub fn endpoint_for(gateway_url: &str) -> Option<String> {
    let url = url::Url::parse(gateway_url.trim()).ok()?;
    let host = url.host_str()?;
    let loopback = matches!(host, "localhost" | "127.0.0.1" | "[::1]");
    match url.scheme() {
        "https" => {}
        "http" if loopback => {}
        _ => return None,
    }
    let origin = match url.port() {
        Some(port) => format!("{}://{host}:{port}", url.scheme()),
        None => format!("{}://{host}", url.scheme()),
    };
    Some(format!("{origin}{TOKENS_PATH}"))
}

/// The `POST` body for `token` from `device_id`, or `None` for an update
/// token without its activity's id, which the hub cannot key.
pub fn register_body(token: &LiveActivityToken, device_id: &str) -> Option<serde_json::Value> {
    let mut body = serde_json::json!({
        "kind": token.kind.wire(),
        "token": token.token,
        "environment": if token.sandbox { "sandbox" } else { "production" },
        "device_id": device_id,
    });
    match (&token.kind, &token.activity_id) {
        (LiveActivityTokenKind::Update, None) => return None,
        (_, Some(id)) => {
            body["activity_id"] = serde_json::Value::String(id.clone());
        }
        (LiveActivityTokenKind::Start, None) => {}
    }
    Some(body)
}

/// The `DELETE` body for this device's token of `kind` (and, for an update
/// token, the activity's).
pub fn delete_body(
    kind: LiveActivityTokenKind,
    device_id: &str,
    activity_id: Option<&str>,
) -> serde_json::Value {
    let mut body = serde_json::json!({ "kind": kind.wire(), "device_id": device_id });
    if let Some(id) = activity_id {
        body["activity_id"] = serde_json::Value::String(id.to_string());
    }
    body
}

/// The tokens this device registered, remembered with the session so signing
/// out can delete them — even in a later process than the one that
/// registered them.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredTokens {
    pub endpoint: String,
    pub entries: Vec<StoredToken>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredToken {
    pub kind: LiveActivityTokenKind,
    pub activity_id: Option<String>,
}

impl StoredTokens {
    pub fn decode(json: Option<&str>) -> Self {
        json.and_then(|j| serde_json::from_str(j).ok())
            .unwrap_or_default()
    }

    pub fn encode(&self) -> String {
        serde_json::to_string(self).expect("plain data always serialises")
    }

    /// Remember a token registered at `endpoint`. Registered somewhere else
    /// before, the old entries are that endpoint's and are dropped.
    pub fn added(
        mut self,
        endpoint: &str,
        kind: LiveActivityTokenKind,
        activity_id: Option<&str>,
    ) -> Self {
        if self.endpoint != endpoint {
            self = Self {
                endpoint: endpoint.to_string(),
                entries: Vec::new(),
            };
        }
        let entry = StoredToken {
            kind,
            activity_id: activity_id.map(str::to_string),
        };
        if !self.entries.contains(&entry) {
            self.entries.push(entry);
        }
        self
    }

    pub fn removed(mut self, kind: LiveActivityTokenKind, activity_id: Option<&str>) -> Self {
        self.entries
            .retain(|e| !(e.kind == kind && e.activity_id.as_deref() == activity_id));
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn token(kind: LiveActivityTokenKind, activity_id: Option<&str>) -> LiveActivityToken {
        LiveActivityToken {
            kind,
            token: "ab12".into(),
            sandbox: false,
            activity_id: activity_id.map(str::to_string),
        }
    }

    #[test]
    fn the_endpoint_is_on_the_gateways_origin() {
        assert_eq!(
            endpoint_for("https://hub.agentpod.dev/_matrix/push/v1/notify").as_deref(),
            Some("https://hub.agentpod.dev/_supermessage/v1/live-activity/tokens")
        );
        assert_eq!(
            endpoint_for("https://hub.example:8443/x?y=z").as_deref(),
            Some("https://hub.example:8443/_supermessage/v1/live-activity/tokens")
        );
    }

    #[test]
    fn only_https_carries_the_access_token() {
        assert_eq!(
            endpoint_for("http://hub.agentpod.dev/_matrix/push/v1/notify"),
            None
        );
        assert_eq!(endpoint_for("$(SM_PUSH_GATEWAY_URL)"), None);
        assert_eq!(endpoint_for(""), None);
        // A test's mock server.
        assert!(endpoint_for("http://127.0.0.1:4000/n").is_some());
    }

    #[test]
    fn a_start_token_needs_no_activity_and_an_update_token_does() {
        let start = register_body(&token(LiveActivityTokenKind::Start, None), "DEV").unwrap();
        assert_eq!(
            start,
            serde_json::json!({
                "kind": "start", "token": "ab12", "environment": "production",
                "device_id": "DEV",
            })
        );
        assert_eq!(
            register_body(&token(LiveActivityTokenKind::Update, None), "DEV"),
            None
        );
        let update = register_body(
            &LiveActivityToken {
                sandbox: true,
                ..token(LiveActivityTokenKind::Update, Some("act-1"))
            },
            "DEV",
        )
        .unwrap();
        assert_eq!(update["kind"], "update");
        assert_eq!(update["environment"], "sandbox");
        assert_eq!(update["activity_id"], "act-1");
    }

    #[test]
    fn a_deletion_names_the_device_and_the_activity() {
        assert_eq!(
            delete_body(LiveActivityTokenKind::Update, "DEV", Some("act-1")),
            serde_json::json!({"kind": "update", "device_id": "DEV", "activity_id": "act-1"})
        );
        assert_eq!(
            delete_body(LiveActivityTokenKind::Start, "DEV", None),
            serde_json::json!({"kind": "start", "device_id": "DEV"})
        );
    }

    #[test]
    fn the_stored_tokens_follow_the_endpoint() {
        let a = "https://a/_supermessage/v1/live-activity/tokens";
        let b = "https://b/_supermessage/v1/live-activity/tokens";
        let stored = StoredTokens::default()
            .added(a, LiveActivityTokenKind::Start, None)
            .added(a, LiveActivityTokenKind::Start, None)
            .added(a, LiveActivityTokenKind::Update, Some("x"));
        assert_eq!(stored.entries.len(), 2);
        let round = StoredTokens::decode(Some(&stored.encode()));
        assert_eq!(round, stored);
        let moved = stored.clone().added(b, LiveActivityTokenKind::Start, None);
        assert_eq!(moved.endpoint, b);
        assert_eq!(moved.entries.len(), 1);
        let less = stored.removed(LiveActivityTokenKind::Update, Some("x"));
        assert_eq!(less.entries.len(), 1);
        assert_eq!(
            StoredTokens::decode(Some("not json")),
            StoredTokens::default()
        );
    }
}
