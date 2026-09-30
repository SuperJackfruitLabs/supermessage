//! What a notification says, decided once for every process that posts one.
//!
//! Two places post a notification on iOS and they must agree to the word:
//! the app, from a row it is already showing (a *local* notification), and
//! the Notification Service Extension, from an event it has only a push's
//! room and event id for (a *remote* one — see [`crate::session::Session::notification_for`]).
//! If the two decided separately, the same permission request would offer
//! Allow and Reject from one and only Open from the other, and whichever
//! arrived second would win.
//!
//! So both go through [`notification_for_row`]. The NSE first turns its one
//! decrypted event into the [`TimelineRow`] the timeline would have built
//! (`timeline::row_from_parts`, the projection the timeline itself uses), so
//! a notification's body is the row's own preview and its category the row's
//! own render decision. What remains here is the part only a notification
//! needs: which category of actions to offer, and which options they send.
//!
//! **Nothing here chooses what a room is called or how a sender is named.**
//! Those arrive decided — `RoomIdentity.name`, `TimelineRow::sender_name`.

use crate::custom_events::{CustomEventDecision, CustomEventView, GATE_OPTION_IDS};
use crate::dto::TimelineRow;
use crate::item_view::ItemView;

/// Which set of actions a notification offers. The host registers one
/// category per case under [`NotificationCategory::identifier`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NotificationCategory {
    /// An ordinary message. Tapping it opens the room.
    Message,
    /// An AgentPod permission request answerable as Allow once / Reject.
    Permission,
    /// A superpipeline gate answerable as Approve / Request changes / Reject.
    Gate,
    /// A decision that must be read in the app first. Only "Open".
    Decision,
}

impl NotificationCategory {
    /// The `UNNotificationCategory` identifier — and the value the push
    /// gateway puts in `aps.category`, so a push the extension could not
    /// improve still offers the right actions.
    pub fn identifier(self) -> &'static str {
        match self {
            Self::Message => "MESSAGE",
            Self::Permission => "PERMISSION",
            Self::Gate => "GATE",
            Self::Decision => "DECISION",
        }
    }
}

/// The two option ids a PERMISSION notification's actions send.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PermissionAnswers {
    pub allow_option_id: String,
    pub reject_option_id: String,
}

/// What a GATE notification's actions need to answer with no room open: the
/// same three things the card sends.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GateAnswers {
    /// superpipeline's `gate_id` — `CustomEventDecision::subject`.
    pub gate_id: String,
    /// The gate's question; the sentence left in the room is derived from it.
    pub prompt: String,
    /// Which of `approve`, `request_changes` and `reject` the gate offers, in
    /// that order.
    pub option_ids: Vec<String>,
}

/// Why a remote notification should not be shown at all.
///
/// A hint, not a guarantee: without Apple's notification-filtering
/// entitlement an extension cannot drop a push, and an emptied one still
/// shows — as a blank notification, which is what TestFlight build 30 did
/// for every agent reaction and turn card. So a suppressed notification
/// carries [`NotificationDto::fallback_body`] as well: a short, honest line
/// the host shows quietly when it cannot drop the push.
///
/// Most of these are never pushed at all. See `docs/agentpod-events.md`
/// ("Quiet events"): the hub gateway drops what the hub itself sends, and the
/// account push rules `core::push::quiet_push_rules` installs keep the
/// homeserver from pushing the rest — for unencrypted events.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NotificationSuppression {
    /// An edit (`m.replace`): the original already notified.
    Edit,
    Reaction,
    /// A redaction, or an event that has since been redacted.
    Redaction,
    /// The sender is blocked, or the account's push rules say not to notify.
    Filtered,
    /// This account sent it, from another device.
    Own,
    /// Something the timeline draws as context rather than news — a turn
    /// card, a transcript, a state change — which no local notification
    /// would have been posted for either.
    NotNews,
}

/// One notification, decided.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NotificationDto {
    pub room_id: String,
    /// The event it is about. For a gate, the event the decision references.
    pub event_id: String,
    pub title: String,
    pub subtitle: Option<String>,
    /// Plain text, already bounded — the row's own preview.
    pub body: String,
    pub category: NotificationCategory,
    /// Set exactly when `category` is `Permission`.
    pub permission: Option<PermissionAnswers>,
    /// Set exactly when `category` is `Gate`.
    pub gate: Option<GateAnswers>,
    /// Groups notifications into one conversation per room — the gateway's
    /// `thread-id` too.
    pub thread_id: String,
    /// Present when this should not be shown; the text fields are then the
    /// generic ones and must not be displayed.
    pub suppress: Option<NotificationSuppression>,
    /// Set exactly when `suppress` is: the title to show quietly — no sound,
    /// no banner — when the push cannot be dropped. `None` keeps the push's
    /// own title (the room is not always known: a filtered event is never
    /// fetched).
    pub fallback_title: Option<String>,
    /// Set exactly when `suppress` is: one line saying what actually
    /// happened — "Krishna reacted ✅ to a message", "Krishna finished · 4
    /// steps" — rather than a blank notification or a "New message" that is
    /// not one.
    pub fallback_body: Option<String>,
    /// What this event means for the widgets, as far as a push can tell
    /// (`crate::widget`): a decision asked, an agent's line, a finished turn,
    /// a gate the board resolved, or this account answering from elsewhere.
    /// Set by [`notification_for_event`] — the Notification Service
    /// Extension's path — and `None` from [`notification_for_row`], whose
    /// caller has the whole timeline to read instead.
    pub activity: Option<NotificationActivity>,
    /// How the turn this event ended went, when the push said: the hub adds
    /// `"turn": {"total", "failed"}` to the push of an agent's answer
    /// (spec 2026-09-29, A5), because the turn card that carries the same
    /// counts is quiet and never pushed. Only counts, never text. The core
    /// cannot see the push's payload, so the Notification Service Extension
    /// sets this from it; every constructor here leaves it `None`.
    #[uniffi(default = None)]
    pub turn: Option<TurnCounts>,
}

/// A turn's tool calls: how many it made and how many of those failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct TurnCounts {
    pub total: u32,
    pub failed: u32,
}

/// What kind of news a pushed event is for the widgets.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ActivityKind {
    /// A permission request or a gate, asked of the reader.
    Decision,
    /// Somebody said something in the room.
    Message,
    /// An agent's turn card: the turn is over.
    TurnFinished,
    /// The hub's receipt that the board accepted an answer to a gate.
    GateOutcome,
    /// This account answered, from another device: a permission option's
    /// name, or a gate decision.
    OwnAnswer,
}

