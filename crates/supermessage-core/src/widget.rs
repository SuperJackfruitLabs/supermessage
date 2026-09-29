//! What the home- and Lock Screen widgets show, decided once for every
//! process that writes it.
//!
//! Three processes write the widgets' snapshot on iOS, and they must agree
//! about what it says:
//!
//! - **the app**, while it runs, from the roster ([`apply_roster`]) and the
//!   open room ([`apply_timeline`]) — the authoritative picture of who the
//!   agents are and what each last said;
//! - **the Notification Service Extension**, once per push
//!   ([`apply_notification`]) — the only writer while the app is suspended,
//!   which since the app pauses sync in the background is most of the time;
//! - **a widget's button**, through the app ([`mark_answered`],
//!   [`answer_for`]).
//!
//! Each hands in the stored snapshot (JSON, from a file in the App Group the
//! host reads and writes under a lock) and gets back the next one plus whether
//! anything changed and whether the widgets are worth reloading. The host
//! decides nothing: which push is a decision, what a line says, when an agent
//! goes idle, which answers a button may send, and when a stored write is
//! older than the one it would replace are all here.
//!
//! ## Answered is not resolved
//!
//! A tap on a widget's Approve delivers a decision; only the board's receipt
//! (`dev.superpipeline.gate.outcome.v1`, see [`crate::gate_outcome`]) says the
//! gate is over. So a tapped gate stays in the snapshot, marked answered and
//! out of the count, until that receipt is pushed or read — and says
//! "waiting for the board", never "Approved". A permission request has no
//! receipt; the answer is the message itself, so once sent it is shown as
//! sent and ages out.
//!
//! ## Time
//!
//! A widget is drawn at times nobody is writing: WidgetKit asks for a
//! timeline and shows each entry when its date comes. An agent that was
//! "active" at the last write is "idle" fifteen minutes later with nothing
//! having happened. So the snapshot carries [`WidgetFrame`]s — the counts,
//! the fleet line and every agent's state from each moment one of them
//! changes — and the widget shows the frame whose time has come, rather than
//! working out a roster rule of its own.

use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};

use crate::dto::{Membership, RoomRow, TimelineRow};
use crate::notification::{ActivityKind, NotificationCategory, NotificationDto};
use crate::roster::{AgentState, ACTIVE_WITHIN_MS, QUIET_AFTER_MS};

/// The snapshot's shape. A stored snapshot of another schema is not read:
/// the next write starts afresh rather than guessing at old fields.
pub const WIDGET_SCHEMA: u32 = 2;

/// How many decisions the snapshot holds. The large widget shows three; the
/// rest are what the count is made of.
pub const MAX_DECISIONS: usize = 5;

/// How many agents the snapshot holds — the large family's rows.
pub const MAX_AGENTS: usize = 6;

/// How long an agent's latest line may be. One line on a medium widget.
pub const LINE_MAX_CHARS: usize = 90;

/// How long a decision's question may be. Two lines on a medium widget.
pub const QUESTION_MAX_CHARS: usize = 120;

/// How long an answered permission stays on the widget, saying it was sent.
/// It has no receipt to wait for; this is only so the tap visibly landed.
pub const ANSWERED_PERMISSION_KEEP_MS: u64 = 10 * 60 * 1000;

/// How long an answered gate waits for the board's receipt before it leaves
/// the widget. A day: past it, the room is the place to find out.
pub const ANSWERED_GATE_KEEP_MS: u64 = 24 * 60 * 60 * 1000;

/// How often a change that only moves an agent's line may reload the
/// widgets. WidgetKit budgets reloads from the background, and an agent can
/// speak every few seconds; a decision arriving or leaving always reloads.
pub const LINE_RELOAD_EVERY_MS: u64 = 5 * 60 * 1000;

/// How many frames a snapshot carries. Two per agent plus now covers every
/// change the roster rule can make; the bound is only a backstop.
pub const MAX_FRAMES: usize = 1 + 2 * MAX_AGENTS;

// ---------------------------------------------------------------------------
// The snapshot
// ---------------------------------------------------------------------------

