//! Machine-shaped tokens, rendered for a person to read.
//!
//! A fleet is full of names written for a filesystem: `cleaner-cody`,
//! `claude-code`, `Rakeshs-MacBook-Pro.local`, `9247e5a88cfa`. They are correct
//! and they are not what a roster should say. This module turns them into
//! display forms, and lives in the core so the desktop and iOS cannot disagree
//! about what an agent is called.
//!
//! **It never changes identity.** Nothing here is sent to a homeserver, matched
//! against, or stored — the raw token remains the truth, and this is only what
//! gets drawn.

/// Words this fleet spells a particular way.
///
/// Checked against the lowercased token, so `OPENCLAW`, `openclaw` and
/// `OpenClaw` all land on the same answer. Title-casing alone would give
/// "Openclaw", which is wrong in the way that tells a reader nobody looked.
const KNOWN_WORDS: &[(&str, &str)] = &[
    // Harnesses, as their own projects spell them.
    ("openclaw", "OpenClaw"),
    ("opencode", "OpenCode"),
    ("claude", "Claude"),
    ("claude-code", "Claude Code"),
    ("codex", "Codex"),
    ("hermes", "Hermes"),
    ("pi", "Pi"),
    // Apple hardware, which appears in every macOS hostname.
    ("macbook", "MacBook"),
    ("imac", "iMac"),
    ("iphone", "iPhone"),
    ("ipad", "iPad"),
    ("macos", "macOS"),
    ("ios", "iOS"),
    // Suite vocabulary.
    ("agentpod", "AgentPod"),
    ("kaambaan", "Kaambaan"),
    ("api", "API"),
    ("cli", "CLI"),
    ("id", "ID"),
];

/// How many characters of an opaque identifier are worth showing.
///
/// Six is enough to tell two runtimes apart at a glance and short enough to sit
/// in a filter pill. The full value is never the point — nobody reads a
/// twelve-character hex string, they check whether it is *the* one.
const ID_PREFIX_CHARS: usize = 6;

/// The harnesses this fleet runs, as AgentPod's own registry lists them
/// (`apps/node-agent/cmd/agentpod-node/registry.go`).
///
/// [`parse_runtime`] requires the first half to be one of these, and that
/// requirement is the whole guard: "notes on deployment" has the shape of a
/// runtime and is a sentence. Better to file an unknown harness under nothing
/// than to file real rooms under half a sentence.
const KNOWN_HARNESSES: &[&str] = &[
    "hermes",
    "openclaw",
    "claude-code",
    "codex",
    "opencode",
    "pi",
];

/// Whether a token is an opaque identifier rather than a name.
///
/// Long, all hex, no separators — the shape AgentPod gives a provisioned
/// runtime. Deliberately strict: `cafe` and `added` are hex and are also
/// words, so the length floor is what keeps this from mangling English.
fn looks_like_opaque_id(token: &str) -> bool {
    token.len() >= 8 && token.chars().all(|c| c.is_ascii_hexdigit())
}

/// Split on the separators a machine name uses, dropping empties.
fn words(raw: &str) -> Vec<&str> {
    raw.split(['-', '_', '.', ' '])
        .filter(|part| !part.is_empty())
        .collect()
}

fn title_case(word: &str) -> String {
    let mut chars = word.chars();
    match chars.next() {
        Some(first) => {
            let head: String = first.to_uppercase().collect();
            head + chars.as_str().to_lowercase().as_str()
        }
        None => String::new(),
    }
}

fn known(word: &str) -> Option<&'static str> {
    let lowered = word.to_lowercase();
    KNOWN_WORDS
        .iter()
        .find(|(raw, _)| *raw == lowered)
        .map(|(_, display)| *display)
}

/// A person-facing form of one machine-shaped token.
///
/// `cleaner-cody` becomes `Cleaner Cody`, `hanuman` becomes `Hanuman`,
/// `claude-code` becomes `Claude Code`. An opaque identifier is shortened
/// rather than title-cased, because `9247E5A88Cfa` is not an improvement on
/// `9247e5a88cfa`.
pub fn humanise(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    if looks_like_opaque_id(trimmed) {
        return short_id(trimmed);
    }

    // The multi-word entries have to be matched before splitting, or
    // `claude-code` becomes two lookups and loses its space.
    if let Some(display) = known(trimmed) {
        return display.to_string();
    }

    let parts = words(trimmed);
    if parts.is_empty() {
        return String::new();
    }

    let mut out: Vec<String> = Vec::with_capacity(parts.len());
    for (index, part) in parts.iter().enumerate() {
        if looks_like_opaque_id(part) {
            out.push(short_id(part));
            continue;
        }
        // "Rakeshs-MacBook-Pro" is a macOS hostname built from "Rakesh's
        // MacBook Pro", and reading it back is the difference between a
        // machine's name and a person's. Narrow on purpose: only a trailing
        // `s` *immediately before a device word* becomes an apostrophe, so an
        // ordinary name ending in s is left alone.
        let owns_next = parts
            .get(index + 1)
            .is_some_and(|next| matches!(known(next), Some("MacBook") | Some("iMac")));
        if owns_next && part.len() > 1 && part.to_lowercase().ends_with('s') {
            let stem = &part[..part.len() - 1];
            out.push(format!("{}'s", title_case(stem)));
            continue;
        }
        out.push(
            known(part)
                .map(str::to_string)
                .unwrap_or_else(|| title_case(part)),
        );
    }
    out.join(" ")
}