/// The facts [`crate::widget`] needs from one pushed event, and nothing the
/// notification itself does not already know.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NotificationActivity {
    pub kind: ActivityKind,
    /// The room's `RoomIdentity.name` — the agent's or the board's.
    pub room_name: String,
    /// Whether the room's name reads as an agent's (a glyph or a role, the
    /// roster's own test), for a room the widgets do not list yet.
    pub room_is_agent: bool,
    /// The sender's Matrix id.
    pub sender: String,
    /// The event's `origin_server_ts`.
    pub at_ms: u64,
    /// `Message`: its preview line. `TurnFinished`: "Finished · 4 steps".
    /// `OwnAnswer`: the plain text sent, when it was a message.
    pub line: Option<String>,
    /// `GateOutcome` and a gate `OwnAnswer`: the gate named.
    pub gate_id: Option<String>,
    /// `GateOutcome` and a gate `OwnAnswer`: the event referenced.
    pub references: Option<String>,
    /// A gate `OwnAnswer`: the option chosen.
    pub option_id: Option<String>,
}

/// The body a notification carries when there is nothing better to say. The
/// push gateway's own `aps.alert` says the same, so a push the extension could
/// not improve and one it could are worded alike.
pub const GENERIC_BODY: &str = "New message";

/// What a still-encrypted message says: this device has no key for it yet.
pub const ENCRYPTED_BODY: &str = "Encrypted message";

impl NotificationDto {
    /// A notification that says only that something arrived.
    pub fn generic(room_id: &str, event_id: &str, title: &str) -> Self {
        Self {
            room_id: room_id.to_string(),
            event_id: event_id.to_string(),
            title: title.to_string(),
            subtitle: None,
            body: GENERIC_BODY.to_string(),
            category: NotificationCategory::Message,
            permission: None,
            gate: None,
            thread_id: room_id.to_string(),
            suppress: None,
            fallback_title: None,
            fallback_body: None,
            activity: None,
            turn: None,
        }
    }

    /// One that should not be shown, and why — with the quiet line to show
    /// instead when it cannot be dropped.
    pub fn suppressed(
        room_id: &str,
        event_id: &str,
        why: NotificationSuppression,
        fallback_title: Option<&str>,
        fallback_body: String,
    ) -> Self {
        Self {
            suppress: Some(why),
            fallback_title: fallback_title.filter(|t| !t.is_empty()).map(str::to_string),
            fallback_body: Some(fallback_body),
            ..Self::generic(room_id, event_id, "")
        }
    }
}

// ---------------------------------------------------------------------------
// The quiet lines: what a suppressed notification says when it must show.
// ---------------------------------------------------------------------------

/// A filtered event's line: it was never fetched, so nothing more is known.
pub const QUIET_FILTERED_BODY: &str = "Quiet activity";

/// A message removed before the extension could read it.
pub const QUIET_REMOVED_BODY: &str = "A message was removed";

/// This account's own event, from another device. Said, minimally, only
/// because the push cannot be taken back.
pub const QUIET_OWN_BODY: &str = "Sent from your other device";

/// The longest reaction key quoted in full. A key is free text — usually one
/// emoji, occasionally a sentence.
const REACTION_KEY_MAX_CHARS: usize = 16;

fn reacted_line(who: &str, key: &str) -> String {
    let key = key.trim();
    if key.is_empty() {
        return format!("{who} reacted to a message");
    }
    let mut quoted: String = key.chars().take(REACTION_KEY_MAX_CHARS).collect();
    if key.chars().count() > REACTION_KEY_MAX_CHARS {
        quoted.push('…');
    }
    format!("{who} reacted {quoted} to a message")
}

/// A finished turn, from its card's `counts` — "Krishna finished · 4 steps",
/// ", 1 failed" when any did. The same numbers the card's "Did" row shows.
fn turn_line(who: &str, payload: Option<&serde_json::Value>) -> String {
    format!("{who} {}", turn_summary(payload))
}

/// A finished turn without its subject — "finished · 4 steps" — for a line
/// that is already under the agent's name.
fn turn_summary(payload: Option<&serde_json::Value>) -> String {
    let counts = payload.and_then(|p| p.get("counts"));
    let number = |key: &str| {
        counts
            .and_then(|c| c.get(key))
            .and_then(serde_json::Value::as_f64)
            .filter(|n| n.is_finite() && *n >= 0.0)
            .map(|n| n as u64)
    };
    let Some(total) = number("total") else {
        return "finished a turn".to_string();
    };
    let noun = if total == 1 { "step" } else { "steps" };
    match number("failed").filter(|f| *f > 0) {
        Some(failed) => format!("finished · {total} {noun}, {failed} failed"),
        None => format!("finished · {total} {noun}"),
    }
}