/// Everything the widgets draw. Serialized as camelCase JSON into the App
/// Group; the widget extension decodes it with its own `Codable` mirror,
/// which a Kit test pins against this.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WidgetSnapshot {
    pub schema: u32,
    /// Bumped by every write that changed something.
    pub revision: u64,
    pub updated_at_ms: u64,
    /// When the roster behind [`Self::agents`] was read by the app — `0`
    /// until the app has written one. A roster write older than this loses.
    pub roster_at_ms: u64,
    /// When a write last asked the widgets to reload.
    pub reloaded_at_ms: u64,
    pub signed_in: bool,
    /// Newest first, at most [`MAX_DECISIONS`].
    pub decisions: Vec<WidgetDecision>,
    /// Whether a decision was pushed out of [`Self::decisions`] by newer
    /// ones and has not been seen resolved — the count is then a floor.
    pub overflow: bool,
    /// Agent rooms, most recently active first, at most [`MAX_AGENTS`].
    pub agents: Vec<WidgetAgent>,
    /// Rooms the roster says owe the reader an answer that no decision above
    /// describes.
    pub roster_waiting: u32,
    /// What to draw from each moment on; the first is from the write.
    pub frames: Vec<WidgetFrame>,
}

/// Which kind of question a decision is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum WidgetDecisionKind {
    /// An AgentPod permission request: Allow once / Reject.
    Permission,
    /// A superpipeline gate: Approve / Request changes / Reject.
    Gate,
    /// A decision the widget cannot answer — it must be read in the app.
    Open,
}

/// One answer a decision offers.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WidgetOption {
    /// What is sent: a permission option's name, or a gate's decision id.
    pub id: String,
    pub label: String,
    /// Whether a widget button may send it. "Request changes" needs typed
    /// feedback, which a widget cannot take, so it opens the card instead.
    pub inline: bool,
    /// Whether it refuses rather than grants — drawn as the quieter button.
    pub declines: bool,
}

/// An answer this device sent and nothing has yet said is over.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WidgetAnswered {
    pub option_id: String,
    pub at_ms: u64,
    /// What the widget says in place of the buttons: "Sent: Allow once",
    /// "Sent: Approve · waiting for the board". Never the outcome.
    pub line: String,
}

/// One decision the reader owes, or has just answered.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WidgetDecision {
    pub kind: WidgetDecisionKind,
    pub room_id: String,
    /// The event the card is — and, for a gate, the one its decision
    /// references.
    pub event_id: String,
    /// The agent's or the board's name: the room's `RoomIdentity.name`.
    pub agent: String,
    /// Who asked, so only their receipt closes it (the rule
    /// [`crate::gate_outcome`] applies to cards).
    pub asker: Option<String>,
    /// The question, bounded to [`QUESTION_MAX_CHARS`].
    pub question: String,
    pub options: Vec<WidgetOption>,
    /// A gate's `gate_id`.
    pub gate_id: Option<String>,
    /// A gate's question as the card had it, unbounded — the sentence left in
    /// the room is derived from it when the widget answers.
    pub prompt: String,
    pub asked_at_ms: u64,
    pub answered: Option<WidgetAnswered>,
}

impl WidgetDecision {
    fn pending(&self) -> bool {
        self.answered.is_none()
    }
}

/// One agent room.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WidgetAgent {
    pub room_id: String,
    pub name: String,
    pub last_activity_ms: Option<u64>,
    /// What it last said, as the roster previews it, bounded to
    /// [`LINE_MAX_CHARS`]. `None` when there is nothing to say.
    pub line: Option<String>,
    /// The step of a turn the app watched in progress.
    pub step: Option<String>,
    /// Whether the roster itself said this room owes an answer.
    pub roster_needs_you: bool,
}

/// How an agent's state is drawn. Carried beside the word so a host colours
/// by meaning rather than by matching text.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum WidgetTone {
    NeedsYou,
    Working,
    Active,
    Idle,
    Quiet,
}

/// An agent's state within one frame.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WidgetAgentState {
    /// "needs you", "working", "active", "idle", "quiet".
    pub word: String,
    pub tone: WidgetTone,
}

