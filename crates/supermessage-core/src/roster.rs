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
//!
//! The views over the arrangement — filter chips, the Needs you inbox, the
//! agent directory — keep or drop rows; they never order them.

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
    /// Whether `state`'s activity word (`active`, `idle`, `quiet`) describes
    /// anything. It does for an agent's room; for a room of people, "idle"
    /// says nothing a reader wants to know. See [`describes_agent`].
    ///
    /// `NeedsYou` is shown regardless: owing an answer is not about agents.
    pub describes_agent: bool,
    /// The generated tile for an agent's room: `Some` exactly when
    /// `describes_agent`. It rides on the row for the same reason `state`
    /// does — a renderer cannot ask the core per row.
    #[uniffi(default = None)]
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub avatar: Option<crate::agent_avatar::AgentAvatarStyle>,
}

/// Whether a room reads as an agent's, as far as the roster can tell.
///
/// The room list carries no member list, so this reads the signals it does
/// have: a bridge runtime (`harness on host`), or a name in the suite's
/// `<glyph> Name — Role` convention, which is how agent rooms are named here.
/// A plain name such as `research` is a room of people.
pub fn describes_agent(row: &RoomRow) -> bool {
    row.room.runtime.is_some() || row.identity.glyph.is_some() || row.identity.role.is_some()
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
        .map(|row| {
            let describes = describes_agent(row);
            RosterRow {
                state: state_for(row, now_ms),
                describes_agent: describes,
                avatar: describes.then(|| crate::agent_avatar::style_for_room(&row.room.id)),
                row: row.clone(),
            }
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

/// Which rooms a chip keeps. Filtering keeps or drops rows; it never orders
/// them — the arrangement above decides the order, and every filter shows a
/// subset of it.
///
/// The serialised keys are the iOS raw values, so a choice stored by an older
/// build still reads. Only [`OFFERED_FILTERS`] are offered as chips; the other
/// two are the views the Needs you inbox and the Agents directory are built on.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum RosterFilter {
    All,
    Unread,
    Agents,
    NeedsYou,
}

/// The chips a host offers, in order.
pub const OFFERED_FILTERS: [RosterFilter; 2] = [RosterFilter::All, RosterFilter::Unread];

impl RosterFilter {
    pub fn title(&self) -> &'static str {
        match self {
            Self::All => "All",
            Self::Unread => "Unread",
            Self::Agents => "Agents",
            Self::NeedsYou => "Needs you",
        }
    }

    /// The stored form: the serde name, which is the iOS raw value.
    pub fn key(&self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Unread => "unread",
            Self::Agents => "agents",
            Self::NeedsYou => "needsYou",
        }
    }

    pub fn admits(&self, row: &RosterRow) -> bool {
        match self {
            Self::All => true,
            Self::Unread => row.row.room.unread > 0,
            Self::Agents => row.describes_agent,
            Self::NeedsYou => row.state == AgentState::NeedsYou,
        }
    }
}

/// Read a stored chip. A value that is unknown, empty, or not offered reads as
/// All: a stored choice from an older build must never narrow the list while
/// no chip is lit.
pub fn filter_from_key(key: &str) -> RosterFilter {
    OFFERED_FILTERS
        .into_iter()
        .find(|filter| filter.key() == key)
        .unwrap_or(RosterFilter::All)
}

/// `sections` narrowed to what `filter` admits, in the same order.
///
/// A section that lost rows drops its `detail`: a count of "2" over one row
/// is a number that is not on screen. An untouched section keeps it.
pub fn filter_sections(sections: &[RosterSection], filter: RosterFilter) -> Vec<RosterSection> {
    if filter == RosterFilter::All {
        return sections.to_vec();
    }
    sections
        .iter()
        .filter_map(|section| {
            let rows: Vec<RosterRow> = section
                .rows
                .iter()
                .filter(|row| filter.admits(row))
                .cloned()
                .collect();
            if rows.is_empty() {
                return None;
            }
            let detail = if rows.len() == section.rows.len() {
                section.detail.clone()
            } else {
                None
            };
            Some(RosterSection {
                detail,
                rows,
                ..section.clone()
            })
        })
        .collect()
}