/// The notification a row deserves, or `None` when it is not news.
///
/// `room_name` is the room's `RoomIdentity.name`. A message is titled with
/// its sender and subtitled with the room, unless the two are the same (a
/// direct conversation); a decision is titled with the room and subtitled
/// with the card's own label, because the question is the room's, not the
/// sender's.
pub fn notification_for_row(
    row: &TimelineRow,
    room_id: &str,
    event_id: &str,
    room_name: &str,
) -> Option<NotificationDto> {
    let subtitle = (row.sender_name != room_name).then(|| room_name.to_string());
    let message = |body: String| NotificationDto {
        title: row.sender_name.clone(),
        subtitle: subtitle.clone(),
        body,
        ..NotificationDto::generic(room_id, event_id, "")
    };
    // An agent's answer, spoken: posted after the text it speaks, which
    // already notified — so, like a transcript, never a second interruption.
    // Read off the row rather than the view: a voice reply whose text is not
    // loaded is drawn standalone, and it is still not news.
    if row.voice_reply.is_some() {
        return None;
    }
    // The same for a transcript, whatever it is drawn as: standalone, or
    // hidden because its note now carries it (`crate::voice_transcript`).
    if row.voice_transcript.is_some() {
        return None;
    }
    match &row.view {
        ItemView::CustomEvent { view, label, .. } => {
            // A card with nothing to decide — a turn, a run, a station
            // status — is context, not an interruption.
            let CustomEventView::Rendered {
                decision: Some(decision),
                ..
            } = view
            else {
                return None;
            };
            let base = NotificationDto {
                title: room_name.to_string(),
                subtitle: Some(label.clone()),
                body: decision.prompt.clone(),
                category: NotificationCategory::Decision,
                ..NotificationDto::generic(room_id, event_id, "")
            };
            if let Some(gate_id) = &decision.subject {
                return Some(match gate_answers(decision, gate_id) {
                    Some(gate) => NotificationDto {
                        category: NotificationCategory::Gate,
                        gate: Some(gate),
                        ..base
                    },
                    None => base,
                });
            }
            Some(match permission_answers(decision) {
                Some(answers) => NotificationDto {
                    category: NotificationCategory::Permission,
                    permission: Some(answers),
                    ..base
                },
                None => base,
            })
        }
        ItemView::Bubble { .. } | ItemView::Emote => row.reply_preview.clone().map(message),
        ItemView::Image {
            alt, caption: cap, ..
        } => Some(message(
            cap.clone()
                .or_else(|| row.reply_preview.clone())
                .unwrap_or_else(|| alt.clone()),
        )),
        ItemView::MediaFile { filename, .. } => Some(message(
            row.reply_preview
                .clone()
                .unwrap_or_else(|| filename.clone()),
        )),
        // "Voice message", or the audio file's name — the core's title, not
        // the file name a voice note happens to be uploaded under.
        ItemView::Audio { audio, .. } => Some(message(
            audio.caption.clone().unwrap_or_else(|| audio.title.clone()),
        )),
        // A failed turn is a message — the one other clients show as its
        // body — and the reader is waiting on the answer it replaces.
        ItemView::TurnError { card } => Some(message(
            row.reply_preview
                .clone()
                .unwrap_or_else(|| card.headline.clone()),
        )),
        // What a voice note said, posted after the note. The note already
        // notified, so the transcript is not a second interruption.
        ItemView::VoiceTranscript { .. } => None,
        ItemView::System { .. }
        | ItemView::UnreadMarker
        | ItemView::Placeholder { .. }
        | ItemView::DateDivider
        | ItemView::None => None,
    }
}

/// The two options a PERMISSION notification's actions send, or `None` when
/// the request does not offer both.
///
/// The actions are registered ahead of time as a fixed pair — "Allow once" and
/// "Reject" — so they can only be offered when the request has an option that
/// means each. The renderer hands back an option's *name* as its id, and those
/// names are ACP's: "Allow once", "Allow always", "Reject". **"Always" is never
/// taken for "once"**: a lock-screen tap must not grant more than the button
/// said.
pub fn permission_answers(decision: &CustomEventDecision) -> Option<PermissionAnswers> {
    fn normalised(s: &str) -> String {
        s.trim().to_lowercase()
    }
    let options = &decision.options;
    let allow = options
        .iter()
        .find(|o| normalised(&o.id) == "allow once")
        .or_else(|| {
            options.iter().find(|o| {
                let n = normalised(&o.id);
                n.starts_with("allow") && !n.contains("always")
            })
        })?;
    let reject = options
        .iter()
        .find(|o| normalised(&o.id) == "reject")
        .or_else(|| {
            options.iter().find(|o| {
                let n = normalised(&o.id);
                (n.starts_with("reject") || n.starts_with("deny")) && !n.contains("always")
            })
        })?;
    Some(PermissionAnswers {
        allow_option_id: allow.id.clone(),
        reject_option_id: reject.id.clone(),
    })
}

/// What a GATE notification can answer, or `None` when it can answer nothing
/// and should only open.
///
/// Matched on option **ids**, never labels: a label is free text per board
/// ("Ship it", "LGTM"), the id is superpipeline's `GateDecision`. A gate needs
/// Approve or Reject among them to be worth actions — "Request changes" alone
/// still needs the card read first.
pub fn gate_answers(decision: &CustomEventDecision, gate_id: &str) -> Option<GateAnswers> {
    if gate_id.is_empty() {
        return None;
    }
    let option_ids: Vec<String> = GATE_OPTION_IDS
        .iter()
        .filter(|known| decision.options.iter().any(|o| o.id == **known))
        .map(|id| id.to_string())
        .collect();
    if !option_ids
        .iter()
        .any(|id| id == "approve" || id == "reject")
    {
        return None;
    }
    Some(GateAnswers {
        gate_id: gate_id.to_string(),
        prompt: decision.prompt.clone(),
        option_ids,
    })
}

// ---------------------------------------------------------------------------
// A push's one event, as the timeline would have projected it.
// ---------------------------------------------------------------------------

use matrix_sdk::ruma::events::reaction::SyncReactionEvent;
use matrix_sdk::ruma::events::room::message::{Relation, SyncRoomMessageEvent};
use matrix_sdk::ruma::events::{AnySyncMessageLikeEvent, AnySyncTimelineEvent};
use matrix_sdk::ruma::serde::Raw;
use matrix_sdk::ruma::UserId;
use matrix_sdk_ui::notification_client::{
    NotificationEvent, NotificationItem, RawNotificationEvent,
};

/// Everything a notification needs from a fetched, decrypted event.
///
/// The SDK's [`NotificationItem`] reduced to plain values, so the decision
/// below is testable with a hand-built event and no homeserver.
pub struct FetchedEvent<'a> {
    pub room_id: &'a str,
    pub event_id: &'a str,
    /// The room's computed display name, before `RoomIdentity` parsing.
    pub room_display_name: &'a str,
    pub sender_display_name: Option<&'a str>,
    /// The decrypted event, when it is a timeline event.
    pub raw: Option<&'a Raw<AnySyncTimelineEvent>>,
    /// The same event parsed, with any reply fallback already removed.
    pub event: Option<&'a AnySyncTimelineEvent>,
    /// An invitation's inviter, when this is an invitation rather than an
    /// event in a joined room.
    pub invited_by: Option<&'a UserId>,
}

impl<'a> FetchedEvent<'a> {
    /// Borrow the parts of an SDK notification item.
    pub fn from_item(item: &'a NotificationItem, room_id: &'a str, event_id: &'a str) -> Self {
        let (raw, event, invited_by) = match (&item.raw_event, &item.event) {
            (RawNotificationEvent::Timeline(raw), NotificationEvent::Timeline(event)) => {
                (Some(raw), Some(event.as_ref()), None)
            }
            (_, NotificationEvent::Invite(invite)) => (None, None, Some(invite.sender.as_ref())),
            _ => (None, None, None),
        };
        Self {
            room_id,
            event_id,
            room_display_name: &item.room_computed_display_name,
            sender_display_name: item.sender_display_name.as_deref(),
            raw,
            event,
            invited_by,
        }
    }
}

