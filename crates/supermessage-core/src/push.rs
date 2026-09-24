//! Registering this device for push, the client half of notifications.
//!
//! A Matrix client does not talk to APNs or FCM. It hands the homeserver a
//! *pusher* — "send notifications for this account to that gateway, addressed
//! to this device token" — and the homeserver POSTs to the gateway, which
//! forwards to Apple or Google. The gateway is infrastructure outside this
//! repository (see AGENTS.md on Sygnal); this module only describes the device
//! to the homeserver.
//!
//! `event_id_only`, always: the push carries a room and an event id and no
//! message content, so nothing a person wrote passes through the gateway or
//! Apple. The app fetches and decrypts the event itself.

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
    fn the_pusher_is_addressed_to_this_device_and_app() {
        let pusher = pusher_for(&registration());
        assert_eq!(pusher.ids.pushkey, "abcd");
        assert_eq!(pusher.ids.app_id, "dev.supermessage.ios");
    }
}