/// One offered chip with the number of rooms it would leave.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RosterFilterChip {
    pub filter: RosterFilter,
    pub title: String,
    pub count: u32,
}

/// The offered chips over `sections`, in order, each with its count.
pub fn filter_chips(sections: &[RosterSection]) -> Vec<RosterFilterChip> {
    OFFERED_FILTERS
        .into_iter()
        .map(|filter| RosterFilterChip {
            filter,
            title: filter.title().to_string(),
            count: sections
                .iter()
                .flat_map(|section| &section.rows)
                .filter(|row| filter.admits(row))
                .count() as u32,
        })
        .collect()
}

/// What to say when a chip leaves nothing.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RosterEmptyState {
    pub title: String,
    pub description: String,
}

pub fn filter_empty_state(filter: RosterFilter) -> RosterEmptyState {
    let (title, description) = match filter {
        RosterFilter::All => ("Nothing here", ""),
        RosterFilter::Unread => ("Nothing unread", "Every room is read."),
        RosterFilter::Agents => ("No agents here", "No room here reads as an agent's."),
        RosterFilter::NeedsYou => ("You're all caught up", "Nothing is waiting on you."),
    };
    RosterEmptyState {
        title: title.into(),
        description: description.into(),
    }
}

/// Everything that owes the reader an answer: pending decisions and
/// invitations, each in the arrangement's newest-first order.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct NeedsYouInbox {
    pub decisions: Vec<RosterRow>,
    pub invitations: Vec<RosterRow>,
    /// Decisions and invitations together.
    pub count: u32,
    /// What the tab badge shows: decisions only. Invitations have their own
    /// row, and an invitation alone must not light the badge.
    pub badge_count: u32,
}

pub fn needs_you_inbox(rows: &[RoomRow], now_ms: u64) -> NeedsYouInbox {
    let all: Vec<RosterRow> = sections(rows, RosterView::Recent, true, now_ms)
        .into_iter()
        .flat_map(|section| section.rows)
        .collect();
    let is_invite = |row: &RosterRow| is_invitation(&row.row);
    // An invitation is never also a decision, even when its preview is pending.
    let decisions: Vec<RosterRow> = all
        .iter()
        .filter(|row| row.state == AgentState::NeedsYou && !is_invite(row))
        .cloned()
        .collect();
    let invitations: Vec<RosterRow> = all.iter().filter(|row| is_invite(row)).cloned().collect();
    NeedsYouInbox {
        count: (decisions.len() + invitations.len()) as u32,
        badge_count: decisions.len() as u32,
        decisions,
        invitations,
    }
}

/// The Agents directory: rooms that read as an agent's, newest first,
/// invitations left out.
pub fn agent_directory(rows: &[RoomRow], now_ms: u64) -> Vec<RosterRow> {
    sections(rows, RosterView::Recent, false, now_ms)
        .into_iter()
        .flat_map(|section| section.rows)
        .filter(|row| row.describes_agent)
        .collect()
}

/// One room's row, as the arrangement would build it.
pub fn roster_row(rows: &[RoomRow], room_id: &str, now_ms: u64) -> Option<RosterRow> {
    let room = rows.iter().find(|row| row.room.id == room_id)?;
    sections(std::slice::from_ref(room), RosterView::Recent, true, now_ms)
        .into_iter()
        .flat_map(|section| section.rows)
        .next()
}

/// The status a room's header states about its agent.
///
/// Not [`AgentState`]: that is read from a room's stored last activity, this
/// adds the one live fact the header has — whether a turn is running now.
/// It also differs from `widget::agent_state`'s lower-case "working", which is
/// a stored step within `ACTIVE_WITHIN_MS`: a different input for a different
/// surface.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum RoomStatus {
    Working,
    NeedsYou,
    Active,
    Idle,
    Quiet,
}

/// The header status for a room, or `None` when the room is not an agent's.
///
/// A debt outranks a live turn: an agent that owes an answer says so even while
/// it works. `turn_in_progress` is the host's input until M1.3 moves the turn
/// state into the core (ruling 7).
pub fn room_status(
    state: AgentState,
    describes_agent: bool,
    turn_in_progress: bool,
) -> Option<RoomStatus> {
    if !describes_agent {
        return None;
    }
    Some(match state {
        AgentState::NeedsYou => RoomStatus::NeedsYou,
        _ if turn_in_progress => RoomStatus::Working,
        AgentState::Active => RoomStatus::Active,
        AgentState::Idle => RoomStatus::Idle,
        AgentState::Quiet => RoomStatus::Quiet,
    })
}

