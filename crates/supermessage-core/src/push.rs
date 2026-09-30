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
//! Apple on its way to a notification. (The hub's fleet Live Activity pushes
//! are the one exception, by operator decision of 2026-09-29: agent names,
//! steps and a pending decision's question and options reach Apple in
//! plaintext there. See `crate::live_activity`.) The app fetches and decrypts the event itself — on iOS in its
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

// ---------------------------------------------------------------------------
// Quiet events: account push rules so the homeserver never pushes them.
// ---------------------------------------------------------------------------

use matrix_sdk::ruma::push::{
    ConditionalPushRule, EventMatchConditionData, EventPropertyIsConditionData,
    NewConditionalPushRule, PushCondition, Ruleset,
};

/// Every rule this client installs is named under this prefix, so it never
/// touches a rule somebody else set — Element's, the user's, the server's
/// (whose ids begin with a `.`).
pub const QUIET_RULE_PREFIX: &str = "dev.supermessage.quiet.";

/// The event types that must never notify, by the rule id that silences each.
///
/// A reaction is not a message (the spec's own `.m.rule.reaction` says so, but
/// an account's stored ruleset can predate it). The AgentPod turn card is
/// context drawn beside the answer, never news. The separate permission and
/// gate events are the legacy *companions* of a prose message that now carries
/// the same decision under an embedded key (`core::embedded`) — the prose
/// message is what notifies, with the answers, so the companion would only be
/// a second push for one question.
pub const QUIET_EVENT_TYPES: [(&str, &str); 4] = [
    ("reaction", "m.reaction"),
    (
        "agentpod_turn",
        crate::custom_events::TURN_ACTIVITY_EVENT_TYPE,
    ),
    (
        "agentpod_permission",
        crate::custom_events::PERMISSION_REQUEST_EVENT_TYPE,
    ),
    ("superpipeline_gate", crate::custom_events::GATE_EVENT_TYPE),
];

/// The key an edit's `rel_type` is at, escaped as the spec requires for a
/// dot inside a key (v1.7, "Conditions"): `m.relates_to` is one key.
pub const EDIT_REL_TYPE_KEY: &str = r"content.m\.relates_to.rel_type";

/// The key an AgentPod voice reply's version is at, escaped the same way:
/// `dev.agentpod.voice_reply` is one key (`crate::voice_reply`).
pub const VOICE_REPLY_VERSION_KEY: &str = r"content.dev\.agentpod\.voice_reply.schema_version";

/// The override rules that keep quiet events from being pushed.
///
/// Each is **don't-notify**: an empty `actions` list, which is how the spec
/// has written `dont_notify` since v1.7 (the string is now ignored). Override,
/// so they are evaluated before any rule that would notify for a message.
///
/// **Only an unencrypted event is matched.** The homeserver evaluates these
/// against what it can read: in an encrypted room every event's type is
/// `m.room.encrypted` and its content is ciphertext, so an encrypted reaction
/// or turn card is still pushed, and the Notification Service Extension's
/// quiet line (`NotificationDto::fallback_body`) is what the reader sees.
/// Org rooms are unencrypted by product decision, so most are caught here.
pub fn quiet_push_rules() -> Vec<NewConditionalPushRule> {
    let mut rules: Vec<NewConditionalPushRule> = QUIET_EVENT_TYPES
        .iter()
        .map(|(name, event_type)| {
            NewConditionalPushRule::new(
                format!("{QUIET_RULE_PREFIX}{name}"),
                vec![PushCondition::EventMatch(EventMatchConditionData::new(
                    "type".into(),
                    (*event_type).into(),
                ))],
                Vec::new(),
            )
        })
        .collect();
    // An agent's answer, spoken: the text it speaks was posted first and
    // notified. Keyed on the version so a voice note that merely mentions the
    // key in its body does not match, and a later schema is a later decision.
    rules.push(NewConditionalPushRule::new(
        format!("{QUIET_RULE_PREFIX}agentpod_voice_reply"),
        vec![PushCondition::EventPropertyIs(
            EventPropertyIsConditionData::new(
                VOICE_REPLY_VERSION_KEY.into(),
                matrix_sdk::ruma::int!(1).into(),
            ),
        )],
        Vec::new(),
    ));
    // An edit: the original already notified. The same condition as the
    // spec's `.m.rule.suppress_edits`, which an older stored ruleset lacks.
    rules.push(NewConditionalPushRule::new(
        format!("{QUIET_RULE_PREFIX}edit"),
        vec![PushCondition::EventPropertyIs(
            EventPropertyIsConditionData::new(EDIT_REL_TYPE_KEY.into(), "m.replace".into()),
        )],
        Vec::new(),
    ));
    rules
}

