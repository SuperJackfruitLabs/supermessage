//! What a host shows about a call, and the rules for reading it. Pure.

use serde::Serialize;

/// What the person sees when the token service refuses the call (master plan, Global
/// Constraints). Exact.
pub const REFUSED_COPY: &str = "Calls are not available for this agent yet";

/// The LiveKit participant attribute the agent publishes its state in (C6).
pub const STATE_ATTRIBUTE: &str = "superdial.state";

/// The agent's side of the call, as the call bar names it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum AgentCallState {
    Connecting,
    Listening,
    Thinking,
    Speaking,
}

impl AgentCallState {
    /// Reads a `superdial.state` value; anything else is not a state.
    pub fn from_attribute(value: &str) -> Option<Self> {
        match value {
            "connecting" => Some(Self::Connecting),
            "listening" => Some(Self::Listening),
            "thinking" => Some(Self::Thinking),
            "speaking" => Some(Self::Speaking),
            _ => None,
        }
    }
}

/// Whether a LiveKit identity is one of the agent's devices: `{agent_user_id}:{device_id}`,
/// with a device part that is non-empty and holds no `:`.
///
/// Any participant can set its own `superdial.state` (the token grants
/// `canUpdateOwnMetadata`), so the call bar reads it from the agent's identity only. A plain
/// prefix test is not enough: a user ID may carry a port (`@agent_x:s:8448`), and that user's
/// identity `@agent_x:s:8448:DEV` starts with `@agent_x:s:`.
pub fn is_agent_identity(identity: &str, agent_user_id: &str) -> bool {
    identity
        .strip_prefix(agent_user_id)
        .and_then(|rest| rest.strip_prefix(':'))
        .is_some_and(|device| !device.is_empty() && !device.contains(':'))
}

/// Where our side of the call is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum CallPhase {
    /// Pressed Call; asking for a token, publishing membership, connecting media.
    Connecting,
    /// In the call, with the microphone and speaker open.
    Live,
}

/// The call that is running, for the call bar.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CallView {
    pub room_id: String,
    pub agent_user_id: String,
    /// What the room header calls the agent.
    pub agent_name: String,
    pub phase: CallPhase,
    /// The agent's `superdial.state`; `None` until its participant says.
    pub agent_state: Option<AgentCallState>,
    /// When we joined (Unix ms); the bar's clock counts from here.
    pub started_at_ms: Option<u64>,
    pub muted: bool,
}

/// Something to tell the person in the room a call was for: a refusal, a failure, an ending.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CallNotice {
    pub room_id: String,
    pub text: String,
}

/// Everything a host shows about calls; sent whole on every change.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CallSnapshot {
    /// The desktop setting. Off: no button, no bar, and nothing starts.
    pub enabled: bool,
    pub call: Option<CallView>,
    pub notice: Option<CallNotice>,
    /// A call that was hung up has not finished leaving. No call starts until it has (the
    /// Call button stays hidden, and a start waits), because its leave would clear the
    /// membership a new call publishes under the same key.
    pub leaving: bool,
    /// Rises by one with every change. A command's answer and an `sm://call` event may arrive
    /// in either order, so a host keeps the snapshot with the highest `seq` and drops an older
    /// one that arrives after it.
    pub seq: u64,
}

/// Why a call did not start.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JoinFailure {
    /// The token service refused (C5 403): the room is not a bridge agent's, or calls are off.
    Refused,
    /// The homeserver offers no LiveKit transport.
    NoTransport,
    Other(String),
}

impl JoinFailure {
    /// What the person is told.
    pub fn copy(&self) -> &'static str {
        match self {
            JoinFailure::Refused => REFUSED_COPY,
            JoinFailure::NoTransport => "This homeserver doesn't offer calls yet",
            JoinFailure::Other(_) => "The call couldn't start",
        }
    }
}

