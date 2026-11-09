//! When a notification is posted, what its actions are, and what an action
//! means — decided once for every host.
//!
//! [`crate::notification`] decides what a single row's notification *says*.
//! This module decides the rest: *whether* one is posted (for the roster and
//! for the open timeline), which actions it offers, and what a tapped action
//! sends. It is a port of the rules iOS held in Swift
//! (`NotificationComposer`, `NotificationKeys`, `LocalNotifier`,
//! `WidgetAnswering`) so Android and the desktop build on one copy.
//!
//! **Nothing here reads message content the core did not already compose.**
//! A message's body is the row's `reply_preview` or the roster's
//! `RoomPreview::text`; a decision's is `CustomEventDecision::prompt`; titles
//! are `RoomIdentity::name` and `TimelineRow::sender_name`. The only choice
//! made here is *whether* — the host's job, since notifications are a
//! platform surface — and what an action means.
//!
//! Not here, because they are host lifecycle: the catch-up window after a
//! launch, the cache of a room's notification mode, and the time budget a
//! background answer gets.

use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};

use crate::dto::{Membership, RoomRow, TimelineRow};
use crate::notification::{
    self, GateAnswers, NotificationCategory, NotificationDto, PermissionAnswers,
};
use crate::room_info::NotificationMode;
use crate::widget::WidgetAnswer;

// `userInfo` keys (`NotificationRouter.swift:58-66`) and action ids (`:69-75`).
pub const KEY_ROOM_ID: &str = "sm.roomId";
pub const KEY_EVENT_ID: &str = "sm.eventId";
pub const KEY_ALLOW_OPTION_ID: &str = "sm.allowOptionId";
pub const KEY_REJECT_OPTION_ID: &str = "sm.rejectOptionId";
/// A gate's `gate_id`, its question, and the option ids it offers
/// (space-separated — superpipeline's ids contain no spaces).
pub const KEY_GATE_ID: &str = "sm.gateId";
pub const KEY_GATE_PROMPT: &str = "sm.gatePrompt";
pub const KEY_GATE_OPTIONS: &str = "sm.gateOptions";
/// A gate.v2's option for each action (`notification::GateAnswersV2`), each
/// under its own key because a v2 id is the board's free choice and may hold
/// a space. Their presence is what makes an action answer with decision.v2.
pub const KEY_GATE_V2_APPROVE: &str = "sm.gateV2.approve";
pub const KEY_GATE_V2_REVISE: &str = "sm.gateV2.revise";
pub const KEY_GATE_V2_REJECT: &str = "sm.gateV2.reject";
/// Set to "1" for a gate.v2, so an action whose slot carries no v2 option
/// opens rather than falling back to a decision.v1.
pub const KEY_GATE_V2: &str = "sm.gateV2";
/// A subject-bound gate.v2's binding, copied into the decision.v2.
pub const KEY_GATE_SUBJECT_ID: &str = "sm.gateSubjectId";
pub const KEY_GATE_SUBJECT_DIGEST: &str = "sm.gateSubjectDigest";
pub const ACTION_ALLOW_ONCE: &str = "sm.permission.allowOnce";
pub const ACTION_REJECT: &str = "sm.permission.reject";
pub const ACTION_APPROVE_GATE: &str = "sm.gate.approve";
/// A text-input action: what the reader types is the gate's comment.
pub const ACTION_REQUEST_CHANGES: &str = "sm.gate.requestChanges";
pub const ACTION_REJECT_GATE: &str = "sm.gate.reject";
pub const ACTION_OPEN: &str = "sm.open";
/// iOS's system dismiss id and Android's own: both mean "nothing to do".
pub const DISMISS_ACTIONS: [&str; 2] = [
    "com.apple.UNNotificationDismissActionIdentifier",
    "sm.dismiss",
];
pub const ANSWER_FAILED_TITLE: &str = "Your answer wasn't sent";
pub const ANSWER_FAILED_BODY: &str = "Open the conversation to answer it there.";

/// superpipeline's `GateDecision` ids — the only three its resolution
/// endpoint accepts (`NotificationRouter.swift:78-80`).
const OPTION_APPROVE: &str = "approve";
const OPTION_REQUEST_CHANGES: &str = "request_changes";
const OPTION_REJECT: &str = "reject";

/// What the notifier needs to know about the app when it decides
/// (`NotificationComposer.swift:125-149`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct NotificationContext {
    /// The room on screen, or `None` on the roster.
    pub open_room_id: Option<String>,
    /// Whether the app is in the foreground and active.
    pub app_active: bool,
    /// The room the timeline store is subscribed to. That room's events are
    /// seen row by row, so the roster path leaves it alone rather than
    /// posting a second, vaguer notification for the same message.
    pub timeline_room_id: Option<String>,
    /// Whether this device receives remote pushes for the account — a
    /// pusher is registered. Then the push shows what arrives while the app
    /// is away, and the app only adds what it sees first, in the foreground.
    #[uniffi(default = false)]
    #[serde(default)]
    pub remote_push: bool,
}

/// One notification the host should post, decided.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct PlannedNotification {
    /// The request id: stable per event. For an event it is the event id —
    /// the push gateway's `apns-collapse-id` too, so a local and a remote
    /// notification for one event replace each other rather than stacking.
    pub id: String,
    pub room_id: String,
    pub event_id: Option<String>,
    pub title: String,
    pub subtitle: Option<String>,
    pub body: String,
    pub category: NotificationCategory,
    /// Set exactly when `category` is `Permission`.
    pub permission: Option<PermissionAnswers>,
    /// Set exactly when `category` is `Gate`.
    pub gate: Option<GateAnswers>,
}

impl PlannedNotification {
    /// Whether this asks the reader for a decision rather than telling them
    /// something was said (`NotificationComposer.swift:92`).
    pub fn is_decision(&self) -> bool {
        self.category != NotificationCategory::Message
    }
}

/// The text field of a text-input action.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct NotificationTextInput {
    pub button_title: String,
    pub placeholder: String,
}

/// One action a notification offers.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct NotificationActionSpec {
    pub id: String,
    pub title: String,
    pub destructive: bool,
    /// The device must be unlocked to run it.
    pub requires_unlock: bool,
    /// Running it brings the app to the front.
    pub opens_app: bool,
    pub text_input: Option<NotificationTextInput>,
}

/// An answer given from a notification, ready to send with no room open
/// (`NotificationRouter.swift:36-52`). Field order is the order Swift labels.
///
/// `GateV2` carries the gate it answers, so it is far larger than the rest;
/// it stays unboxed because this crosses UniFFI, which has no `Box` in a
/// variant, and one answer exists per tapped notification.
#[allow(clippy::large_enum_variant)]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum NotificationAnswer {
    /// An AgentPod permission request: `option_id` goes into the room as a
    /// plain message, as every other client answers one.
    Permission { room_id: String, option_id: String },
    /// A superpipeline gate: a `dev.superpipeline.gate.decision.v1`
    /// referencing `gate_event_id`, exactly what the card sends.
    Gate {
        room_id: String,
        gate_event_id: String,
        gate_id: String,
        option_id: String,
        comment: Option<String>,
        prompt: String,
    },
    /// A gate.v2: a `dev.superpipeline.gate.decision.v2` referencing
    /// `gate_event_id`, sent with `send_gate_decision_v2_to`. `gate` is
    /// rebuilt from what the notification carried — the one option the
    /// action sends and the subject binding — so the core checks it exactly
    /// as it checks a card's answer. It never holds an approving option for a
    /// subject-bound gate.
    GateV2 {
        room_id: String,
        gate_event_id: String,
        gate: crate::gate_v2::GateV2Decision,
        option_id: String,
        comment: Option<String>,
    },
}