/// What to draw from `from_ms` until the next frame.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WidgetFrame {
    pub from_ms: u64,
    /// Decisions still owed — answered ones do not count.
    pub needs_you: u32,
    /// "3" or, when some were pushed out, "5+".
    pub needs_you_count: String,
    /// "1 needs you", "3 need you", "Nothing needs you".
    pub needs_you_line: String,
    /// Agents working or recently active.
    pub working: u32,
    /// The fleet in one line, for the inline Lock Screen widget:
    /// "2 working · 1 needs you", "All quiet".
    pub pulse: String,
    /// One per [`WidgetSnapshot::agents`], in order.
    pub states: Vec<WidgetAgentState>,
}

impl WidgetSnapshot {
    /// Nothing known yet, for an account that is (`signed_in`) or is not.
    pub fn empty(signed_in: bool) -> Self {
        Self {
            schema: WIDGET_SCHEMA,
            revision: 0,
            updated_at_ms: 0,
            roster_at_ms: 0,
            reloaded_at_ms: 0,
            signed_in,
            decisions: Vec::new(),
            overflow: false,
            agents: Vec::new(),
            roster_waiting: 0,
            frames: Vec::new(),
        }
    }

    /// The stored snapshot, or `None` when there is none, it does not parse,
    /// or it is another schema's.
    pub fn decode(json: Option<&str>) -> Option<Self> {
        let snapshot: Self = serde_json::from_str(json?).ok()?;
        (snapshot.schema == WIDGET_SCHEMA).then_some(snapshot)
    }

    fn encode(&self) -> String {
        serde_json::to_string(self).expect("plain data always serialises")
    }

    /// The part of the snapshot a widget draws — everything but when it was
    /// written — so an unchanged picture is not rewritten.
    fn picture(&self) -> impl PartialEq + '_ {
        (
            self.signed_in,
            &self.decisions,
            self.overflow,
            &self.agents,
            self.roster_waiting,
        )
    }
}

/// What a host does with a write.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WidgetWrite {
    /// The snapshot to store. Identical to what was stored when `changed` is
    /// false, so writing it anyway is harmless.
    pub json: String,
    pub changed: bool,
    /// Whether to ask WidgetKit to reload. Only ever with `changed`.
    pub reload: bool,
}

/// A turn the app is watching, for [`apply_roster`].
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WidgetLiveTurn {
    pub room_id: String,
    pub step: String,
}

/// What a widget button sends, for an answer the snapshot still owes: the
/// same facts a notification action carries.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WidgetAnswer {
    pub room_id: String,
    pub event_id: String,
    pub option_id: String,
    /// `Some` for a gate: its `gate_id` and question.
    pub gate_id: Option<String>,
    pub prompt: String,
}

// ---------------------------------------------------------------------------
// Words
// ---------------------------------------------------------------------------

fn bound(text: &str, max_chars: usize) -> Option<String> {
    let collapsed = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.is_empty() {
        return None;
    }
    if collapsed.chars().count() <= max_chars {
        return Some(collapsed);
    }
    let mut cut: String = collapsed.chars().take(max_chars).collect();
    cut.push('…');
    Some(cut)
}

fn capitalised(text: &str) -> String {
    let mut chars = text.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

fn gate_label(id: &str) -> &'static str {
    match id {
        "approve" => "Approve",
        "request_changes" => "Request changes",
        _ => "Reject",
    }
}

fn answered_line(decision: &WidgetDecision, option_id: &str) -> String {
    let label = decision
        .options
        .iter()
        .find(|o| o.id == option_id)
        .map(|o| o.label.clone())
        .unwrap_or_else(|| option_id.to_string());
    match decision.kind {
        WidgetDecisionKind::Gate => format!("Sent: {label} · waiting for the board"),
        _ => format!("Sent: {label}"),
    }
}

fn needs_you_line(count: u32, overflow: bool) -> String {
    match (count, overflow) {
        (0, _) => "Nothing needs you".to_string(),
        (n, true) => format!("{n}+ need you"),
        (1, false) => "1 needs you".to_string(),
        (n, false) => format!("{n} need you"),
    }
}