/// A sender named the way their row in the timeline would name them.
fn sender_name(sender: &str, display_name: Option<&str>) -> String {
    let dto = crate::timeline::project_item_parts(
        "",
        None,
        "message",
        None,
        None,
        Some(sender),
        display_name,
        None,
        false,
        None,
        None,
        None,
        None,
        None,
        false,
        None,
        None,
        false,
        Vec::new(),
        Vec::new(),
    );
    TimelineRow::new(dto).sender_name
}

/// The notification for one fetched event.
///
/// Never fails: an event this cannot describe gets the generic body, because
/// a push the homeserver decided to send is somebody wanting the reader's
/// attention, and dropping it on a parse would be the worse error.
pub fn notification_for_event(fetched: &FetchedEvent<'_>, own_user: &UserId) -> NotificationDto {
    let mut note = decide_event(fetched, own_user);
    note.activity = activity_for_event(fetched, own_user, &note);
    note
}

/// The widgets' reading of one fetched event (see [`NotificationActivity`]),
/// or `None` when it changes nothing a widget shows — a reaction, an edit, a
/// redaction, a state change, an invitation.
///
/// Read after the notification is decided and from it where it can be: a
/// decision is whatever [`notification_for_row`] said was one, and a message's
/// line is the notification's own body. Only what a notification drops —
/// this account's own answer, the hub's gate receipt, a turn card's counts —
/// is read back off the event.
fn activity_for_event(
    fetched: &FetchedEvent<'_>,
    own_user: &UserId,
    note: &NotificationDto,
) -> Option<NotificationActivity> {
    let (raw, event) = (fetched.raw?, fetched.event?);
    let identity = crate::room_identity::parse_room_identity(fetched.room_display_name);
    let content: Option<serde_json::Value> = raw.get_field("content").ok().flatten();
    let text = |key: &str| {
        content
            .as_ref()
            .and_then(|c| c.get(key))
            .and_then(serde_json::Value::as_str)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
    };
    let reference = || {
        content
            .as_ref()
            .and_then(|c| c.get("m.relates_to"))
            .filter(|r| {
                r.get("rel_type").and_then(serde_json::Value::as_str) == Some("m.reference")
            })
            .and_then(|r| r.get("event_id"))
            .and_then(serde_json::Value::as_str)
            .map(str::to_string)
    };
    let base = |kind: ActivityKind| NotificationActivity {
        kind,
        room_name: identity.name.clone(),
        room_is_agent: identity.glyph.is_some() || identity.role.is_some(),
        sender: event.sender().to_string(),
        at_ms: u64::from(event.origin_server_ts().0),
        line: None,
        gate_id: None,
        references: None,
        option_id: None,
    };
    let event_type = event.event_type().to_string();
    let suite_type = text("suite_event_type");
    let is_outcome = event_type == crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE
        || suite_type.as_deref() == Some(crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE);

    if is_outcome {
        return Some(NotificationActivity {
            gate_id: text("gate_id"),
            references: reference(),
            ..base(ActivityKind::GateOutcome)
        });
    }
    if event.sender() == own_user {
        if matches!(note.suppress, Some(NotificationSuppression::Own)) {
            if suite_type.as_deref() == Some(crate::timeline::GATE_DECISION_SUITE_TYPE) {
                return Some(NotificationActivity {
                    gate_id: text("gate_id"),
                    references: reference(),
                    option_id: text("option_id"),
                    ..base(ActivityKind::OwnAnswer)
                });
            }
            if event_type == "m.room.message" {
                return Some(NotificationActivity {
                    line: text("body"),
                    ..base(ActivityKind::OwnAnswer)
                });
            }
        }
        return None;
    }
    if event_type == crate::custom_events::TURN_ACTIVITY_EVENT_TYPE {
        return Some(NotificationActivity {
            line: Some(turn_summary(content.as_ref())),
            ..base(ActivityKind::TurnFinished)
        });
    }
    if note.suppress.is_some() {
        return None;
    }
    match note.category {
        NotificationCategory::Permission
        | NotificationCategory::Gate
        | NotificationCategory::Decision => Some(base(ActivityKind::Decision)),
        NotificationCategory::Message => Some(NotificationActivity {
            line: Some(note.body.clone()),
            ..base(ActivityKind::Message)
        }),
    }
}