/// The first few characters of an opaque identifier, with an ellipsis.
fn short_id(raw: &str) -> String {
    let prefix: String = raw.chars().take(ID_PREFIX_CHARS).collect();
    if raw.chars().count() > ID_PREFIX_CHARS {
        format!("{prefix}…")
    } else {
        prefix
    }
}

/// A host as a roster should name it: no `.local`, no kebab, no shouting.
pub fn host_label(raw: &str) -> String {
    // `.local` is mDNS plumbing and says nothing to a reader. Stripped before
    // splitting, because `.` is also a word separator here and would otherwise
    // turn into "Local".
    let without_mdns = raw
        .trim()
        .strip_suffix(".local")
        .or_else(|| raw.trim().strip_suffix(".lan"))
        .unwrap_or(raw.trim());
    humanise(without_mdns)
}

/// `<harness> on <host>`, both in their display forms.
///
/// The preposition is a word rather than `@`: an `@` in a roster reads as a
/// Matrix user id, which this is not.
pub fn runtime_label(harness: &str, host: &str) -> String {
    let harness = humanise(harness);
    let host = host_label(host);
    match (harness.is_empty(), host.is_empty()) {
        (true, true) => String::new(),
        (false, true) => harness,
        (true, false) => host,
        (false, false) => format!("{harness} on {host}"),
    }
}

/// Whether a room name was written by a machine rather than by a person.
///
/// The distinction matters because [`humanise`] is wrong for prose. Applied to
/// `id.agentpod.dev Admin Room` it would split on the dots and produce "ID
/// AgentPod Dev Admin Room"; applied to `.claude` it would quietly drop a
/// leading dot that means something.
///
/// So: no spaces (a space means someone already wrote this for reading), no
/// dots or slashes (those carry structure worth keeping), and either a
/// kebab/snake separator or a single all-lowercase word.
fn looks_machine_written(raw: &str) -> bool {
    if raw.contains(' ') || raw.contains('.') || raw.contains('/') {
        return false;
    }
    let has_separator = raw.contains('-') || raw.contains('_');
    let single_lowercase_word = !raw.is_empty()
        && raw
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit());
    has_separator || single_lowercase_word
}

/// A room's display name: humanised when it looks machine-written, and left
/// exactly as it is otherwise.
///
/// A person who names a room keeps their capitalisation.
pub fn room_name_label(raw: &str) -> String {
    let trimmed = raw.trim();
    if looks_machine_written(trimmed) {
        humanise(trimmed)
    } else {
        trimmed.to_string()
    }
}

/// The person behind a Matrix user id.
///
/// `@cleaner-cody:supermessage.dev` is an address; "Cleaner Cody" is who it
/// belongs to. Read receipts and reaction chips both need the second one, and
/// neither has a display name to hand — the SDK gives them user ids and
/// nothing else. A leading `_` and any `bridge_` prefix belong to the bridge
/// that minted the account, not to the person, so they come off first.
pub fn user_label(raw: &str) -> String {
    let trimmed = raw.trim();
    let Some(rest) = trimmed.strip_prefix('@') else {
        return room_name_label(trimmed);
    };
    let localpart = rest.split(':').next().unwrap_or(rest);
    // `_agentpod_ganesha` — a bridge namespaces its puppets with a leading
    // underscore, so only the segment after the namespace is the name. The
    // leading underscore is the whole signal: without it, `john_doe` is a
    // person who wrote their own localpart, and dropping `john` renames them.
    let name = match localpart.strip_prefix('_') {
        Some(namespaced) => match namespaced.trim_start_matches('_').split_once('_') {
            Some((_, tail)) if !tail.is_empty() => tail,
            _ => namespaced,
        },
        None => localpart,
    };
    humanise(name)
}