fn pulse(working: u32, needs_you: u32, overflow: bool) -> String {
    let asks = (needs_you > 0).then(|| needs_you_line(needs_you, overflow));
    match (working, asks) {
        (0, None) => "All quiet".to_string(),
        (0, Some(asks)) => asks,
        (n, None) => format!("{n} working"),
        (n, Some(asks)) => format!("{n} working · {asks}"),
    }
}

// ---------------------------------------------------------------------------
// Frames
// ---------------------------------------------------------------------------

fn agent_state(agent: &WidgetAgent, decisions: &[WidgetDecision], at_ms: u64) -> WidgetAgentState {
    let owes = agent.roster_needs_you
        || decisions
            .iter()
            .any(|d| d.pending() && d.room_id == agent.room_id);
    let state = |word: &str, tone| WidgetAgentState {
        word: word.to_string(),
        tone,
    };
    if owes {
        return state(AgentState::NeedsYou.word(), WidgetTone::NeedsYou);
    }
    let elapsed = agent
        .last_activity_ms
        .map(|last| at_ms.saturating_sub(last));
    // A step is only "working" while the turn is recent: a step the app saw
    // an hour ago and never saw finish says nothing about now.
    if agent.step.is_some() && elapsed.is_some_and(|e| e <= ACTIVE_WITHIN_MS) {
        return state("working", WidgetTone::Working);
    }
    match elapsed {
        Some(e) if e <= ACTIVE_WITHIN_MS => state(AgentState::Active.word(), WidgetTone::Active),
        Some(e) if e <= QUIET_AFTER_MS => state(AgentState::Idle.word(), WidgetTone::Idle),
        _ => state(AgentState::Quiet.word(), WidgetTone::Quiet),
    }
}

fn frame_at(snapshot: &WidgetSnapshot, at_ms: u64) -> WidgetFrame {
    let states: Vec<WidgetAgentState> = snapshot
        .agents
        .iter()
        .map(|a| agent_state(a, &snapshot.decisions, at_ms))
        .collect();
    let needs_you =
        snapshot.decisions.iter().filter(|d| d.pending()).count() as u32 + snapshot.roster_waiting;
    let working = states
        .iter()
        .filter(|s| matches!(s.tone, WidgetTone::Working | WidgetTone::Active))
        .count() as u32;
    WidgetFrame {
        from_ms: at_ms,
        needs_you,
        needs_you_count: if snapshot.overflow && needs_you > 0 {
            format!("{needs_you}+")
        } else {
            needs_you.to_string()
        },
        needs_you_line: needs_you_line(needs_you, snapshot.overflow),
        working,
        pulse: pulse(working, needs_you, snapshot.overflow),
        states,
    }
}

/// Every moment after `now_ms` at which some agent's state changes: fifteen
/// minutes after it last spoke, and a day after.
fn frames(snapshot: &WidgetSnapshot, now_ms: u64) -> Vec<WidgetFrame> {
    let mut moments: Vec<u64> = snapshot
        .agents
        .iter()
        .filter_map(|a| a.last_activity_ms)
        .flat_map(|last| [last + ACTIVE_WITHIN_MS + 1, last + QUIET_AFTER_MS + 1])
        .filter(|at| *at > now_ms)
        .collect();
    moments.sort_unstable();
    moments.dedup();
    std::iter::once(now_ms)
        .chain(moments)
        .take(MAX_FRAMES)
        .map(|at| frame_at(snapshot, at))
        .collect()
}

// ---------------------------------------------------------------------------
// Writing
// ---------------------------------------------------------------------------

