//! What the dock above the composer says after the reader has written to an
//! agent (A3): never a silent gap between sending and the answer.
//!
//! ```text
//! send ──▶ Sent to Atlas ──(reacts · types · starts a turn)──▶ Atlas is on it…
//!                 │                                                  │
//!                 └──(Atlas's message lands, or ✅ / ❌ on it)────────┴──▶ nothing
//! ```
//!
//! Derived, not stored: every input is already on screen somewhere — the
//! timeline, the typing notices, the live turn — so a state machine that kept
//! its own copy could only drift from them. Leaving the room and coming back
//! gives the same answer, because it is recomputed from the same facts.

use serde::{Deserialize, Serialize};

use crate::display_name::names_label;
use crate::dto::TimelineRow;

/// Which of the dock's two sentences.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum AcknowledgementKind {
    /// "Sent to Atlas" — no sign of the agent yet.
    Sent,
    /// "Atlas is on it…" — it has reacted, is typing, or has a turn going.
    OnIt,
}

/// The dock's state: which sentence, who it names, and the sentence itself.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Acknowledgement {
    pub kind: AcknowledgementKind,
    /// Who the sentence names — usually one. Empty only for a bare "On it…".
    pub names: Vec<String>,
    pub text: String,
}

/// The hub's marks for a turn that is over.
pub const FINISHED_MARKS: [&str; 2] = ["✅", "❌"];

fn sent(name: &str) -> Acknowledgement {
    Acknowledgement {
        kind: AcknowledgementKind::Sent,
        names: vec![name.to_string()],
        text: format!("Sent to {name}"),
    }
}

fn on_it(names: &[String]) -> Acknowledgement {
    let text = match names.len() {
        0 => "On it…".to_string(),
        1 => format!("{} is on it…", names[0]),
        _ => format!("{} are on it…", names_label(names)),
    };
    Acknowledgement {
        kind: AcknowledgementKind::OnIt,
        names: names.to_vec(),
        text,
    }
}

/// Whether someone other than the reader has reacted to `row` with a key
/// `wanted` accepts. A reaction on the reader's own message, in an agent's
/// room, is the agent saying it has seen it — 👀 is the usual one. The
/// reader's own reaction is one of the `count`, so it is taken off.
fn reacted_by_anyone_else(row: &TimelineRow, wanted: impl Fn(&str) -> bool) -> bool {
    row.item
        .reactions
        .iter()
        .any(|r| wanted(&r.key) && r.count > u32::from(r.by_me))
}