impl NotificationAnswer {
    pub fn room_id(&self) -> &str {
        match self {
            Self::Permission { room_id, .. }
            | Self::Gate { room_id, .. }
            | Self::GateV2 { room_id, .. } => room_id,
        }
    }
}

/// What an action on a delivered notification means.
// It carries a `NotificationAnswer`, which is large for the reason given there and
// crosses UniFFI the same way, so it stays unboxed too.
#[allow(clippy::large_enum_variant)]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum NotificationResponse {
    /// Open the room.
    Open { room_id: String },
    /// Send this answer — no room needs to be open.
    Answer { answer: NotificationAnswer },
    /// Nothing this app can act on.
    Ignore,
}

/// A notification the core decided, planned. Its request id is the event id
/// (`NotificationComposer.swift:94-104`).
pub fn planned_from_decided(note: &NotificationDto) -> PlannedNotification {
    PlannedNotification {
        id: note.event_id.clone(),
        room_id: note.room_id.clone(),
        event_id: Some(note.event_id.clone()),
        title: note.title.clone(),
        subtitle: note.subtitle.clone(),
        body: note.body.clone(),
        category: note.category,
        permission: note.permission.clone(),
        gate: note.gate.clone(),
    }
}

/// A room the reader is looking at, in an app they are looking at, does not
/// notify: the message is already on screen (`NotificationComposer.swift:164`).
pub fn is_suppressed(room_id: &str, context: &NotificationContext) -> bool {
    context.app_active && context.open_room_id.as_deref() == Some(room_id)
}

/// Notifications for rooms whose roster row changed between two snapshots
/// (`NotificationComposer.swift:178-212`).
///
/// Only a room present in both is compared. That is what makes the first
/// roster after launch or sign-in (an empty `previous`) a baseline rather
/// than a notification for every unread room in the account, and a room that
/// has just appeared — joined, or brought in by a space switch — is not news
/// either.
pub fn for_roster(
    previous: &[RoomRow],
    next: &[RoomRow],
    context: &NotificationContext,
) -> Vec<PlannedNotification> {
    // With remote push, every room's news arrives as a push, decided
    // elsewhere. The roster knows no event ids, so a note from here could
    // never be matched to that push and would be its duplicate.
    if context.remote_push {
        return Vec::new();
    }
    // Last wins on a duplicate id (Swift: `uniquingKeysWith: { _, last in last }`).
    let before: HashMap<&str, &RoomRow> =
        previous.iter().map(|r| (r.room.id.as_str(), r)).collect();
    let mut out = Vec::new();
    for row in next {
        let id = row.room.id.as_str();
        let Some(old) = before.get(id) else { continue };
        if row.room.membership != Membership::Joined
            || context.timeline_room_id.as_deref() == Some(id)
            || is_suppressed(id, context)
            || row.room.unread <= old.room.unread
            || row.room.last_message_is_own
        {
            continue;
        }
        let ms = row.room.last_activity_ms.unwrap_or(row.room.unread);
        let planned = |id: String, body: String, category| PlannedNotification {
            id,
            room_id: row.room.id.clone(),
            event_id: None,
            title: row.identity.name.clone(),
            subtitle: None,
            body,
            category,
            permission: None,
            gate: None,
        };
        match &row.preview {
            Some(preview) if preview.pending => out.push(planned(
                format!("{id}#pending#{ms}"),
                preview.text.clone(),
                NotificationCategory::Decision,
            )),
            preview => {
                if let Some(text) = preview
                    .as_ref()
                    .map(|p| p.text.clone())
                    .or_else(|| row.room.last_message.clone())
                {
                    out.push(planned(
                        format!("{id}#{ms}"),
                        text,
                        NotificationCategory::Message,
                    ));
                }
            }
        }
    }
    out
}

/// Notifications for new rows in the subscribed room
/// (`NotificationComposer.swift:225-255`).
///
/// `since_ms`: rows older than this are history — what the subscription
/// loaded, or back-pagination fetched — and never notify. `already_notified`:
/// event ids posted before. A row is re-emitted whenever anything about it
/// changes (a reaction, a receipt), and each of those must not be a new
/// notification.
pub fn for_timeline(
    room_id: &str,
    room_name: &str,
    rows: &[TimelineRow],
    since_ms: u64,
    already_notified: &[String],
    context: &NotificationContext,
) -> Vec<PlannedNotification> {
    if is_suppressed(room_id, context) {
        return Vec::new();
    }
    // In the background with push, the push is the notification.
    if !context.app_active && context.remote_push {
        return Vec::new();
    }
    let seen: HashSet<&str> = already_notified.iter().map(String::as_str).collect();
    let mut out = Vec::new();
    for row in rows {
        if row.item.is_own {
            continue;
        }
        // A local echo has no event id; a remote event always does.
        let Some(event_id) = row.item.event_id.as_deref() else {
            continue;
        };
        if seen.contains(event_id) {
            continue;
        }
        if !row.item.timestamp_ms.is_some_and(|at| at >= since_ms) {
            continue;
        }
        if let Some(note) = notification::notification_for_row(row, room_id, event_id, room_name) {
            out.push(planned_from_decided(&note));
        }
    }
    out
}

/// Whether a room's notification setting lets this through.
///
/// A decision is always delivered: it is somebody waiting on the reader, and
/// a muted chatty agent room is exactly where one gets lost. A plain message
/// is held back in a muted room and in a mentions-only one — the second
/// because telling a mention from a message is the core's decision and the
/// roster does not carry it (`NotificationComposer.swift:296-309`).
pub fn delivers(note: &PlannedNotification, mode: Option<NotificationMode>) -> bool {
    if note.is_decision() {
        return true;
    }
    match mode {
        Some(NotificationMode::Muted) | Some(NotificationMode::MentionsOnly) => false,
        Some(NotificationMode::Default) | Some(NotificationMode::AllMessages) | None => true,
    }
}

/// Whether a remote notification arriving in the foreground is shown.
///
/// Not for the room on screen — the message is already there — and not for an
/// event this app already posted locally from the open timeline. That local
/// note used the event id as its identifier, which is also the push's
/// collapse id, so it would only replace itself; showing the banner again
/// would be the same news twice (`NotificationComposer.swift:285-292`).
pub fn presents_remote(
    room_id: Option<&str>,
    event_id: Option<&str>,
    context: &NotificationContext,
    already_notified: &[String],
) -> bool {
    if room_id.is_some_and(|room| is_suppressed(room, context)) {
        return false;
    }
    if event_id.is_some_and(|event| already_notified.iter().any(|seen| seen == event)) {
        return false;
    }
    true
}

fn action(
    id: &str,
    title: &str,
    destructive: bool,
    opens_app: bool,
    text_input: Option<NotificationTextInput>,
) -> NotificationActionSpec {
    NotificationActionSpec {
        id: id.to_string(),
        title: title.to_string(),
        destructive,
        requires_unlock: !opens_app,
        opens_app,
        text_input,
    }
}

