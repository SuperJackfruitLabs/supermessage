//! Who this account already knows.
//!
//! There is no address book in Matrix. The nearest honest answer to "who can
//! I talk to" is *everyone in the rooms you are already in*, which is what
//! this module assembles — deduplicated, named by the same rules the timeline
//! names a sender by, and with the bridge's runtime line read out of the
//! display name rather than left in it.
//!
//! It exists because the new-conversation screen asked for a raw
//! `@someone:server` and offered no way to pick one of the agents the reader
//! talks to every day — on an app whose whole purpose is talking to agents.

use serde::Serialize;

use crate::dto::RuntimeDto;

/// Someone this account shares a room with.
#[derive(Debug, Clone, PartialEq, Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct PersonDto {
    /// The raw Matrix user id — what an invite is addressed to.
    pub user_id: String,
    /// What to call them, with any `(harness @ host)` suffix taken off and
    /// carried in [`Self::runtime`] instead.
    pub name: String,
    /// The harness and machine, when this is an agent rather than a person.
    ///
    /// Also the flag that says *which* it is: the app is for talking to
    /// agents, and a list that cannot tell one from a colleague is a list
    /// that buries what the reader came for.
    pub runtime: Option<RuntimeDto>,
    /// Their avatar as a raw `mxc:` URI, fetched by the host on demand.
    pub avatar_url: Option<String>,
}

/// Builds a [`PersonDto`] from already-extracted parts. Pure and SDK-free,
/// mirroring `core::timeline::project_item_parts`.
pub fn project_person_parts(
    user_id: &str,
    display_name: Option<&str>,
    avatar_url: Option<&str>,
) -> PersonDto {
    // The raw name is what carries the bridge's suffix; a name already
    // composed for display would have to be taken back apart.
    let raw = display_name
        .map(str::trim)
        .filter(|name| !name.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| crate::display_name::user_label(user_id));

    let (name, runtime) = crate::display_name::sender_parts(&raw);
    let runtime = runtime.and_then(|_| {
        // `sender_parts` renders the runtime as one string for display; the
        // parts are wanted separately here, so they are read from the raw
        // name rather than parsed back out of the rendered form.
        raw.rfind(" (")
            .and_then(|open| raw.strip_suffix(')').map(|body| &body[open + 2..]))
            .and_then(|inner| inner.split_once(" @ "))
            .map(|(harness, host)| RuntimeDto {
                harness: crate::display_name::humanise(harness.trim()),
                host: crate::display_name::host_label(host.trim()),
            })
    });

    PersonDto {
        user_id: user_id.to_string(),
        name,
        runtime,
        avatar_url: avatar_url.map(str::to_string),
    }
}

/// Orders a directory: agents first, then everyone else, each alphabetically.
///
/// Agents first because this app is for talking to them — a list that sorts
/// them in among colleagues makes the reader hunt for the thing they opened
/// the screen to do.
pub fn arrange(mut people: Vec<PersonDto>) -> Vec<PersonDto> {
    people.sort_by(|a, b| {
        b.runtime
            .is_some()
            .cmp(&a.runtime.is_some())
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    people
}

/// Filters a directory by what the reader has typed.
///
/// Matches the name, the runtime, and the raw user id: someone who knows the
/// id types the id, and someone looking for "the one on ashram" types the
/// host.
pub fn matching(people: &[PersonDto], query: &str) -> Vec<PersonDto> {
    let needle = query.trim().to_lowercase();
    if needle.is_empty() {
        return people.to_vec();
    }
    people
        .iter()
        .filter(|person| {
            person.name.to_lowercase().contains(&needle)
                || person.user_id.to_lowercase().contains(&needle)
                || person.runtime.as_ref().is_some_and(|runtime| {
                    runtime.harness.to_lowercase().contains(&needle)
                        || runtime.host.to_lowercase().contains(&needle)
                })
        })
        .cloned()
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn person(user_id: &str, display_name: Option<&str>) -> PersonDto {
        project_person_parts(user_id, display_name, None)
    }

    #[test]
    fn an_agents_runtime_is_read_out_of_its_name_not_left_in_it() {
        let dto = person("@ganesha:x.org", Some("ganesha (openclaw @ ashram)"));
        assert_eq!(dto.name, "Ganesha");
        let runtime = dto.runtime.expect("an agent's runtime");
        assert_eq!(runtime.harness, "OpenClaw");
        assert_eq!(runtime.host, "Ashram");
    }

    #[test]
    fn a_person_is_a_person_and_carries_no_runtime() {
        let dto = person("@rakesh:x.org", Some("Rakesh"));
        assert_eq!(dto.name, "Rakesh");
        assert!(dto.runtime.is_none(), "a colleague was labelled a runtime");
    }

    #[test]
    fn someone_with_no_display_name_is_still_named() {
        // A directory row reading `@cleaner-cody:supermessage.dev` is an
        // address where a name belongs.
        let dto = person("@cleaner-cody:supermessage.dev", None);
        assert_eq!(dto.name, "Cleaner Cody");
    }

    #[test]
    fn agents_come_first_because_that_is_what_this_app_is_for() {
        let arranged = arrange(vec![
            person("@alice:x.org", Some("Alice")),
            person("@z:x.org", Some("zeta (openclaw @ ashram)")),
            person("@bob:x.org", Some("Bob")),
        ]);
        assert_eq!(
            arranged.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(),
            vec!["Zeta", "Alice", "Bob"]
        );
    }

    #[test]
    fn a_search_finds_someone_by_the_machine_they_run_on() {
        let people = vec![
            person("@g:x.org", Some("ganesha (openclaw @ ashram)")),
            person("@h:x.org", Some("hanuman (hermes @ vault)")),
        ];
        let hits = matching(&people, "ashram");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].name, "Ganesha");
    }

    #[test]
    fn a_search_finds_someone_by_the_id_they_are_addressed_by() {
        let people = vec![person(
            "@cleaner-cody:supermessage.dev",
            Some("Cleaner Cody"),
        )];
        assert_eq!(matching(&people, "cleaner-cody").len(), 1);
    }

    #[test]
    fn an_empty_search_hides_nobody() {
        let people = vec![person("@a:x.org", Some("A")), person("@b:x.org", Some("B"))];
        assert_eq!(matching(&people, "   ").len(), 2);
    }
}