/// [`notification_for_event`] without the widgets' reading.
fn decide_event(fetched: &FetchedEvent<'_>, own_user: &UserId) -> NotificationDto {
    let room_id = fetched.room_id;
    let event_id = fetched.event_id;
    let room_name = crate::room_identity::parse_room_identity(fetched.room_display_name).name;

    if let Some(inviter) = fetched.invited_by {
        let who = sender_name(inviter.as_str(), fetched.sender_display_name);
        return NotificationDto {
            body: format!("{who} invited you"),
            ..NotificationDto::generic(room_id, event_id, &room_name)
        };
    }

    let (Some(raw), Some(event)) = (fetched.raw, fetched.event) else {
        return NotificationDto::generic(room_id, event_id, &room_name);
    };
    if event.sender() == own_user {
        return NotificationDto::suppressed(
            room_id,
            event_id,
            NotificationSuppression::Own,
            Some(&room_name),
            QUIET_OWN_BODY.to_string(),
        );
    }

    let sender = event.sender().as_str();
    let who = sender_name(sender, fetched.sender_display_name);
    let quiet = |why: NotificationSuppression, line: String| {
        NotificationDto::suppressed(room_id, event_id, why, Some(&room_name), line)
    };
    let timestamp = Some(u64::from(event.origin_server_ts().0));
    let parts = |kind: &str, msgtype: Option<&str>, body: Option<&str>| {
        crate::timeline::project_item_parts(
            event_id,
            Some(event_id),
            kind,
            msgtype,
            None,
            Some(sender),
            fetched.sender_display_name,
            None,
            false,
            body,
            None,
            None,
            None,
            timestamp,
            false,
            None,
            None,
            false,
            Vec::new(),
            Vec::new(),
        )
    };

    let row = match event {
        AnySyncTimelineEvent::MessageLike(message) => match message {
            AnySyncMessageLikeEvent::RoomMessage(SyncRoomMessageEvent::Original(original)) => {
                if matches!(original.content.relates_to, Some(Relation::Replacement(_))) {
                    return quiet(
                        NotificationSuppression::Edit,
                        format!("{who} edited a message"),
                    );
                }
                let msgtype = &original.content.msgtype;
                let mut dto = parts("message", Some(msgtype.msgtype()), Some(msgtype.body()));
                dto.media = crate::timeline::media_meta(msgtype);
                crate::timeline::row_from_parts(dto, Some(raw), own_user)
            }
            AnySyncMessageLikeEvent::Reaction(reaction) => {
                let key = match reaction {
                    SyncReactionEvent::Original(original) => {
                        original.content.relates_to.key.as_str()
                    }
                    SyncReactionEvent::Redacted(_) => "",
                };
                return quiet(NotificationSuppression::Reaction, reacted_line(&who, key));
            }
            AnySyncMessageLikeEvent::RoomRedaction(_) => {
                return quiet(
                    NotificationSuppression::Redaction,
                    format!("{who} removed a message"),
                );
            }
            other if other.is_redacted() => {
                return quiet(
                    NotificationSuppression::Redaction,
                    QUIET_REMOVED_BODY.to_string(),
                );
            }
            // Still encrypted: the extension ran out of ways to get the key.
            // Said honestly, and still from its sender, because the push is
            // real — somebody wrote something.
            AnySyncMessageLikeEvent::RoomEncrypted(_) => {
                let row = TimelineRow::new(parts("unableToDecrypt", None, None));
                return NotificationDto {
                    title: row.sender_name,
                    subtitle: None,
                    body: ENCRYPTED_BODY.to_string(),
                    ..NotificationDto::generic(room_id, event_id, &room_name)
                };
            }
            other => {
                let event_type = other.event_type().to_string();
                // A gate's structured receipt: its readable line, posted
                // beside it, is the news. Never a second buzz, never actions.
                if event_type == crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE {
                    return quiet(
                        NotificationSuppression::NotNews,
                        format!("{who} posted an update"),
                    );
                }
                // A suite event this build draws: project it as the timeline
                // does, so a permission request notifies as one.
                if crate::custom_events::default_registry()
                    .get(&event_type)
                    .is_some()
                {
                    let (payload, body) = crate::timeline::custom_message_payload(Some(raw));
                    let mut dto = parts("customMessage", None, body.as_deref());
                    dto.detail = Some(event_type.clone());
                    dto.custom_payload = payload.clone().map(crate::dto::CustomPayload);
                    let row = crate::timeline::row_from_parts(dto, Some(raw), own_user);
                    if let Some(note) = notification_for_row(&row, room_id, event_id, &room_name) {
                        return note;
                    }
                    let line = if event_type == crate::custom_events::TURN_ACTIVITY_EVENT_TYPE {
                        turn_line(&who, payload.as_ref())
                    } else {
                        format!("{who} posted an update")
                    };
                    return quiet(NotificationSuppression::NotNews, line);
                } else {
                    // A sticker, a call, a poll: news, but nothing this can
                    // say more about than the gateway already did.
                    let row = TimelineRow::new(parts("customMessage", None, None));
                    return NotificationDto {
                        title: row.sender_name,
                        subtitle: None,
                        ..NotificationDto::generic(room_id, event_id, &room_name)
                    };
                }
            }
        },
        AnySyncTimelineEvent::State(_) => {
            return quiet(
                NotificationSuppression::NotNews,
                format!("{who} updated the room"),
            );
        }
    };

    notification_for_row(&row, room_id, event_id, &room_name).unwrap_or_else(|| {
        let line = match &row.view {
            _ if row.voice_reply.is_some() => format!("{who} replied with a voice message"),
            ItemView::VoiceTranscript { .. } => format!("{who} transcribed a voice message"),
            _ => format!("{who} posted an update"),
        };
        quiet(NotificationSuppression::NotNews, line)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::CustomEventDecisionOption;
    use matrix_sdk::ruma::user_id;
    use serde_json::{json, Value};

    fn decision(ids: &[&str], subject: Option<&str>) -> CustomEventDecision {
        CustomEventDecision {
            prompt: "Allow it?".into(),
            options: ids
                .iter()
                .map(|id| CustomEventDecisionOption {
                    id: id.to_string(),
                    label: id.to_string(),
                })
                .collect(),
            subject: subject.map(str::to_string),
        }
    }

    // --- permission_answers: moved from NotificationComposer.permissionAnswers,
    // with its cases.

    #[test]
    fn allow_once_and_reject_are_the_pair() {
        let answers =
            permission_answers(&decision(&["Allow once", "Allow always", "Reject"], None)).unwrap();
        assert_eq!(answers.allow_option_id, "Allow once");
        assert_eq!(answers.reject_option_id, "Reject");
    }

    #[test]
    fn always_is_never_taken_for_once() {
        assert_eq!(
            permission_answers(&decision(&["Allow always", "Reject"], None)),
            None
        );
        assert_eq!(
            permission_answers(&decision(&["Allow once", "Reject always"], None)),
            None
        );
    }

    #[test]
    fn a_near_spelling_is_accepted_when_the_exact_one_is_absent() {
        let answers = permission_answers(&decision(&[" ALLOW ", "Deny"], None)).unwrap();
        assert_eq!(answers.allow_option_id, " ALLOW ");
        assert_eq!(answers.reject_option_id, "Deny");
    }

    #[test]
    fn the_exact_spelling_wins_over_an_earlier_near_one() {
        let answers =
            permission_answers(&decision(&["Allow for now", "Allow once", "Reject"], None))
                .unwrap();
        assert_eq!(answers.allow_option_id, "Allow once");
    }

    #[test]
    fn a_request_without_both_answers_has_no_pair() {
        assert_eq!(permission_answers(&decision(&["Allow once"], None)), None);
        assert_eq!(permission_answers(&decision(&["Reject"], None)), None);
    }

    // --- gate_answers

    #[test]
    fn a_gates_options_are_its_known_ids_in_order() {
        let gate =
            gate_answers(&decision(&["reject", "approve", "later"], Some("g")), "g").unwrap();
        assert_eq!(gate.option_ids, vec!["approve", "reject"]);
        assert_eq!(gate.gate_id, "g");
    }

    #[test]
    fn request_changes_alone_only_opens() {
        assert_eq!(
            gate_answers(&decision(&["request_changes"], Some("g")), "g"),
            None
        );
        assert_eq!(gate_answers(&decision(&["approve"], Some("")), ""), None);
    }

    // --- a push's event, end to end through the timeline's projection

    fn raw(json: Value) -> Raw<AnySyncTimelineEvent> {
        Raw::from_json(serde_json::value::to_raw_value(&json).unwrap())
    }

    fn notify(json: Value) -> NotificationDto {
        let raw = raw(json);
        let event = raw.deserialize().expect("a valid event");
        let fetched = FetchedEvent {
            room_id: "!r:hs",
            event_id: "$e",
            room_display_name: "🛠 Hermes — Ops",
            sender_display_name: Some("Agent Hermes"),
            raw: Some(&raw),
            event: Some(&event),
            invited_by: None,
        };
        notification_for_event(&fetched, user_id!("@me:hs"))
    }

    fn message(content: Value) -> Value {
        json!({
            "type": "m.room.message",
            "event_id": "$e",
            "sender": "@agent_hermes:hs",
            "origin_server_ts": 1,
            "content": content,
        })
    }

    fn permission_payload() -> Value {
        json!({
            "schema_version": 1,
            "session_id": "s1",
            "request_seq": 3,
            "title": "Run the tests",
            "options": [
                { "option_id": "allow_once", "name": "Allow once" },
                { "option_id": "reject", "name": "Reject" }
            ]
        })
    }

    #[test]
    fn a_message_is_titled_by_its_sender_and_subtitled_by_the_parsed_room() {
        let note = notify(message(
            json!({ "msgtype": "m.text", "body": "  hello\n there " }),
        ));
        assert_eq!(note.suppress, None);
        assert_eq!(note.category, NotificationCategory::Message);
        assert_eq!(note.title, "Agent Hermes");
        // `RoomIdentity.name`: the glyph and the role are not the name.
        assert_eq!(note.subtitle.as_deref(), Some("Hermes"));
        assert_eq!(note.body, "hello\n there");
        assert_eq!(note.thread_id, "!r:hs");
    }

    #[test]
    fn a_permission_request_embedded_in_prose_notifies_as_a_permission() {
        let note = notify(message(json!({
            "msgtype": "m.text",
            "body": "Allow Run the tests? 1 allow once, 2 reject",
            "dev.agentpod.permission": permission_payload(),
        })));
        assert_eq!(note.category, NotificationCategory::Permission);
        assert_eq!(note.title, "Hermes");
        assert_eq!(note.subtitle.as_deref(), Some("Permission"));
        assert_eq!(note.body, "Allow Run the tests?");
        assert_eq!(
            note.permission,
            Some(PermissionAnswers {
                allow_option_id: "Allow once".into(),
                reject_option_id: "Reject".into(),
            })
        );
    }

    #[test]
    fn the_separate_permission_event_notifies_the_same_way() {
        let mut content = permission_payload();
        content["body"] = json!("Allow Run the tests?");
        let note = notify(json!({
            "type": "dev.agentpod.permission.v1",
            "event_id": "$e",
            "sender": "@agent_hermes:hs",
            "origin_server_ts": 1,
            "content": content,
        }));
        assert_eq!(note.category, NotificationCategory::Permission);
        assert_eq!(note.body, "Allow Run the tests?");
    }

    #[test]
    fn an_embedded_gate_notifies_as_a_gate() {
        let note = notify(message(json!({
            "msgtype": "m.text",
            "body": "Approve Ship v2?",
            "dev.superpipeline.gate": {
                "schema_version": 1,
                "gate_id": "gate-9",
                "card_title": "Ship v2",
                "options": [{ "id": "approve" }, { "id": "request_changes" }, { "id": "reject" }]
            }
        })));
        assert_eq!(note.category, NotificationCategory::Gate);
        let gate = note.gate.expect("a gate with answers");
        assert_eq!(gate.gate_id, "gate-9");
        assert_eq!(
            gate.option_ids,
            vec!["approve", "request_changes", "reject"]
        );
        assert_eq!(note.body, "Approve \"Ship v2\"?");
    }

    #[test]
    fn edits_reactions_and_redactions_are_not_shown() {
        let edit = notify(message(json!({
            "msgtype": "m.text",
            "body": "* fixed",
            "m.new_content": { "msgtype": "m.text", "body": "fixed" },
            "m.relates_to": { "rel_type": "m.replace", "event_id": "$orig" }
        })));
        assert_eq!(edit.suppress, Some(NotificationSuppression::Edit));

        let reaction = notify(json!({
            "type": "m.reaction", "event_id": "$e", "sender": "@agent_hermes:hs",
            "origin_server_ts": 1,
            "content": { "m.relates_to": { "rel_type": "m.annotation", "event_id": "$x", "key": "👍" } }
        }));
        assert_eq!(reaction.suppress, Some(NotificationSuppression::Reaction));

        let redaction = notify(json!({
            "type": "m.room.redaction", "event_id": "$e", "sender": "@agent_hermes:hs",
            "origin_server_ts": 1, "redacts": "$x", "content": { "redacts": "$x" }
        }));
        assert_eq!(redaction.suppress, Some(NotificationSuppression::Redaction));
    }

    #[test]
    fn this_accounts_own_message_is_not_shown() {
        let mut own = message(json!({ "msgtype": "m.text", "body": "from my laptop" }));
        own["sender"] = json!("@me:hs");
        assert_eq!(notify(own).suppress, Some(NotificationSuppression::Own));
    }

    #[test]
    fn a_turn_card_is_context_not_news() {
        let note = notify(json!({
            "type": "dev.agentpod.turn.v1", "event_id": "$e", "sender": "@agent_hermes:hs",
            "origin_server_ts": 1,
            "content": {
                "schema_version": 1, "session_id": "s", "body": "Used 2 tools",
                "tools": [{ "title": "Read", "status": "completed" }],
                "counts": { "total": 1, "failed": 0, "omitted": 0 }
            }
        }));
        assert_eq!(note.suppress, Some(NotificationSuppression::NotNews));
    }

    fn voice_note(key: Option<Value>) -> Value {
        let mut content = json!({
            "msgtype": "m.audio", "body": "Voice message.ogg", "url": "mxc://hs/a",
            "info": { "mimetype": "audio/ogg", "duration": 4210 },
            "org.matrix.msc3245.voice": {}
        });
        if let Some(key) = key {
            content["dev.agentpod.voice_reply"] = key;
        }
        message(content)
    }

    /// An agent's answer, spoken, comes after the text it speaks, which
    /// already notified — so it is quiet, like a transcript, and says so.
    #[test]
    fn a_voice_reply_is_not_a_second_notification() {
        let note = notify(voice_note(Some(json!({
            "schema_version": 1, "text_event_id": "$text", "voice": "bf_emma", "seconds": 4
        }))));
        assert_eq!(note.suppress, Some(NotificationSuppression::NotNews));
        assert_eq!(
            note.fallback_body.as_deref(),
            Some("Agent Hermes replied with a voice message")
        );
    }

    /// Someone's own voice note is news, and so is one whose key is not the
    /// contract: only the hub's voice reply is quiet.
    #[test]
    fn an_ordinary_voice_note_still_notifies() {
        let broken = json!({ "schema_version": 2, "text_event_id": "$t", "voice": "v" });
        for key in [None, Some(broken)] {
            let note = notify(voice_note(key.clone()));
            assert_eq!(note.suppress, None, "{key:?}");
            assert_eq!(note.body, "Voice message");
        }
    }

    #[test]
    fn a_still_encrypted_message_says_so_from_its_sender() {
        let note = notify(json!({
            "type": "m.room.encrypted", "event_id": "$e", "sender": "@agent_hermes:hs",
            "origin_server_ts": 1,
            "content": {
                "algorithm": "m.megolm.v1.aes-sha2", "ciphertext": "AAAA",
                "sender_key": "k", "device_id": "D", "session_id": "S"
            }
        }));
        assert_eq!(note.suppress, None);
        assert_eq!(note.body, ENCRYPTED_BODY);
        assert_eq!(note.title, "Agent Hermes");
    }

    #[test]
    fn an_invitation_names_who_sent_it() {
        let fetched = FetchedEvent {
            room_id: "!r:hs",
            event_id: "$e",
            room_display_name: "Launch",
            sender_display_name: None,
            raw: None,
            event: None,
            invited_by: Some(user_id!("@agent_strategy-sam:hs")),
        };
        let note = notification_for_event(&fetched, user_id!("@me:hs"));
        assert_eq!(note.title, "Launch");
        assert_eq!(note.body, "Strategy Sam invited you");
    }

    fn gate_outcome() -> Value {
        json!({
            "suite_event_type": crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
            "gate_id": "gate-9",
            "board_id": "brd_1",
            "decision": "approve",
            "decided_by": "rakesh",
            "m.relates_to": { "rel_type": "m.reference", "event_id": "$gate" }
        })
    }

    // --- the quiet line a suppressed notification shows when it cannot be
    // dropped (TestFlight build 30 showed these blank)

    fn reaction(key: &str) -> Value {
        json!({
            "type": "m.reaction", "event_id": "$e", "sender": "@agent_hermes:hs",
            "origin_server_ts": 1,
            "content": { "m.relates_to": { "rel_type": "m.annotation", "event_id": "$x", "key": key } }
        })
    }

    fn turn(counts: Value) -> Value {
        json!({
            "type": "dev.agentpod.turn.v1", "event_id": "$e", "sender": "@agent_hermes:hs",
            "origin_server_ts": 1,
            "content": {
                "schema_version": 1, "session_id": "s", "body": "Used tools",
                "tools": [{ "title": "Read", "status": "completed" }],
                "counts": counts
            }
        })
    }

    #[test]
    fn a_gate_outcome_in_prose_notifies_as_the_sentence_it_is() {
        let mut content = gate_outcome();
        content["msgtype"] = json!("m.text");
        content["body"] = json!("Approved by rakesh — the board has it.");
        let note = notify(message(content));
        assert_eq!(note.suppress, None);
        assert_eq!(note.category, NotificationCategory::Message);
        assert_eq!(note.gate, None);
        assert_eq!(note.permission, None);
        assert_eq!(note.body, "Approved by rakesh — the board has it.");
    }

    #[test]
    fn a_gate_outcome_event_is_not_news() {
        let note = notify(json!({
            "type": crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
            "event_id": "$e",
            "sender": "@agent_hermes:hs",
            "origin_server_ts": 1,
            "content": gate_outcome(),
        }));
        assert_eq!(note.suppress, Some(NotificationSuppression::NotNews));
        assert_eq!(note.category, NotificationCategory::Message);
        assert_eq!(note.gate, None);
        assert_eq!(
            note.fallback_body.as_deref(),
            Some("Agent Hermes posted an update")
        );
    }

    #[test]
    fn a_reaction_says_who_reacted_with_what() {
        let note = notify(reaction("✅"));
        assert_eq!(note.suppress, Some(NotificationSuppression::Reaction));
        assert_eq!(
            note.fallback_body.as_deref(),
            Some("Agent Hermes reacted ✅ to a message")
        );
        // The room by its `RoomIdentity.name`, as every other notification.
        assert_eq!(note.fallback_title.as_deref(), Some("Hermes"));
        // The text fields stay the generic ones: they are not what to show.
        assert_eq!(note.body, GENERIC_BODY);
    }

    #[test]
    fn a_long_reaction_key_is_cut_and_an_empty_one_is_not_quoted() {
        let long = "a".repeat(40);
        let note = notify(reaction(&long));
        assert_eq!(
            note.fallback_body.as_deref(),
            Some(format!("Agent Hermes reacted {}… to a message", "a".repeat(16)).as_str())
        );
        let exact = "b".repeat(16);
        assert_eq!(
            notify(reaction(&exact)).fallback_body.as_deref(),
            Some(format!("Agent Hermes reacted {exact} to a message").as_str())
        );
        assert_eq!(
            notify(reaction("  ")).fallback_body.as_deref(),
            Some("Agent Hermes reacted to a message")
        );
    }

    #[test]
    fn a_finished_turn_says_how_many_steps() {
        let note = notify(turn(json!({ "total": 4, "failed": 0, "omitted": 0 })));
        assert_eq!(note.suppress, Some(NotificationSuppression::NotNews));
        assert_eq!(
            note.fallback_body.as_deref(),
            Some("Agent Hermes finished · 4 steps")
        );
        assert_eq!(
            notify(turn(json!({ "total": 1 }))).fallback_body.as_deref(),
            Some("Agent Hermes finished · 1 step")
        );
        assert_eq!(
            notify(turn(json!({ "total": 3, "failed": 1 })))
                .fallback_body
                .as_deref(),
            Some("Agent Hermes finished · 3 steps, 1 failed")
        );
        // Counts that are not numbers are not guessed at.
        assert_eq!(
            notify(turn(json!({ "total": "many" })))
                .fallback_body
                .as_deref(),
            Some("Agent Hermes finished a turn")
        );
    }

    #[test]
    fn edits_removals_and_state_say_what_happened() {
        let edit = notify(message(json!({
            "msgtype": "m.text",
            "body": "* fixed",
            "m.new_content": { "msgtype": "m.text", "body": "fixed" },
            "m.relates_to": { "rel_type": "m.replace", "event_id": "$orig" }
        })));
        assert_eq!(
            edit.fallback_body.as_deref(),
            Some("Agent Hermes edited a message")
        );

        let redaction = notify(json!({
            "type": "m.room.redaction", "event_id": "$e", "sender": "@agent_hermes:hs",
            "origin_server_ts": 1, "redacts": "$x", "content": { "redacts": "$x" }
        }));
        assert_eq!(
            redaction.fallback_body.as_deref(),
            Some("Agent Hermes removed a message")
        );

        let state = notify(json!({
            "type": "m.room.topic", "event_id": "$e", "sender": "@agent_hermes:hs",
            "origin_server_ts": 1, "state_key": "", "content": { "topic": "t" }
        }));
        assert_eq!(state.suppress, Some(NotificationSuppression::NotNews));
        assert_eq!(
            state.fallback_body.as_deref(),
            Some("Agent Hermes updated the room")
        );
    }

    #[test]
    fn this_accounts_own_event_says_only_the_minimum() {
        let mut own = message(json!({ "msgtype": "m.text", "body": "from my laptop" }));
        own["sender"] = json!("@me:hs");
        let note = notify(own);
        assert_eq!(note.fallback_body.as_deref(), Some(QUIET_OWN_BODY));
        // Nothing the reader wrote is repeated back to them.
        assert!(!note.fallback_body.unwrap().contains("laptop"));
    }

    #[test]
    fn a_fallback_is_carried_exactly_when_the_notification_is_suppressed() {
        let shown = notify(message(json!({ "msgtype": "m.text", "body": "hi" })));
        assert_eq!(shown.suppress, None);
        assert_eq!(shown.fallback_title, None);
        assert_eq!(shown.fallback_body, None);

        let filtered = NotificationDto::suppressed(
            "!r:hs",
            "$e",
            NotificationSuppression::Filtered,
            Some(""),
            QUIET_FILTERED_BODY.to_string(),
        );
        // An empty title is no title: the push keeps its own.
        assert_eq!(filtered.fallback_title, None);
        assert_eq!(filtered.fallback_body.as_deref(), Some(QUIET_FILTERED_BODY));
    }

    // --- what a push means for the widgets (`crate::widget`)

    fn activity_of(json: Value) -> NotificationActivity {
        notify(json).activity.expect("an activity")
    }

    #[test]
    fn a_decision_push_is_a_decision_from_its_sender_at_its_time() {
        let mut event = message(json!({
            "msgtype": "m.text",
            "body": "Allow Run the tests? 1 allow once, 2 reject",
            "dev.agentpod.permission": permission_payload(),
        }));
        event["origin_server_ts"] = json!(1_700_000_000_123u64);
        let activity = activity_of(event);
        assert_eq!(activity.kind, ActivityKind::Decision);
        assert_eq!(activity.sender, "@agent_hermes:hs");
        assert_eq!(activity.at_ms, 1_700_000_000_123);
        // `RoomIdentity.name`, and a glyph and a role make it an agent's.
        assert_eq!(activity.room_name, "Hermes");
        assert!(activity.room_is_agent);
    }

    #[test]
    fn a_message_push_carries_its_line() {
        let activity = activity_of(message(json!({ "msgtype": "m.text", "body": "Deployed" })));
        assert_eq!(activity.kind, ActivityKind::Message);
        assert_eq!(activity.line.as_deref(), Some("Deployed"));
    }

    #[test]
    fn a_turn_card_push_says_it_finished_without_the_name() {
        let activity = activity_of(turn(json!({ "total": 4, "failed": 1 })));
        assert_eq!(activity.kind, ActivityKind::TurnFinished);
        assert_eq!(
            activity.line.as_deref(),
            Some("finished · 4 steps, 1 failed")
        );
    }

    #[test]
    fn a_receipt_in_either_form_names_its_gate_and_reference() {
        let custom = activity_of(json!({
            "type": crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
            "event_id": "$e", "sender": "@agent_hermes:hs", "origin_server_ts": 1,
            "content": gate_outcome(),
        }));
        let mut prose = gate_outcome();
        prose["msgtype"] = json!("m.text");
        prose["body"] = json!("Approved by rakesh");
        let prose = activity_of(message(prose));
        for activity in [custom, prose] {
            assert_eq!(activity.kind, ActivityKind::GateOutcome);
            assert_eq!(activity.gate_id.as_deref(), Some("gate-9"));
            assert_eq!(activity.references.as_deref(), Some("$gate"));
        }
    }

    #[test]
    fn this_accounts_own_answers_are_read_back() {
        let mut said = message(json!({ "msgtype": "m.text", "body": "Allow once" }));
        said["sender"] = json!("@me:hs");
        let activity = activity_of(said);
        assert_eq!(activity.kind, ActivityKind::OwnAnswer);
        assert_eq!(activity.line.as_deref(), Some("Allow once"));
        assert_eq!(activity.option_id, None);

        let mut decided = message(json!({
            "msgtype": "m.text",
            "body": "Approved — Ship v2",
            "suite_event_type": crate::timeline::GATE_DECISION_SUITE_TYPE,
            "gate_id": "gate-9",
            "option_id": "approve",
            "m.relates_to": { "rel_type": "m.reference", "event_id": "$gate" }
        }));
        decided["sender"] = json!("@me:hs");
        let activity = activity_of(decided);
        assert_eq!(activity.kind, ActivityKind::OwnAnswer);
        assert_eq!(activity.gate_id.as_deref(), Some("gate-9"));
        assert_eq!(activity.option_id.as_deref(), Some("approve"));
        assert_eq!(activity.references.as_deref(), Some("$gate"));
    }

    #[test]
    fn reactions_edits_and_state_change_nothing_a_widget_shows() {
        assert_eq!(notify(reaction("👍")).activity, None);
        let edit = notify(message(json!({
            "msgtype": "m.text",
            "body": "* fixed",
            "m.new_content": { "msgtype": "m.text", "body": "fixed" },
            "m.relates_to": { "rel_type": "m.replace", "event_id": "$orig" }
        })));
        assert_eq!(edit.activity, None);
        let state = notify(json!({
            "type": "m.room.topic", "event_id": "$e", "sender": "@agent_hermes:hs",
            "origin_server_ts": 1, "state_key": "", "content": { "topic": "t" }
        }));
        assert_eq!(state.activity, None);
    }

    #[test]
    fn category_identifiers_are_the_registered_ones() {
        assert_eq!(NotificationCategory::Message.identifier(), "MESSAGE");
        assert_eq!(NotificationCategory::Permission.identifier(), "PERMISSION");
        assert_eq!(NotificationCategory::Gate.identifier(), "GATE");
        assert_eq!(NotificationCategory::Decision.identifier(), "DECISION");
    }
}