/// Finish a write: prune, bound, frame, and say whether it changed anything
/// and is worth a reload.
fn finish(stored: Option<WidgetSnapshot>, mut next: WidgetSnapshot, now_ms: u64) -> WidgetWrite {
    next.decisions.retain(|d| match (&d.answered, d.kind) {
        (None, _) => true,
        (Some(a), WidgetDecisionKind::Gate) => {
            now_ms.saturating_sub(a.at_ms) < ANSWERED_GATE_KEEP_MS
        }
        (Some(a), _) => now_ms.saturating_sub(a.at_ms) < ANSWERED_PERMISSION_KEEP_MS,
    });
    next.decisions
        .sort_by_key(|d| std::cmp::Reverse(d.asked_at_ms));
    if next.decisions.len() > MAX_DECISIONS {
        if next.decisions[MAX_DECISIONS..]
            .iter()
            .any(WidgetDecision::pending)
        {
            next.overflow = true;
        }
        next.decisions.truncate(MAX_DECISIONS);
    }
    if !next.decisions.iter().any(WidgetDecision::pending) {
        // Nothing owed that we know of, so nothing unseen can be counted.
        next.overflow = false;
    }
    next.agents
        .sort_by_key(|a| std::cmp::Reverse(a.last_activity_ms.unwrap_or(0)));
    next.agents.truncate(MAX_AGENTS);

    // Nothing stored yet is always a change: the first write is news.
    let before = stored.unwrap_or_else(|| WidgetSnapshot::empty(next.signed_in));
    let changed = before.picture() != next.picture() || before.revision == 0;
    if !changed {
        return WidgetWrite {
            json: before.encode(),
            changed: false,
            reload: false,
        };
    }
    next.frames = frames(&next, now_ms);
    let counts_moved = before.decisions != next.decisions
        || before.signed_in != next.signed_in
        || before.roster_waiting != next.roster_waiting
        || before.overflow != next.overflow;
    let reload =
        counts_moved || now_ms.saturating_sub(before.reloaded_at_ms) >= LINE_RELOAD_EVERY_MS;
    next.schema = WIDGET_SCHEMA;
    next.revision = before.revision + 1;
    next.updated_at_ms = now_ms;
    if reload {
        next.reloaded_at_ms = now_ms;
    } else {
        next.reloaded_at_ms = before.reloaded_at_ms;
    }
    WidgetWrite {
        json: next.encode(),
        changed: true,
        reload,
    }
}

/// The agent entry for `room_id`, added when the room reads as an agent's
/// and there is room for it.
fn agent_mut<'a>(
    snapshot: &'a mut WidgetSnapshot,
    room_id: &str,
    name: &str,
    is_agent: bool,
) -> Option<&'a mut WidgetAgent> {
    if let Some(index) = snapshot.agents.iter().position(|a| a.room_id == room_id) {
        return snapshot.agents.get_mut(index);
    }
    if !is_agent {
        return None;
    }
    snapshot.agents.push(WidgetAgent {
        room_id: room_id.to_string(),
        name: name.to_string(),
        last_activity_ms: None,
        line: None,
        step: None,
        roster_needs_you: false,
    });
    snapshot.agents.last_mut()
}

/// Say `line` at `at_ms` for an agent — unless it already said something
/// later, which a push delivered out of order must not overwrite.
fn speak(agent: &mut WidgetAgent, line: Option<String>, at_ms: u64) -> bool {
    if agent.last_activity_ms.is_some_and(|last| last > at_ms) {
        return false;
    }
    agent.last_activity_ms = Some(at_ms);
    if line.is_some() {
        agent.line = line;
    }
    true
}

/// The decision a decided notification describes, or `None` when it is not
/// one.
fn decision_from(
    note: &NotificationDto,
    asker: Option<&str>,
    at_ms: u64,
    agent: &str,
) -> Option<WidgetDecision> {
    let question = bound(&note.body, QUESTION_MAX_CHARS).unwrap_or_default();
    let base = WidgetDecision {
        kind: WidgetDecisionKind::Open,
        room_id: note.room_id.clone(),
        event_id: note.event_id.clone(),
        agent: agent.to_string(),
        asker: asker.map(str::to_string),
        question,
        options: Vec::new(),
        gate_id: None,
        prompt: note.body.clone(),
        asked_at_ms: at_ms,
        answered: None,
    };
    match note.category {
        NotificationCategory::Message => None,
        NotificationCategory::Decision => Some(base),
        NotificationCategory::Permission => {
            let answers = note.permission.as_ref()?;
            Some(WidgetDecision {
                kind: WidgetDecisionKind::Permission,
                options: vec![
                    WidgetOption {
                        id: answers.allow_option_id.clone(),
                        label: capitalised(answers.allow_option_id.trim()),
                        inline: true,
                        declines: false,
                    },
                    WidgetOption {
                        id: answers.reject_option_id.clone(),
                        label: capitalised(answers.reject_option_id.trim()),
                        inline: true,
                        declines: true,
                    },
                ],
                ..base
            })
        }
        NotificationCategory::Gate => {
            let gate = note.gate.as_ref()?;
            Some(WidgetDecision {
                kind: WidgetDecisionKind::Gate,
                options: gate
                    .option_ids
                    .iter()
                    .map(|id| WidgetOption {
                        id: id.clone(),
                        label: gate_label(id).to_string(),
                        inline: id != "request_changes",
                        declines: id != "approve",
                    })
                    .collect(),
                gate_id: Some(gate.gate_id.clone()),
                prompt: gate.prompt.clone(),
                ..base
            })
        }
    }
}

