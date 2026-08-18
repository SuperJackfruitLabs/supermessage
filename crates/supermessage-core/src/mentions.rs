//! Which members a finished message addresses, for `m.mentions`.
//!
//! **A correction to the migration's spec, recorded here rather than
//! silently.** `$lib/components/mentions.ts` was listed as moving wholesale on
//! the grounds that it "detects a mention of the logged-in user". It does not.
//! Four of its five exports are composer autocomplete — the query being typed
//! at the caret, ranking candidates, inserting a completion — which is input
//! UX, differs legitimately per platform, and has no business in the core.
//!
//! One export is not. [`collect_mentions`] produces the `m.mentions` field
//! that goes on the wire, and that field is how an agent running its own
//! Matrix client decides a message in a room full of agents was addressed to
//! it. Two clients disagreeing about it is a bug — a mention silently dropped,
//! or one attributed to the wrong member — so it lives here, and the caret
//! handling stays in TypeScript.

/// A member a message can address.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Mentionable {
    pub user_id: String,
    /// The member's display name, `None` when they have none set.
    pub display_name: Option<String>,
}

/// What a member is called: display name if they have one, else their id.
///
/// Shared with the composer through [`collect_mentions`]'s matching rather
/// than duplicated, because the label is what the composer *inserted* — if the
/// two ever disagreed, a mention would be written one way and read another.
pub fn mention_label(member: &Mentionable) -> &str {
    member
        .display_name
        .as_deref()
        .unwrap_or(member.user_id.as_str())
}

/// The user ids `text` mentions.
///
/// Matched on the label the composer inserted, **longest first**: with members
/// called "Ana" and "Ana Lyra", a message addressed to the latter must not
/// report the former as mentioned too. Each member is reported at most once,
/// however many times they appear.
pub fn collect_mentions(text: &str, members: &[Mentionable]) -> Vec<String> {
    let mut by_length: Vec<&Mentionable> = members.iter().collect();
    by_length.sort_by_key(|member| std::cmp::Reverse(mention_label(member).len()));

    let mut found = Vec::new();
    let mut remaining = text.to_string();

    for member in by_length {
        let needle = format!("@{}", mention_label(member));
        if !remaining.contains(&needle) {
            continue;
        }
        found.push(member.user_id.clone());
        // Replaced rather than left in place, so a shorter name that is a
        // prefix of this one cannot match the same characters again.
        remaining = remaining.replace(&needle, " ");
    }

    found
}

#[cfg(test)]
mod tests {
    use super::*;

    fn member(user_id: &str, display_name: Option<&str>) -> Mentionable {
        Mentionable {
            user_id: user_id.to_string(),
            display_name: display_name.map(str::to_string),
        }
    }

    #[test]
    fn finds_the_members_addressed() {
        let members = vec![
            member("@ana:example.org", Some("Ana")),
            member("@bo:example.org", Some("Bo")),
        ];
        assert_eq!(
            collect_mentions("@Ana can you look, @Bo too", &members),
            vec!["@ana:example.org", "@bo:example.org"]
        );
    }

    #[test]
    fn prefers_the_longest_name_so_a_prefix_does_not_steal_the_match() {
        // The case that makes the sort load-bearing: addressed to Ana Lyra,
        // Ana must not also be reported.
        let members = vec![
            member("@ana:example.org", Some("Ana")),
            member("@lyra:example.org", Some("Ana Lyra")),
        ];
        assert_eq!(
            collect_mentions("@Ana Lyra please review", &members),
            vec!["@lyra:example.org"]
        );
    }

    #[test]
    fn reports_a_member_once_however_often_they_appear() {
        let members = vec![member("@ana:example.org", Some("Ana"))];
        assert_eq!(
            collect_mentions("@Ana @Ana @Ana", &members),
            vec!["@ana:example.org"]
        );
    }

    #[test]
    fn finds_nobody_in_a_message_that_mentions_nobody() {
        let members = vec![member("@ana:example.org", Some("Ana"))];
        assert_eq!(
            collect_mentions("nothing to see", &members),
            Vec::<String>::new()
        );
    }

    #[test]
    fn does_not_mistake_an_email_for_a_mention() {
        // An address puts its `@` *after* the name, so `@Ana` never appears.
        let members = vec![member("@ana:example.org", Some("Ana"))];
        assert_eq!(
            collect_mentions("write to ana@example.org", &members),
            Vec::<String>::new()
        );
    }

    #[test]
    fn a_label_appearing_inside_a_longer_word_after_an_at_still_matches() {
        // Pinning a real limitation rather than pretending it is not there.
        // The match is a substring search, so `someone@anacondas.example`
        // contains `@Ana` and reports Ana as mentioned. Carried over from the
        // TypeScript deliberately: a port is not the place to change
        // behaviour, and `m.mentions` erring toward *including* someone is
        // the safer direction — an agent that reads a message addressed to it
        // is better than one that misses it.
        let members = vec![member("@ana:example.org", Some("Ana"))];
        assert_eq!(
            collect_mentions("write to someone@Anacondas.example", &members),
            vec!["@ana:example.org"]
        );
    }

    #[test]
    fn falls_back_to_the_user_id_for_a_member_with_no_display_name() {
        let members = vec![member("@ana:example.org", None)];
        assert_eq!(mention_label(&members[0]), "@ana:example.org");
        assert_eq!(
            collect_mentions("hello @@ana:example.org", &members),
            vec!["@ana:example.org"]
        );
    }
}