/// The actions a category offers, with iOS's copy
/// (`A/Platform/LocalNotifier.swift:23-61`).
///
/// Every answer requires unlock and none opens the app: the device must be
/// unlocked to answer, and answering does not open the app — the host sends
/// it in the background, whether or not the app was running. Only "Open"
/// opens the app, and it is the one action that needs no unlock.
///
/// Gates are answered from the notification too — the operator's decision of
/// 2026-09-27, reversing the earlier rule that a gate only opened (its
/// details sit behind the card's disclosure). The notification carries the
/// gate's question, and an action the gate does not offer opens it instead
/// ([`response`]). A decision that must be read in the app first offers only
/// "Open".
pub fn actions(category: NotificationCategory) -> Vec<NotificationActionSpec> {
    let allow = action(ACTION_ALLOW_ONCE, "Allow once", false, false, None);
    let reject = action(ACTION_REJECT, "Reject", true, false, None);
    let approve = action(ACTION_APPROVE_GATE, "Approve", false, false, None);
    let request_changes = action(
        ACTION_REQUEST_CHANGES,
        "Request changes",
        false,
        false,
        Some(NotificationTextInput {
            button_title: "Send".to_string(),
            placeholder: "What should change?".to_string(),
        }),
    );
    let reject_gate = action(ACTION_REJECT_GATE, "Reject", true, false, None);
    let open = action(ACTION_OPEN, "Open", false, true, None);
    match category {
        NotificationCategory::Message => Vec::new(),
        NotificationCategory::Permission => vec![allow, reject],
        NotificationCategory::Gate => vec![approve, request_changes, reject_gate, open],
        NotificationCategory::GateReview => vec![request_changes, reject_gate, open],
        NotificationCategory::Decision => vec![open],
    }
}

/// The `userInfo` a notification is posted with
/// (`NotificationRouter.swift:85-96`). Nothing else is carried.
pub fn user_info(note: &PlannedNotification) -> HashMap<String, String> {
    let mut info = HashMap::from([(KEY_ROOM_ID.to_string(), note.room_id.clone())]);
    if let Some(id) = &note.event_id {
        info.insert(KEY_EVENT_ID.to_string(), id.clone());
    }
    if let Some(permission) = &note.permission {
        info.insert(
            KEY_ALLOW_OPTION_ID.to_string(),
            permission.allow_option_id.clone(),
        );
        info.insert(
            KEY_REJECT_OPTION_ID.to_string(),
            permission.reject_option_id.clone(),
        );
    }
    if let Some(gate) = &note.gate {
        info.insert(KEY_GATE_ID.to_string(), gate.gate_id.clone());
        info.insert(KEY_GATE_PROMPT.to_string(), gate.prompt.clone());
        info.insert(KEY_GATE_OPTIONS.to_string(), gate.option_ids.join(" "));
        if let Some(v2) = &gate.v2 {
            info.insert(KEY_GATE_V2.to_string(), "1".to_string());
            for (key, id) in [
                (KEY_GATE_V2_APPROVE, &v2.approve_option_id),
                (KEY_GATE_V2_REVISE, &v2.revise_option_id),
                (KEY_GATE_V2_REJECT, &v2.reject_option_id),
            ] {
                if let Some(id) = id {
                    info.insert(key.to_string(), id.clone());
                }
            }
            if let Some(subject) = &v2.subject {
                info.insert(KEY_GATE_SUBJECT_ID.to_string(), subject.id.clone());
                info.insert(KEY_GATE_SUBJECT_DIGEST.to_string(), subject.digest.clone());
            }
        }
    }
    info
}

/// Decode an action on a delivered notification
/// (`NotificationRouter.swift:108-160`).
///
/// `action_id` is the system's default action id for a plain tap, which lands
/// in the last branch along with [`ACTION_OPEN`]. `user_text` is what the
/// reader typed into a text-input action, when the action was one.
///
/// Anything that cannot be answered exactly as the button said — an option
/// the notification did not carry, a gate that does not offer it, "Request
/// changes" with nothing typed — opens the room instead. A tap that silently
/// does something other than its label is the one outcome worse than asking
/// the reader to look.
pub fn response(
    action_id: &str,
    user_info: &HashMap<String, String>,
    user_text: Option<&str>,
) -> NotificationResponse {
    let Some(room) = user_info.get(KEY_ROOM_ID) else {
        return NotificationResponse::Ignore;
    };
    let open = || NotificationResponse::Open {
        room_id: room.clone(),
    };
    let permission = |key: &str| match user_info.get(key) {
        Some(option) => NotificationResponse::Answer {
            answer: NotificationAnswer::Permission {
                room_id: room.clone(),
                option_id: option.clone(),
            },
        },
        None => open(),
    };
    let gate = |option: &str, comment: Option<String>| {
        gate_response(option, comment, room, user_info).unwrap_or_else(open)
    };
    match action_id {
        ACTION_ALLOW_ONCE => permission(KEY_ALLOW_OPTION_ID),
        ACTION_REJECT => permission(KEY_REJECT_OPTION_ID),
        ACTION_APPROVE_GATE => gate(OPTION_APPROVE, None),
        ACTION_REJECT_GATE => gate(OPTION_REJECT, None),
        ACTION_REQUEST_CHANGES => {
            // Feedback is the whole point of this answer — superpipeline
            // hands it to the rework. Sent empty, the card goes back with no
            // reason, so an empty reply is read as "let me look" instead.
            let comment = user_text.unwrap_or("").trim();
            if comment.is_empty() {
                open()
            } else {
                gate(OPTION_REQUEST_CHANGES, Some(comment.to_string()))
            }
        }
        id if DISMISS_ACTIONS.contains(&id) => NotificationResponse::Ignore,
        _ => open(),
    }
}

/// The gate path (`NotificationRouter.swift:147-160`): an answer only when the
/// notification carried its event, a non-empty gate id and the option.
fn gate_response(
    option: &str,
    comment: Option<String>,
    room: &str,
    user_info: &HashMap<String, String>,
) -> Option<NotificationResponse> {
    let event = user_info.get(KEY_EVENT_ID)?;
    let gate = user_info.get(KEY_GATE_ID).filter(|g| !g.is_empty())?;
    let offered = user_info.get(KEY_GATE_OPTIONS)?;
    if !offered
        .split(' ')
        .filter(|p| !p.is_empty())
        .any(|p| p == option)
    {
        return None;
    }
    if user_info.contains_key(KEY_GATE_V2) {
        return gate_v2_response(option, comment, room, event, gate, user_info);
    }
    Some(NotificationResponse::Answer {
        answer: NotificationAnswer::Gate {
            room_id: room.to_string(),
            gate_event_id: event.clone(),
            gate_id: gate.clone(),
            option_id: option.to_string(),
            comment,
            prompt: user_info.get(KEY_GATE_PROMPT).cloned().unwrap_or_default(),
        },
    })
}

/// The gate.v2 path: the v2 option the notification carried for the action's
/// slot, sent as a decision.v2. `None` — so the room opens — when the slot
/// carries none, or it would approve a subject-bound gate (a notification
/// never shows the preview, so it never approves one, whatever it carried).
fn gate_v2_response(
    slot: &str,
    comment: Option<String>,
    room: &str,
    event: &str,
    gate_id: &str,
    user_info: &HashMap<String, String>,
) -> Option<NotificationResponse> {
    use crate::gate_v2::{GateOptionMeaning, GateOptionV2, GateSubjectBinding, GateV2Decision};
    let (key, meaning, label) = match slot {
        OPTION_APPROVE => (KEY_GATE_V2_APPROVE, GateOptionMeaning::Approve, "Approve"),
        OPTION_REQUEST_CHANGES => (
            KEY_GATE_V2_REVISE,
            GateOptionMeaning::Revise,
            "Request changes",
        ),
        OPTION_REJECT => (KEY_GATE_V2_REJECT, GateOptionMeaning::Reject, "Reject"),
        _ => return None,
    };
    let option_id = user_info.get(key).filter(|id| !id.is_empty())?;
    let subject = match (
        user_info.get(KEY_GATE_SUBJECT_ID),
        user_info.get(KEY_GATE_SUBJECT_DIGEST),
    ) {
        (Some(id), Some(digest)) => Some(GateSubjectBinding {
            id: id.clone(),
            digest: digest.clone(),
        }),
        (None, None) => None,
        // Half a binding is no binding: open rather than guess.
        _ => return None,
    };
    if meaning == GateOptionMeaning::Approve && subject.is_some() {
        return None;
    }
    Some(NotificationResponse::Answer {
        answer: NotificationAnswer::GateV2 {
            room_id: room.to_string(),
            gate_event_id: event.to_string(),
            gate: GateV2Decision {
                gate_id: gate_id.to_string(),
                options: vec![GateOptionV2 {
                    id: option_id.clone(),
                    label: label.to_string(),
                    meaning,
                    needs_comment: meaning == GateOptionMeaning::Revise,
                    input: None,
                    scope: None,
                    requires: None,
                }],
                preview: Vec::new(),
                approvals_withheld: None,
                subject,
                // A notification never chooses a version (charter
                // 2026-11-09 §7): an approval of a variant gate is never
                // carried, and request changes and reject need none.
                variants: Vec::new(),
                // The answer only; a quorum is the board's to count.
                quorum: None,
                release: None,
            },
            option_id: option_id.clone(),
            comment,
        },
    })
}

