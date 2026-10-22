//! Who a room's messages are for, and what to call them.
//!
//! This task holds the one type the typing line needs; the rest of the cast
//! (who the agents are, who a message here is addressed to) arrives with the
//! session API that reads the member list.

use serde::{Deserialize, Serialize};

use crate::mentions::Mentionable;
use crate::people::PersonDto;

/// The single agent a room's messages go to, as the header names it.
///
/// The typing line and the acknowledgement dock say "Atlas", not the bridge's
/// `atlas-bot`, because the header already does; the cast carries the header's
/// name so the three never disagree.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Counterpart {
    pub user_id: String,
    pub name: String,
}

/// Who is in the room on screen, as the composer and the typing line need it.
///
/// Built from the people this account shares rooms with (agents first, never
/// this account) and the room's member ids; it decides nothing about either.
#[derive(Debug, Clone, PartialEq, Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RoomCast {
    pub room_id: String,
    /// The room's other members, in the order the core gave them.
    pub people: Vec<PersonDto>,
    pub counterpart: Option<Counterpart>,
    /// The agents in the room, by id.
    pub agent_ids: Vec<String>,
    /// Who a message sent here is for, when that is one agent.
    pub addressee: Option<String>,
    /// Everyone a message here could address, named as the picker inserts them.
    pub mentionables: Vec<Mentionable>,
}

/// Who the header is naming, if it names one participant.
///
/// An agent's room is named for its agent ("Atlas — reviewer"), while the
/// agent's own profile may say `atlas-bot` and the typing notice carried
/// whatever the member store had cached: one agent, three names. So when the
/// room is an agent's and exactly one agent is in it, that agent is the
/// header, and is called what the header calls it. Anything else has no
/// counterpart.
pub fn counterpart(
    people: &[PersonDto],
    header_name: Option<&str>,
    is_agent_room: bool,
) -> Option<Counterpart> {
    let header = header_name.filter(|n| !n.is_empty())?;
    if !is_agent_room {
        return None;
    }
    let mut agents = people.iter().filter(|p| p.runtime.is_some());
    match (agents.next(), agents.next()) {
        (Some(agent), None) => Some(Counterpart {
            user_id: agent.user_id.clone(),
            name: header.to_owned(),
        }),
        _ => None,
    }
}

/// Joins everyone known with the room's members.
///
/// `member_ids` of `None` (the room's detail could not be read) falls back to
/// everyone known, since a picker offering too many people is a smaller fault
/// than one offering nobody; no counterpart is guessed then, because without
/// the members there is no knowing who is in the room.
pub fn cast_from(
    room_id: &str,
    everyone: &[PersonDto],
    member_ids: Option<&[String]>,
    header_name: Option<&str>,
    is_agent_room: bool,
) -> RoomCast {
    let people: Vec<PersonDto> = match member_ids {
        Some(ids) => everyone
            .iter()
            .filter(|p| ids.iter().any(|id| id == &p.user_id))
            .cloned()
            .collect(),
        None => everyone.to_vec(),
    };
    let counterpart = member_ids.and_then(|_| counterpart(&people, header_name, is_agent_room));
    let agents: Vec<&PersonDto> = people.iter().filter(|p| p.runtime.is_some()).collect();
    let addressee = match &counterpart {
        Some(c) => Some(c.name.clone()),
        None if agents.len() == 1 => Some(agents[0].name.clone()),
        None => None,
    };
    RoomCast {
        room_id: room_id.to_owned(),
        agent_ids: agents.iter().map(|p| p.user_id.clone()).collect(),
        mentionables: people
            .iter()
            .map(|p| Mentionable {
                user_id: p.user_id.clone(),
                display_name: Some(p.name.clone()),
            })
            .collect(),
        people,
        counterpart,
        addressee,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dto::RuntimeDto;

    fn person(id: &str, name: &str, agent: bool) -> PersonDto {
        PersonDto {
            user_id: id.into(),
            name: name.into(),
            initial: name.chars().next().unwrap().to_string(),
            runtime: agent.then(|| RuntimeDto {
                harness: "claude".into(),
                host: "ci".into(),
            }),
            avatar_url: None,
        }
    }
    fn atlas() -> PersonDto {
        person("@atlas:x", "atlas-bot", true)
    }
    fn quill() -> PersonDto {
        person("@quill:x", "Quill", true)
    }
    fn krishna() -> PersonDto {
        person("@k:x", "Krishna", false)
    }
    fn ids(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    /// Mutation seen failing: dropping the exactly-one-agent check (taking
    /// the first agent) fails the two-agent `None` assertion.
    #[test]
    fn an_agents_room_with_one_agent_in_it_that_agent_is_the_header() {
        let c = counterpart(&[atlas(), krishna()], Some("Atlas"), true).unwrap();
        assert_eq!((c.user_id.as_str(), c.name.as_str()), ("@atlas:x", "Atlas"));
        assert_eq!(counterpart(&[atlas(), quill()], Some("Atlas"), true), None);
        assert_eq!(counterpart(&[atlas()], Some("Ops"), false), None);
        assert_eq!(counterpart(&[atlas()], Some(""), true), None);
        assert_eq!(counterpart(&[atlas()], None, true), None);
    }

    /// Mutation seen failing: using `everyone` when members are known fails
    /// the people-ids assertion.
    #[test]
    fn the_cast_is_the_rooms_members_not_everyone_known() {
        let members = ids(&["@atlas:x", "@k:x", "@me:x"]);
        let cast = cast_from(
            "!r:x",
            &[atlas(), quill(), krishna()],
            Some(&members),
            Some("Atlas"),
            true,
        );
        let people: Vec<&str> = cast.people.iter().map(|p| p.user_id.as_str()).collect();
        assert_eq!(people, ["@atlas:x", "@k:x"]);
        assert_eq!(cast.counterpart.unwrap().name, "Atlas");
        assert_eq!(cast.addressee.as_deref(), Some("Atlas"));
        let names: Vec<_> = cast
            .mentionables
            .iter()
            .map(|m| m.display_name.clone().unwrap())
            .collect();
        assert_eq!(names, ["atlas-bot", "Krishna"]);
        assert_eq!(cast.agent_ids, ["@atlas:x"]);
        assert_eq!(cast.room_id, "!r:x");
    }

    /// Mutation seen failing: computing the counterpart even when members are
    /// unknown (header "Atlas", agent room) fails the `None` assertion.
    #[test]
    fn without_the_members_no_counterpart_is_guessed() {
        let cast = cast_from("!r:x", &[atlas()], None, Some("Atlas"), true);
        assert_eq!(cast.people.len(), 1);
        assert_eq!(cast.counterpart, None);
        assert_eq!(cast.addressee.as_deref(), Some("atlas-bot"));
    }

    /// Mutation seen failing: addressee taking the first agent regardless of
    /// count fails the `None` assertion.
    #[test]
    fn two_agents_and_no_header_have_no_addressee() {
        let members = ids(&["@atlas:x", "@quill:x", "@k:x"]);
        let cast = cast_from(
            "!r:x",
            &[atlas(), quill(), krishna()],
            Some(&members),
            None,
            false,
        );
        assert_eq!(cast.addressee, None);
        assert_eq!(cast.agent_ids, ["@atlas:x", "@quill:x"]);
    }
}
