//! How the roster is ordered and grouped.
//!
//! Three arrangements rather than one, because a fleet is read for different
//! reasons: to find a room you have in mind, to answer whatever is waiting,
//! or to see how a machine is doing.
//!
//! **In the core, not in a host.** Every rule here is a product decision about
//! what a fleet looks like — how long silence takes to become quiet, which
//! room outranks which, what a section is called when it is the only one. Two
//! hosts each holding their own copy is two clients that disagree about what a
//! roster is, and this module exists because they did.

use serde::{Deserialize, Serialize};

use crate::dto::RoomRow;

/// Which arrangement the reader chose.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum RosterView {
    /// Newest first, one list. For finding a room you already have in mind.
    Recent,
    /// What owes you an answer, above everything else.
    Waiting,
    /// Grouped by the machine the agent runs on.
    Machine,
}

impl RosterView {
    pub fn title(&self) -> &'static str {
        match self {
            Self::Recent => "Recent",
            Self::Waiting => "Waiting",
            Self::Machine => "Machine",
        }
    }
}

/// What an agent is doing, as far as the roster can honestly tell.
///
/// Not a health check. The roster does not know whether a process is running
/// and must not imply that it does — every case here is a statement about
/// when the room last spoke, or about what it said.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum AgentState {
    /// Owes the reader an answer. The core said so — `preview.pending`.
    NeedsYou,
    /// Spoke recently enough to count as active.
    Active,
    /// Nothing lately, but within living memory.
    Idle,
    /// Silent long enough that its absence is the fact.
    Quiet,
}

impl AgentState {
    pub fn word(&self) -> &'static str {
        match self {
            Self::NeedsYou => "needs you",
            Self::Active => "active",
            Self::Idle => "idle",
            Self::Quiet => "quiet",
        }
    }
}

/// One row of the roster, with the state the roster may say about it.
///
/// The state travels *with* the row rather than being asked for per row: a
/// host that asks would pay a round trip per visible room per re-render,
/// which is the one cost profile a list cannot absorb — the same reasoning
/// `TimelineRow` gives for carrying its `ItemView`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RosterRow {
    pub row: RoomRow,
    pub state: AgentState,
}

/// One section of the roster.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RosterSection {
    pub id: String,
    /// `None` for an arrangement that does not label its one section.
    pub title: Option<String>,
    /// A count the header may show — waiting rooms, agents on a host.
    pub detail: Option<String>,
    pub rows: Vec<RosterRow>,
    /// Whether this section is the one that wants attention.
    pub attention: bool,
}

/// Within this, a room counts as active rather than idle.
pub const ACTIVE_WITHIN_MS: u64 = 15 * 60 * 1000;

/// How long without a word before a room reads as quiet rather than idle.
///
/// A day is the shape of this work: an agent that said nothing since
/// yesterday is between tasks, one that said nothing for three days has been
/// left alone.
pub const QUIET_AFTER_MS: u64 = 24 * 60 * 60 * 1000;

/// What the roster may say about a room.
///
/// `NeedsYou` outranks everything: a room that owes an answer is not
/// described by how recently it spoke.
pub fn state_for(row: &RoomRow, now_ms: u64) -> AgentState {
    if row.preview.as_ref().is_some_and(|preview| preview.pending) {
        return AgentState::NeedsYou;
    }
    let Some(last) = row.room.last_activity_ms else {
        return AgentState::Quiet;
    };
    // Saturating, because a room whose last activity is in the future — a
    // homeserver clock ahead of this device — is not three days silent.
    let elapsed = now_ms.saturating_sub(last);
    if elapsed <= ACTIVE_WITHIN_MS {
        AgentState::Active
    } else if elapsed <= QUIET_AFTER_MS {
        AgentState::Idle
    } else {
        AgentState::Quiet
    }
}

/// Whether a room is an invitation rather than a conversation.
fn is_invitation(row: &RoomRow) -> bool {
    row.affordance == crate::invitation::RoomAffordance::RespondToInvitation
}