/// Which of [`quiet_push_rules`] the account's `current` ruleset still
/// needs, so installing them is idempotent: a second launch sends nothing.
///
/// A rule of ours that is present but **disabled** is left alone — somebody
/// turned it off on purpose, from another client, and turning it back on
/// every launch would be overriding them. One that is present with other
/// conditions (an older build's) is replaced.
pub fn quiet_rules_to_install(current: &Ruleset) -> Vec<NewConditionalPushRule> {
    quiet_push_rules()
        .into_iter()
        .filter(|wanted| {
            let existing: Option<&ConditionalPushRule> = current
                .override_
                .iter()
                .find(|rule| rule.rule_id == wanted.rule_id);
            match existing {
                None => true,
                Some(rule) if !rule.enabled => false,
                Some(rule) => {
                    let same_conditions = serde_json::to_value(&rule.conditions).ok()
                        == serde_json::to_value(&wanted.conditions).ok();
                    let quiet = !rule.actions.iter().any(|a| a.should_notify());
                    !(same_conditions && quiet)
                }
            }
        })
        .collect()
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

    // --- quiet rules

    fn ruleset(overrides: serde_json::Value) -> Ruleset {
        serde_json::from_value(serde_json::json!({ "override": overrides })).unwrap()
    }

    /// A rule as the homeserver lists it back: what we sent, plus
    /// `default` and `enabled`.
    fn listed(rule: &NewConditionalPushRule, enabled: bool) -> serde_json::Value {
        let mut value = serde_json::to_value(rule).unwrap();
        value["default"] = false.into();
        value["enabled"] = enabled.into();
        value
    }

    #[test]
    fn the_quiet_rules_are_namespaced_dont_notify_overrides() {
        let bodies: Vec<serde_json::Value> = quiet_push_rules()
            .iter()
            .map(|r| serde_json::to_value(r).unwrap())
            .collect();
        assert_eq!(
            bodies,
            vec![
                serde_json::json!({
                    "rule_id": "dev.supermessage.quiet.reaction", "actions": [],
                    "conditions": [{ "kind": "event_match", "key": "type", "pattern": "m.reaction" }]
                }),
                serde_json::json!({
                    "rule_id": "dev.supermessage.quiet.agentpod_turn", "actions": [],
                    "conditions": [{ "kind": "event_match", "key": "type", "pattern": "dev.agentpod.turn.v1" }]
                }),
                serde_json::json!({
                    "rule_id": "dev.supermessage.quiet.agentpod_permission", "actions": [],
                    "conditions": [{ "kind": "event_match", "key": "type", "pattern": "dev.agentpod.permission.v1" }]
                }),
                serde_json::json!({
                    "rule_id": "dev.supermessage.quiet.superpipeline_gate", "actions": [],
                    "conditions": [{ "kind": "event_match", "key": "type", "pattern": "dev.superpipeline.gate.v1" }]
                }),
                serde_json::json!({
                    "rule_id": "dev.supermessage.quiet.agentpod_voice_reply", "actions": [],
                    "conditions": [{
                        "kind": "event_property_is",
                        "key": "content.dev\\.agentpod\\.voice_reply.schema_version",
                        "value": 1
                    }]
                }),
                serde_json::json!({
                    "rule_id": "dev.supermessage.quiet.edit", "actions": [],
                    // One backslash on the wire: `m.relates_to` is one key.
                    "conditions": [{
                        "kind": "event_property_is",
                        "key": "content.m\\.relates_to.rel_type",
                        "value": "m.replace"
                    }]
                }),
            ]
        );
    }

    /// Which quiet rules `event` matches, evaluated the way the homeserver
    /// evaluates them — ruma's own evaluator, which tuwunel runs.
    async fn matched(event: serde_json::Value) -> Vec<String> {
        use matrix_sdk::ruma::push::{FlattenedJson, PushConditionRoomCtx};
        use matrix_sdk::ruma::serde::Raw;
        use matrix_sdk::ruma::{owned_room_id, owned_user_id, uint};

        let ctx = PushConditionRoomCtx::new(
            owned_room_id!("!r:hs"),
            uint!(3),
            owned_user_id!("@me:hs"),
            "Me".into(),
        );
        let raw: Raw<serde_json::Value> =
            Raw::from_json(serde_json::value::to_raw_value(&event).unwrap());
        let flat = FlattenedJson::from_raw(&raw);
        let mut out = Vec::new();
        for rule in quiet_push_rules() {
            let mut all = true;
            for condition in &rule.conditions {
                all &= condition.applies(&flat, &ctx).await;
            }
            if all {
                out.push(rule.rule_id);
            }
        }
        out
    }

    fn event(ty: &str, content: serde_json::Value) -> serde_json::Value {
        serde_json::json!({
            "type": ty, "event_id": "$e", "sender": "@agent:hs",
            "origin_server_ts": 1, "room_id": "!r:hs", "content": content
        })
    }

    /// The rules silence what they claim to and leave an ordinary message —
    /// a reply included, which is a relation too — alone.
    #[tokio::test]
    async fn the_quiet_rules_silence_their_events_and_nothing_else() {
        use serde_json::json;
        assert_eq!(
            matched(event(
                "m.reaction",
                json!({ "m.relates_to": { "rel_type": "m.annotation", "event_id": "$x", "key": "✅" } })
            ))
            .await,
            vec!["dev.supermessage.quiet.reaction"]
        );
        assert_eq!(
            matched(event(
                "m.room.message",
                json!({
                    "msgtype": "m.text", "body": "* fixed",
                    "m.relates_to": { "rel_type": "m.replace", "event_id": "$x" }
                })
            ))
            .await,
            vec!["dev.supermessage.quiet.edit"]
        );
        // The hub's voice reply, and nothing that only resembles one: a
        // plain voice note, a later schema, the key's name in a body.
        let voice_reply = |version: serde_json::Value| {
            json!({
                "msgtype": "m.audio", "body": "Voice message.ogg",
                "org.matrix.msc3245.voice": {},
                "dev.agentpod.voice_reply": {
                    "schema_version": version, "text_event_id": "$t", "voice": "bf_emma"
                }
            })
        };
        assert_eq!(
            matched(event("m.room.message", voice_reply(json!(1)))).await,
            vec!["dev.supermessage.quiet.agentpod_voice_reply"]
        );
        assert!(matched(event("m.room.message", voice_reply(json!(2))))
            .await
            .is_empty());
        assert!(matched(event(
            "m.room.message",
            json!({
                "msgtype": "m.audio", "body": "dev.agentpod.voice_reply",
                "org.matrix.msc3245.voice": {}
            })
        ))
        .await
        .is_empty());
        for (name, ty) in QUIET_EVENT_TYPES {
            assert_eq!(
                matched(event(ty, json!({ "body": "x" }))).await,
                vec![format!("{QUIET_RULE_PREFIX}{name}")]
            );
        }
        assert!(matched(event(
            "m.room.message",
            json!({
                "msgtype": "m.text", "body": "hello",
                "m.relates_to": { "m.in_reply_to": { "event_id": "$x" } }
            })
        ))
        .await
        .is_empty());
        assert!(matched(event(
            "m.room.message",
            json!({ "msgtype": "m.text", "body": "m.reaction dev.agentpod.turn.v1" })
        ))
        .await
        .is_empty());
    }

    #[test]
    fn a_fresh_account_needs_every_quiet_rule() {
        let needed: Vec<String> = quiet_rules_to_install(&ruleset(serde_json::json!([])))
            .into_iter()
            .map(|r| r.rule_id)
            .collect();
        assert_eq!(needed.len(), 6);
        assert!(needed.iter().all(|id| id.starts_with(QUIET_RULE_PREFIX)));
    }

    #[test]
    fn installed_rules_are_not_sent_again() {
        let installed: Vec<serde_json::Value> =
            quiet_push_rules().iter().map(|r| listed(r, true)).collect();
        assert!(quiet_rules_to_install(&ruleset(serde_json::Value::Array(installed))).is_empty());
    }

    #[test]
    fn a_rule_somebody_disabled_stays_disabled_and_a_changed_one_is_replaced() {
        let mut rules = quiet_push_rules();
        let mut current: Vec<serde_json::Value> = Vec::new();
        // The reaction rule, turned off from another client.
        current.push(listed(&rules[0], false));
        // The edit rule as an older build wrote it: another condition.
        let mut stale = rules.pop().unwrap();
        stale.conditions = vec![PushCondition::EventMatch(EventMatchConditionData::new(
            "content.m.relates_to.rel_type".into(),
            "m.replace".into(),
        ))];
        current.push(listed(&stale, true));
        // The turn rule, but made to notify: not quiet, so replaced.
        let mut loud = listed(&rules[1], true);
        loud["actions"] = serde_json::json!(["notify"]);
        current.push(loud);
        // Somebody else's rule on the same event type is not ours to touch.
        current.push(serde_json::json!({
            "rule_id": "user.reactions", "default": false, "enabled": true,
            "actions": ["notify"],
            "conditions": [{ "kind": "event_match", "key": "type", "pattern": "m.reaction" }]
        }));
        let needed: Vec<String> =
            quiet_rules_to_install(&ruleset(serde_json::Value::Array(current)))
                .into_iter()
                .map(|r| r.rule_id)
                .collect();
        assert_eq!(
            needed,
            vec![
                "dev.supermessage.quiet.agentpod_turn",
                "dev.supermessage.quiet.agentpod_permission",
                "dev.supermessage.quiet.superpipeline_gate",
                "dev.supermessage.quiet.agentpod_voice_reply",
                "dev.supermessage.quiet.edit",
            ]
        );
    }

    #[test]
    fn the_pusher_is_addressed_to_this_device_and_app() {
        let pusher = pusher_for(&registration());
        assert_eq!(pusher.ids.pushkey, "abcd");
        assert_eq!(pusher.ids.app_id, "dev.supermessage.ios");
    }
}