/// What the dock says, or `None` for nothing.
///
/// - `rows`: the timeline, oldest first.
/// - `addressee`: who a message here is for. `None` in a room with no single
///   agent, which never says "Sent to" — it can only name agents that are
///   visibly typing.
/// - `agent_ids`: the room's agents, to tell their replies from anyone else's.
///   Empty when not yet known, and then any other member's message counts as
///   the answer — the dock errs towards going away.
/// - `sent_this_session`: whether the reader has sent here since signing in
///   (the core session owns it). An unanswered message from last week is not
///   news.
/// - `typing_agents`: the agents whose typing notice is up, by name
///   (`typing_view(..).agents`).
/// - `turn_in_progress`: a live turn is streaming and has not finished.
pub fn state(
    rows: &[TimelineRow],
    addressee: Option<&str>,
    agent_ids: &[String],
    sent_this_session: bool,
    typing_agents: &[String],
    turn_in_progress: bool,
) -> Option<Acknowledgement> {
    // Working says itself whether or not the reader just sent: this is the
    // sentence that replaced "Atlas is typing…". In a room of several agents
    // there is no addressee, and whoever is typing is named.
    // **Not while a live turn is on screen.** Its card already says who is
    // working, for how long, and on what; a pill saying "Krishna is on it…"
    // under it made four "working" signals at once (2026-09-24). The pill
    // stays for an agent that only sends typing notices.
    if turn_in_progress {
        return None;
    }
    if let Some(addressee) = addressee {
        if !typing_agents.is_empty() {
            return Some(on_it(&[addressee.to_string()]));
        }
    } else if !typing_agents.is_empty() {
        return Some(on_it(typing_agents));
    }
    let addressee = addressee?;
    if !sent_this_session {
        return None;
    }
    let last_own = rows.iter().rposition(|r| r.item.is_own)?;
    // Any non-membership message from the agent after the reader's last one
    // ends the dock — an edited progress message included: an agent that
    // answers by editing one message in place (a bridge with no live channel)
    // has still put it there, and "Sent to" must not outlive it.
    let answered = rows[last_own + 1..].iter().any(|row| {
        if row.item.is_own || row.membership_verb.is_some() {
            return false;
        }
        match &row.item.sender {
            Some(sender) => agent_ids.is_empty() || agent_ids.contains(sender),
            None => false,
        }
    });
    if answered {
        return None;
    }
    // The AgentPod hub marks the message a turn answered: 👀 while it works,
    // ✅ when it is done, ❌ when it failed. A turn can end with no reply at
    // all — the agent chose silence (OpenClaw's NO_REPLY) and the hub says
    // done with ✅ alone — so a finished mark ends the dock just as a reply
    // would. Read as "on it", it said "Krishna is on it…" for a turn that was
    // over (2026-09-26).
    let own = &rows[last_own];
    if reacted_by_anyone_else(own, |k| FINISHED_MARKS.contains(&k)) {
        return None;
    }
    Some(if reacted_by_anyone_else(own, |_| true) {
        on_it(&[addressee.to_string()])
    } else {
        sent(addressee)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dto::{ReactionDto, TimelineItemDto};

    const ATLAS: &str = "@atlas:x";

    fn react(key: &str, count: u32, by_me: bool) -> ReactionDto {
        ReactionDto {
            key: key.into(),
            display_key: key.into(),
            count,
            by_me,
            senders: vec![],
        }
    }

    fn row(
        id: &str,
        own: bool,
        sender: &str,
        reactions: Vec<ReactionDto>,
        membership: Option<&str>,
    ) -> TimelineRow {
        TimelineRow::new(TimelineItemDto {
            id: id.into(),
            event_id: Some(id.into()),
            kind: if membership.is_some() {
                "membership"
            } else {
                "message"
            }
            .into(),
            msgtype: None,
            detail: membership.map(Into::into),
            sender: Some(sender.into()),
            sender_avatar: None,
            sender_display_name: None,
            body: Some("b".into()),
            formatted_body: None,
            media: None,
            custom_payload: None,
            timestamp_ms: Some(1),
            is_own: own,
            send_state: None,
            reply_to: None,
            edited: false,
            reactions,
            read_by: vec![],
            editable: false,
            membership_subject: None,
        })
    }

    fn own(id: &str) -> TimelineRow {
        row(id, true, "@me:x", vec![], None)
    }
    fn own_with(id: &str, reactions: Vec<ReactionDto>) -> TimelineRow {
        row(id, true, "@me:x", reactions, None)
    }
    fn from(id: &str, sender: &str) -> TimelineRow {
        row(id, false, sender, vec![], None)
    }

    fn agents() -> Vec<String> {
        vec![ATLAS.to_string()]
    }
    fn names(raw: &[&str]) -> Vec<String> {
        raw.iter().map(|s| s.to_string()).collect()
    }

    /// The common case: addressee Atlas, agents `[atlas]`, sent this session,
    /// nobody typing, no live turn.
    fn dock(rows: &[TimelineRow]) -> Option<Acknowledgement> {
        state(rows, Some("Atlas"), &agents(), true, &[], false)
    }

    /// Port of AcknowledgementTests (ComposerRevampTests.swift:418).
    ///
    /// Mutation seen failing: `sent()` saying "Sent to" with the id instead
    /// of the name.
    #[test]
    fn sent_with_no_sign_of_the_agent_yet() {
        let a = dock(&[own("$1")]).unwrap();
        assert_eq!(a.kind, AcknowledgementKind::Sent);
        assert_eq!(a.names, names(&["Atlas"]));
        assert_eq!(a.text, "Sent to Atlas");
    }

    /// Port of AcknowledgementTests (:423).
    ///
    /// Mutation seen failing: dropping the `turn_in_progress` early return
    /// (the third assertion saw `OnIt`); ignoring typing agents (the second
    /// saw `Sent`).
    #[test]
    fn a_reaction_or_typing_means_it_is_on_it_a_live_turn_shows_its_own_card() {
        let reacted = [own_with("$1", vec![react("👀", 1, false)])];
        let a = dock(&reacted).unwrap();
        assert_eq!(a.kind, AcknowledgementKind::OnIt);
        assert_eq!(a.names, names(&["Atlas"]));
        assert_eq!(a.text, "Atlas is on it…");

        let typing = names(&["Atlas"]);
        let a = state(&[own("$1")], Some("Atlas"), &agents(), true, &typing, false).unwrap();
        assert_eq!(a.kind, AcknowledgementKind::OnIt);

        assert_eq!(
            state(&reacted, Some("Atlas"), &agents(), true, &[], true),
            None
        );
        assert_eq!(
            state(&[own("$1")], Some("Atlas"), &agents(), true, &typing, true),
            None
        );
    }

    /// Port of AcknowledgementTests (:436).
    ///
    /// Mutation seen failing: removing the `FINISHED_MARKS` check (✅ read as
    /// `OnIt`); counting the reader's own ✅ as finished (fourth assertion).
    #[test]
    fn the_agents_done_or_failed_mark_ends_it_with_or_without_a_reply() {
        let marked = |key: &str, count, by_me| [own_with("$1", vec![react(key, count, by_me)])];
        assert_eq!(dock(&marked("✅", 1, false)), None);
        assert_eq!(dock(&marked("❌", 1, false)), None);
        assert_eq!(
            dock(&marked("✅", 1, true)).unwrap().kind,
            AcknowledgementKind::Sent
        );
        // The reader and the agent both marked it: count 2, one of them theirs.
        assert_eq!(dock(&marked("✅", 2, true)), None);
        // Typing wins over a finished mark: the agent is working on something.
        let a = state(
            &marked("✅", 1, false),
            Some("Atlas"),
            &agents(),
            true,
            &names(&["Atlas"]),
            false,
        )
        .unwrap();
        assert_eq!(a.kind, AcknowledgementKind::OnIt);
        assert_eq!(a.names, names(&["Atlas"]));
    }

    /// Port of AcknowledgementTests (:460).
    ///
    /// Mutation seen failing: `count > 0` instead of `count > by_me` (the
    /// reader's own 👍 read as `OnIt`).
    #[test]
    fn the_readers_own_reaction_is_not_the_agents() {
        let a = dock(&[own_with("$1", vec![react("👍", 1, true)])]).unwrap();
        assert_eq!(a.kind, AcknowledgementKind::Sent);
    }

    /// Port of AcknowledgementTests (:468).
    ///
    /// Mutation seen failing: dropping the `membership_verb` check (the
    /// agent's "joined" row ended the dock); ignoring `agent_ids` (Krishna's
    /// reply ended it).
    #[test]
    fn the_agents_answer_ends_it_someone_elses_does_not() {
        assert_eq!(dock(&[own("$1"), from("$2", ATLAS)]), None);
        assert_eq!(
            dock(&[own("$1"), from("$2", "@krishna:x")]).unwrap().kind,
            AcknowledgementKind::Sent
        );
        let joined = row("$2", false, ATLAS, vec![], Some("joined"));
        assert_eq!(
            dock(&[own("$1"), joined]).unwrap().kind,
            AcknowledgementKind::Sent
        );
    }

    /// Port of AcknowledgementTests (:481).
    ///
    /// Mutation seen failing: searching all rows instead of those after the
    /// last own row (the old answer ended the dock).
    #[test]
    fn an_answer_from_before_the_latest_send_does_not_count() {
        let rows = [own("$1"), from("$2", ATLAS), own("$3")];
        assert_eq!(dock(&rows).unwrap().kind, AcknowledgementKind::Sent);
    }

    /// Port of AcknowledgementTests (:493).
    ///
    /// Mutation seen failing: dropping the `sent_this_session` guard.
    #[test]
    fn nothing_to_say_without_a_send_this_session_or_without_an_agent() {
        assert_eq!(
            state(&[own("$1")], Some("Atlas"), &agents(), false, &[], false),
            None
        );
        assert_eq!(state(&[own("$1")], None, &agents(), true, &[], false), None);
    }

    /// Port of AcknowledgementTests (:500).
    ///
    /// Mutation seen failing: the no-addressee typing arm returning only the
    /// first name.
    #[test]
    fn several_agents_typing_in_a_room_with_no_one_addressee_are_all_named() {
        let a = state(&[], None, &[], false, &names(&["Atlas", "Quill"]), false).unwrap();
        assert_eq!(a.kind, AcknowledgementKind::OnIt);
        assert_eq!(a.names, names(&["Atlas", "Quill"]));
        assert_eq!(a.text, "Atlas and Quill are on it…");
    }

    /// Review Focus 2, ruling 1: three or more are worded the core's way.
    ///
    /// Mutation seen failing: the multi-name arm formatted as
    /// "{first} and {n-1} others are on it…".
    #[test]
    fn three_or_more_working_agents_are_named_the_cores_way() {
        let text = |typing: &[&str]| {
            state(&[], None, &[], false, &names(typing), false)
                .unwrap()
                .text
        };
        assert_eq!(
            text(&["Atlas", "Quill", "Hermes"]),
            "Atlas, Quill and 1 other are on it…"
        );
        assert_eq!(
            text(&["Atlas", "Quill", "Hermes", "Vayu"]),
            "Atlas, Quill and 2 others are on it…"
        );
    }

    /// Review Focus 2: with the agents unknown, anyone else's reply is taken
    /// as the answer, so the dock goes away rather than hanging.
    ///
    /// Mutation seen failing: requiring `agent_ids.contains(sender)` even
    /// when the list is empty (the dock stayed at `Sent`).
    #[test]
    fn with_the_agents_unknown_anyone_elses_reply_ends_it() {
        let rows = [own("$1"), from("$2", "@krishna:x")];
        assert_eq!(state(&rows, Some("Atlas"), &[], true, &[], false), None);
    }

    /// Review Focus 1: a bridge agent with no live channel answers by editing
    /// a progress message. That message is the agent's, after the reader's
    /// send, so the dock ends — it must not hang in "Sent to", and while the
    /// agent's mark is only 👀 it moves through "is on it" to done.
    ///
    /// Mutation seen failing: skipping rows whose `item.edited` is true when
    /// looking for the answer (the dock stayed at `Sent`).
    #[test]
    fn a_progress_message_the_agent_edits_ends_the_dock() {
        let mut progress = from("$2", ATLAS);
        progress.item.edited = true;
        progress.item.body = Some("🔎 Searching files…".into());

        // Sent, with nothing yet.
        assert_eq!(dock(&[own("$1")]).unwrap().kind, AcknowledgementKind::Sent);
        // The agent reacts: on it.
        let reacted = [own_with("$1", vec![react("👀", 1, false)])];
        assert_eq!(dock(&reacted).unwrap().text, "Atlas is on it…");
        // The progress message lands and is edited in place: done.
        let rows = [own_with("$1", vec![react("👀", 1, false)]), progress];
        assert_eq!(dock(&rows), None);
    }
}