/// The notification answer a widget's button sends: the same two paths a
/// notification's actions take, so a widget cannot answer differently
/// (`WidgetAnswering.swift:7-15`).
pub fn answer_for_widget(answer: &WidgetAnswer) -> NotificationAnswer {
    match &answer.gate_id {
        Some(gate_id) => NotificationAnswer::Gate {
            room_id: answer.room_id.clone(),
            gate_event_id: answer.event_id.clone(),
            gate_id: gate_id.clone(),
            option_id: answer.option_id.clone(),
            comment: None,
            prompt: answer.prompt.clone(),
        },
        None => NotificationAnswer::Permission {
            room_id: answer.room_id.clone(),
            option_id: answer.option_id.clone(),
        },
    }
}

/// Something the reader tried from a notification did not land. Said plainly,
/// and tapping it opens the room so they can answer there
/// (`LocalNotifier.swift:93-99`).
pub fn answer_failed(room_id: &str) -> PlannedNotification {
    PlannedNotification {
        id: format!("{room_id}#answer-failed"),
        room_id: room_id.to_string(),
        event_id: None,
        title: ANSWER_FAILED_TITLE.to_string(),
        subtitle: None,
        body: ANSWER_FAILED_BODY.to_string(),
        category: NotificationCategory::Decision,
        permission: None,
        gate: None,
    }
}