fn start(stored: Option<&str>, signed_in: bool) -> (Option<WidgetSnapshot>, WidgetSnapshot) {
    let stored = WidgetSnapshot::decode(stored);
    let next = stored
        .clone()
        .unwrap_or_else(|| WidgetSnapshot::empty(signed_in));
    (stored, next)
}

/// One push, as the Notification Service Extension decided it.
///
/// - a permission request or a gate is added as a decision (once — the same
///   event pushed twice is one question);
/// - a message moves its agent's line and last activity, never backwards;
/// - a turn card says the turn finished;
/// - the board's receipt removes the gate it closes, from its own asker only;
/// - this account's answer from another device removes a permission request
///   it answers, and marks a gate answered (not resolved).
pub fn apply_notification(
    stored: Option<&str>,
    note: &NotificationDto,
    now_ms: u64,
) -> WidgetWrite {
    let (stored, mut next) = start(stored, true);
    // A push reaches only a signed-in extension.
    next.signed_in = true;
    let Some(activity) = note.activity.as_ref() else {
        return finish(stored, next, now_ms);
    };
    let room_id = note.room_id.as_str();
    match activity.kind {
        ActivityKind::Decision => {
            let known = next.decisions.iter().any(|d| d.event_id == note.event_id);
            if !known {
                if let Some(decision) = decision_from(
                    note,
                    Some(&activity.sender),
                    activity.at_ms,
                    &activity.room_name,
                ) {
                    let question = decision.question.clone();
                    next.decisions.push(decision);
                    if let Some(agent) = agent_mut(
                        &mut next,
                        room_id,
                        &activity.room_name,
                        activity.room_is_agent,
                    ) {
                        speak(agent, Some(question), activity.at_ms);
                    }
                }
            }
        }
        ActivityKind::Message => {
            let line = activity
                .line
                .as_deref()
                .and_then(|l| bound(l, LINE_MAX_CHARS));
            if let Some(agent) = agent_mut(
                &mut next,
                room_id,
                &activity.room_name,
                activity.room_is_agent,
            ) {
                speak(agent, line, activity.at_ms);
            }
        }
        ActivityKind::TurnFinished => {
            let line = activity
                .line
                .as_deref()
                .map(capitalised)
                .and_then(|l| bound(&l, LINE_MAX_CHARS));
            if let Some(agent) = agent_mut(
                &mut next,
                room_id,
                &activity.room_name,
                activity.room_is_agent,
            ) {
                if speak(agent, line, activity.at_ms) {
                    agent.step = None;
                }
            }
        }
        ActivityKind::GateOutcome => {
            next.decisions.retain(|d| {
                let closes = d.kind == WidgetDecisionKind::Gate
                    && d.room_id == room_id
                    && d.asker
                        .as_deref()
                        .is_none_or(|asker| asker == activity.sender)
                    && match activity.gate_id.as_deref() {
                        Some(gate) => d.gate_id.as_deref() == Some(gate),
                        None => activity.references.as_deref() == Some(d.event_id.as_str()),
                    };
                !closes
            });
        }
        ActivityKind::OwnAnswer => match activity.option_id.as_deref() {
            // A gate decision: answered, and still the board's to resolve.
            Some(option) => {
                for d in next.decisions.iter_mut().filter(|d| {
                    d.kind == WidgetDecisionKind::Gate
                        && d.room_id == room_id
                        && d.answered.is_none()
                        && ((activity.gate_id.is_some() && d.gate_id == activity.gate_id)
                            || activity.references.as_deref() == Some(d.event_id.as_str()))
                }) {
                    let line = answered_line(d, option);
                    d.answered = Some(WidgetAnswered {
                        option_id: option.to_string(),
                        at_ms: activity.at_ms,
                        line,
                    });
                }
            }
            // A permission answer is the option's name, as plain text.
            None => {
                if let Some(text) = activity.line.as_deref().map(str::trim) {
                    next.decisions.retain(|d| {
                        !(d.kind == WidgetDecisionKind::Permission
                            && d.room_id == room_id
                            && d.asked_at_ms <= activity.at_ms
                            && d.options.iter().any(|o| o.id.trim() == text))
                    });
                }
            }
        },
    }
    finish(stored, next, now_ms)
}