/// A set of people, named the way a sentence would name them.
///
/// Read receipts and reaction chips both have the same problem: a list of
/// user ids that has to become something a person reads at a glance. Naming
/// everyone is unreadable past two, and a bare count ("3") says nothing about
/// who — so the first two are named and the rest are counted.
pub fn people_label(user_ids: &[String]) -> String {
    let names: Vec<String> = user_ids.iter().map(|id| user_label(id)).collect();
    match names.len() {
        0 => String::new(),
        1 => names[0].clone(),
        2 => format!("{} and {}", names[0], names[1]),
        n => {
            let rest = n - 2;
            let others = if rest == 1 { "other" } else { "others" };
            format!("{}, {} and {rest} {others}", names[0], names[1])
        }
    }
}

/// A sender's display name, with any `(harness @ host)` suffix made readable.
///
/// The bridge names an agent `ganesha (openclaw @ ashram)`, which is accurate
/// and reads like a config line. The parenthetical is the only part touched —
/// everything a person set stays as they set it.
pub fn sender_label(raw: &str) -> String {
    let (head, runtime) = sender_parts(raw);
    match runtime {
        Some(runtime) => format!("{head} ({runtime})"),
        None => head,
    }
}

/// A sender's name and, separately, the runtime its bridge appended.
///
/// Carried apart rather than composed and re-split, for the reason
/// `timelineGrouping.ts` gives about membership verbs: a caller that needs
/// half of a rendered string should be handed that half, not left to parse
/// the sentence back apart.
///
/// The runtime is worth saying in a roster, where rooms differ, and in a room
/// where more than one agent speaks. In a room with a single speaker it is the
/// same words under every message.
pub fn sender_parts(raw: &str) -> (String, Option<String>) {
    let trimmed = raw.trim();
    let Some(open) = trimmed.rfind(" (") else {
        return (room_name_label(trimmed), None);
    };
    if !trimmed.ends_with(')') {
        return (room_name_label(trimmed), None);
    }
    let inner = &trimmed[open + 2..trimmed.len() - 1];
    let Some((harness, host)) = inner.split_once(" @ ") else {
        return (room_name_label(trimmed), None);
    };

    let head = room_name_label(trimmed[..open].trim());
    let runtime = runtime_label(harness.trim(), host.trim());
    if runtime.is_empty() {
        (head, None)
    } else {
        (head, Some(runtime))
    }
}