/// The push as it came, when nothing better could be built
/// (`NotificationService.swift:17-19`): "New message".
pub fn generic(room_id: &str, event_id: &str, title: &str) -> PlannedNotification {
    planned_from_decided(&NotificationDto::generic(room_id, event_id, title))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::{CustomEventDecision, CustomEventDecisionOption, CustomEventView};
    use crate::dto::{Membership, RoomRow, RoomSummary, TimelineRow};
    use crate::item_view::ItemView;
    use crate::notification::{GateAnswers, PermissionAnswers};
    use crate::room_preview::RoomPreview;

    const NOW: u64 = 1_700_000_000_000;

    fn context(
        open: Option<&str>,
        active: bool,
        timeline: Option<&str>,
        push: bool,
    ) -> NotificationContext {
        NotificationContext {
            open_room_id: open.map(str::to_string),
            app_active: active,
            timeline_room_id: timeline.map(str::to_string),
            remote_push: push,
        }
    }

    fn background() -> NotificationContext {
        context(None, false, None, false)
    }

    /// `KT/NotificationComposerTests.swift:12-59`.
    fn room_row(
        id: &str,
        unread: u64,
        own: bool,
        pending: bool,
        text: &str,
        membership: Membership,
        at: Option<u64>,
    ) -> RoomRow {
        let mut row = RoomRow::new(RoomSummary {
            id: id.to_string(),
            name: id.to_string(),
            avatar_url: None,
            unread,
            last_message: Some(text.to_string()),
            last_message_is_own: own,
            last_message_names_sender: false,
            last_event_type: None,
            last_activity_ms: at,
            runtime: None,
            membership,
        });
        row.identity.name = format!("Room {id}");
        row.preview = Some(RoomPreview {
            text: text.to_string(),
            pending,
        });
        row
    }

    fn room(id: &str, unread: u64) -> RoomRow {
        room_row(
            id,
            unread,
            false,
            false,
            "hello",
            Membership::Joined,
            Some(NOW),
        )
    }

    fn timeline_row(event_id: Option<&str>, own: bool, at_ms: Option<u64>) -> TimelineRow {
        TimelineRow::new(crate::timeline::project_item_parts(
            event_id.unwrap_or("local-1"),
            event_id,
            "message",
            Some("m.text"),
            None,
            Some("@atlas:x.org"),
            Some("Atlas"),
            None,
            false,
            Some("hi there"),
            None,
            None,
            None,
            at_ms,
            own,
            None,
            None,
            false,
            Vec::new(),
            Vec::new(),
        ))
    }

    fn decision(ids: &[&str], subject: Option<&str>) -> CustomEventDecision {
        CustomEventDecision {
            prompt: "Ship it?".into(),
            options: ids
                .iter()
                .map(|id| CustomEventDecisionOption {
                    id: id.to_string(),
                    label: id.to_string(),
                })
                .collect(),
            subject: subject.map(str::to_string),
            gate_v2: None,
            question_v2: None,
            delivery_v2: None,
            permission_v2: None,
            attention_v2: None,
            deadline: None,
        }
    }

    fn decision_row(event_id: &str, decision: CustomEventDecision) -> TimelineRow {
        let mut row = timeline_row(Some(event_id), false, Some(NOW));
        row.view = ItemView::CustomEvent {
            view: CustomEventView::Rendered {
                fields: Vec::new(),
                reasoning: None,
                newer_version: false,
                decision: Some(decision),
                link: None,
                outcome: None,
            },
            label: "Permission".into(),
            event_type: "dev.agentpod.permission.v1".into(),
        };
        row
    }

    fn gate_note() -> PlannedNotification {
        PlannedNotification {
            id: "$g".into(),
            room_id: "!a".into(),
            event_id: Some("$g".into()),
            title: "Room !a".into(),
            subtitle: Some("Gate".into()),
            body: "Ship it?".into(),
            category: NotificationCategory::Gate,
            permission: None,
            gate: Some(GateAnswers {
                gate_id: "gate-1".into(),
                prompt: "Ship it?".into(),
                option_ids: vec!["approve".into(), "request_changes".into(), "reject".into()],
                v2: None,
            }),
        }
    }

    fn permission_note() -> PlannedNotification {
        PlannedNotification {
            id: "$p".into(),
            room_id: "!a".into(),
            event_id: Some("$p".into()),
            title: "Room !a".into(),
            subtitle: Some("Permission".into()),
            body: "Allow it?".into(),
            category: NotificationCategory::Permission,
            permission: Some(PermissionAnswers {
                allow_option_id: "Allow once".into(),
                reject_option_id: "Reject".into(),
            }),
            gate: None,
        }
    }

    fn message_note() -> PlannedNotification {
        PlannedNotification {
            id: "$m".into(),
            room_id: "!a".into(),
            event_id: Some("$m".into()),
            title: "Atlas".into(),
            subtitle: None,
            body: "hi".into(),
            category: NotificationCategory::Message,
            permission: None,
            gate: None,
        }
    }

    fn decision_note() -> PlannedNotification {
        PlannedNotification {
            category: NotificationCategory::Decision,
            ..message_note()
        }
    }

    fn info(note: &PlannedNotification) -> HashMap<String, String> {
        user_info(note)
    }

    // --- the roster

    /// Mutation seen failing: a room missing from `previous` read as unread 0 failed the baseline assertion
    #[test]
    fn the_first_roster_is_a_baseline_not_a_burst() {
        assert!(for_roster(&[], &[room("!a", 5)], &background()).is_empty());
    }

    /// Mutation seen failing: changing the roster id format from `{id}#{ms}` to `{id}#x` failed it
    #[test]
    fn a_new_message_in_another_room_notifies_with_the_cores_preview() {
        let mut next = room("!a", 1);
        next.preview = Some(RoomPreview {
            text: "deploy finished".into(),
            pending: false,
        });
        let out = for_roster(&[room("!a", 0)], &[next], &background());
        assert_eq!(out.len(), 1);
        let note = &out[0];
        assert_eq!(note.category, NotificationCategory::Message);
        assert_eq!(note.body, "deploy finished");
        assert_eq!(note.title, "Room !a");
        assert_eq!(note.room_id, "!a");
        assert_eq!(note.id, "!a#1700000000000");
        assert_eq!(note.event_id, None);
    }

    /// Mutation seen failing: a room missing from `previous` read as unread 0 failed it
    #[test]
    fn a_room_new_to_the_roster_is_not_news() {
        assert!(for_roster(
            &[room("!a", 0)],
            &[room("!a", 0), room("!b", 4)],
            &background()
        )
        .is_empty());
    }

    /// Mutation seen failing: comparing with `<` instead of `<=` failed it
    #[test]
    fn an_unread_count_that_did_not_rise_is_not_news() {
        assert!(for_roster(&[room("!a", 3)], &[room("!a", 3)], &background()).is_empty());
        assert!(for_roster(&[room("!a", 3)], &[room("!a", 2)], &background()).is_empty());
    }

    /// Mutation seen failing: removing the `last_message_is_own` skip failed it
    #[test]
    fn the_readers_own_message_does_not_notify_them() {
        let own = room_row("!a", 1, true, false, "hello", Membership::Joined, Some(NOW));
        assert!(for_roster(&[room("!a", 0)], &[own], &background()).is_empty());
    }

    /// Mutation seen failing: removing the `is_suppressed` skip in `for_roster` failed it
    #[test]
    fn the_open_room_with_the_app_in_front_does_not_notify() {
        let out = for_roster(
            &[room("!a", 0), room("!b", 0)],
            &[room("!a", 1), room("!b", 1)],
            &context(Some("!a"), true, None, false),
        );
        let ids: Vec<&str> = out.iter().map(|n| n.room_id.as_str()).collect();
        assert_eq!(ids, vec!["!b"]);
    }

    /// Mutation seen failing: `is_suppressed` ignoring `app_active` failed it
    #[test]
    fn the_open_room_notifies_once_the_app_is_in_the_background() {
        let out = for_roster(
            &[room("!a", 0)],
            &[room("!a", 1)],
            &context(Some("!a"), false, None, false),
        );
        assert_eq!(out.len(), 1);
    }

    /// Mutation seen failing: removing the `timeline_room_id` skip in `for_roster` failed it
    #[test]
    fn the_subscribed_room_is_left_to_the_timeline_path() {
        let out = for_roster(
            &[room("!a", 0)],
            &[room("!a", 1)],
            &context(None, false, Some("!a"), false),
        );
        assert!(out.is_empty());
    }

    /// Mutation seen failing: making the pending branch unreachable (posted as a Message) failed it
    #[test]
    fn a_pending_decision_in_another_room_opens_it_rather_than_offering_answers() {
        let pending = room_row(
            "!a",
            1,
            false,
            true,
            "Waiting on you",
            Membership::Joined,
            Some(NOW),
        );
        let out = for_roster(&[room("!a", 0)], &[pending], &background());
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].category, NotificationCategory::Decision);
        assert_eq!(out[0].permission, None);
        assert_eq!(out[0].gate, None);
        assert_eq!(out[0].id, "!a#pending#1700000000000");
        assert_eq!(out[0].body, "Waiting on you");
    }

    /// Mutation seen failing: removing the `Joined` membership skip failed it
    #[test]
    fn an_invitations_roster_row_is_not_a_message() {
        let invited = room_row(
            "!a",
            1,
            false,
            false,
            "hello",
            Membership::Invited,
            Some(NOW),
        );
        assert!(for_roster(&[room("!a", 0)], &[invited], &background()).is_empty());
    }

    /// Mutation seen failing: dropping the `remote_push` guard in `for_roster` failed it
    #[test]
    fn with_push_the_roster_posts_nothing() {
        let previous = [room("!a", 0)];
        let next = [room("!a", 1)];
        assert!(for_roster(&previous, &next, &context(None, false, None, true)).is_empty());
        assert_eq!(for_roster(&previous, &next, &background()).len(), 1);
    }

    /// Mutation seen failing: `unwrap_or(row.room.unread)` becoming `unwrap_or(0)` failed it
    #[test]
    fn a_room_without_an_activity_time_is_named_by_its_unread_count() {
        let before = room_row("!a", 0, false, false, "hello", Membership::Joined, None);
        let after = room_row("!a", 3, false, false, "hello", Membership::Joined, None);
        let out = for_roster(&[before], &[after], &background());
        assert_eq!(out[0].id, "!a#3");
    }

    /// Mutation seen failing: posting an empty body when there is no text (`.or(Some(String::new()))`) failed it
    #[test]
    fn a_row_with_nothing_to_say_posts_nothing() {
        let mut next = room("!a", 1);
        next.preview = None;
        next.room.last_message = None;
        assert!(for_roster(&[room("!a", 0)], &[next], &background()).is_empty());
    }

    /// Mutation seen failing: dropping the `last_message` fallback failed it
    #[test]
    fn the_last_message_speaks_when_the_core_composed_no_preview() {
        let mut next = room("!a", 1);
        next.preview = None;
        next.room.last_message = Some("from the summary".into());
        let out = for_roster(&[room("!a", 0)], &[next], &background());
        assert_eq!(out[0].body, "from the summary");
    }

    /// Mutation seen failing: reading `previous` first-wins (`.rev()`) failed it
    #[test]
    fn a_duplicate_previous_row_is_read_as_its_last() {
        // Swift: `uniquingKeysWith: { _, last in last }`.
        let out = for_roster(
            &[room("!a", 0), room("!a", 5)],
            &[room("!a", 3)],
            &background(),
        );
        assert!(out.is_empty());
    }

    // --- the subscribed room

    fn subscribed() -> NotificationContext {
        context(Some("!a"), false, Some("!a"), false)
    }

    /// Mutation seen failing: `is_suppressed` ignoring `app_active` failed it
    #[test]
    fn a_new_message_in_the_subscribed_room_notifies_while_the_app_is_away() {
        let rows = [timeline_row(Some("$1"), false, Some(NOW))];
        let out = for_timeline("!a", "Room !a", &rows, NOW, &[], &subscribed());
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].category, NotificationCategory::Message);
        assert_eq!(out[0].id, "$1");
        assert_eq!(out[0].event_id.as_deref(), Some("$1"));
        assert_eq!(out[0].subtitle.as_deref(), Some("Room !a"));
        assert_eq!(out[0].title, "Atlas");
    }

    /// Mutation seen failing: turning the `is_suppressed` guard in `for_timeline` into `false` failed it
    #[test]
    fn the_subscribed_room_on_screen_with_the_app_in_front_is_silent() {
        let rows = [timeline_row(Some("$1"), false, Some(NOW))];
        let ctx = context(Some("!a"), true, Some("!a"), false);
        assert!(for_timeline("!a", "Room !a", &rows, NOW, &[], &ctx).is_empty());
    }

    /// Mutation seen failing: suppressing on `app_active` alone in `for_timeline` failed it
    #[test]
    fn the_subscribed_room_notifies_once_the_reader_has_gone_back_to_the_roster() {
        let rows = [timeline_row(Some("$1"), false, Some(NOW))];
        let ctx = context(None, true, Some("!a"), false);
        assert_eq!(
            for_timeline("!a", "Room !a", &rows, NOW, &[], &ctx).len(),
            1
        );
    }

    /// Mutation seen failing: replacing `at >= since_ms` with `at >= 0` failed it
    #[test]
    fn history_does_not_notify() {
        let rows = [timeline_row(Some("$1"), false, Some(NOW - 1))];
        assert!(for_timeline("!a", "Room !a", &rows, NOW, &[], &subscribed()).is_empty());
    }

    /// Mutation seen failing: disabling the `already_notified` check in `for_timeline` failed it
    #[test]
    fn a_row_already_posted_is_not_posted_again_when_it_changes() {
        let rows = [timeline_row(Some("$1"), false, Some(NOW))];
        let seen = ["$1".to_string()];
        assert!(for_timeline("!a", "Room !a", &rows, NOW, &seen, &subscribed()).is_empty());
    }

    /// Mutation seen failing: removing the `is_own` skip, and reading a missing event id as empty, each failed it
    #[test]
    fn own_rows_and_local_echoes_do_not_notify() {
        let rows = [
            timeline_row(Some("$1"), true, Some(NOW)),
            timeline_row(None, false, Some(NOW)),
            timeline_row(Some("$3"), false, None),
        ];
        assert!(for_timeline("!a", "Room !a", &rows, NOW, &[], &subscribed()).is_empty());
    }

    /// Mutation seen failing: disabling the `!app_active && remote_push` guard in `for_timeline` failed it
    #[test]
    fn with_push_the_open_timeline_notifies_in_the_foreground_and_not_in_the_background() {
        let rows = [timeline_row(Some("$1"), false, Some(NOW))];
        let count = |active: bool, push: bool| {
            for_timeline(
                "!a",
                "Room !a",
                &rows,
                NOW,
                &[],
                &context(None, active, Some("!a"), push),
            )
            .len()
        };
        assert_eq!(count(false, true), 0);
        assert_eq!(count(true, true), 1);
        assert_eq!(count(false, false), 1);
    }

    /// Mutation seen failing: `is_suppressed` ignoring `app_active` failed it
    #[test]
    fn a_permission_row_carries_both_answers() {
        let rows = [decision_row(
            "$p",
            decision(&["Allow once", "Allow always", "Reject"], None),
        )];
        let out = for_timeline("!a", "Room !a", &rows, NOW, &[], &subscribed());
        assert_eq!(out[0].category, NotificationCategory::Permission);
        assert_eq!(
            out[0].permission,
            Some(PermissionAnswers {
                allow_option_id: "Allow once".into(),
                reject_option_id: "Reject".into(),
            })
        );
    }

    /// Mutation seen failing: `is_suppressed` ignoring `app_active` failed it
    #[test]
    fn a_gate_row_carries_its_gate_and_its_own_event() {
        let rows = [decision_row(
            "$g",
            decision(&["approve", "request_changes", "reject"], Some("gate-1")),
        )];
        let out = for_timeline("!a", "Room !a", &rows, NOW, &[], &subscribed());
        assert_eq!(out[0].category, NotificationCategory::Gate);
        assert_eq!(out[0].event_id.as_deref(), Some("$g"));
        let gate = out[0].gate.as_ref().unwrap();
        assert_eq!(gate.gate_id, "gate-1");
        assert_eq!(
            gate.option_ids,
            vec!["approve", "request_changes", "reject"]
        );
        assert_eq!(out[0].permission, None);
    }

    // --- remote pushes and the room's setting

    /// Mutation seen failing: disabling the `already_notified` check in `presents_remote` failed it
    #[test]
    fn a_push_in_the_foreground_is_shown_unless_its_room_is_open_or_already_posted() {
        let open = context(Some("!a"), true, None, false);
        let seen = ["$1".to_string()];
        assert!(!presents_remote(Some("!a"), Some("$2"), &open, &[]));
        assert!(presents_remote(Some("!b"), Some("$2"), &open, &[]));
        assert!(!presents_remote(Some("!b"), Some("$1"), &open, &seen));
        assert!(presents_remote(None, None, &open, &seen));
    }

    /// Mutation seen failing: joining the gate options with commas in `user_info` failed it
    #[test]
    fn a_decided_notification_is_planned_under_its_event_id() {
        let dto = NotificationDto {
            category: NotificationCategory::Gate,
            gate: Some(GateAnswers {
                gate_id: "gate-1".into(),
                prompt: "Ship it?".into(),
                option_ids: vec!["approve".into(), "reject".into()],
                v2: None,
            }),
            title: "Room".into(),
            subtitle: Some("Gate".into()),
            body: "Ship it?".into(),
            ..NotificationDto::generic("!a", "$gate", "")
        };
        let planned = planned_from_decided(&dto);
        assert_eq!(planned.id, "$gate");
        assert_eq!(planned.event_id.as_deref(), Some("$gate"));
        assert_eq!(planned.subtitle.as_deref(), Some("Gate"));
        assert_eq!(planned.gate, dto.gate);
        assert_eq!(info(&planned)[KEY_GATE_OPTIONS], "approve reject");
    }

    /// Mutation seen failing: dropping the `is_decision` short-circuit in `delivers` failed it
    #[test]
    fn a_muted_room_holds_messages_back_but_never_a_decision() {
        let message = message_note();
        let decision = decision_note();
        assert!(!delivers(&message, Some(NotificationMode::Muted)));
        assert!(!delivers(&message, Some(NotificationMode::MentionsOnly)));
        assert!(delivers(&message, Some(NotificationMode::Default)));
        assert!(delivers(&message, Some(NotificationMode::AllMessages)));
        assert!(delivers(&message, None));
        assert!(delivers(&decision, Some(NotificationMode::Muted)));
        assert!(delivers(&gate_note(), Some(NotificationMode::MentionsOnly)));
        assert!(delivers(&permission_note(), Some(NotificationMode::Muted)));
    }

    // --- actions

    fn spec(
        id: &str,
        title: &str,
        destructive: bool,
        requires_unlock: bool,
        opens_app: bool,
        text_input: Option<NotificationTextInput>,
    ) -> NotificationActionSpec {
        NotificationActionSpec {
            id: id.into(),
            title: title.into(),
            destructive,
            requires_unlock,
            opens_app,
            text_input,
        }
    }

    /// Mutation seen failing: making the permission Reject non-destructive, and making Open require unlock, each failed it
    #[test]
    fn each_category_offers_ioss_actions_with_ioss_copy() {
        let allow = spec(
            "sm.permission.allowOnce",
            "Allow once",
            false,
            true,
            false,
            None,
        );
        let reject = spec("sm.permission.reject", "Reject", true, true, false, None);
        let approve = spec("sm.gate.approve", "Approve", false, true, false, None);
        let changes = spec(
            "sm.gate.requestChanges",
            "Request changes",
            false,
            true,
            false,
            Some(NotificationTextInput {
                button_title: "Send".into(),
                placeholder: "What should change?".into(),
            }),
        );
        let reject_gate = spec("sm.gate.reject", "Reject", true, true, false, None);
        let open = spec("sm.open", "Open", false, false, true, None);
        assert_eq!(actions(NotificationCategory::Message), vec![]);
        assert_eq!(
            actions(NotificationCategory::Permission),
            vec![allow, reject]
        );
        assert_eq!(
            actions(NotificationCategory::Gate),
            vec![approve, changes, reject_gate, open.clone()]
        );
        assert_eq!(actions(NotificationCategory::Decision), vec![open]);
    }

    fn answer_permission(room: &str, option: &str) -> NotificationResponse {
        NotificationResponse::Answer {
            answer: NotificationAnswer::Permission {
                room_id: room.into(),
                option_id: option.into(),
            },
        }
    }

    fn answer_gate(option: &str, comment: Option<&str>) -> NotificationResponse {
        NotificationResponse::Answer {
            answer: NotificationAnswer::Gate {
                room_id: "!a".into(),
                gate_event_id: "$g".into(),
                gate_id: "gate-1".into(),
                option_id: option.into(),
                comment: comment.map(str::to_string),
                prompt: "Ship it?".into(),
            },
        }
    }

    fn open(room: &str) -> NotificationResponse {
        NotificationResponse::Open {
            room_id: room.into(),
        }
    }

    /// Mutation seen failing: making `ACTION_ALLOW_ONCE` read the reject key failed it
    #[test]
    fn allow_once_and_reject_send_the_option_the_notification_carried() {
        let info = info(&permission_note());
        assert_eq!(
            response(ACTION_ALLOW_ONCE, &info, None),
            answer_permission("!a", "Allow once")
        );
        assert_eq!(
            response(ACTION_REJECT, &info, None),
            answer_permission("!a", "Reject")
        );
        assert_eq!(
            response(
                "com.apple.UNNotificationDefaultActionIdentifier",
                &info,
                None
            ),
            open("!a")
        );
        assert_eq!(response(ACTION_OPEN, &info, None), open("!a"));
    }

    /// Mutation seen failing: a missing option answering `Ignore` instead of `Open` failed it
    #[test]
    fn an_action_with_no_option_to_send_opens_the_room() {
        let info = info(&decision_note());
        assert_eq!(response(ACTION_ALLOW_ONCE, &info, None), open("!a"));
        assert_eq!(response(ACTION_REJECT, &info, None), open("!a"));
        assert_eq!(response(ACTION_APPROVE_GATE, &info, None), open("!a"));
    }

    /// Mutation seen failing: joining the gate options with commas in `user_info` failed it
    #[test]
    fn approve_and_reject_send_the_gates_decision() {
        let info = info(&gate_note());
        assert_eq!(
            response(ACTION_APPROVE_GATE, &info, None),
            answer_gate("approve", None)
        );
        assert_eq!(
            response(ACTION_REJECT_GATE, &info, None),
            answer_gate("reject", None)
        );
        // The permission "Reject" is not the gate's.
        assert_eq!(response(ACTION_REJECT, &info, None), open("!a"));
    }

    /// Mutation seen failing: joining the gate options with commas in `user_info` failed it
    #[test]
    fn request_changes_sends_what_the_reader_typed() {
        let info = info(&gate_note());
        assert_eq!(
            response(ACTION_REQUEST_CHANGES, &info, Some("  Add a test first.\n")),
            answer_gate("request_changes", Some("Add a test first."))
        );
    }

    /// Mutation seen failing: skipping the trim emptiness check (`if comment.is_empty()` made `if false`) failed it
    #[test]
    fn request_changes_with_nothing_typed_opens_the_gate() {
        let info = info(&gate_note());
        for typed in [None, Some(""), Some("  \n ")] {
            assert_eq!(response(ACTION_REQUEST_CHANGES, &info, typed), open("!a"));
        }
    }

    /// Mutation seen failing: dropping the offered-options check in `gate_response` failed it
    #[test]
    fn an_action_the_gate_does_not_offer_opens_it() {
        let mut note = gate_note();
        note.gate.as_mut().unwrap().option_ids = vec!["approve".into()];
        let info = info(&note);
        assert_eq!(response(ACTION_REJECT_GATE, &info, None), open("!a"));
        assert_eq!(
            response(ACTION_REQUEST_CHANGES, &info, Some("no")),
            open("!a")
        );
        assert_eq!(
            response(ACTION_APPROVE_GATE, &info, None),
            answer_gate("approve", None)
        );
    }

    /// Mutation seen failing: accepting an empty gate id failed it
    #[test]
    fn a_gate_without_its_event_or_id_opens_instead() {
        let mut info = info(&gate_note());
        info.insert(KEY_GATE_ID.into(), String::new());
        assert_eq!(response(ACTION_APPROVE_GATE, &info, None), open("!a"));
        let mut info = self::info(&gate_note());
        info.remove(KEY_EVENT_ID);
        assert_eq!(response(ACTION_APPROVE_GATE, &info, None), open("!a"));
    }

    /// Mutation seen failing: the default branch of `response` returning `Ignore` failed it
    #[test]
    fn a_plain_tap_on_a_gate_opens_it() {
        let info = info(&gate_note());
        assert_eq!(
            response(
                "com.apple.UNNotificationDefaultActionIdentifier",
                &info,
                None
            ),
            open("!a")
        );
        assert_eq!(response(ACTION_OPEN, &info, None), open("!a"));
    }

    /// Mutation seen failing: a missing option answering `Ignore` instead of `Open` failed it
    #[test]
    fn a_push_the_extension_could_not_improve_opens_and_answers_nothing() {
        let info = HashMap::from([
            (KEY_ROOM_ID.to_string(), "!r:hs".to_string()),
            (KEY_EVENT_ID.to_string(), "$e".to_string()),
        ]);
        assert_eq!(response(ACTION_ALLOW_ONCE, &info, None), open("!r:hs"));
    }

    /// Mutation seen failing: renaming `"sm.dismiss"` in `DISMISS_ACTIONS` failed it (after the literal-id loop was added)
    #[test]
    fn dismissing_is_ignored_on_either_platform() {
        let info = info(&gate_note());
        for id in DISMISS_ACTIONS {
            assert_eq!(response(id, &info, None), NotificationResponse::Ignore);
        }
        // The ids themselves, not only the constant that holds them.
        for id in [
            "com.apple.UNNotificationDismissActionIdentifier",
            "sm.dismiss",
        ] {
            assert_eq!(response(id, &info, None), NotificationResponse::Ignore);
        }
        let none = HashMap::new();
        for id in [
            ACTION_ALLOW_ONCE,
            ACTION_REJECT,
            ACTION_APPROVE_GATE,
            ACTION_REQUEST_CHANGES,
            ACTION_REJECT_GATE,
            ACTION_OPEN,
            "sm.dismiss",
            "anything",
        ] {
            assert_eq!(
                response(id, &none, Some("typed")),
                NotificationResponse::Ignore
            );
        }
        // An empty room id is still present, as in Swift.
        let empty = HashMap::from([(KEY_ROOM_ID.to_string(), String::new())]);
        assert_eq!(response(ACTION_OPEN, &empty, None), open(""));
    }

    /// Mutation seen failing: joining the gate options with commas in `user_info` failed it
    #[test]
    fn the_user_info_carries_only_what_is_set() {
        let failed = answer_failed("!a");
        assert_eq!(
            user_info(&failed),
            HashMap::from([(KEY_ROOM_ID.to_string(), "!a".to_string())])
        );
        let permission = info(&permission_note());
        assert_eq!(permission.len(), 4);
        assert_eq!(permission[KEY_ALLOW_OPTION_ID], "Allow once");
        assert_eq!(permission[KEY_REJECT_OPTION_ID], "Reject");
        let gate = info(&gate_note());
        assert_eq!(gate.len(), 5);
        assert_eq!(gate[KEY_GATE_ID], "gate-1");
        assert_eq!(gate[KEY_GATE_PROMPT], "Ship it?");
        assert_eq!(gate[KEY_GATE_OPTIONS], "approve request_changes reject");
    }

    // --- gate.v2 from a notification

    fn gate_v2_note(subject: bool) -> PlannedNotification {
        use crate::gate_v2::GateSubjectBinding;
        use crate::notification::GateAnswersV2;
        PlannedNotification {
            category: if subject {
                NotificationCategory::GateReview
            } else {
                NotificationCategory::Gate
            },
            gate: Some(GateAnswers {
                gate_id: "gate-1".into(),
                prompt: "Ship it?".into(),
                option_ids: if subject {
                    vec!["request_changes".into(), "reject".into()]
                } else {
                    vec!["approve".into(), "request_changes".into(), "reject".into()]
                },
                v2: Some(GateAnswersV2 {
                    approve_option_id: (!subject).then(|| "ship it".into()),
                    revise_option_id: Some("send_back".into()),
                    reject_option_id: Some("decline".into()),
                    subject: subject.then(|| GateSubjectBinding {
                        id: "aps_1".into(),
                        digest: "sha256:1".into(),
                    }),
                }),
            }),
            ..gate_note()
        }
    }

    fn sent_v2(response: NotificationResponse) -> (String, Option<String>, serde_json::Value) {
        let NotificationResponse::Answer {
            answer:
                NotificationAnswer::GateV2 {
                    room_id,
                    gate_event_id,
                    gate,
                    option_id,
                    comment,
                },
        } = response
        else {
            panic!("expected a decision.v2, got {response:?}");
        };
        assert_eq!((room_id.as_str(), gate_event_id.as_str()), ("!a", "$g"));
        // What the core would send for it — validated as a card's answer is.
        let content =
            crate::gate_v2::decision_request(&gate, &option_id, None, comment.as_deref(), "$g")
                .expect("the rebuilt gate answers");
        (option_id, comment, content)
    }

    #[test]
    fn a_gate_review_offers_no_approve_action() {
        let ids: Vec<String> = actions(NotificationCategory::GateReview)
            .into_iter()
            .map(|a| a.id)
            .collect();
        assert_eq!(
            ids,
            [ACTION_REQUEST_CHANGES, ACTION_REJECT_GATE, ACTION_OPEN]
        );
    }

    #[test]
    fn a_gate_v2_notification_answers_with_decision_v2_by_meaning() {
        let info = info(&gate_v2_note(false));
        assert_eq!(info[KEY_GATE_V2], "1");
        let (option, _, content) = sent_v2(response(ACTION_APPROVE_GATE, &info, None));
        assert_eq!(option, "ship it");
        assert_eq!(
            content["suite_event_type"],
            "dev.superpipeline.gate.decision.v2"
        );
        assert!(content.get("subject").is_none());
        let (option, _, _) = sent_v2(response(ACTION_REJECT_GATE, &info, None));
        assert_eq!(option, "decline");
        let (option, comment, content) = sent_v2(response(
            ACTION_REQUEST_CHANGES,
            &info,
            Some(" more tests "),
        ));
        assert_eq!(option, "send_back");
        assert_eq!(comment.as_deref(), Some("more tests"));
        assert_eq!(content["comment"], "more tests");
        // Nothing typed still opens.
        assert_eq!(
            response(ACTION_REQUEST_CHANGES, &info, Some("  ")),
            open("!a")
        );
    }

    #[test]
    fn a_subject_bound_gate_v2_is_never_approved_from_a_notification() {
        let info = info(&gate_v2_note(true));
        assert_eq!(info[KEY_GATE_SUBJECT_ID], "aps_1");
        assert!(!info.contains_key(KEY_GATE_V2_APPROVE));
        assert_eq!(response(ACTION_APPROVE_GATE, &info, None), open("!a"));
        // Even a forged approve key opens: the binding forbids it.
        let mut forged = info.clone();
        forged.insert(KEY_GATE_V2_APPROVE.into(), "approve_automatic".into());
        forged.insert(
            KEY_GATE_OPTIONS.into(),
            "approve request_changes reject".into(),
        );
        assert_eq!(response(ACTION_APPROVE_GATE, &forged, None), open("!a"));
        // Reject and revise go, bound to the subject.
        let (_, _, content) = sent_v2(response(ACTION_REJECT_GATE, &info, None));
        assert_eq!(
            content["subject"],
            serde_json::json!({ "id": "aps_1", "digest": "sha256:1" })
        );
        // Half a binding opens rather than sending unbound.
        let mut half = info.clone();
        half.remove(KEY_GATE_SUBJECT_DIGEST);
        assert_eq!(response(ACTION_REJECT_GATE, &half, None), open("!a"));
    }

    #[test]
    fn a_gate_v2_action_with_no_option_for_its_slot_opens() {
        let mut note = gate_v2_note(false);
        let gate = note.gate.as_mut().unwrap();
        gate.v2.as_mut().unwrap().reject_option_id = None;
        let info = info(&note);
        // `option_ids` still says reject; with no v2 option it must not fall
        // back to a decision.v1 "reject" the gate may not offer.
        assert_eq!(response(ACTION_REJECT_GATE, &info, None), open("!a"));
    }

    /// Mutation seen failing: changing `ANSWER_FAILED_TITLE` failed it
    #[test]
    fn an_answer_that_did_not_land_says_so_and_only_opens() {
        assert_eq!(
            answer_failed("!a"),
            PlannedNotification {
                id: "!a#answer-failed".into(),
                room_id: "!a".into(),
                event_id: None,
                title: "Your answer wasn't sent".into(),
                subtitle: None,
                body: "Open the conversation to answer it there.".into(),
                category: NotificationCategory::Decision,
                permission: None,
                gate: None,
            }
        );
    }

    /// Mutation seen failing: dropping the title passed to `NotificationDto::generic` in `generic` failed it
    #[test]
    fn a_push_that_cannot_be_fetched_says_new_message() {
        let note = generic("!a", "$e", "Room");
        assert_eq!(note.id, "$e");
        assert_eq!(note.event_id.as_deref(), Some("$e"));
        assert_eq!(note.title, "Room");
        assert_eq!(note.body, "New message");
        assert_eq!(note.category, NotificationCategory::Message);
    }

    /// Mutation seen failing: answering every widget button as a permission failed it
    #[test]
    fn a_widgets_button_answers_the_way_a_notification_does() {
        let gate = answer_for_widget(&WidgetAnswer {
            room_id: "!a".into(),
            event_id: "$g".into(),
            option_id: "approve".into(),
            gate_id: Some("gate-1".into()),
            prompt: "Ship it?".into(),
        });
        assert_eq!(
            gate,
            NotificationAnswer::Gate {
                room_id: "!a".into(),
                gate_event_id: "$g".into(),
                gate_id: "gate-1".into(),
                option_id: "approve".into(),
                comment: None,
                prompt: "Ship it?".into(),
            }
        );
        assert_eq!(gate.room_id(), "!a");
        let permission = answer_for_widget(&WidgetAnswer {
            room_id: "!b".into(),
            event_id: "$p".into(),
            option_id: "Allow once".into(),
            gate_id: None,
            prompt: "Allow it?".into(),
        });
        assert_eq!(
            permission,
            NotificationAnswer::Permission {
                room_id: "!b".into(),
                option_id: "Allow once".into(),
            }
        );
        assert_eq!(permission.room_id(), "!b");
    }

    /// Mutation seen failing: removing `#[serde(default)]` from `remote_push` failed it
    #[test]
    fn the_desktop_reads_the_plan_in_camel_case() {
        let value = serde_json::to_value(gate_note()).unwrap();
        assert_eq!(value["roomId"], "!a");
        assert_eq!(value["eventId"], "$g");
        assert_eq!(value["category"], "gate");
        assert_eq!(
            value["gate"]["optionIds"],
            serde_json::json!(["approve", "request_changes", "reject"])
        );
        let ctx: NotificationContext = serde_json::from_value(serde_json::json!({
            "openRoomId": null, "appActive": true, "timelineRoomId": null
        }))
        .unwrap();
        assert!(!ctx.remote_push);
        assert!(ctx.app_active);
        let response = serde_json::to_value(answer_gate("approve", None)).unwrap();
        assert_eq!(response["kind"], "answer");
        assert_eq!(response["answer"]["kind"], "gate");
        assert_eq!(response["answer"]["gateEventId"], "$g");
    }
}