/// The app's roster, which replaces what pushes said about the agents.
///
/// `as_of_ms` is when `rows` were read. A roster older than the last one
/// written loses — a slow write never undoes a newer one — and an agent
/// whose pushed line is newer than the roster's keeps it. Decisions are not
/// in the roster (it cannot see a card), so they carry over, less those in
/// rooms the roster no longer has.
pub fn apply_roster(
    stored: Option<&str>,
    rows: &[RoomRow],
    live: &[WidgetLiveTurn],
    as_of_ms: u64,
    now_ms: u64,
) -> WidgetWrite {
    let (stored, mut next) = start(stored, true);
    if let Some(newer) = stored.as_ref().filter(|s| s.roster_at_ms > as_of_ms) {
        let unchanged = newer.clone();
        return finish(stored, unchanged, now_ms);
    }
    next.signed_in = true;
    next.roster_at_ms = as_of_ms;

    let mut seen = HashSet::new();
    let arranged: Vec<_> =
        crate::roster::sections(rows, crate::roster::RosterView::Recent, false, as_of_ms)
            .into_iter()
            .flat_map(|section| section.rows)
            .filter(|row| seen.insert(row.row.room.id.clone()))
            .collect();
    let previous: HashMap<&str, &WidgetAgent> = stored
        .as_ref()
        .map(|s| s.agents.iter().map(|a| (a.room_id.as_str(), a)).collect())
        .unwrap_or_default();
    let steps: HashMap<&str, &str> = live
        .iter()
        .map(|t| (t.room_id.as_str(), t.step.as_str()))
        .collect();

    next.agents = arranged
        .iter()
        .filter(|row| row.describes_agent)
        .take(MAX_AGENTS)
        .map(|row| {
            let id = row.row.room.id.as_str();
            let mut agent = WidgetAgent {
                room_id: id.to_string(),
                name: row.row.identity.name.clone(),
                last_activity_ms: row.row.room.last_activity_ms,
                line: row
                    .row
                    .preview
                    .as_ref()
                    .filter(|p| !p.pending)
                    .and_then(|p| bound(&p.text, LINE_MAX_CHARS)),
                step: steps.get(id).and_then(|s| bound(s, LINE_MAX_CHARS)),
                roster_needs_you: row.state == AgentState::NeedsYou,
            };
            if let Some(pushed) = previous.get(id) {
                if pushed.last_activity_ms > agent.last_activity_ms {
                    agent.last_activity_ms = pushed.last_activity_ms;
                    agent.line = pushed.line.clone().or(agent.line);
                }
            }
            agent
        })
        .collect();

    // Only a room the roster says this account is no longer in. A room the
    // roster does not list may simply be outside the space the reader has
    // chosen (`Session::space_select` scopes the roster), and a decision
    // there is still owed.
    let gone: HashSet<&str> = rows
        .iter()
        .filter(|r| r.room.membership != Membership::Joined)
        .map(|r| r.room.id.as_str())
        .collect();
    next.decisions
        .retain(|d| !gone.contains(d.room_id.as_str()));
    let described: HashSet<&str> = next
        .decisions
        .iter()
        .filter(|d| d.pending())
        .map(|d| d.room_id.as_str())
        .collect();
    next.roster_waiting = arranged
        .iter()
        .filter(|row| row.state == AgentState::NeedsYou)
        .filter(|row| !described.contains(row.row.room.id.as_str()))
        .count() as u32;
    finish(stored, next, now_ms)
}

