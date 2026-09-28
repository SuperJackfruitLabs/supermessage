//! Registering this device for push, the client half of notifications.
//!
//! A Matrix client does not talk to APNs or FCM. It hands the homeserver a
//! *pusher* — "send notifications for this account to that gateway, addressed
//! to this device token" — and the homeserver POSTs to the gateway, which
//! forwards to Apple or Google. The gateway is the AgentPod hub's own
//! `/_matrix/push/v1/notify` (operator decision of 2026-09-28), outside this
//! repository; this module only describes the device to the homeserver.
//!
//! `event_id_only`, always: the push carries a room and an event id and no
//! message content, so nothing a person wrote passes through the gateway or
//! Apple. The app fetches and decrypts the event itself — on iOS in its
//! Notification Service Extension, through `Session::notification_for`.
//!
//! No `default_payload`: the hub's gateway builds the whole APNs payload
//! itself — a generic `aps.alert`, `mutable-content` so the extension runs,
//! `thread-id` and a collapse id from the room and event, and the category
//! for a decision — so there is nothing for a pusher to add.

use matrix_sdk::ruma::api::client::push::{Pusher, PusherIds, PusherInit, PusherKind};
use matrix_sdk::ruma::push::{HttpPusherData, PushFormat};

/// What a host knows about its own push channel.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PushRegistration {
    /// The device token, as the platform issued it (APNs: hex).
    pub pushkey: String,
    /// Which app and environment the gateway should route to —
    /// `dev.supermessage.ios` for production APNs, a `.dev` suffix for the
    /// sandbox. The gateway's configuration names these.
    pub app_id: String,
    pub app_display_name: String,
    pub device_display_name: String,
    /// BCP 47, for the gateway's own wording where it has any.
    pub lang: String,
    /// The gateway's `/_matrix/push/v1/notify` URL.
    pub gateway_url: String,
}

/// The pusher the homeserver is asked to store for `registration`.
///
/// Pure, so the one decision that matters — `event_id_only` — is testable
/// without a homeserver.
pub fn pusher_for(registration: &PushRegistration) -> Pusher {
    let mut data = HttpPusherData::new(registration.gateway_url.clone());
    data.format = Some(PushFormat::EventIdOnly);
    PusherInit {
        ids: PusherIds::new(registration.pushkey.clone(), registration.app_id.clone()),
        kind: PusherKind::Http(data),
        app_display_name: registration.app_display_name.clone(),
        device_display_name: registration.device_display_name.clone(),
        profile_tag: None,
        lang: registration.lang.clone(),
    }
    .into()
}

/// The two ids that name a pusher, as this device remembers them — enough to
/// remove it later, from a later process.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct StoredPusher {
    pub pushkey: String,
    pub app_id: String,
}

impl StoredPusher {
    pub fn into_ids(self) -> PusherIds {
        PusherIds::new(self.pushkey, self.app_id)
    }
}

/// The ids of the pusher `registration` asks for.
pub fn pusher_ids(registration: &PushRegistration) -> StoredPusher {
    StoredPusher {
        pushkey: registration.pushkey.clone(),
        app_id: registration.app_id.clone(),
    }
}

pub fn encode_ids(ids: &StoredPusher) -> String {
    serde_json::to_string(ids).expect("two strings always serialise")
}

/// `None` for anything that is not what [`encode_ids`] wrote — a value that
/// cannot name a pusher is one there is nothing to remove for.
pub fn decode_ids(json: &str) -> Option<StoredPusher> {
    serde_json::from_str(json).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn registration() -> PushRegistration {
        PushRegistration {
            pushkey: "abcd".into(),
            app_id: "dev.supermessage.ios".into(),
            app_display_name: "supermessage".into(),
            device_display_name: "iPhone".into(),
            lang: "en".into(),
            gateway_url: "https://push.example.org/_matrix/push/v1/notify".into(),
        }
    }

    #[test]
    fn a_push_never_carries_message_content() {
        let pusher = pusher_for(&registration());
        match pusher.kind {
            PusherKind::Http(data) => {
                assert_eq!(data.format, Some(PushFormat::EventIdOnly));
                assert_eq!(data.url, "https://push.example.org/_matrix/push/v1/notify");
            }
            other => panic!("expected an http pusher, got {other:?}"),
        }
    }

    #[test]
    fn stored_ids_round_trip_and_garbage_names_nothing() {
        let ids = pusher_ids(&registration());
        assert_eq!(decode_ids(&encode_ids(&ids)), Some(ids.clone()));
        assert_eq!(ids.clone().into_ids().pushkey, "abcd");
        assert_eq!(decode_ids("not json"), None);
    }

    #[test]
    fn the_pusher_is_addressed_to_this_device_and_app() {
        let pusher = pusher_for(&registration());
        assert_eq!(pusher.ids.pushkey, "abcd");
        assert_eq!(pusher.ids.app_id, "dev.supermessage.ios");
    }
}