/// How many invitations are being withheld, for the picker to admit to.
///
/// Hidden must never mean gone: a roster that silently drops a room you were
/// invited to is a roster that lost it.
pub fn hidden_invitations(rows: &[RoomRow], shows_invitations: bool) -> u32 {
    if shows_invitations {
        return 0;
    }
    rows.iter().filter(|row| is_invitation(row)).count() as u32
}

/// Arrange `rows` for one view.
///
/// `shows_invitations` is off by default in the app, and hiding them here
/// rather than in a view keeps every arrangement agreeing about what the
/// roster contains.
pub fn sections(
    rows: &[RoomRow],
    view: RosterView,
    shows_invitations: bool,
    now_ms: u64,
) -> Vec<RosterSection> {
    let mut by_recency: Vec<RosterRow> = rows
        .iter()
        .filter(|row| shows_invitations || !is_invitation(row))
        .map(|row| RosterRow {
            state: state_for(row, now_ms),
            row: row.clone(),
        })
        .collect();
    by_recency.sort_by(|a, b| {
        b.row
            .room
            .last_activity_ms
            .unwrap_or(0)
            .cmp(&a.row.room.last_activity_ms.unwrap_or(0))
    });

    match view {
        RosterView::Recent => vec![RosterSection {
            id: "recent".into(),
            title: None,
            detail: None,
            rows: by_recency,
            attention: false,
        }],

        RosterView::Waiting => {
            let (waiting, rest): (Vec<RosterRow>, Vec<RosterRow>) = by_recency
                .into_iter()
                .partition(|row| row.state == AgentState::NeedsYou);

            let mut out = Vec::new();
            if !waiting.is_empty() {
                out.push(RosterSection {
                    id: "waiting".into(),
                    title: Some("Waiting on you".into()),
                    detail: Some(waiting.len().to_string()),
                    rows: waiting.clone(),
                    attention: true,
                });
            }
            if !rest.is_empty() {
                out.push(RosterSection {
                    id: "rest".into(),
                    // Named only when something sits above it. On a quiet
                    // fleet this is the whole roster, and "Everything else"
                    // would be labelling the absence of a section.
                    title: if waiting.is_empty() {
                        None
                    } else {
                        Some("Everything else".into())
                    },
                    detail: None,
                    rows: rest,
                    attention: false,
                });
            }
            out
        }

        RosterView::Machine => {
            let mut hosts: Vec<String> = Vec::new();
            let mut grouped: std::collections::HashMap<String, Vec<RosterRow>> =
                std::collections::HashMap::new();
            for row in by_recency {
                // A room with no runtime is not an agent's. Filed under its
                // own heading rather than guessed at — see `parse_runtime`.
                let host = row
                    .row
                    .room
                    .runtime
                    .as_ref()
                    .map(|runtime| runtime.host.clone())
                    .unwrap_or_else(|| "Elsewhere".to_string());
                if !grouped.contains_key(&host) {
                    hosts.push(host.clone());
                }
                grouped.entry(host).or_default().push(row);
            }

            hosts
                .into_iter()
                .map(|host| {
                    let rows = grouped.remove(&host).unwrap_or_default();
                    let waiting = rows
                        .iter()
                        .filter(|row| row.state == AgentState::NeedsYou)
                        .count();
                    let agents = if rows.len() == 1 {
                        "1 agent".to_string()
                    } else {
                        format!("{} agents", rows.len())
                    };
                    RosterSection {
                        id: host.clone(),
                        title: Some(host),
                        detail: Some(if waiting > 0 {
                            format!("{agents} · {waiting} waiting")
                        } else {
                            agents
                        }),
                        rows,
                        attention: waiting > 0,
                    }
                })
                .collect()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dto::Membership;
    use crate::dto::{RoomRow, RoomSummary, RuntimeDto};

    const NOW: u64 = 1_700_000_000_000;

    fn row(id: &str, last_activity_ms: Option<u64>) -> RoomRow {
        RoomRow::new(RoomSummary {
            id: id.to_string(),
            name: id.to_string(),
            avatar_url: None,
            unread: 0,
            last_message: None,
            last_message_is_own: false,
            last_message_names_sender: false,
            last_event_type: None,
            last_activity_ms,
            runtime: None,
            membership: Membership::Joined,
        })
    }

    fn on_host(id: &str, host: &str, last_activity_ms: Option<u64>) -> RoomRow {
        let mut row = row(id, last_activity_ms);
        row.room.runtime = Some(RuntimeDto {
            harness: "OpenClaw".into(),
            host: host.into(),
        });
        row
    }

    /// A room that owes an answer, which is what `preview.pending` means.
    fn waiting(id: &str, last_activity_ms: Option<u64>) -> RoomRow {
        let mut row = row(id, last_activity_ms);
        row.preview = Some(crate::room_preview::RoomPreview {
            text: "A decision is waiting".into(),
            pending: true,
        });
        row
    }

    fn invitation(id: &str) -> RoomRow {
        let mut row = row(id, Some(NOW));
        row.room.membership = Membership::Invited;
        row.affordance = crate::invitation::room_affordance(Membership::Invited);
        row
    }

    #[test]
    fn owing_an_answer_outranks_how_recently_a_room_spoke() {
        // Silent for a week, and still the first thing you should look at.
        let stale = waiting("!a:x", Some(NOW - 7 * QUIET_AFTER_MS));
        assert_eq!(state_for(&stale, NOW), AgentState::NeedsYou);
    }

    #[test]
    fn recency_reads_as_active_then_idle_then_quiet() {
        assert_eq!(state_for(&row("!a:x", Some(NOW)), NOW), AgentState::Active);
        assert_eq!(
            state_for(&row("!b:x", Some(NOW - ACTIVE_WITHIN_MS - 1)), NOW),
            AgentState::Idle
        );
        assert_eq!(
            state_for(&row("!c:x", Some(NOW - QUIET_AFTER_MS - 1)), NOW),
            AgentState::Quiet
        );
    }

    #[test]
    fn a_room_that_never_said_anything_is_quiet_not_active() {
        // No timestamp is an absence, and the tempting default — treating it
        // as "now" — makes an empty room the liveliest thing on the roster.
        assert_eq!(state_for(&row("!a:x", None), NOW), AgentState::Quiet);
    }

    #[test]
    fn a_clock_running_ahead_does_not_make_a_room_ancient() {
        let future = row("!a:x", Some(NOW + 60_000));
        assert_eq!(state_for(&future, NOW), AgentState::Active);
    }

    #[test]
    fn invitations_are_withheld_but_counted() {
        let rows = vec![row("!a:x", Some(NOW)), invitation("!b:x")];
        let shown = sections(&rows, RosterView::Recent, false, NOW);
        assert_eq!(shown[0].rows.len(), 1, "an invitation was listed anyway");
        assert_eq!(
            hidden_invitations(&rows, false),
            1,
            "hidden must never mean gone"
        );
        assert_eq!(hidden_invitations(&rows, true), 0);

        let all = sections(&rows, RosterView::Recent, true, NOW);
        assert_eq!(all[0].rows.len(), 2);
    }

    #[test]
    fn what_needs_you_comes_first_whatever_spoke_last() {
        let rows = vec![
            row("!chatty:x", Some(NOW)),
            waiting("!owes:x", Some(NOW - QUIET_AFTER_MS)),
        ];
        let out = sections(&rows, RosterView::Waiting, false, NOW);
        assert_eq!(out[0].title.as_deref(), Some("Waiting on you"));
        assert_eq!(out[0].detail.as_deref(), Some("1"));
        assert!(out[0].attention);
        assert_eq!(out[0].rows[0].row.room.id, "!owes:x");
        assert_eq!(out[1].title.as_deref(), Some("Everything else"));
    }

    #[test]
    fn a_quiet_fleet_gets_no_headings_at_all() {
        // "Everything else" above the whole roster is a label for the absence
        // of a section.
        let rows = vec![row("!a:x", Some(NOW)), row("!b:x", Some(NOW - 1000))];
        let out = sections(&rows, RosterView::Waiting, false, NOW);
        assert_eq!(out.len(), 1);
        assert!(out[0].title.is_none(), "a lone section was given a heading");
        assert!(!out[0].attention);
    }

    #[test]
    fn machines_group_their_agents_and_say_how_many_want_something() {
        let mut needs = on_host("!b:x", "Ashram", Some(NOW - 1000));
        needs.preview = waiting("!b:x", None).preview;
        let rows = vec![on_host("!a:x", "Ashram", Some(NOW)), needs];

        let out = sections(&rows, RosterView::Machine, false, NOW);
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].title.as_deref(), Some("Ashram"));
        assert_eq!(out[0].detail.as_deref(), Some("2 agents · 1 waiting"));
        assert!(out[0].attention);
    }

    #[test]
    fn one_agent_is_an_agent_not_agents() {
        let rows = vec![on_host("!a:x", "Ashram", Some(NOW))];
        let out = sections(&rows, RosterView::Machine, false, NOW);
        assert_eq!(out[0].detail.as_deref(), Some("1 agent"));
        assert!(
            !out[0].attention,
            "a host with nothing waiting wants nothing"
        );
    }

    #[test]
    fn a_room_with_no_runtime_is_filed_not_guessed_at() {
        let rows = vec![row("!a:x", Some(NOW))];
        let out = sections(&rows, RosterView::Machine, false, NOW);
        assert_eq!(out[0].title.as_deref(), Some("Elsewhere"));
    }

    #[test]
    fn a_row_arrives_already_knowing_what_it_is_doing() {
        // Carried, not asked for. A host that asks per row pays a round trip
        // per visible room per re-render, which is the one cost profile a
        // list cannot absorb.
        let rows = vec![
            waiting("!owes:x", Some(NOW)),
            row("!fresh:x", Some(NOW)),
            row("!old:x", Some(NOW - QUIET_AFTER_MS - 1)),
        ];
        let out = sections(&rows, RosterView::Recent, false, NOW);
        let states: std::collections::HashMap<&str, AgentState> = out[0]
            .rows
            .iter()
            .map(|r| (r.row.room.id.as_str(), r.state))
            .collect();

        assert_eq!(states["!owes:x"], AgentState::NeedsYou);
        assert_eq!(states["!fresh:x"], AgentState::Active);
        assert_eq!(states["!old:x"], AgentState::Quiet);
    }

    #[test]
    fn every_arrangement_orders_by_recency_inside_a_section() {
        let rows = vec![
            row("!old:x", Some(NOW - 10_000)),
            row("!new:x", Some(NOW)),
            row("!middle:x", Some(NOW - 5_000)),
        ];
        for view in [RosterView::Recent, RosterView::Waiting, RosterView::Machine] {
            let out = sections(&rows, view, false, NOW);
            let ids: Vec<&str> = out[0].rows.iter().map(|r| r.row.room.id.as_str()).collect();
            assert_eq!(ids, vec!["!new:x", "!middle:x", "!old:x"], "{view:?}");
        }
    }

    #[test]
    fn a_host_keeps_the_place_its_liveliest_agent_earned_it() {
        // Hosts are ordered by first appearance in a recency-sorted list, so
        // the machine with the newest activity heads the roster.
        let rows = vec![
            on_host("!a:x", "Vault", Some(NOW - 10_000)),
            on_host("!b:x", "Ashram", Some(NOW)),
        ];
        let out = sections(&rows, RosterView::Machine, false, NOW);
        assert_eq!(out[0].title.as_deref(), Some("Ashram"));
        assert_eq!(out[1].title.as_deref(), Some("Vault"));
    }
}