/// The word the header shows for a status.
pub fn room_status_word(status: RoomStatus) -> &'static str {
    match status {
        RoomStatus::Working => "Working",
        RoomStatus::NeedsYou => "Needs you",
        RoomStatus::Active => "Active",
        RoomStatus::Idle => "Idle",
        RoomStatus::Quiet => "Quiet",
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

    /// Mutation seen failing: setting `avatar` unconditionally.
    #[test]
    fn an_agents_row_carries_its_tile_and_a_peoples_row_none() {
        let agent = sections(
            &[on_host("!h:x", "foundry", Some(NOW))],
            RosterView::Recent,
            false,
            NOW,
        );
        assert_eq!(
            agent[0].rows[0].avatar,
            Some(crate::agent_avatar::style_for_room("!h:x"))
        );
        let people = sections(
            &[row("research", Some(NOW))],
            RosterView::Recent,
            false,
            NOW,
        );
        assert_eq!(people[0].rows[0].avatar, None);
    }

    #[test]
    fn a_room_of_people_is_not_described_as_an_idle_agent() {
        // A plain name with no runtime: people, not an agent.
        assert!(!describes_agent(&row("research", Some(NOW))));

        let mut glyphed = row("!g:x", Some(NOW));
        glyphed.identity = crate::room_identity::parse_room_identity("✳ Atlas — Platform");
        assert!(describes_agent(&glyphed));

        assert!(describes_agent(&on_host("!h:x", "foundry", Some(NOW))));
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

    // --- views over the arrangement (ported from RosterViewsTests.swift) ---

    fn fixture(
        id: &str,
        minutes_ago: f64,
        unread: u64,
        pending: bool,
        agent: bool,
        invited: bool,
    ) -> RoomRow {
        let ms = NOW - (minutes_ago * 60_000.0) as u64;
        let mut row = row(id, Some(ms));
        row.room.unread = unread as _;
        if agent {
            row.room.runtime = Some(RuntimeDto {
                harness: "claude-code".into(),
                host: "foundry".into(),
            });
        }
        if invited {
            row.room.membership = Membership::Invited;
            row.affordance = crate::invitation::RoomAffordance::RespondToInvitation;
        }
        row.preview = Some(crate::room_preview::RoomPreview {
            text: "hi".into(),
            pending,
        });
        row
    }

    fn roster() -> Vec<RoomRow> {
        vec![
            fixture("a", 1.0, 3, false, true, false),
            fixture("b", 2.0, 0, true, true, false),
            fixture("c", 3.0, 1, false, false, false),
            fixture("d", 4.0, 0, false, false, true),
            fixture("e", 5.0, 0, true, false, false),
        ]
    }

    fn waiting_view() -> Vec<RosterSection> {
        sections(&roster(), RosterView::Waiting, false, NOW)
    }

    fn ids(sections: &[RosterSection]) -> Vec<Vec<String>> {
        sections
            .iter()
            .map(|s| s.rows.iter().map(|r| r.row.room.id.clone()).collect())
            .collect()
    }

    fn id_rows(rows: &[RosterRow]) -> Vec<String> {
        rows.iter().map(|r| r.row.room.id.clone()).collect()
    }

    fn strs(v: &[&[&str]]) -> Vec<Vec<String>> {
        v.iter()
            .map(|g| g.iter().map(|s| s.to_string()).collect())
            .collect()
    }

    /// Mutation seen failing: `All` returning an empty list (left: [])
    #[test]
    fn all_is_the_cores_arrangement_unchanged() {
        let w = waiting_view();
        assert_eq!(filter_sections(&w, RosterFilter::All), w);
    }

    /// Mutation seen failing: Unread admitting `unread == 0` (kept [[b, e]])
    #[test]
    fn unread_keeps_only_rooms_with_unread_messages_in_the_cores_order() {
        assert_eq!(
            ids(&filter_sections(&waiting_view(), RosterFilter::Unread)),
            strs(&[&["a", "c"]])
        );
    }

    /// Mutation seen failing: Agents admitting `!describes_agent` (kept [[e], [c]])
    #[test]
    fn agents_keeps_rooms_that_read_as_an_agents_across_sections() {
        assert_eq!(
            ids(&filter_sections(&waiting_view(), RosterFilter::Agents)),
            strs(&[&["b"], &["a"]])
        );
    }

    /// Mutation seen failing: NeedsYou admitting `state != NeedsYou` (kept [[a, c]])
    #[test]
    fn needs_you_keeps_rooms_owing_an_answer() {
        assert_eq!(
            ids(&filter_sections(&waiting_view(), RosterFilter::NeedsYou)),
            strs(&[&["b", "e"]])
        );
    }

    /// Mutation seen failing: iterating section rows in reverse ("Unread reordered the roster")
    #[test]
    fn filtering_never_reorders() {
        let recent = sections(&roster(), RosterView::Recent, true, NOW);
        for filter in [
            RosterFilter::All,
            RosterFilter::Unread,
            RosterFilter::Agents,
            RosterFilter::NeedsYou,
        ] {
            let kept: Vec<String> = filter_sections(&recent, filter)
                .iter()
                .flat_map(|s| s.rows.iter().map(|r| r.row.room.id.clone()))
                .collect();
            let expected: Vec<String> = recent
                .iter()
                .flat_map(|s| &s.rows)
                .filter(|r| filter.admits(r))
                .map(|r| r.row.room.id.clone())
                .collect();
            assert_eq!(kept, expected, "{filter:?} reordered the roster");
        }
    }

    /// Mutation seen failing: always keeping `detail` (Agents section kept Some("2"))
    #[test]
    fn a_section_that_lost_rows_drops_its_count_an_untouched_one_keeps_it() {
        let w = waiting_view();
        assert_eq!(w[0].detail.as_deref(), Some("2"));
        let agents = filter_sections(&w, RosterFilter::Agents);
        assert_eq!(agents[0].detail, None);
        let needs = filter_sections(&w, RosterFilter::NeedsYou);
        assert_eq!(needs[0].detail.as_deref(), Some("2"));
        assert_eq!(needs[0].title, w[0].title);
    }

    /// The Swift test's title said "both"; its assertion is decisions only.
    ///
    /// Mutation seen failing: badge counting invitations too (left: 3)
    #[test]
    fn the_inbox_is_pending_decisions_plus_invitations_and_the_badge_counts_decisions() {
        let inbox = needs_you_inbox(&roster(), NOW);
        assert_eq!(id_rows(&inbox.decisions), ["b", "e"]);
        assert_eq!(id_rows(&inbox.invitations), ["d"]);
        assert_eq!(inbox.count, 3);
        assert_eq!(inbox.badge_count, 2);
    }

    /// Mutation seen failing: a decision also being any unread room (count 1)
    #[test]
    fn an_answered_decision_leaves_the_inbox() {
        let answered = vec![
            fixture("b", 1.0, 0, false, true, false),
            fixture("c", 1.0, 4, false, false, false),
        ];
        let inbox = needs_you_inbox(&answered, NOW);
        assert_eq!(inbox.count, 0);
        assert!(inbox.decisions.is_empty() && inbox.invitations.is_empty());
    }

    /// Mutation seen failing: dropping the `!is_invite` guard (decisions not empty)
    #[test]
    fn an_invitation_is_not_also_counted_as_a_decision() {
        let inbox = needs_you_inbox(&[fixture("x", 1.0, 0, true, false, true)], NOW);
        assert!(inbox.decisions.is_empty());
        assert_eq!(inbox.count, 1);
        assert_eq!(inbox.badge_count, 0);
    }

    /// Mutation seen failing: the directory arranging with invitations shown (left: [z, a, b])
    #[test]
    fn the_agents_tab_lists_agent_rooms_newest_first_and_leaves_out_invitations() {
        let mut rows = roster();
        rows.push(fixture("z", 0.5, 0, false, true, true));
        assert_eq!(id_rows(&agent_directory(&rows, NOW)), ["a", "b"]);
    }

    /// Mutation seen failing: `room.id != room_id` (state Active)
    #[test]
    fn a_single_rooms_roster_row_is_the_one_the_core_arranged() {
        let rows = roster();
        let b = roster_row(&rows, "b", NOW).unwrap();
        assert_eq!(b.state, AgentState::NeedsYou);
        assert!(b.describes_agent);
        assert!(!roster_row(&rows, "c", NOW).unwrap().describes_agent);
        assert_eq!(roster_row(&rows, "nope", NOW), None);
    }

    /// Mutation seen failing: `filter_from_key` accepting every key ("agents" read as Agents)
    #[test]
    fn a_stored_filter_that_is_not_offered_reads_as_all() {
        assert_eq!(filter_from_key("all"), RosterFilter::All);
        assert_eq!(filter_from_key("unread"), RosterFilter::Unread);
        for stale in ["agents", "needsYou", "machine", ""] {
            assert_eq!(filter_from_key(stale), RosterFilter::All, "{stale:?}");
        }
    }

    /// Mutation seen failing: counting every row for every chip (Unread 4)
    #[test]
    fn chips_count_what_each_offered_chip_would_leave() {
        let chips: Vec<(RosterFilter, String, u32)> = filter_chips(&waiting_view())
            .into_iter()
            .map(|c| (c.filter, c.title, c.count))
            .collect();
        assert_eq!(
            chips,
            vec![
                (RosterFilter::All, "All".to_string(), 4),
                (RosterFilter::Unread, "Unread".to_string(), 2),
            ]
        );
    }

    /// Mutation seen failing: dropping the full stop of "Every room is read."
    #[test]
    fn each_chip_says_why_it_left_nothing() {
        let said = |f| {
            let e = filter_empty_state(f);
            (e.title, e.description)
        };
        let pair = |a: &str, b: &str| (a.to_string(), b.to_string());
        assert_eq!(said(RosterFilter::All), pair("Nothing here", ""));
        assert_eq!(
            said(RosterFilter::Unread),
            pair("Nothing unread", "Every room is read.")
        );
        assert_eq!(
            said(RosterFilter::Agents),
            pair("No agents here", "No room here reads as an agent's.")
        );
        assert_eq!(
            said(RosterFilter::NeedsYou),
            pair("You're all caught up", "Nothing is waiting on you.")
        );
        let titles: Vec<&str> = [
            RosterFilter::All,
            RosterFilter::Unread,
            RosterFilter::Agents,
            RosterFilter::NeedsYou,
        ]
        .iter()
        .map(|f| f.title())
        .collect();
        assert_eq!(titles, ["All", "Unread", "Agents", "Needs you"]);
    }

    /// Mutation seen failing: narrowing `_ if turn_in_progress` to
    /// `AgentState::Active if turn_in_progress` (left: Some(Idle), right:
    /// Some(Working)).
    #[test]
    fn the_header_status_vocabulary() {
        use AgentState::*;
        assert_eq!(room_status(Active, true, true), Some(RoomStatus::Working));
        assert_eq!(
            room_status(NeedsYou, true, true),
            Some(RoomStatus::NeedsYou)
        );
        assert_eq!(room_status(Idle, true, true), Some(RoomStatus::Working));
        assert_eq!(room_status(Quiet, true, true), Some(RoomStatus::Working));
        assert_eq!(room_status(Active, true, false), Some(RoomStatus::Active));
        assert_eq!(room_status(Idle, true, false), Some(RoomStatus::Idle));
        assert_eq!(room_status(Quiet, true, false), Some(RoomStatus::Quiet));
        assert_eq!(room_status(NeedsYou, false, false), None);
        assert_eq!(room_status(Active, false, true), None);
    }

    /// Mutation seen failing: the NeedsYou word as "Needs You" (left:
    /// [.., "Needs You", ..], right: [.., "Needs you", ..]).
    #[test]
    fn the_status_words() {
        let words: Vec<&str> = [
            RoomStatus::Working,
            RoomStatus::NeedsYou,
            RoomStatus::Active,
            RoomStatus::Idle,
            RoomStatus::Quiet,
        ]
        .into_iter()
        .map(room_status_word)
        .collect();
        assert_eq!(words, ["Working", "Needs you", "Active", "Idle", "Quiet"]);
    }
}