/// Reads a superdial join error.
pub fn join_failure(e: &superdial::Error) -> JoinFailure {
    match e {
        superdial::Error::Issuer(superdial::IssuerError::Refused(_)) => JoinFailure::Refused,
        superdial::Error::NoLiveKitTransport => JoinFailure::NoTransport,
        other => JoinFailure::Other(other.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use superdial::{Error, IssuerError, RefusalReason};

    #[test]
    fn state_attribute_values_parse() {
        assert_eq!(
            AgentCallState::from_attribute("connecting"),
            Some(AgentCallState::Connecting)
        );
        assert_eq!(
            AgentCallState::from_attribute("listening"),
            Some(AgentCallState::Listening)
        );
        assert_eq!(
            AgentCallState::from_attribute("thinking"),
            Some(AgentCallState::Thinking)
        );
        assert_eq!(
            AgentCallState::from_attribute("speaking"),
            Some(AgentCallState::Speaking)
        );
        assert_eq!(AgentCallState::from_attribute("Speaking"), None);
        assert_eq!(AgentCallState::from_attribute(""), None);
        assert_eq!(AgentCallState::from_attribute("asleep"), None);
    }

    #[test]
    fn agent_identity_is_user_then_one_device() {
        assert!(is_agent_identity("@agent_x:s:SUPERDIAL", "@agent_x:s"));
        assert!(is_agent_identity("@agent_x:s:DEV", "@agent_x:s"));
        assert!(!is_agent_identity("@agent_x:s", "@agent_x:s"));
        assert!(!is_agent_identity("@agent_x:s:", "@agent_x:s"));
    }

    #[test]
    fn state_from_a_non_agent_identity_is_ignored() {
        // The caller itself, another person, and two look-alikes of the agent.
        assert!(!is_agent_identity("@u:s:DEV", "@agent_x:s"));
        assert!(!is_agent_identity("@agent_x:s:8448:DEV", "@agent_x:s"));
        assert!(!is_agent_identity("@agent_x:s.evil:DEV", "@agent_x:s"));
        assert!(!is_agent_identity("@agent_xy:s:DEV", "@agent_x:s"));
        // The port case read the other way round is the agent itself.
        assert!(is_agent_identity("@agent_x:s:8448:DEV", "@agent_x:s:8448"));
    }

    #[test]
    fn refusal_maps_to_the_contract_copy() {
        for reason in [
            RefusalReason::HarnessMode,
            RefusalReason::NotAgentRoom,
            RefusalReason::NotMember,
            RefusalReason::CallsDisabled,
            RefusalReason::Other("new".into()),
        ] {
            let failure = join_failure(&Error::Issuer(IssuerError::Refused(reason)));
            assert_eq!(failure, JoinFailure::Refused);
            assert_eq!(failure.copy(), "Calls are not available for this agent yet");
        }
    }

    #[test]
    fn no_transport_and_other_errors_map() {
        assert_eq!(
            join_failure(&Error::NoLiveKitTransport),
            JoinFailure::NoTransport
        );
        assert_eq!(
            JoinFailure::NoTransport.copy(),
            "This homeserver doesn't offer calls yet"
        );
        let other = join_failure(&Error::Issuer(IssuerError::Http {
            status: 502,
            body: "bad gateway".into(),
        }));
        assert!(matches!(other, JoinFailure::Other(_)));
        assert_eq!(other.copy(), "The call couldn't start");
        assert!(matches!(
            join_failure(&Error::Transport("down".into())),
            JoinFailure::Other(_)
        ));
    }

    #[test]
    fn view_serializes_camel_case() {
        let snapshot = CallSnapshot {
            enabled: true,
            call: Some(CallView {
                room_id: "!r:s".into(),
                agent_user_id: "@agent_x:s".into(),
                agent_name: "Ada".into(),
                phase: CallPhase::Live,
                agent_state: Some(AgentCallState::Thinking),
                started_at_ms: Some(1_764_200_000_000),
                muted: false,
            }),
            notice: None,
            leaving: false,
            seq: 7,
        };
        let v = serde_json::to_value(&snapshot).unwrap();
        assert_eq!(v["call"]["agentState"], "thinking");
        assert_eq!(v["call"]["phase"], "live");
        assert_eq!(v["call"]["startedAtMs"], 1_764_200_000_000u64);
        assert_eq!(v["call"]["agentUserId"], "@agent_x:s");
        assert_eq!(v["notice"], serde_json::Value::Null);
        assert_eq!(v["leaving"], false);
        assert_eq!(v["seq"], 7);
    }
}