/// The harness and host a room's agent runs on, read from the room topic.
///
/// AgentPod's bridge writes a topic like `openclaw on ashram — openclaw:ganesha`
/// — the runtime, then an em dash, then the internal address. Only the part
/// before the dash is a runtime, and only when it has the shape.
///
/// `None` is a **normal outcome**, not a failure: a room a person made has a
/// topic about the room, and a roster grouped by machine simply files it under
/// nothing. Same posture as `parse_room_identity`, for the same reason.
///
/// Accepts `on` or `@` between the two halves, because the bridge has written
/// both and a display rule should not be the thing that breaks when it changes.
pub fn parse_runtime(topic: &str) -> Option<(String, String)> {
    let head = topic.split('\u{2014}').next().unwrap_or(topic).trim();
    if head.is_empty() {
        return None;
    }

    let (harness, host) = head.split_once(" on ").or_else(|| head.split_once(" @ "))?;
    let harness = harness.trim();
    let host = host.trim();
    if harness.is_empty() || host.is_empty() {
        return None;
    }
    // A runtime is two tokens, and the first names a harness we run. Both
    // checks earn their place: the space test rejects long prose, and the
    // registry test rejects short prose — "notes on deployment" passes the
    // first and fails the second.
    if harness.contains(' ') || host.contains(' ') {
        return None;
    }
    if !KNOWN_HARNESSES.contains(&harness.to_lowercase().as_str()) {
        return None;
    }
    Some((humanise(harness), host_label(host)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_kebab_name_becomes_words() {
        assert_eq!(humanise("cleaner-cody"), "Cleaner Cody");
    }

    #[test]
    fn a_bare_name_is_capitalised() {
        assert_eq!(humanise("hanuman"), "Hanuman");
        assert_eq!(humanise("ganesha"), "Ganesha");
    }

    #[test]
    fn a_harness_keeps_the_casing_its_own_project_uses() {
        // Title-casing alone gives "Openclaw", which is the spelling that tells
        // a reader nobody looked.
        assert_eq!(humanise("openclaw"), "OpenClaw");
        assert_eq!(humanise("opencode"), "OpenCode");
        assert_eq!(humanise("claude-code"), "Claude Code");
        assert_eq!(humanise("codex"), "Codex");
    }

    #[test]
    fn a_mac_hostname_reads_as_a_person_again() {
        // These are built by macOS from "Rakesh's MacBook Pro", and reading it
        // back is the difference between naming a machine and naming a person.
        assert_eq!(
            host_label("Rakeshs-MacBook-Pro.local"),
            "Rakesh's MacBook Pro"
        );
        assert_eq!(host_label("rakeshs-macbook-pro"), "Rakesh's MacBook Pro");
    }

    #[test]
    fn a_name_ending_in_s_is_not_made_possessive_on_its_own() {
        // The apostrophe rule is narrow deliberately: only immediately before a
        // device word. Otherwise every plural in the fleet grows one.
        assert_eq!(humanise("cypress"), "Cypress");
        assert_eq!(humanise("logs-archive"), "Logs Archive");
    }

    #[test]
    fn mdns_suffixes_are_plumbing_and_are_dropped() {
        assert_eq!(host_label("ashram.local"), "Ashram");
        assert_eq!(host_label("ashram"), "Ashram");
    }

    #[test]
    fn an_opaque_runtime_id_is_shortened_rather_than_title_cased() {
        // What AgentPod gives a provisioned runtime. Nobody reads twelve hex
        // characters; they check whether it is *the* one, and six answers that.
        assert_eq!(humanise("9247e5a88cfa"), "9247e5…");
        assert_eq!(host_label("9247e5a88cfa"), "9247e5…");
    }

    #[test]
    fn a_short_hex_looking_word_is_left_alone() {
        // `cafe`, `added`, `decade` are hex and are also words. The length
        // floor is what keeps this from mangling English.
        assert_eq!(humanise("cafe"), "Cafe");
        assert_eq!(humanise("added"), "Added");
    }

    #[test]
    fn a_runtime_reads_as_a_sentence_not_an_address() {
        // `@` in a roster reads as a Matrix user id, which this is not.
        assert_eq!(runtime_label("openclaw", "ashram"), "OpenClaw on Ashram");
        assert_eq!(
            runtime_label("claude-code", "Rakeshs-MacBook-Pro.local"),
            "Claude Code on Rakesh's MacBook Pro"
        );
    }

    #[test]
    fn half_a_runtime_still_says_something() {
        assert_eq!(runtime_label("openclaw", ""), "OpenClaw");
        assert_eq!(runtime_label("", "ashram"), "Ashram");
        assert_eq!(runtime_label("", ""), "");
    }

    #[test]
    fn nothing_in_produces_nothing_out() {
        assert_eq!(humanise(""), "");
        assert_eq!(humanise("   "), "");
    }

    #[test]
    fn a_machine_written_room_name_is_humanised() {
        assert_eq!(room_name_label("cleaner-cody"), "Cleaner Cody");
        assert_eq!(room_name_label("hanuman"), "Hanuman");
        assert_eq!(room_name_label("super-chotu"), "Super Chotu");
        assert_eq!(room_name_label("idea-bank"), "Idea Bank");
    }

    #[test]
    fn a_name_someone_wrote_is_left_exactly_as_written() {
        // The one that would go wrong loudly: splitting on dots turns a
        // hostname into four title-cased words, and the room is real.
        assert_eq!(
            room_name_label("id.agentpod.dev Admin Room"),
            "id.agentpod.dev Admin Room"
        );
        // A leading dot means something to whoever named it.
        assert_eq!(room_name_label(".claude"), ".claude");
        // Existing capitalisation is a decision, not an accident.
        assert_eq!(room_name_label("Threat Hunter Theo"), "Threat Hunter Theo");
        assert_eq!(room_name_label("iOS"), "iOS");
    }

    #[test]
    fn a_kebab_name_carrying_structure_is_still_left_alone() {
        // These are the cases the dot, space and slash guards actually earn. A
        // separator alone is not evidence of a machine-written *name*: a dot
        // may be a host, a version or a file, and splitting on it loses that.
        assert_eq!(room_name_label("agent-7.local"), "agent-7.local");
        assert_eq!(room_name_label("release-notes v2"), "release-notes v2");
        assert_eq!(room_name_label("ops/on-call"), "ops/on-call");
    }

    #[test]
    fn a_bridged_agent_reads_as_a_name_not_a_config_line() {
        assert_eq!(
            sender_label("ganesha (openclaw @ ashram)"),
            "Ganesha (OpenClaw on Ashram)"
        );
        assert_eq!(
            sender_label("cleaner-cody (claude-code @ Rakeshs-MacBook-Pro.local)"),
            "Cleaner Cody (Claude Code on Rakesh's MacBook Pro)"
        );
    }

    #[test]
    fn a_senders_name_and_its_runtime_come_apart() {
        // So a room with one speaker can drop the runtime — the same words
        // under every message — without parsing the composed string back apart.
        assert_eq!(
            sender_parts("ganesha (openclaw @ ashram)"),
            (
                "Ganesha".to_string(),
                Some("OpenClaw on Ashram".to_string())
            )
        );
        assert_eq!(sender_parts("Rakesh"), ("Rakesh".to_string(), None));
    }

    #[test]
    fn a_person_keeps_the_name_they_chose() {
        // Only the `(harness @ host)` shape is rewritten. Anything else —
        // including a parenthetical someone wrote themselves — is theirs.
        assert_eq!(sender_label("Rakesh"), "Rakesh");
        assert_eq!(sender_label("Ada (she/her)"), "Ada (she/her)");
        assert_eq!(
            sender_label("@rakesh:id.agentpod.dev"),
            "@rakesh:id.agentpod.dev"
        );
    }

    #[test]
    fn a_bridged_topic_says_which_machine_an_agent_runs_on() {
        assert_eq!(
            parse_runtime("openclaw on ashram \u{2014} openclaw:ganesha"),
            Some(("OpenClaw".into(), "Ashram".into()))
        );
        assert_eq!(
            parse_runtime("claude-code @ Rakeshs-MacBook-Pro.local"),
            Some(("Claude Code".into(), "Rakesh's MacBook Pro".into()))
        );
    }

    #[test]
    fn a_topic_someone_wrote_is_not_a_runtime() {
        // The failure that would matter: a roster grouped by machine filing
        // real rooms under half a sentence.
        assert_eq!(parse_runtime("Notes on deployment"), None);
        assert_eq!(parse_runtime("Where we plan the release"), None);
        assert_eq!(parse_runtime(""), None);
        assert_eq!(parse_runtime("\u{2014} openclaw:ganesha"), None);
    }

    #[test]
    fn an_id_embedded_in_a_longer_name_is_shortened_in_place() {
        assert_eq!(humanise("agent-9247e5a88cfa"), "Agent 9247e5…");
    }
}

#[cfg(test)]
mod user_label_tests {
    use super::user_label;

    #[test]
    fn a_user_id_reads_as_the_person_it_names() {
        assert_eq!(user_label("@cleaner-cody:supermessage.dev"), "Cleaner Cody");
        assert_eq!(user_label("@hanuman:supermessage.dev"), "Hanuman");
    }

    #[test]
    fn a_bridges_underscore_prefix_is_not_part_of_anyones_name() {
        assert_eq!(user_label("@_agentpod_ganesha:supermessage.dev"), "Ganesha");
    }

    #[test]
    fn an_underscore_someone_chose_is_not_a_bridge_namespace() {
        assert_eq!(user_label("@john_doe:supermessage.dev"), "John Doe");
    }

    #[test]
    fn a_provisioned_runtimes_hex_localpart_is_shortened_not_shouted() {
        assert_eq!(user_label("@9247e5a88cfa:supermessage.dev"), "9247e5…");
    }

    #[test]
    fn something_that_is_not_a_user_id_is_left_recognisable() {
        assert_eq!(user_label("Rakesh"), "Rakesh");
    }
}

#[cfg(test)]
mod people_label_tests {
    use super::people_label;

    fn ids(raw: &[&str]) -> Vec<String> {
        raw.iter().map(|id| id.to_string()).collect()
    }

    #[test]
    fn nobody_is_nothing_to_say() {
        assert_eq!(people_label(&ids(&[])), "");
    }

    #[test]
    fn one_person_is_named() {
        assert_eq!(people_label(&ids(&["@cleaner-cody:x.org"])), "Cleaner Cody");
    }

    #[test]
    fn two_people_are_both_named() {
        assert_eq!(
            people_label(&ids(&["@cleaner-cody:x.org", "@hanuman:x.org"])),
            "Cleaner Cody and Hanuman"
        );
    }

    #[test]
    fn a_crowd_names_the_first_two_and_counts_the_rest() {
        assert_eq!(
            people_label(&ids(&[
                "@cleaner-cody:x.org",
                "@hanuman:x.org",
                "@ganesha:x.org",
                "@rakesh:x.org",
            ])),
            "Cleaner Cody, Hanuman and 2 others"
        );
    }

    #[test]
    fn one_left_over_is_one_other_not_one_others() {
        assert_eq!(
            people_label(&ids(&[
                "@cleaner-cody:x.org",
                "@hanuman:x.org",
                "@ganesha:x.org"
            ])),
            "Cleaner Cody, Hanuman and 1 other"
        );
    }
}