/// The open room's loaded timeline, which can see what a push could not: a
/// card the board resolved while the app was reading, and a permission this
/// account answered in the room.
pub fn apply_timeline(
    stored: Option<&str>,
    room_id: &str,
    rows: &[TimelineRow],
    now_ms: u64,
) -> WidgetWrite {
    let (stored, mut next) = start(stored, true);
    let still_asked = |event_id: &str| -> Option<bool> {
        let row = rows
            .iter()
            .find(|r| r.item.event_id.as_deref() == Some(event_id))?;
        Some(
            crate::notification::notification_for_row(row, room_id, event_id, "")
                .is_some_and(|n| n.category != NotificationCategory::Message),
        )
    };
    next.decisions.retain(|d| {
        if d.room_id != room_id {
            return true;
        }
        // Not loaded: this timeline cannot say.
        if still_asked(&d.event_id) == Some(false) {
            return false;
        }
        if d.kind == WidgetDecisionKind::Permission {
            let answered = rows.iter().any(|r| {
                r.item.is_own
                    && r.item.timestamp_ms.unwrap_or(0) >= d.asked_at_ms
                    && r.item
                        .body
                        .as_deref()
                        .is_some_and(|b| d.options.iter().any(|o| o.id.trim() == b.trim()))
            });
            return !answered;
        }
        true
    });
    finish(stored, next, now_ms)
}

/// What a widget's button for `option_id` on the decision `event_id` in
/// `room_id` sends — or `None` when it must send nothing: the decision is no
/// longer in the snapshot (resolved, or never pushed), it was already
/// answered, or the option is not one a button may send.
pub fn answer_for(
    stored: Option<&str>,
    room_id: &str,
    event_id: &str,
    option_id: &str,
) -> Option<WidgetAnswer> {
    let snapshot = WidgetSnapshot::decode(stored)?;
    let decision = snapshot
        .decisions
        .iter()
        .find(|d| d.room_id == room_id && d.event_id == event_id)?;
    if !decision.pending() || decision.kind == WidgetDecisionKind::Open {
        return None;
    }
    decision
        .options
        .iter()
        .find(|o| o.id == option_id && o.inline)?;
    Some(WidgetAnswer {
        room_id: room_id.to_string(),
        event_id: event_id.to_string(),
        option_id: option_id.to_string(),
        gate_id: decision.gate_id.clone(),
        prompt: decision.prompt.clone(),
    })
}

/// Mark a decision answered with `option_id`, the moment a widget's button
/// is tapped — before the send lands, so the widget stops offering it. The
/// line says it was sent; for a gate, that the board still has to accept.
pub fn mark_answered(
    stored: Option<&str>,
    room_id: &str,
    event_id: &str,
    option_id: &str,
    now_ms: u64,
) -> WidgetWrite {
    let (stored, mut next) = start(stored, true);
    for d in next
        .decisions
        .iter_mut()
        .filter(|d| d.room_id == room_id && d.event_id == event_id && d.answered.is_none())
    {
        let line = answered_line(d, option_id);
        d.answered = Some(WidgetAnswered {
            option_id: option_id.to_string(),
            at_ms: now_ms,
            line,
        });
    }
    finish(stored, next, now_ms)
}

/// Undo [`mark_answered`]: the send failed, so the decision is owed again.
pub fn clear_answer(
    stored: Option<&str>,
    room_id: &str,
    event_id: &str,
    now_ms: u64,
) -> WidgetWrite {
    let (stored, mut next) = start(stored, true);
    for d in next
        .decisions
        .iter_mut()
        .filter(|d| d.room_id == room_id && d.event_id == event_id)
    {
        d.answered = None;
    }
    finish(stored, next, now_ms)
}

/// Signed out: nothing to show, and nothing of the last account kept.
pub fn signed_out(stored: Option<&str>, now_ms: u64) -> WidgetWrite {
    let (stored, _) = start(stored, false);
    let next = WidgetSnapshot::empty(false);
    finish(stored, next, now_ms)
}

#[cfg(test)]
mod tests;
